# Agent instructions for xek

Read `README.md` first for what xek is and how it is built. This file adds the rules an
agent must follow when working in this repository.

## Dependencies are pinned in `etc/refs`

XEK's Scala modules compile against Soundness, pinned in `etc/refs`. Nothing in Soundness
depends on XEK's jars; Soundness and the applications consume the `xek` *script*, pinned
separately by version and SHA-256 in each of their `etc/xek.tsv` files.

`etc/refs` is tab-separated, one upstream per line: `repository`, `version`, and for a snapshot
the `commit` it was built from. A version `X.Y.Z` is a GitHub Release. A version
`X.Y.Z-<12 hex>` is a **snapshot**: an unreleased upstream build, published from that repository
by `make snapshot` as the pre-release tagged `snapshot-<hex>`, where the hex is the start of the
filtered tree hash of its commit (its tree minus what `.dockerignore` excludes) and `X.Y.Z` the
version it declares for its next release. The build reads the file through the `deps` object in
`build.mill`; there is no version to edit in `build.mill` or in `.github/workflows/ci.yml`.

### Rules

1. **Never install an upstream by hand into `~/.ivy2/local`** (`publishLocal` in a sibling
   checkout) and leave the pin pointing at a release: the build then compiles against bytes CI
   cannot see. If a change needs an unreleased upstream, publish a snapshot there (`make
   snapshot` in a *pushed*, clean checkout of the commit) and pin the line it prints.
2. Run `make sync-deps` after editing `etc/refs`, and whenever a build fails to resolve a
   `dev.propensive` coordinate. It installs every pin, transitively, from GitHub Releases —
   exactly what the shared CI workflow does — and repairs a jar whose digest differs. For a
   snapshot nobody has published, it builds the pinned commit from a sibling checkout at
   `../<name>` (or `$PROPENSIVE_WORK/<name>`).
3. A snapshot pin is a **debt** the PR description should mention: the upstream has to be
   released, and the pin bumped to that release, before this repository can be released.
   `make release` runs `deps.py check` and refuses while any pin, transitively, is a snapshot.
4. When bumping a pin, bump only `etc/refs`. If the new version breaks the build, the PR that
   fixes the breakage carries the bump; do not split them.
5. Do not edit `etc/shared` or `etc/github-ref` casually: `etc/github-ref` pins the commit of
   propensive/.github whose scripts (`sync-deps.sh`, `snapshot.sh`, `deps.py`,
   `release-launcher.sh`, …) run here, and a bump is a deliberate one-line change. Set
   `PROPENSIVE_GITHUB=/path/to/a/.github/checkout` to test a change to the scripts themselves.
6. **Never pin a Soundness snapshot here, even briefly.** Soundness pins *this* repository's
   release in its `etc/xek.tsv`, so a snapshot pin in `etc/refs` closes a cycle: neither side
   could be released before the other. The pin is always a Soundness release, and everything
   Scala here — `src/example`, the end-to-end fixture, included — uses only that release's API.
   When a protocol change needs new daemon behaviour, do not teach the fixture about it: the
   assertion belongs in Soundness's `ethereal` suite, run with `XEK` pointing at a script built
   from this checkout (`make runners-build`, then `etc/ci/xek-script-build.sh`). The order is
   then fixed: this repository merges and releases first, Soundness bumps `etc/xek.tsv` to the
   new release, and only afterwards may `etc/refs` here move up to the Soundness release that
   followed.

## The Rust toolchain is a pinned nightly

`rust-toolchain.toml` pins a *nightly by date*, and `etc/ci/runners-build.sh` builds the stubs
with unstable flags. This is deliberate: compiling the standard library for size
(`-Zbuild-std`, `-Cpanic=immediate-abort`) is what takes a stub from ~0.5 MB to 0.2–0.3 MB,
and nothing stable can reach that because the weight is inside stable's precompiled std (the
panic backtrace printer and its DWARF reader). The stubs themselves are ordinary native
binaries; nothing links against them, so the nightly costs users nothing. What it costs is
that the *build recipe* can rot: a nightly promises nothing about its `-Z` flags, and the pin
is what keeps `make runners-build` reproducible.

### Rules

1. **Keep the pin current.** Bump the date in `rust-toolchain.toml` as a matter of course when
   touching the Rust source, and at the latest before each release (`git tag -s xek-X.Y`), so the stubs
   are not built by a nightly months behind the compiler's fixes. A bump is its own commit;
   CI's `cargo test` runs on the pinned toolchain and validates it.
2. **Expect a bump to disturb the flags**, and fix them in the same PR. Everything unstable
   lives in `runners-build.sh`, each flag with its purpose in a comment, and in
   `etc/ci/zigcc-aarch64-linux.sh`, a shim that drops a linker argument zig rejects. It has
   already happened once: the `panic_immediate_abort` std feature became the
   `-Cpanic=immediate-abort` strategy. When a flag is renamed, replace it; when one stops
   helping, drop it; do not pile up alternatives.
3. **Measure before and after any change to the flags or the profile**, on all five targets,
   and put the numbers in the PR. The ready reckoner is `make runners-build` followed by
   `ls -l dist/runners`; for what is inside a stub, `cargo install cargo-bloat` and run it with
   `CARGO_PROFILE_RELEASE_STRIP=false` and the same `-Z` arguments. Compare gzipped sizes too:
   that is what the embed-all polyglot pays. A change that does not save at least ~1% on most
   targets is not worth its churn.
4. **Know what the flags give up**, so a bug report is recognised for what it is. A panic in
   the stub aborts with no message. `{:?}` formats to nothing, which only the `debug!` macro
   uses. The Linux stubs have no unwind tables, so debuggers cannot walk their frames. All
   three are traded for size on purpose; do not "fix" them without re-measuring.
5. **Don't move the flags into `.cargo/config.toml` or `Cargo.toml`.** They belong to the
   stub build only; the unit tests and `ethereal-sign` build without them, on the same nightly,
   and `-Zbuild-std` under `cargo test` would need a different panic strategy.

### Tools are not dependencies

What this repository *runs* — fume, to run its tests — is pinned in `etc/tools`, not in
`etc/refs`. A tool is always a release, never a snapshot; it is not walked transitively and does
not gate a release, because a release of it exists by definition. That distinction is what keeps
the release graph free of cycles (Soundness runs flair, flair depends on Pyrocosm, Pyrocosm
depends on Soundness). `make tools` installs the pinned commands. Never pin a tool in `etc/refs`
to get an unreleased build of it: release the tool instead.

The whole flow, and the scripts, are documented in the README of
[propensive/.github](https://github.com/propensive/.github).
