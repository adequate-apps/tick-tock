#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# RNG-03: proves the committed range layers are what the generators produce
# from their inputs. Regenerates both files into a scratch directory and
# compares byte for byte.
#
#   tools/gen-ranges/check.sh INPUT_DIR
#
# INPUT_DIR holds the inputs, which stay outside the repository:
#   musescore-instruments.xml                  MuseScore's instruments.xml
#   range-overlay-source.with-decisions.json   the curated overlay source
# The committed files record each input's sha256; a mismatch there is
# reported before anything is regenerated. Exit 0 when both reproduce, 1 on
# any difference, 2 when an input is missing.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
inputs="${1:?usage: check.sh INPUT_DIR}"
xml="$inputs/musescore-instruments.xml"
overlay_src="$inputs/range-overlay-source.with-decisions.json"
for f in "$xml" "$overlay_src"; do
    [ -f "$f" ] || { echo "gen-ranges check: missing input $f" >&2; exit 2; }
done

base="$root/core/src/jvmMain/resources/ranges/base.json"
overlay="$root/core/src/jvmMain/resources/ranges/overlay.json"
recorded_hash() { sed -n 's/^ *"sourceSha256": *"\([0-9a-f]*\)".*/\1/p' "$1" | head -1; }
actual_hash() { sha256sum "$1" | cut -d' ' -f1; }

status=0
for pair in "$base|$xml" "$overlay|$overlay_src"; do
    committed=${pair%%|*}; input=${pair#*|}
    if [ "$(recorded_hash "$committed")" != "$(actual_hash "$input")" ]; then
        echo "gen-ranges check: ${committed#"$root"/} was generated from a different ${input##*/} (sha256 differs)"
        status=1
    fi
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 -I "$root/tools/gen-ranges/gen_ranges.py" "$xml" "$work/base.json" > /dev/null
python3 -I "$root/tools/gen-ranges/gen_overlay.py" "$overlay_src" "$work/overlay.json" > /dev/null
for name in base overlay; do
    if cmp -s "$work/$name.json" "$root/core/src/jvmMain/resources/ranges/$name.json"; then
        echo "gen-ranges check: $name.json reproduces byte for byte"
    else
        echo "gen-ranges check: $name.json differs from the generator's output" >&2
        diff -u "$root/core/src/jvmMain/resources/ranges/$name.json" "$work/$name.json" | head -40 || true
        status=1
    fi
done
exit $status
