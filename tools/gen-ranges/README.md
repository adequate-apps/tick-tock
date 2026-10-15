# Range generators

The app's instrument ranges come in three layers (see `docs/DECISIONS.md`, decision 3):

1. **Base**: `core/src/jvmMain/resources/ranges/base.json`, written by `gen_ranges.py` from
   MuseScore's `instruments.xml` (GPL-3.0, credited in `THIRD_PARTY.md`). Values are copied in
   sounding MIDI with the transposition MuseScore declares. The XML stays out of the repository.
2. **App overlay**: `core/src/jvmMain/resources/ranges/overlay.json`, written by `gen_overlay.py`
   from the owner's curated corrections. Every entry carries a reason.
3. **User**: personal overrides, kept in the portable personal configuration
   (`docs/personal-config.md`).

`ticktock.core.ranges.RangeResolver` applies them in that order and reports which layer set each
end of the effective range.

## Inputs and their provenance

Both inputs stay outside the repository, in one directory the owner keeps:

| Input | Committed output | Provenance |
|---|---|---|
| `musescore-instruments.xml` | `base.json` | MuseScore's `instruments.xml`, unmodified; `base.json` records its sha256. |
| `range-overlay-source.with-decisions.json` | `overlay.json` | The canonical overlay source. The keyed and extended exceptions and the key map are the owner's curated corrections. The twenty `DECISIONS` entries (RNG-05: keys whose professional top sits 8 or more semitones above the amateur top) were drafted during M0 by the core builder from the ranges the specification lists per family; the owner has not yet confirmed their wording. `overlay.json` records the file's name and sha256. |

The earlier `range-overlay-source.json` holds the same corrections without the decisions block; it
is superseded and produces an overlay that fails RNG-05.

## Regenerating

```sh
python3 -I tools/gen-ranges/gen_ranges.py /path/to/inputs/musescore-instruments.xml
python3 -I tools/gen-ranges/gen_overlay.py /path/to/inputs/range-overlay-source.with-decisions.json
JAVA_HOME=/path/to/jdk-21 ./gradlew :core:jvmTest
```

## Checking reproduction (RNG-03)

```sh
tools/gen-ranges/check.sh /path/to/inputs
```

Regenerates both files into a scratch directory and compares them byte for byte with the committed
ones, after checking that each input's sha256 matches the one recorded in the committed file.
`tools/verify.sh` runs this check whenever `TICKTOCK_RANGE_INPUTS` names the input directory and
says so when it does not (the inputs are never on CI). `RangeLayerDataTest` checks that both files
carry a source hash either way.

`instrument-ids.json` maps each catalogue key to a MuseScore instrument id. `CatalogueTest`
asserts the Kotlin catalogue and this file name the same keys and ids, so the generator and the
app can never disagree about which entry a key means.

`base.json` records the sha256 of the XML it was generated from and `overlay.json` the sha256 of
its source document. Re-running a generator on the same input regenerates the file byte for byte; a
changed hash means a different input and is a reviewed data change. The golden test (`RangeGoldenTest`) pins every key's effective keyed and
extended range, so any change to either layer shows up as a reviewed golden update.
