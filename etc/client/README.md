# Client stub manifests

Each `<version>.tsv` records the SHA-256 hashes of the reusable native client stubs published as
the GitHub release `client-<version>` (assets `client-<label>[.exe]`). It is generated and
committed by `etc/ci/client-release.sh` (`make client-release RUNNERS_VERSION=<version>`).

Format — one tab-separated line per platform, sorted by label:

```
<label>	<sha256>
```

where `<label>` is one of `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`.

These hashes are the source of truth for application packaging: an online polyglot launcher
embeds them to verify the stub it downloads at runtime; a monoglot or offline build verifies the
stub bytes it downloads at build time against them. The stubs are version-independent and
reusable across applications — they are republished only when the Rust client source changes.

## The manifest the builder reads

`res/core/xek/client.{tsv,version,url}` is the copy compiled into `xek-core`, and is what
`Client.standard` names, and what the `xek` command downloads and verifies stubs against. `client-release.sh` rewrites all three, so publishing a release
is a data change rather than a code change.

## Provenance

Releases `runners-0.1` through `runners-0.5` were published from the Soundness repository,
before this project was extracted, and `res/core/xek/client.url` still points there. Those
stubs are byte-identical to what this repository builds from the same sources.

The first release made from here supersedes that: build with `make client-release
RUNNERS_VERSION=0.6`, or — to keep the published bytes and their hashes exactly as they are —
fetch `runners-0.5` from `propensive/soundness` and re-upload those files under a `propensive/xek`
release, then point `client.url` at it.
