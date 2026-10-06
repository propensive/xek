# Client stub manifests

Each `<version>.tsv` records the SHA-256 hashes of the reusable native client stubs published as
the GitHub release `<version>` (assets `client-<label>[.exe]`), and `<version>.SHA256SUMS` the
hashes of every asset of that release — the stubs, the `xek` command in each of its forms, and
`install.sh` — as the release published it. Both are written by `etc/ci/client-record.sh`, which
the release runs once it is public, in the draft pull request it opens here.

Format of a `.tsv` — one tab-separated line per platform, sorted by label:

```
<label>	<sha256>
```

where `<label>` is one of `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`, `windows-x64`.

These hashes are the source of truth for application packaging: an online polyglot launcher
embeds them to verify the stub it downloads at runtime; a native or embed-all build verifies the
stub bytes it downloads at build time against them. The stubs are version-independent and
reusable across applications — they are republished only when the Rust client source changes.
`make client-fetch RUNNERS_VERSION=<version>` downloads a release's stubs into `dist/client`,
verified against its manifest here.

## The manifest the builder reads

`res/core/xek/client.{tsv,version,url}` is the copy compiled into `xek-core`, and is what
`Client.standard` names, and what the `xek` command downloads and verifies stubs against.
`client-record.sh` rewrites all three in the same pull request, so adopting a release is a data
change rather than a code change.

## Provenance

Releases `runners-0.1` through `runners-0.5` were published from the Soundness repository,
before this project was extracted. Those from this repository were tagged `xeq-<version>` under
the project's earlier name, then `xek-0.10`, and with the bare version from `1.0.0`;
`client-fetch.sh` probes each naming in turn. Up to `xek-0.10` the stubs were published as
`runner-<label>`; from `1.0.0` they are `client-<label>`.
