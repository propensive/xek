# Packaging

### About

Shipping a JVM application to someone who just wants to run it starts with distribution: a JAR
becomes a self-contained executable — a native launcher per platform, or a single polyglot
installer script that runs as shell script, batch file and PowerShell alike. Building one is
joining a bare client stub, a 5108-byte configuration record and the JAR (`stub ‖ record ‖
jar`); the reference implementation is the `xek` command published with each client release.

### On distribution

"Install the JVM, download the JAR, run this command" loses users at every step. What a
command-line tool should ship as is one file that runs — finding or fetching a suitable JVM
itself — and what it should weigh is its own code, not megabytes of dependencies that already sit
on a public repository. And when the tool is a library, its bundled dependencies must not fight
the host application's: the oldest deployment problem on the JVM.

A distributable described as a value in the build is direct style applied to packaging.

Everything comes from the `xek` package:

```scala
import xek.*
```

### Executables and installers

A packaging configuration names the application, its targets and its delivery, and `pack` produces
the artifact. *Native* delivery assembles one launcher binary for one platform; *embed-all*
produces a polyglot installer script carrying every platform's launcher and the application,
choosing the right one where it runs; *download* keeps the script small, fetching the platform's
launcher on demand and verifying it by hash:

```scala
val jarPath = t"/tmp/mytool.jar".as[Path on Linux]
val outputPath = t"/tmp/mytool".as[Path on Linux]
val clientSource = Packaging.ClientSource.standard

val packaging = Packaging
  ( name         = t"mytool",
    targets      = List(t"linux-x64", t"macos-arm64"),
    delivery     = Packaging.Delivery.EmbedAll,
    dependencies = Packaging.Dependencies.FatJar(jarPath),
    output       = outputPath,
    clientSource = clientSource )

Packager.pack(packaging)
```

The launchers locate or fetch a JVM within the configured version policy, and support signed
self-upgrade. For that, the record carries the application's identifier and the keys an upgrade
must be signed with — a release key, and optionally a recovery key kept offline — given as
`Packaging(…, appId = t"propensive/mytool", signing = Packaging.Signing(publicKey =
releaseKeyPath, recoveryKey = recoveryKeyPath))`. A release key needs an application id. Signing
is not part of the build: a release is signed afterwards, with `xek sign`
([`spec/ethrcfg.md`](../spec/ethrcfg.md)).

### Bundling as a toolchain format

The same packaging is reachable as a toolchain format, so an application can be
compiled and bundled in one path rather than packaged as a separate step afterwards. An
`Executable` runs from `Jar` rather than from a universe, and the delivery mode is part of the
node's identity, since each is a different distributable:

```scala
Toolchain(jarEdges(), executableEdges()).produce
  ( Deliverable.Emission(out, classpath),
    Universe.Classfile,
    Executable(Packaging.Delivery.EmbedAll),
    destination,
    List(executableOptions.name(t"mytool"), executableOptions.client.standard),
    List(EntryPoint(fqcn"com.example.Main")) )
```

`executableOptions.client.standard` names the published client release, verified against its
committed manifest, while `client.local` reads prebuilt stubs from a directory instead. Targets
default to every platform the client source names, and `executableOptions.target` adds one
explicitly. `executableOptions.java` sets the minimum and preferred JVM versions, `bundle.jre`
and `bundle.jdk` say which the launcher downloads when none is installed (nothing is embedded),
and `signing`, `appId` and `buildId` configure the keys,
the application and the upgrade ordering recorded in each executable.

### Where the stubs come from

`Client.standard` is the published release recorded in this repository's resources, verified
against its committed manifest; `Packaging.ClientSource.Local` reads prebuilt stubs from a
directory instead — the output of `make client-build` or `make client-fetch` — which is what
the test suite and `make e2e` use.

The stubs are not built by the Scala build and are never stored in a jar. They are released on
their own cadence, by tagging `X.Y.Z`, with the `xek` command, and the release opens a pull
request rewriting the resources the builder reads, so adopting a new client is a data change.
`Packager` builds with `xek-core` — the one implementation of the byte format, which the `xek`
command runs too — so an Anthology build and a user at a shell produce the same bytes.

### From a shell

The same packaging is the `xek` command:

```sh
xek build app.jar                              # ./app, a native executable for this platform
xek build -p linux-x64 -p windows-x64 app.jar  # app-linux-x64 and app-windows-x64.exe
xek build --polyglot app.jar                   # ./app, one file for sh, PowerShell and cmd.exe
xek build --polyglot -x bat -p linux-x64,macos-arm64 app.jar dist/
xek build --download app.jar                   # a polyglot file which fetches its stub on first run
xek build --dispatch executables.tsv app       # a polyglot file which fetches a complete executable
xek build --java 25 --java-min 21 --jdk app.jar
xek build --build-id 42 --app-id propensive/mytool --public-key release.pub --recovery-key recovery.pub app.jar
xek sign --key release.seed --in mytool --out mytool.signed
```

`xek --help` lists the subcommands, `xek build --help` the options of one, and `xek '{admin}' install` installs its tab-completions.

### The other end

An XEK executable is only half of a running application: the launcher starts a *daemon*, and the
two speak the protocol in [`spec/`](../spec/README.md). An application being packaged here must
therefore be one that implements that protocol — `ethereal`, in Soundness, is the reference
implementation, and `src/example` is the smallest application that uses it.
