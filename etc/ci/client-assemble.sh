#!/usr/bin/env bash
#
# Assemble a release of xek: the five native client stubs; the `xek` command, as an embed-all
# polyglot file for every platform (also as `xek.cmd`, the same bytes) and as a native executable
# for each (`xek-<platform>[.exe]`); the `install.sh` that https://propensive.dev/xek serves; and
# `<version>.SHA256SUMS` over all of them, written into $RELEASE_ASSETS for the shared release
# script to upload.
#
# This is the `assemble` step named in etc/release, run by propensive/.github's release.sh when a
# version tag is pushed; the gates before it (a signed tag, CI green on the commit, no
# release of that version yet) and the upload, digest check, notes and rollback after it are the
# ones every repository's release goes through. Beyond $RELEASE_ASSETS and a temporary directory
# it writes only the three `res/core/xek/client.*` resources, and restores them when it exits,
# so a dry run leaves the checkout as it found it:
#
#   PROPENSIVE_GITHUB=… RELEASE_DRY_RUN=1 ./etc/shared release.sh X.Y.Z
#
# The stubs are version-independent and reusable, so they are released separately from — and far
# less often than — the applications packaged with them: only when the Rust client source
# changes. An application picks up a client fix without being rebuilt; its JAR is appended to a
# bare stub, and the stub bytes are never re-published per application.
#
# `xek upgrade` is the `upgrade` of every tool built with xek, so a release of xek is keyed as
# theirs are (propensive/.github's README, "Signed executables"): when etc/keys holds the public
# keys, release.sh names them in RELEASE_KEYS, and the executables here embed them, the
# application id and the build id; and when it also passes the seed of the release key, each
# native `xek-<platform>` is signed with it and verified, before any digest of it is taken.
# release.sh makes the other gates, and writes `upgrade.tsv` from the executables left here.
#
# Environment (from release.sh): RELEASE_VERSION, RELEASE_TAG, RELEASE_ASSETS, RELEASE_REPO_NAME;
# and RELEASE_APP_ID, RELEASE_BUILD_ID, RELEASE_KEYS, RELEASE_SIGNING_KEY, RELEASE_PREVIOUS_KEY
# and RELEASE_SIGNING_SEED, each of which may be empty or unset.

set -euo pipefail

# The seed is taken out of the environment before anything else runs, as release.sh takes it out
# of its own, so that nothing run here — cargo, zig, Mill, the compiler — inherits it. From here
# it lives only in this unexported variable, and reaches `xek sign` and `xek public-key` alone,
# through the environment of that one command.
SIGNING_SEED=${RELEASE_SIGNING_SEED:-}
unset RELEASE_SIGNING_SEED

cd "$(git rev-parse --show-toplevel)"

: "${RELEASE_VERSION:?set by release.sh}" "${RELEASE_TAG:?}" "${RELEASE_ASSETS:?}"
REPO=${RELEASE_REPO_NAME:-propensive/xek}

WORK=$(mktemp -d)
RESOURCES=(res/core/xek/client.tsv res/core/xek/client.version res/core/xek/client.url)
trap 'git checkout -q -- "${RESOURCES[@]}"; rm -rf "$WORK"' EXIT

fail() { echo "client-assemble: $*" >&2; exit 1; }

# `xek`, from the JAR `$JAR` with no launcher, as `mill xek.cli.bootstrap` runs it, but with
# nothing of Mill's on its standard output, which `xek verify` answers on.
xek() { java -cp "$JAR" xek.bootstrap "$@"; }

# The keys, the application id and the build id every executable embeds, when the release is
# keyed; and whether it is to be signed.
KEYED=()
SIGNED=""
if [[ -n "${RELEASE_KEYS:-}" ]]; then
  [[ -n "${RELEASE_APP_ID:-}" && -n "${RELEASE_BUILD_ID:-}" && -n "${RELEASE_SIGNING_KEY:-}" ]] ||
    fail "RELEASE_KEYS is set without RELEASE_APP_ID, RELEASE_BUILD_ID and RELEASE_SIGNING_KEY"
  KEYED=(--build-id "$RELEASE_BUILD_ID" --public-key "$RELEASE_KEYS/release.pub"
         --recovery-key "$RELEASE_KEYS/recovery.pub" --app-id "$RELEASE_APP_ID")

  # The seed signs with exactly the key this release is expected to be signed with: checked now,
  # with `xek` as it stands, so that a wrong or stale secret costs a compilation and not the
  # stubs' build as well. (release.sh makes this check itself where it has an `xek` to make it
  # with; here, this is the first one there is.)
  if [[ -n "$SIGNING_SEED" ]]; then
    ./etc/shared sync-deps.sh
    ./mill xek.cli.assembly
    JAR="$PWD/out/xek/cli/assembly.dest/out.jar"
    XEK_SIGN_SEED="$SIGNING_SEED" xek public-key --key-env XEK_SIGN_SEED --out "$WORK/derived.pub" \
      >/dev/null || fail "could not derive the public key of the signing seed"
    cmp -s "$WORK/derived.pub" "$RELEASE_SIGNING_KEY" ||
      fail "UPGRADE_SIGNING_SEED is not the seed of $RELEASE_SIGNING_KEY; the secret is wrong or stale"
    echo "client-assemble: the signing seed is the seed of $RELEASE_SIGNING_KEY"
    SIGNED=1
  else
    echo "client-assemble: keyed, but there is no seed, so nothing is signed"
  fi
elif [[ -n "$SIGNING_SEED" ]]; then
  fail "a signing seed was given, but RELEASE_KEYS names no keys"
fi

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
./mill xek.cli.bootstrap build --polyglot --client "$WRAP" "${KEYED[@]}" "$JAR" "$WORK/xek"
./mill xek.cli.bootstrap build --platform "$PLATFORMS" --client "$WRAP" "${KEYED[@]}" "$JAR" "$WORK/xek"
cp "$WORK/xek" "$WORK/xek.cmd"

# Each native executable is signed in place, and then must verify, as this application and with
# this build id, under the key it was signed with and under the latest release's copy of that
# key, which is what the copies installed now will check it against. This comes before the
# installers and the checksums, so the digests they carry are the signed files'. The polyglot
# `xek` is not signed, and need not be: a signature is what lets an executable be staged as an
# upgrade, and only a native one ever is; the executable a polyglot file unpacks embeds the
# keys, and so upgrades like any other.
verifies() {
  local build
  build=$(xek verify --public-key "$2" --app-id "$RELEASE_APP_ID" --in "$1") || return 1
  [[ "$build" == "$RELEASE_BUILD_ID" ]]
}

if [[ -n "$SIGNED" ]]; then
  FOREIGN=()
  [[ ! -f "$RELEASE_KEYS/signing.pub" ]] || FOREIGN=(--foreign-key)
  for f in "$WORK"/xek-*; do
    XEK_SIGN_SEED="$SIGNING_SEED" xek sign --key-env XEK_SIGN_SEED "${FOREIGN[@]}" \
      --in "$f" --out "$f.signed" >/dev/null || fail "signing $(basename "$f") failed"
    mv -f "$f.signed" "$f"
    verifies "$f" "$RELEASE_SIGNING_KEY" ||
      fail "$(basename "$f") does not verify under $RELEASE_SIGNING_KEY"
    [[ -z "${RELEASE_PREVIOUS_KEY:-}" ]] || verifies "$f" "$RELEASE_PREVIOUS_KEY" ||
      fail "$(basename "$f") does not verify under the latest release's key"
  done
  echo "client-assemble: signed every executable as $RELEASE_APP_ID, build $RELEASE_BUILD_ID, and verified each"
fi

# The installers served from https://propensive.dev/xek (and xek.ps1), which redirect to the
# latest release's `install.sh` and `install.ps1`: each downloads the native `xek-<platform>`
# for where it runs and checks it against the digest it embeds, taken here from the very files
# being uploaded, by `xek installer` itself.
./mill xek.cli.bootstrap installer --url "$BASE_URL" --release "$RELEASE_VERSION" --out "$WORK" \
  "$WORK"/xek-*

cp "$WORK/client"/client-* "$WORK"/xek "$WORK"/xek.cmd "$WORK"/xek-* "$WORK/install.sh" \
  "$WORK/install.ps1" "$RELEASE_ASSETS"/

# Every other asset's SHA-256, for downstream fetchers that verify by name: consumers pin an
# `xek` asset's line in their etc/xek.tsv.
SUMS="$RELEASE_ASSETS/$RELEASE_VERSION.SHA256SUMS"
for f in "$RELEASE_ASSETS"/*; do
  [[ "$f" == "$SUMS" ]] && continue
  printf '%s\t%s\n' "$(basename "$f")" "$(sha256 "$f")"
done | sort > "$SUMS"

count=$(find "$RELEASE_ASSETS" -maxdepth 1 -type f | wc -l | tr -d ' ')
echo "client-assemble: $count assets for $RELEASE_TAG in $RELEASE_ASSETS"
