#!/usr/bin/env bash
#
# Download the reusable native client stubs for a published version from GitHub into
# `dist/client`, verifying each against the committed `etc/client/<version>.tsv` manifest.
#
# Use this when the Rust toolchain isn't available to `make client-build`: it fetches the
# exact bytes published by a release (`git tag -s X.Y.Z`). The client are never stored in a
# JAR — builds and tests read them from `dist/client` (or download them here first).
#
# Usage: ./etc/ci/client-fetch.sh <version> [owner/repo] [directory]
#         (or `make client-fetch RUNNERS_VERSION=X [REPO=owner/repo]`); the directory
#         defaults to dist/client

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

VERSION="${1:-}"
REPO="${2:-propensive/xek}"
MANIFEST="etc/client/$VERSION.tsv"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [owner/repo]" >&2; exit 1
fi
if [[ ! -f "$MANIFEST" ]]; then
  echo "client-fetch: manifest $MANIFEST not found (was version $VERSION published?)" >&2; exit 1
fi

OUT="${3:-dist/client}"
mkdir -p "$OUT"
# Releases from 1.0.0 are tagged with the bare version; those from the rename up to 0.10,
# `xek-<version>`; those before it, `xeq-<version>`. Probe newest naming first.
TAG="$VERSION"
for candidate in "$VERSION" "xek-$VERSION" "xeq-$VERSION"; do
  if curl -fsIL "https://github.com/$REPO/releases/download/$candidate/$VERSION.SHA256SUMS" >/dev/null 2>&1; then
    TAG="$candidate"; break
  fi
done
base="https://github.com/$REPO/releases/download/$TAG"

# Releases up to `xek-0.10` published the stubs as `runner-<label>`; from `xek-1.0.0` they are
# `client-<label>`. Whatever a release called them, they are saved under the current name, which
# is what everything here looks for.
case "$VERSION" in
  0.*) remote_prefix="runner-" ;;
  *)   remote_prefix="client-" ;;
esac

while IFS=$'\t' read -r label hash; do
  [[ -z "$label" ]] && continue
  ext=""; [[ "$label" == windows* ]] && ext=".exe"
  name="client-$label$ext"
  remote="$remote_prefix$label$ext"
  echo "client-fetch: downloading $remote"
  if command -v curl >/dev/null 2>&1
  then curl -fsSL "$base/$remote" -o "$OUT/$name"
  else wget -qO "$OUT/$name" "$base/$remote"
  fi
  got=$( { sha256sum "$OUT/$name" 2>/dev/null || shasum -a 256 "$OUT/$name"; } | cut -d' ' -f1)
  if [[ "$got" != "$hash" ]]; then
    echo "client-fetch: SHA-256 mismatch for $name (got $got, expected $hash)" >&2
    rm -f "$OUT/$name"; exit 1
  fi
  chmod +x "$OUT/$name"
done < "$MANIFEST"

echo "client-fetch: $(find "$OUT" -maxdepth 1 -name 'client-*' | wc -l | tr -d ' ') stubs verified into $OUT"
