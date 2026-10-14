#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Proves every 64-bit native library in a build is linked for 16 KB pages
# (DST-03). Devices with 16 KB pages refuse to load a library whose LOAD
# segments are aligned to 4 KB, and Play blocks updates of apps that carry
# one, so a stale prebuilt binary must fail the gate rather than the player.
#
#   tools/check-alignment.sh [APK_OR_DIR ...]
#
# Default: the debug APK under app/build/outputs. An APK is unpacked into a
# temporary directory; a directory is searched as is. Only arm64-v8a and
# x86_64 count: 32-bit ABIs never run on a 16 KB-page kernel. Every LOAD
# segment of every library must report an alignment of at least 0x4000, and
# finding no library at all is a failure, because a check that checked
# nothing would stay green on a broken build.
#
# Reads ELF headers with the NDK's llvm-readelf when ANDROID_HOME (or
# ANDROID_SDK_ROOT) and gradle/libs.versions.toml point at one, otherwise with
# readelf from binutils.
set -euo pipefail

self_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$self_dir/.." && pwd)

page_bytes=16384
abis='arm64-v8a x86_64'

readelf_bin=""
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk=$(sed -n 's/^ndk *= *"\([^"]*\)".*/\1/p' "$root/gradle/libs.versions.toml")
candidate="$sdk/ndk/$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
if [ -n "$sdk" ] && [ -x "$candidate" ]; then
    readelf_bin="$candidate"
elif command -v readelf > /dev/null 2>&1; then
    readelf_bin=$(command -v readelf)
else
    echo "check-alignment: neither the NDK's llvm-readelf nor binutils readelf is available" >&2
    exit 2
fi

[ $# -gt 0 ] || set -- "$root/app/build/outputs/apk/debug/app-debug.apk"

scratch=$(mktemp -d "${TMPDIR:-/tmp}/check-alignment.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

# Collects the libraries to check, one path per line, from an APK or a directory.
libraries() {
    local target="$1" dir abi
    if [ -d "$target" ]; then
        dir="$target"
    elif [ -f "$target" ]; then
        dir="$scratch/$(basename "$target")"
        mkdir -p "$dir"
        # The lib/ tree only; a zip with no libraries is an empty, valid unpack.
        unzip -q -o -d "$dir" "$target" 'lib/*' 2> /dev/null || true
    else
        echo "check-alignment: no APK or directory at $target (run :app:assembleDebug first)" >&2
        exit 2
    fi
    for abi in $abis; do
        find "$dir" -type f -name '*.so' -path "*/$abi/*" | sort
    done
}

# Smallest LOAD alignment of one library, in bytes; the alignment is the last
# field of a LOAD line in both llvm-readelf and binutils output. The hex is
# converted by the shell because mawk has no strtonum.
min_load_alignment() {
    local min=0 field align
    while IFS= read -r field; do
        align=$(( field ))
        if [ "$min" -eq 0 ] || [ "$align" -lt "$min" ]; then min=$align; fi
    done < <("$readelf_bin" -lW "$1" | awk '$1 == "LOAD" { print $NF }')
    echo "$min"
}

checked=0
bad=0
for target in "$@"; do
    while IFS= read -r lib; do
        [ -n "$lib" ] || continue
        align=$(min_load_alignment "$lib")
        shown="${lib#"$scratch"/}"
        shown="${shown#"$root"/}"
        if [ "$align" -ge "$page_bytes" ]; then
            printf '  ok    %-10s %s\n' "$(printf '0x%x' "$align")" "$shown"
        else
            printf '  BAD   %-10s %s\n' "$(printf '0x%x' "$align")" "$shown"
            bad=$((bad + 1))
        fi
        checked=$((checked + 1))
    done < <(libraries "$target")
done

if [ "$checked" -eq 0 ]; then
    echo "check-alignment: no 64-bit native library found in $*; the engine library is missing" >&2
    exit 1
fi
if [ "$bad" -gt 0 ]; then
    echo "check-alignment: $bad of $checked libraries not aligned to 16 KB pages (LOAD alignment under 0x4000)" >&2
    exit 1
fi
echo "check-alignment: $checked 64-bit libraries aligned to 16 KB pages"
