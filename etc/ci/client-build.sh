#!/usr/bin/env bash
#
# Compile the reusable native client stubs (one per platform) with `cargo zigbuild`.
#
# These are the small (~0.2–0.3 MB), generic, version-independent launchers. An application
# binary is built by concatenating a stub, an ETHRCFG record and the app's JAR; the stubs
# themselves are reusable across applications and versions. This build is deliberately
# *separate* from the Mill build, which never compiles Rust: the stubs are data to it, and are
# released independently (`client-assemble.sh`, run by the release an `X.Y.Z` tag starts).
#
# Two things are checked and done here that the format depends on (spec/ethrcfg.md):
#
# - A stub must contain the magic `ETHRCFG` nowhere, because the client finds its record by
#   the FIRST occurrence in its own file. The client reassembles the magic at run time from an
#   obfuscated constant; this script proves that worked.
# - The macOS stubs are ad-hoc signed here, once. Building an executable never touches the
#   stub's bytes, so that signature stays valid in every executable built from it — which is
#   what lets a macOS executable be built on any host with no `codesign` at all.
#
# The stubs are built for size, and most of that comes from compiling the standard library
# itself (`-Zbuild-std`) with the pinned nightly in rust-toolchain.toml, rather than linking the
# precompiled one, which always carries the panic backtrace printer and its DWARF reader. The
# flags below are unstable and have changed before; AGENTS.md records what each is for and how
# to re-measure when a toolchain bump upsets them.
#
# Usage: ./etc/ci/client-build.sh [output-dir]      (default: dist/client)
#
# Produces <output-dir>/client-<label>[.exe] for each platform.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

OUT="${1:-dist/client}"

# triple|label|binary — the platforms the client is cross-compiled for.
TARGETS=(
  "x86_64-pc-windows-gnu|windows-x64|client.exe"
  "x86_64-unknown-linux-gnu|linux-x64|client"
  "aarch64-unknown-linux-gnu|linux-arm64|client"
  "x86_64-apple-darwin|macos-x64|client"
  "aarch64-apple-darwin|macos-arm64|client"
)

if ! command -v cargo >/dev/null 2>&1; then
  echo "client-build: cargo (with the zigbuild subcommand) is required" >&2; exit 1
fi

target_dir=$(mktemp -d)
trap 'rm -rf "$target_dir"' EXIT

triple_args=()
for entry in "${TARGETS[@]}"; do
  IFS='|' read -r triple _ _ <<< "$entry"
  triple_args+=(--target "$triple")
done

# Flags for every target: abort on panic with no message or backtrace machinery, and no source
# locations or `{:?}` bodies baked into the binary (only `debug!` output uses them).
size_flags="-Zunstable-options -Cpanic=immediate-abort -Zlocation-detail=none -Zfmt-debug=none"
# Linux only: no unwind tables. Nothing in the stub unwinds (panics abort), and `.eh_frame` was
# a sixth of the Linux stubs. Windows refuses the flag; macOS gains nothing from it.
linux_flags="$size_flags -Cforce-unwind-tables=no"
# Per-target, not RUSTFLAGS — a plain RUSTFLAGS would override all of these.
export CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS="$size_flags"
export CARGO_TARGET_X86_64_APPLE_DARWIN_RUSTFLAGS="$size_flags"
export CARGO_TARGET_AARCH64_APPLE_DARWIN_RUSTFLAGS="$size_flags"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_RUSTFLAGS="$linux_flags"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUSTFLAGS="$linux_flags -Clinker=$PWD/etc/ci/zigcc-aarch64-linux.sh"

echo "client-build: cross-compiling ${#TARGETS[@]} client stubs (release, $(rustc --version))…"
cargo zigbuild --release \
  --manifest-path Cargo.toml \
  --target-dir "$target_dir" \
  -Zbuild-std=std,panic_abort \
  -Zbuild-std-features=optimize_for_size \
  "${triple_args[@]}"

mkdir -p "$OUT"
for entry in "${TARGETS[@]}"; do
  IFS='|' read -r triple label binary <<< "$entry"
  ext=""; [[ "$binary" == *.exe ]] && ext=".exe"
  cp -f "$target_dir/$triple/release/$binary" "$OUT/client-$label$ext"
  chmod +x "$OUT/client-$label$ext"
done

for f in "$OUT"/client-*; do
  if LC_ALL=C grep -q 'ETHRCFG' "$f"; then
    echo "client-build: $f contains the ETHRCFG magic; a stub must not (see spec/ethrcfg.md)" >&2
    exit 1
  fi
done
echo "client-build: no stub contains the ETHRCFG magic"

# Ad-hoc sign the macOS stubs. Apple's `codesign` on a Mac, `rcodesign` (apple-codesign)
# elsewhere; both produce a valid ad-hoc signature. Hashes are taken from the signed bytes.
for f in "$OUT"/client-macos-*; do
  if command -v codesign >/dev/null 2>&1; then
    codesign --sign - --force "$f"
  elif command -v rcodesign >/dev/null 2>&1; then
    rcodesign sign "$f" >/dev/null
  else
    echo "client-build: neither codesign nor rcodesign found; macOS stubs are unsigned" >&2
    exit 1
  fi
done
echo "client-build: signed the macOS stubs"

echo "client-build: built into $OUT:"
ls -la "$OUT"/client-*
