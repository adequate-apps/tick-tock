#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Write the base range layer from MuseScore's instrument data.

Usage: gen_ranges.py INSTRUMENTS_XML [OUT_JSON]

The XML stays out of the repository; this script is run by hand when the base
layer changes and the result is committed. The output is the base layer only:
MuseScore's values copied as they stand, in sounding MIDI, with the transposition
the entry declares. Nothing here interprets the data. The catalogue decides the
transposition that converts these to written pitch, and the app overlay carries
every curated correction, so the base can be swapped for another source by
changing this file and the data it writes.

Only the standard library is used, so the script runs on any Python 3.

Keyed tier: MuseScore `aPitchRange` (early amateur). Extended tier: `pPitchRange`
(professional). Both are sounding MIDI, inclusive.
"""

import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
IDS_FILE = HERE / "instrument-ids.json"
DEFAULT_OUT = HERE.parent.parent / "core" / "src" / "main" / "resources" / "ranges" / "base.json"

SCHEMA = "ticktock-range-base"
VERSION = 1


def text(element, tag):
    child = element.find(tag)
    if child is None or child.text is None:
        return None
    return child.text.strip()


def span(value):
    low, high = (int(part) for part in value.split("-"))
    if low > high:
        raise ValueError(f"range {value} runs backwards")
    return [low, high]


def main(argv):
    if len(argv) < 2 or len(argv) > 3:
        print(__doc__, file=sys.stderr)
        return 2
    xml_path = Path(argv[1])
    out_path = Path(argv[2]) if len(argv) == 3 else DEFAULT_OUT

    xml_bytes = xml_path.read_bytes()
    root = ET.fromstring(xml_bytes)
    ids = json.loads(IDS_FILE.read_text())

    # The first entry wins when MuseScore repeats an id; the mapped ids are unique
    # today and the generator fails loudly if that ever changes.
    by_id = {}
    for instrument in root.iter("Instrument"):
        musescore_id = instrument.get("id")
        if musescore_id in by_id and musescore_id in ids.values():
            raise SystemExit(f"MuseScore id {musescore_id} appears twice; choose one")
        by_id.setdefault(musescore_id, instrument)

    entries = {}
    for key, musescore_id in ids.items():
        instrument = by_id.get(musescore_id)
        if instrument is None:
            raise SystemExit(f"{key}: MuseScore id {musescore_id} is missing from the XML")
        keyed = text(instrument, "aPitchRange")
        extended = text(instrument, "pPitchRange")
        if keyed is None or extended is None:
            raise SystemExit(f"{key}: {musescore_id} has no amateur or professional range")
        chromatic = text(instrument, "transposeChromatic")
        entries[key] = {
            "musescoreId": musescore_id,
            "musescoreName": text(instrument, "longName") or text(instrument, "trackName") or musescore_id,
            "musescoreFamily": text(instrument, "family") or "",
            "transposeChromatic": int(chromatic) if chromatic else 0,
            "soundingKeyed": span(keyed),
            "soundingExtended": span(extended),
        }

    document = {
        "schema": SCHEMA,
        "version": VERSION,
        "credit": (
            "Instrument ranges from MuseScore's instruments.xml, licensed GPL-3.0. "
            "Keyed tier = early-amateur range (aPitchRange); extended tier = professional range "
            "(pPitchRange). Values are sounding MIDI, inclusive, copied without interpretation."
        ),
        "generator": "tools/gen-ranges/gen_ranges.py",
        "sourceSha256": hashlib.sha256(xml_bytes).hexdigest(),
        "instruments": entries,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {len(entries)} instruments to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
