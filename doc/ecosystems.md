# Beyond the JVM

XEK was built because the JVM is a poor fit for a command line: its startup cost is paid on
every invocation, and nothing about a JAR behaves like a command. The daemon absorbs the first
problem and the client stub the second. This note records how much of that is actually about
the JVM — very little — and which other ecosystems have the same problems, so that the question
need not be researched twice. It proposes no change; the seam a second runtime would use is
described at the end, for when one is wanted.

## What is JVM-specific

Three things, and each is easy to delimit:

- **The record.** `java_min`, `java_pref` and `bundle` in `ETHRCFG`
  ([`spec/ethrcfg.md`](../spec/ethrcfg.md)) are a runtime-version policy and where to fetch
  one from (Adoptium): five bytes of 5108.
- **The payload.** A JAR, found by scanning back from the end of the file for the ZIP
  end-of-central-directory record, with the ZIP64 locator fixed up at build time.
- **The spawn.** The client starts the daemon as `java -jar <self>`, with the `-Dethereal.*`
  properties of [`spec/properties.md`](../spec/properties.md), and the JVM discovery and
  download that precede it are the one large JVM-only module in the client.

Nothing else mentions Java. The wire protocol
([`spec/ethereal-launcher.tel`](../spec/ethereal-launcher.tel)), the shared layout and its
staleness and acceptance negotiation ([`spec/layout.md`](../spec/layout.md)), and the whole of
[`spec/launcher.md`](../spec/launcher.md) are runtime-agnostic — and `launcher.md` is where the
value is. A terminal in raw mode with an OSC 11 query; signals forwarded with an acknowledgement
and a per-signal fallback; half-close for end of input; `closed` for the `| head -1` case;
`TSTP` and `CONT` with the terminal restored in between; Windows console control events with
their deadlines; a faithful exit status; staleness detection; idle exit. That is what every
daemonised tool reinvents, and usually gets wrong for years.

## Ecosystems with the same startup problem

Ranked by how acute the pain is and how well the model fits.

1. **Julia.** The worst time-to-first-anything of any mainstream runtime: seconds to tens of
   seconds once plotting or data frames are loaded. `DaemonMode.jl` is exactly this idea done
   ad hoc, with no terminal, no signals and no staleness; `PackageCompiler` system images are the
   alternative and are heavy. Julia is arguably a better fit than the JVM.
2. **Clojure, and the other JVM languages.** Supported already, by construction, but worth
   naming: Clojure's pain is the sharpest on the JVM (a second or more for `clj`), and the
   community has built nailgun, drip (abandoned) and finally Babashka to escape it. XEK is in
   effect nailgun with a specification, and the terminal and signal semantics nailgun never had.
3. **Erlang and Elixir.** The BEAM boots in 300 ms to a second, `mix` in more, and the runtime
   is *designed* to be long-lived, so the warm daemon is its natural shape. Elixir command-line
   tools barely exist because of startup; a daemon would make them viable. Distribution and
   `-remsh` are the existing route, over TCP with cookies; a local socket is cleaner.
4. **Python.** The interpreter starts quickly; the imports do not. `aws`, `gcloud`, `az`, and
   anything that loads numpy or torch takes 0.5–3 s before it reads an argument. The executable
   layout carries over unchanged: `python3 ./mytool` runs a zipapp with arbitrary prefix bytes,
   because `zipimport` scans for the end-of-central-directory record from the end, as the JVM
   does. The same `stub ‖ record ‖ payload` file would run.
5. **Ruby.** Rails's Spring is the well-known, and widely resented, preloader daemon, infamous
   for serving stale code — the exact bug that `build` with `verify`/`verdict` closes.
6. **Node and TypeScript.** `tsc`, eslint and their relatives spend hundreds of milliseconds
   loading modules. Node has startup snapshots and single-executable applications, so the need
   is moderate; Nx and Turborepo each run a bespoke daemon regardless.
7. **.NET.** Startup is 50–100 ms, but the toolchain already uses daemons ad hoc: MSBuild node
   reuse, the `VBCSCompiler` server. The least pain on this list.

One caveat applies to Python, Ruby and Node. The working directory, environment, umask and
standard streams are process-global there, so one daemon cannot serve concurrent invocations
in-process as a JVM can with threads and per-invocation streams. The daemon for those runtimes
is a **zygote**: warm the imports once, then fork on each `init` and set the directory,
environment and umask in the child, which inherits the connection. The protocol accommodates
that without change — but it rules out Windows, which has no fork.

## Ecosystems that want the protocol, not the warm start

Native toolchains — Go, Rust, OCaml, Haskell — gain nothing from a warm process, but many of
their tools are daemons already, for state rather than startup: Bazel's client and server,
Gradle's daemon, the sbt and Mill servers, watchman, rust-analyzer, ghcid, Nx. Each has its own
client protocol, and each has carried a long tail of bugs around precisely the things in
`launcher.md`: Gradle daemons going stale, Bazel's interrupt handling, nobody doing `TSTP` and
`CONT` properly. Bazel's C++ client is the closest existing thing to the XEK client — find or
start the server, forward arguments, environment and directory, stream the output, forward
interrupts — and it is gRPC and bespoke. A tool whose daemon should *feel like a command* could
adopt `ethereal-launcher.tel` and have the semantics for free. The protocol's name is frozen on
the wire ([`spec/README.md`](../spec/README.md)), and nothing JVM-specific comes with it.

## The half that is already runtime-agnostic

The delivery modes — polyglot embed-all, `--download`, `--dispatch` — and the ML-DSA-44-signed
`.pending` self-upgrade do not care what the payload is. A Go or Rust tool could use the
dispatcher script and the signed upgrade today, if the builder accepted an arbitrary native
executable in place of a stub and a JAR.

## Where the seam would be

If a second runtime were ever wanted, the changes here would be small and the real work would
be elsewhere:

- **The record.** A runtime discriminator in the ten reserved bytes, with zero meaning the JVM
  so that every v4 executable keeps its meaning, and the version-policy fields reinterpreted
  per runtime; or `ETHRCFG\x05` if the fields themselves must differ.
- **The spawn.** The JVM discovery and Adoptium download are the only module to replace:
  `python3 <self>`, `julia <self>`, and so on, with the `-D` properties delivered as
  environment variables.
- **The payload.** The end-of-central-directory scan covers a JAR, a zipapp and anything else
  zip-shaped; any other payload needs a trailer recording its offset.
- **The daemon.** An `ethereal` implementation per runtime, each solving the process-global
  state problem its own way. This lives outside this repository, as the reference daemon does.

In short: Julia and Elixir are where a warm daemon would change what is feasible rather than
merely what is fast; the zygote model is the one real architectural fork; and none of it needs a
change here until `ETHRCFG` or the spawn path does.
