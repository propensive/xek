#!/usr/bin/env bash
#
# Assemble a release of xek: the five native client stubs; the `xek` command, as an embed-all
# polyglot file for every platform (also as `xek.cmd`, the same bytes) and as a native executable
# for each (`xek-<platform>[.exe]`); the `install.sh` that https://propensive.dev/xek serves; and
# `<version>.SHA256SUMS` over all of them, written into $RELEASE_ASSETS for the shared release
# script to upload.
#
# This is the `assemble` step named in etc/release, run by propensive/.github's release.sh when a
# `xek-<version>` tag is pushed; the gates before it (a signed tag, CI green on the commit, no
# release of that version yet) and the upload, digest check, notes and rollback after it are the
# ones every repository's release goes through. Beyond $RELEASE_ASSETS and a temporary directory
# it writes only the three `res/core/xek/client.*` resources, and restores them when it exits,
# so a dry run leaves the checkout as it found it:
#
#   PROPENSIVE_GITHUB=… RELEASE_DRY_RUN=1 ./etc/shared release.sh xek-X.Y.Z
#
# The stubs are version-independent and reusable, so they are released separately from — and far
# less often than — the applications packaged with them: only when the Rust client source
# changes. An application picks up a client fix without being rebuilt; its JAR is appended to a
# bare stub, and the stub bytes are never re-published per application.
#
# Environment (from release.sh): RELEASE_VERSION, RELEASE_TAG, RELEASE_ASSETS, RELEASE_REPO_NAME.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

: "${RELEASE_VERSION:?set by release.sh}" "${RELEASE_TAG:?}" "${RELEASE_ASSETS:?}"
REPO=${RELEASE_REPO_NAME:-propensive/xek}

WORK=$(mktemp -d)
RESOURCES=(res/core/xek/client.tsv res/core/xek/client.version res/core/xek/client.url)
trap 'git checkout -q -- "${RESOURCES[@]}"; rm -rf "$WORK"' EXIT

./etc/ci/client-build.sh "$WORK/client"

sha256() { { sha256sum "$1" 2>/dev/null || shasum -a 256 "$1"; } | cut -d' ' -f1; }

# The platform manifest `xek` bakes in, `label<TAB>sha256`: taken from the exact bytes being
# uploaded, so that `xek` always verifies what it downloads against what was published.
MANIFEST="$WORK/$RELEASE_VERSION.tsv"
for f in "$WORK/client"/client-*; do
  label=$(basename "$f"); label=${label#client-}; label=${label%.exe}
  printf '%s\t%s\n' "$label" "$(sha256 "$f")"
done | sort > "$MANIFEST"

BASE_URL="https://github.com/$REPO/releases/download/$RELEASE_TAG"

# `xek` is built against the stubs published beside it, so the resources naming them are this
# release's while it is compiled; the record pull request (client-record.sh) then commits the
# same three files.
cp "$MANIFEST" res/core/xek/client.tsv
printf '%s\n' "$RELEASE_VERSION" > res/core/xek/client.version
printf '%s\n' "$BASE_URL" > res/core/xek/client.url

# release.sh installs the pinned upstream releases (etc/refs) only for a release made of jars, so an
# assembled release that compiles Scala installs them itself, exactly as CI does: without them,
# nothing from Soundness resolves on a fresh client.
./etc/shared sync-deps.sh

# `xek` built by `xek`, from its own JAR: once as a polyglot file for every platform, and once as
# a native executable for each, which is what a build that knows its platform — Soundness's, say
# — should fetch, since a polyglot file replaces itself on first run. It packages applications
# with the stubs just built, which its resources name; what it is itself wrapped in is whatever
# etc/command-stubs says, since its own daemon — from the Soundness release pinned in etc/refs —
# may not yet speak the base the new stubs do.
export XEK_RELEASE_VERSION="$RELEASE_VERSION"
./mill xek.cli.assembly
JAR="$PWD/out/xek/cli/assembly.dest/out.jar"
PLATFORMS=$(cut -f1 "$MANIFEST" | paste -sd, -)
COMMAND_STUBS=$(grep -v '^#' etc/command-stubs | grep -v '^$' | head -1)
if [[ "$COMMAND_STUBS" == "current" ]]; then
  WRAP="$WORK/client"
else
  echo "client-assemble: wrapping the xek command in the $COMMAND_STUBS stubs (etc/command-stubs)"
  ./etc/ci/client-fetch.sh "$COMMAND_STUBS" "$REPO" "$WORK/command-client"
  WRAP="$WORK/command-client"
fi
./mill xek.cli.bootstrap --polyglot --client "$WRAP" "$JAR" "$WORK/xek"
./mill xek.cli.bootstrap --platform "$PLATFORMS" --client "$WRAP" "$JAR" "$WORK/xek"
cp "$WORK/xek" "$WORK/xek.cmd"

# The installer served from https://propensive.dev/xek, which redirects to the latest release's
# `install.sh`: it downloads the native `xek-<platform>` for where it runs and checks it against
# the digest it embeds, taken here from the very files being uploaded.
./etc/shared generate-install.sh xek "$RELEASE_VERSION" "$RELEASE_TAG" "$WORK" > "$WORK/install.sh"

cp "$WORK/client"/client-* "$WORK"/xek "$WORK"/xek.cmd "$WORK"/xek-* "$WORK/install.sh" \
  "$RELEASE_ASSETS"/

# Every other asset's SHA-256, for downstream fetchers that verify by name: consumers pin an
# `xek` asset's line in their etc/xeq.tsv.
SUMS="$RELEASE_ASSETS/$RELEASE_VERSION.SHA256SUMS"
for f in "$RELEASE_ASSETS"/*; do
  [[ "$f" == "$SUMS" ]] && continue
  printf '%s\t%s\n' "$(basename "$f")" "$(sha256 "$f")"
done | sort > "$SUMS"

count=$(find "$RELEASE_ASSETS" -maxdepth 1 -type f | wc -l | tr -d ' ')
echo "client-assemble: $count assets for $RELEASE_TAG in $RELEASE_ASSETS"
