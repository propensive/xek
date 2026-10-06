#!/bin/sh

# The @@NAME@@ installer, for:
#
#     curl -fsSL <url> | sh
#
# Detects the operating system and CPU architecture, downloads the matching `@@NAME@@`
# executable, verifies its SHA-256 against the digest embedded below, and installs it as
# `@@NAME@@` in ~/.local/bin (or $@@UPPER@@_INSTALL_DIR). POSIX shell only; no stdin is read,
# so piping from curl is safe.
#
# Generated for @@NAME@@ @@RELEASE@@ by `xek installer`; the digests are per-release.

set -e

name="@@NAME@@"
version="@@RELEASE@@"
base="@@BASE@@"

case "$(uname -s)" in
  Darwin)               os=macos ;;
  Linux)                os=linux ;;
  MINGW*|MSYS*|CYGWIN*) os=windows ;;
  *)                    echo "$name: unsupported operating system: $(uname -s)" >&2
                        echo "$name: (in PowerShell, run: irm $base/install.ps1 | iex)" >&2
                        exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64)  arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *)             echo "$name: unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

label="$os-$arch"

case "$label" in
@@DIGESTS@@
  *) echo "$name: no executable is published for $label" >&2; exit 1 ;;
esac

case "$os" in
  windows) suffix=.exe ;;
  *)       suffix= ;;
esac

url="$base/$name-$label$suffix"
dir="${@@UPPER@@_INSTALL_DIR:-$HOME/.local/bin}"
mkdir -p "$dir"
tmp="$dir/.$name.download.$$"
trap 'rm -f "$tmp"' EXIT

echo "Downloading $name $version for $label..."
if command -v curl >/dev/null 2>&1
then curl -fsSL "$url" -o "$tmp"
elif command -v wget >/dev/null 2>&1
then wget -qO "$tmp" "$url"
else echo "$name: neither curl nor wget is available" >&2; exit 1
fi

if command -v sha256sum >/dev/null 2>&1
then actual=$(sha256sum "$tmp" | cut -d' ' -f1)
elif command -v shasum >/dev/null 2>&1
then actual=$(shasum -a 256 "$tmp" | cut -d' ' -f1)
else actual=$(openssl dgst -sha256 "$tmp" | sed 's/.* //')
fi

if [ "$actual" != "$expected" ]
then
  echo "$name: checksum mismatch for $url" >&2
  echo "$name:   expected $expected" >&2
  echo "$name:   received $actual" >&2
  exit 1
fi

chmod +x "$tmp"
mv "$tmp" "$dir/$name$suffix"
trap - EXIT

echo "Installed $name $version to $dir/$name$suffix"

case ":$PATH:" in
  *:"$dir":*) ;;
  *) echo "Note: $dir is not on your PATH; add it with:"
     echo "    export PATH=\"$dir:\$PATH\"" ;;
esac

echo "The first run fetches $name's dependencies; subsequent runs start instantly."

# ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
# ┃                                                                                    ┃
# ┃  If this script has been PRINTED to your terminal, it has not been run: you are    ┃
# ┃  looking at the installer itself. To download and run it in one step, invoke:      ┃
# ┃                                                                                    ┃
# ┃      curl -fsSL <the URL you fetched this from> | sh                               ┃
# ┃                                                                                    ┃
# ┃  or, if you have already saved it to a file:                                       ┃
# ┃                                                                                    ┃
# ┃      sh install.sh                                                                 ┃
# ┃                                                                                    ┃
# ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
