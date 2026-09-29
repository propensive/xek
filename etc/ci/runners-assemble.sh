#!/usr/bin/env bash
#
# Assemble a release of xek: the five native runner stubs, the `xek` builder script (also as
# `xek.cmd`, the same bytes), and `<version>.SHA256SUMS` over all of them, written into
# $RELEASE_ASSETS for the shared release script to upload.
#
# This is the `assemble` step named in etc/release, run by propensive/.github's release.sh when a
# `xek-<version>` tag is pushed; the gates before it (a signed tag, CI green on the commit, no
# release of that version yet) and the upload, digest check, notes and rollback after it are the
# ones every repository's release goes through. It writes nothing outside $RELEASE_ASSETS and a
# temporary directory, so a dry run leaves the checkout as it found it:
#
#   PROPENSIVE_GITHUB=… RELEASE_DRY_RUN=1 ./etc/shared release.sh xek-X.Y
#
# The stubs are version-independent and reusable, so they are released separately from — and far
# less often than — the applications packaged with them: only when the Rust runner source
# changes. An application picks up a runner fix without being rebuilt; its JAR is appended to a
# bare stub, and the stub bytes are never re-published per application.
#
# Environment (from release.sh): RELEASE_VERSION, RELEASE_TAG, RELEASE_ASSETS, RELEASE_REPO_NAME.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

: "${RELEASE_VERSION:?set by release.sh}" "${RELEASE_TAG:?}" "${RELEASE_ASSETS:?}"
REPO=${RELEASE_REPO_NAME:-propensive/xek}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

./etc/ci/runners-build.sh "$WORK/runners"

sha256() { { sha256sum "$1" 2>/dev/null || shasum -a 256 "$1"; } | cut -d' ' -f1; }

# The platform manifest the builder script bakes in, `label<TAB>sha256`: taken from the exact bytes
# being uploaded, so the script always verifies what it downloads against what was published.
MANIFEST="$WORK/$RELEASE_VERSION.tsv"
for f in "$WORK/runners"/runner-*; do
  label=$(basename "$f"); label=${label#runner-}; label=${label%.exe}
  printf '%s\t%s\n' "$label" "$(sha256 "$f")"
done | sort > "$MANIFEST"

BASE_URL="https://github.com/$REPO/releases/download/$RELEASE_TAG"
./etc/ci/xek-script-build.sh "$RELEASE_VERSION" "$BASE_URL" "$MANIFEST" "$WORK/xek"

cp "$WORK/runners"/runner-* "$WORK/xek" "$WORK/xek.cmd" "$RELEASE_ASSETS"/

# Every other asset's SHA-256, for downstream fetchers that verify by name: consumers pin the
# `xek` line in their etc/xeq.tsv.
SUMS="$RELEASE_ASSETS/$RELEASE_VERSION.SHA256SUMS"
for f in "$RELEASE_ASSETS"/*; do
  [[ "$f" == "$SUMS" ]] && continue
  printf '%s\t%s\n' "$(basename "$f")" "$(sha256 "$f")"
done | sort > "$SUMS"

count=$(find "$RELEASE_ASSETS" -maxdepth 1 -type f | wc -l | tr -d ' ')
echo "runners-assemble: $count assets for $RELEASE_TAG in $RELEASE_ASSETS"
