#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Write the app overlay layer from the owner's curated range corrections.

Usage: gen_overlay.py OVERLAY_SOURCE_JSON [OUT_JSON]

The source file is the owner's working document: per-key keyed and extended
exceptions in written MIDI, each with the reason it exists, plus the explicit
decisions for keys where MuseScore's professional top sits far above its amateur
top. This script only reshapes it into the versioned overlay the app loads, so
the committed overlay and the owner's notes never drift apart.

Source layout, all optional:
  KEYED_EXCEPTIONS    {key: [[low, high], reason]}   replaces the keyed tier
  EXTENDED_EXCEPTIONS {key: [[low, high], reason]}   replaces the extended tier
  HAND_SET            {key: [[low, high], reason]}   keyed tier for keys with no base entry
  DECISIONS           {key: reason}                  accepted professional tops (RNG-05)
"""

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_OUT = HERE.parent.parent / "core" / "src" / "main" / "resources" / "ranges" / "overlay.json"

SCHEMA = "ticktock-range-overlay"
VERSION = 1


def entries(table):
    result = {}
    for key, (span, reason) in table.items():
        low, high = span
        if low > high:
            raise SystemExit(f"{key}: {span} runs backwards")
        if not reason.strip():
            raise SystemExit(f"{key}: every overlay entry needs a reason")
        result[key] = {"low": low, "high": high, "reason": reason}
    return result


def main(argv):
    if len(argv) < 2 or len(argv) > 3:
        print(__doc__, file=sys.stderr)
        return 2
    source = json.loads(Path(argv[1]).read_text())
    out_path = Path(argv[2]) if len(argv) == 3 else DEFAULT_OUT

    keyed = entries(source.get("KEYED_EXCEPTIONS", {}))
    keyed.update(entries(source.get("HAND_SET", {})))
    extended = entries(source.get("EXTENDED_EXCEPTIONS", {}))
    decisions = dict(source.get("DECISIONS", {}))
    for key, reason in decisions.items():
        if not reason.strip():
            raise SystemExit(f"{key}: a decision needs its reason")

    document = {
        "schema": SCHEMA,
        "version": VERSION,
        "note": (
            "App-wide corrections to the base layer, in written MIDI. A keyed entry replaces the "
            "keyed tier; an extended entry replaces the extended tier; the resolver then widens "
            "extended to cover keyed. Decisions record why a wide professional top was accepted."
        ),
        "keyed": keyed,
        "extended": extended,
        "decisions": decisions,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {len(keyed)} keyed, {len(extended)} extended, {len(decisions)} decisions to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
