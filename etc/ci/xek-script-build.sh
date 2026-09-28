#!/usr/bin/env bash
#
# Assemble the polyglot `xek` builder script from its three shell sections and the launcher
# templates, and write `dist/xek` and a byte-identical `dist/xek.cmd`.
#
# The result is one file that runs as bash, cmd.exe and PowerShell (via res/core/xek/xek.tmpl),
# carrying the ten launcher templates in a base64 payload region so it can generate installer,
# online-launcher and dispatcher scripts with no other files. Publishing is a data change: the
# release version, base URL and stub hashes are baked in here.
#
# Usage: ./etc/ci/xek-script-build.sh <version> <runners-url> <manifest.tsv> [out]
#          (or `make xek-script`)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

VERSION="${1:?usage: xek-script-build.sh <version> <runners-url> <manifest.tsv> [out]}"
URL="${2:?missing runners base URL}"
MANIFEST="${3:?missing manifest tsv}"
OUT="${4:-dist/xek}"

RES=res/core/xek
SRC=src/script

# The launcher templates the builder embeds (uncompressed). xek.tmpl carries the @@markers@@
# the builder substitutes the per-delivery templates into at generation time.
TEMPLATES=(
  xek.tmpl
  xek-installer.sh xek-installer.bat xek-installer.ps1
  xek-onlinelauncher.sh xek-onlinelauncher.bat xek-onlinelauncher.ps1
  xek-dispatcher.sh xek-dispatcher.bat xek-dispatcher.ps1
)

mkdir -p "$(dirname "$OUT")"
tmp=$(mktemp)

# 1. The polyglot prefix: xek.tmpl with the builder's own three sections spliced in, and the
#    header's ${VERSION} filled.
VERSION="$VERSION" envsubst '$VERSION' < "$RES/xek.tmpl" | awk \
  -v batf="$SRC/xek-build.bat" -v ps1f="$SRC/xek-build.ps1" -v shf="$SRC/xek-build.sh" '
  function dump(f,  l){ while ((getline l < f) > 0) print l; close(f) }
  /@@BAT@@/ { dump(batf); next }
  /@@PS1@@/ { dump(ps1f); next }
  /@@SH@@/  { dump(shf);  next }
  { print }
' > "$tmp"

# 2. The payload region: baked metadata, then the manifest, then the templates.
{
  printf '# XEK_VERSION=%s\n' "$VERSION"
  printf '# RUNNERS_URL=%s\n' "$URL"

  # runners:label=sha256,...  (from the tab-separated manifest, platform rows only)
  runners=$(awk -F'\t' '$1 ~ /^(linux|macos|windows)-/ {printf "%s%s=%s", sep, $1, $2; sep=","} END{print ""}' "$MANIFEST")
  printf 'runners:%s\n' "$runners"

  # Encode each template, computing 1-based line offsets relative to the index line, matching
  # the extractor in xek-build.sh (`absline = index_num + off + 1`).
  staging=$(mktemp -d)
  index=""; offset=1
  for t in "${TEMPLATES[@]}"; do
    base64 < "$RES/$t" | tr -d '\r\n' | fold -w 8000 > "$staging/$t.b64"
    lines=$(wc -l < "$staging/$t.b64" | tr -d ' ')
    [ -s "$staging/$t.b64" ] && lines=$((lines + 1))   # fold leaves the last slice unterminated
    [ -n "$index" ] && index="$index,"
    index="$index$t=$offset"
    offset=$((offset + lines + 2))
  done
  printf 'index:%s\n' "$index"
  for t in "${TEMPLATES[@]}"; do
    printf -- '-----BEGIN CERTIFICATE-----\n'
    cat "$staging/$t.b64"
    printf '\n-----END CERTIFICATE-----\n'
  done
  rm -rf "$staging"

  printf '#>\n'
} >> "$tmp"

cp -f "$tmp" "$OUT"; chmod +x "$OUT"
cp -f "$tmp" "${OUT}.cmd"
rm -f "$tmp"
echo "xek-script-build: wrote $OUT and ${OUT}.cmd ($(wc -c < "$OUT") bytes)"
