#!/bin/sh
#
# Linker shim for the aarch64-unknown-linux-gnu stub. Recent nightlies pass
# `-Wl,--fix-cortex-a53-843419` (a Cortex-A53 erratum workaround) for this target, and zig's
# `cc` driver, which cargo-zigbuild puts behind the linker, rejects it as unsupported. Drop that
# one argument and hand the rest to the linker cargo-zigbuild configured, which it exports to
# the build in the variable below. Remove this shim once cargo-zigbuild filters the flag itself.

for arg in "$@"; do
  shift
  case "$arg" in
    -Wl,--fix-cortex-a53-843419) ;;
    *) set -- "$@" "$arg" ;;
  esac
done

if [ -z "${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER:-}" ]; then
  echo "zigcc-aarch64-linux: CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER is not set; run this through cargo zigbuild" >&2
  exit 1
fi
exec "$CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER" "$@"
