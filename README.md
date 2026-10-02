# XEK

**A JVM application as a single executable file.**

XEK is the Cross-platform Executable Kit. An XEK executable is a small native *client stub*
with the application's JAR appended to it. Running it starts — or reuses — a background daemon
holding a warm JVM, forwards the invocation's arguments, environment, streams and signals to it,
and returns its exit status. So the JVM's startup cost is paid once rather than once per
invocation, and the command behaves like any other command: it reads a pipe, respects `Ctrl-C`,
and reports a status.

The stub is generic and reusable. It is the same bytes for every application on a given
platform, so building an executable is joining three files — the stub, a small configuration
record and the JAR — not compiling. That is what makes cross-platform packaging cheap: every platform's
executable can be built on one machine, in about as long as it takes to copy a file.

```sh
$ xek mytool.jar
Wrote /home/you/mytool
$ ./mytool --version
mytool 1.0.0
```

The `xek` command builds them. It is an XEK executable itself, written with
[Soundness](https://github.com/propensive/soundness), and builds for any platform from any
other. On Linux or macOS, install it with

```sh
curl -fsSL https://propensive.dev/xek | sh
```

which puts it in `~/.local/bin` (or `$XEK_INSTALL_DIR`); on Windows, download `xek-windows-x64.exe`
from the [latest release](https://github.com/propensive/xek/releases/latest). Then:

```sh
xek app.jar                              # ./app, a native executable for this platform
xek app.jar dist/tool                    # dist/tool
xek -p linux-x64 -p windows-x64 app.jar  # app-linux-x64 and app-windows-x64.exe
xek --polyglot app.jar                   # ./app, one file for every platform (see below)
xek --polyglot --exclude bat app.jar     # …leaving out the cmd.exe section
xek --polyglot --platforms linux-x64,macos-arm64 app.jar
xek --download app.jar                   # a polyglot file which fetches its stub on first run
xek --dispatch executables.tsv app       # a polyglot file which fetches a whole executable
xek --java 25 --java-min 21 --jdk app.jar
```

Options may come before or after the JAR, and `xek --help` lists them all. The `--java` options
record which runtime an executable wants: the client uses a suitable installed Java, and
otherwise downloads the preferred version from Adoptium on first run. `xek '{admin}' install`
installs tab-completions for `xek`, as for any XEK executable.

## What's here

| Path | |
|---|---|
| `src/client` | The client stub, in Rust: platform detection, JVM discovery, the daemon handshake, terminal modes, signals, and signed self-upgrade. 0.2–0.3 MB per platform |
| `src/sign` | `ethereal-sign` — keygen and signing for the self-upgrade path |
| `src/core` | The builder: the configuration record, stubs (local, or downloaded, verified and cached), native assembly, and the polyglot launchers (`res/core/xek`) — one file valid as `sh`, `.bat` and PowerShell |
| `src/cli` | The `xek` command: `core` behind a command line with tab-completions, and an XEK executable itself. Published with the client |
| `src/packager` | `Packager` — turns a `Packaging` into a distributable with `core` |
| `src/toolchain` | The same packaging as an [Anthology](https://github.com/propensive/soundness) toolchain format, so an application compiles and packages in one pass |
| `spec/` | **The contract** between a launcher and a daemon, and the reason the two can be developed apart |
| `src/example` | The end-to-end fixture: the smallest daemonized application there is |

## Delivery modes

An application's JAR reaches a user in one of four shapes, and the same `Packaging` describes
each:

- **native** — one self-contained binary for one platform. The plain case.
- **embed-all** (`--polyglot`) — a polyglot script carrying *every* platform's stub and the
  application, which unpacks the right one where it runs. One file, works anywhere, offline. It
  runs as it is in `sh`; PowerShell and `cmd.exe` need it renamed to end `.ps1` or `.bat`.
- **download** (`--download`) — a polyglot script carrying the application and a table of
  (url, hash) pairs; on first run it fetches the one stub it needs, verifies it, appends the
  embedded JAR and replaces itself.
- **dispatcher** (`--dispatch`) — a polyglot script carrying *nothing* but a table of (url, hash) pairs
  naming complete per-platform executables. The smallest possible cross-platform artefact.

In every downloading case the bytes are verified against a SHA-256 recorded at publication.

## The two halves

XEK is the launcher half. The other half is a **daemon** — the JVM-side implementation that
accepts the connection, reconstitutes the invocation's context and runs the application. The
reference daemon is `ethereal`, in [Soundness](https://github.com/propensive/soundness).

Neither repository depends on the other. They meet at [`spec/`](spec/README.md): a TEL schema
for the wire protocol, the configuration block's layout, the system properties, and the files
they share. Both sides carry the schema's signature and refuse a peer that disagrees, so a
mismatched pair fails at the first message rather than misreading fields — which is precisely
what makes it safe for them to release on their own cadences.

XEK's Scala modules are *built* against Soundness's libraries, as any Scala project might be.
Nothing published as a library here depends on `ethereal`, the daemon. The `xek` command does,
as any XEK application must, and so does the end-to-end fixture, which needs something at the
other end of the socket to be a test at all. That pin (`etc/refs`) is always
a Soundness *release*, never a snapshot, because Soundness in turn pins an XEK release in its
`etc/xek.tsv`: a protocol change is released here first, and Soundness follows.

## Building

Requires a JDK, and — to build stubs rather than download them — `rustup`, which installs the
nightly pinned in `rust-toolchain.toml` on first use, with
[`cargo-zigbuild`](https://github.com/rust-cross/cargo-zigbuild) and `zig` for cross-compiling.
The stubs are built with a nightly because compiling the standard library for size
(`-Zbuild-std`) is what keeps them small; the pin is bumped deliberately, as `AGENTS.md`
describes.

```sh
make build           # the Scala modules
make xek             # dist/xek, the `xek` command, built by itself
make test            # the test suite, through the `fume` client
make cargo-test      # the client's own unit tests

make client-build   # cross-compile the five stubs into dist/client
make client-fetch RUNNERS_VERSION=0.5   # or download them, hash-verified

make e2e             # package the example app around a real stub and run it
```

The Scala side resolves Soundness components from `~/.ivy2/local`; `make sync-releases
VERSION=X.Y.Z` in a Soundness checkout puts them there. The compiler is the
[proscala](https://github.com/propensive/proscala) fork, downloaded and cached automatically.

## Releasing client stubs

Stubs are released on their own cadence, and only when the Rust source changes, by tagging — as
every repository in the ecosystem is released:

```sh
git tag -s xek-1.0.0 && git push --tags
```

The tag fires `.github/workflows/release.yml`, which runs the shared `release.sh` from
[propensive/.github](https://github.com/propensive/.github) as configured by `etc/release`. It
gates on a verified signed tag and on CI already being green on that commit; cross-compiles the
five stubs and builds the `xek` command around them, as a polyglot `xek` and a native
`xek-<platform>` for each platform (`etc/ci/client-assemble.sh`); uploads them to the
`xek-1.0.0` release, with `1.0.0.SHA256SUMS`, and checks every digest; and, if anything fails,
deletes the release and the tag. Once the release is public it opens two draft pull requests: one
here recording the hashes in `etc/client/1.0.0.tsv` and `etc/client/1.0.0.SHA256SUMS` and
rewriting `res/core/xek/client.{tsv,version,url}`, the resources the builder reads
(`etc/ci/client-record.sh`); and one in Soundness moving its `etc/xeq.tsv` to the release
(`etc/downstream`). Adopting a release is therefore a data change, not a code change, and an
application picks up a client fix without anything being rebuilt.

The `xek` command is itself an XEK executable, run by a daemon built from the Soundness release
pinned in `etc/refs`. When the protocol base has moved since that daemon was written, the
command is wrapped in the stubs of the last release that daemon speaks — `etc/command-stubs`
says which — while packaging applications with the new ones; the pull request that moves
`etc/refs` to a daemon speaking the new base sets it back to `current`.

Versions are `X.Y.Z` from `1.0.0`: the first two-part versions, up to `xek-0.10`, predate the
protocol's settling, and a third part now distinguishes a client fix, which changes no contract,
from a release that revises `spec/`.

To rehearse a release without publishing anything, from a checkout of the commit to be tagged:

```sh
RELEASE_DRY_RUN=1 ./etc/shared release.sh xek-1.0.0
```

## Status

Extracted from Soundness, where this machinery grew as the `ziggurat` library and the Rust
client inside `ethereal`. Client releases up to `runners-0.5` were published from that
repository under the `client-` tag prefix; releases from here use `xek-`, and `xek-0.6` — the
first made from this repository — supersedes them and adds the builder as a release asset: a
polyglot shell script up to `xek-0.9`, and the `xek` command after it. `xek-1.0.0` is the first
three-part version, and the first whose base signature the daemon side adopted after release.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
