# Range generators

The app's instrument ranges come in three layers (see `docs/DECISIONS.md`, decision 3):

1. **Base**: `core/src/main/resources/ranges/base.json`, written by `gen_ranges.py` from
   MuseScore's `instruments.xml` (GPL-3.0, credited in `THIRD_PARTY.md`). Values are copied in
   sounding MIDI with the transposition MuseScore declares. The XML stays out of the repository.
2. **App overlay**: `core/src/main/resources/ranges/overlay.json`, written by `gen_overlay.py`
   from the owner's curated corrections. Every entry carries a reason.
3. **User**: personal overrides, kept in the portable personal configuration
   (`docs/personal-config.md`).

`ticktock.core.ranges.RangeResolver` applies them in that order and reports which layer set each
end of the effective range.

## Regenerating

```sh
python3 -I tools/gen-ranges/gen_ranges.py /path/to/instruments.xml
python3 -I tools/gen-ranges/gen_overlay.py /path/to/range-overlay-source.json
JAVA_HOME=/path/to/jdk-21 ./gradlew :core:test
```

`instrument-ids.json` maps each catalogue key to a MuseScore instrument id. `CatalogueTest`
asserts the Kotlin catalogue and this file name the same keys and ids, so the generator and the
app can never disagree about which entry a key means.

`base.json` records the sha256 of the XML it was generated from. Re-running the generator on the
same XML regenerates the file byte for byte; a changed hash means a different upstream revision and
is a reviewed data change. The golden test (`RangeGoldenTest`) pins every key's effective keyed and
extended range, so any change to either layer shows up as a reviewed golden update.
