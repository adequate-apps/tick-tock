# Personal configuration file

The personal configuration is the part of a player's setup worth carrying to another phone: their
own instrument ranges, their named horns, and the settings that describe how they practise. It
exports to one JSON file and imports on another device. Before an import applies anything, the
app lists every change the file would make (see "Import" below).

The code lives in `core/src/commonMain/kotlin/ticktock/core/config/`:
`PersonalConfigCodec` parses, validates and writes the file; `PersonalConfigMerge` computes the
import diff and the merged result. Both are pure functions with JVM tests.

## Format

```json
{
  "format": "ticktock-personal-config",
  "version": 1,
  "rangeOverrides": {
    "eb_alto": { "keyedLow": 57, "keyedHigh": 93 }
  },
  "hornProfiles": [
    { "id": "7c1e", "instrument": "eb_alto", "nickname": "Silver alto" }
  ],
  "settings": {
    "instrument": "eb_alto",
    "a4Hz": 442,
    "displayMode": "written",
    "spelling": "flats",
    "responseMode": "slow",
    "noiseFloorDb": -50,
    "keepAwake": true,
    "earsFirst": false,
    "announceSettledNotes": false,
    "droneVoice": "root-fifth",
    "clickSound": "beep",
    "visualOffsetMs": 15
  }
}
```

`format` and `version` are required. Every other field is optional: a file that carries only one
range override is a complete, valid file, and an import of it changes only that override.

### `rangeOverrides`

An object keyed by catalogue key (`eb_alto`, `bb_tenor`, `flute_c`, ...; the full list is
`ticktock.core.catalogue.Catalogue`). Each value gives the player's **keyed** range in written
MIDI (`C4` = 60, inclusive). The override replaces the keyed tier and widens the extended tier when
it reaches past it; it never narrows the extended tier (behaviour spec RNG-15).

Validation: the key names a catalogue instrument; `keyedLow` and `keyedHigh` are whole numbers
with `keyedLow <= keyedHigh`; both sound inside the notes the app reads (A0..C8 sounding, after
the instrument's transposition).

### `hornProfiles`

A list of named horns. `id` is any non-empty text that stays stable for that horn (the app
generates a random one); `instrument` is a catalogue key; `nickname` is non-empty text. Ids are
unique within the file. On import a profile with a known id replaces that profile; a new id is
added. Profiles are never removed by an import.

### `settings`

| Field | Type | Allowed values |
|---|---|---|
| `instrument` | text | a catalogue key |
| `a4Hz` | number | 415 to 466 inclusive (decision 16) |
| `displayMode` | text | `written`, `concert` |
| `spelling` | text | `auto`, `sharps`, `flats` |
| `responseMode` | text | `fast`, `normal`, `slow` |
| `noiseFloorDb` | whole number | -80 to -10 |
| `keepAwake` | boolean | |
| `earsFirst` | boolean | the readout hides while a note sounds (EAR-03) |
| `announceSettledNotes` | boolean | settled notes are read by a polite live region (TLK-02) |
| `droneVoice` | text | `sine`, `warm`, `root-fifth` |
| `clickSound` | text | `wood`, `click`, `beep` |
| `visualOffsetMs` | whole number | -500 to 500 |

The settings table is the prefs field table in `core/src/commonMain/kotlin/ticktock/core/prefs/PrefsSchema.kt`:
a row with a portable link travels in this file under the same key and the same spelling of its
value, and `PrefsPortable` moves the rows in both directions. Device-bound state stays out of the
file on purpose: permission grants, the paused-capture state, the measured Bluetooth offset, the
drone and click volumes, the metronome's current setup, diagnostics counters and practice history
belong to the phone they were set on.

## Versions

- A file whose `version` is **higher** than the app knows is refused with the sentence
  "version N is newer than this app reads (version 1); update the app to import it". Nothing is
  applied, because the newer file's values may carry meanings this build cannot check.
- A file with the **current** version and fields the app does not know keeps those fields: they
  are reported as warnings, held unread, and written back unchanged on the next export. A newer
  phone's extra settings survive a round trip through an older one.
- When this format changes in a way an old reader could misread, `version` goes up and this
  document gains a section per version.

## Validation

Validation is strict about values and lenient about vocabulary. A wrong value anywhere fails the
whole file, and every problem is listed at once with the path it was found at, for example:

```
rangeOverrides.eb_alto: keyedLow F#/Gb6 is above keyedHigh A#/Bb3
settings.a4Hz: expected a number from 415 to 466, found 414
hornProfiles[1].id: id "a" appears more than once
```

An unknown field is a warning, never an error.

## Import

An import is a merge in which the file wins wherever it says something and whatever it leaves out
stays as it is on this device. Nothing is deleted. Before applying, the app shows one sentence per
change, built by `PersonalConfigMerge.diff`:

```
Range for Alto saxophone in Eb: A#/Bb3..F#/Gb6 becomes A3..A6.
Horn profile "Shop tenor" (Tenor saxophone in Bb) is added.
A4: 440 Hz becomes 442 Hz.
Keep screen awake is set to on.
2 fields from a newer version of the app will be replaced or added.
```

The last line appears only when the file carries fields this version does not know whose values
differ from what the device holds; they are merged as they are, so the diff can only count them.
When the file changes nothing, the list is the single sentence "Nothing will change."
Applying the same file twice produces an empty diff the second time.

## Export

`PersonalConfigCodec.write` produces the file with two-space indentation and fields in the order
shown above, so two exports of the same configuration are byte-identical and a text diff between
two exports reads as a change list.
