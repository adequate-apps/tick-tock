# Decisions

Owner decisions that shape the rebuild. Each one overrides anything in the planning notes that
disagrees with it. Changing a decision here is an owner action, never an agent's.

## 1. Minimum SDK 28

AAudio usage and content types and the UNPROCESSED input preset are API 28 calls. Supporting
API 26 would mean a second audio path, for about one to two percent of devices.

## 2. Install checkpoints

Every new build artifact is a reviewed event. The M0 set was approved as one unit:

- the `org.jetbrains.kotlin.jvm` plugin marker (makes `:core` a pure JVM module);
- `androidx.test` runner, rules, ext-junit and orchestrator;
- Compose `ui-test-junit4` and `ui-test-manifest`;
- Robolectric 4.16.1, Roborazzi 1.63.0 and the Robolectric android-all jar for API 36;
- one online Gradle resolution that writes `gradle/verification-metadata.xml` with sha256 for every
  artifact.

Anything outside that set is a new checkpoint: the agent stops and asks, stating package, exact
version, source, purpose, transitive impact, lifecycle scripts, release age, provenance and
rollback.

## 3. Instrument ranges come in layers, and personal configuration is portable

- Layer 1 is the generated base table, derived from MuseScore's instrument data (credited in
  `THIRD_PARTY.md`). The base data sits in its own file behind the generator, so it can later be
  replaced by independently derived ranges by changing data only.
- Layer 2 is an app-wide overlay the project curates: a versioned data file with a reason on every
  entry.
- Layer 3 is the player's own overrides, kept in their personal configuration.
- The effective range is base, then overlay, then player. The UI shows which layer set each end.
- The personal configuration (overrides, horn profiles, settings worth carrying) exports to one
  versioned, documented file and imports on another device. An import shows what it will change
  before applying.

## 4. Background playback ships in v1.2, with a media-style notification

- The app coexists with music players: someone may play along with a track while tuning or using
  the metronome or drone. TickTock requests no exclusive audio focus and never treats another app's
  playback as a reason to stop.
- An incoming call still stops output.
- In 2.0, moving to the background stops sound and the microphone, and nothing resumes on its own.

## 5. Storage, sessions and sharing

- Unsaved raw readings expire after 7 days. Per-note curves are kept, and space use is visible in
  Settings.
- Players can name and save sessions (the horn, the shop, notes, the date). A saved session keeps
  its readings, its per-instrument intonation tables and any recordings until the player deletes it.
- A saved session exports as a shareable package. Someone without the app opens it as a
  self-contained HTML report with the tables, a CSV and standard audio files; someone with the app
  imports it as a saved session.
- Named sessions and saving the table ship in 2.0. Recordings in sessions and the shareable package
  ship in v1.1 together with the recording deck.

## 6. Application id is a deliberate placeholder

The id is `com.example.ticktock.placeholder`. Stores reject the `com.example` namespace, so an
unfinished build cannot ship by accident, and `app/build.gradle.kts` fails every release build while
the id starts with `com.example`. The owner sets the real id, in their own namespace, before the
first release; it is permanent once published.

## 7. Licence: GPL-3.0-or-later, fully owned

- The code is GPL-3.0-or-later and the copyright is held solely by the owner, so the project can be
  dual-licensed or sold later. Contributions from anyone else need a licence agreement first.
- No code from a third-party upstream author enters this tree, in any form. Agents implement from
  the specification documents and the MuseScore data under clean-room rules: never open that
  author's source, read the previous 1.x app for behaviour only, copy no line.
- No third-party GPL code. MuseScore's instrument ranges are used as factual data and credited.

## 8. Name

TickTock is the working name and will be workshopped later. It replaces the earlier name in all
user-facing text; the bell icon stays for now. The name lives in exactly one string resource and one
Kotlin constant so a rename is a two-line change.

## Resolved questions

- **Signing.** 1.x was never on a store and had no release keystore, so 2.0 starts with a new
  upload key under the new application id. 1.x users uninstall the old app.
  Custody of that key (where the keystore lives, its two encrypted copies and the name of the CI
  secret that carries it) is an owner action still to be recorded here; the M0 exit criterion
  "upload-key custody recorded" stays open until it is.
- **Data migration from 1.x.** None. A new application id cannot read the old app's private storage,
  and the old history carried breath noise and four wrong transpositions.
- **A4 range** stays 430–450 Hz. **Half-cent rounding** is away from zero. **Sine voice:** an output
  open failure names the device and the refused rates; sine is a selectable voice.
- **Detector language.** Kotlin in `:core`, gated by a per-hop cost measurement on the reference
  phone at M2, with a C++ move behind the same interface as the fallback.
- **Storage engine.** Framework SQLite with hand-written migrations and one JSON prefs file. Room
  and DataStore are the upgrade path if the schema ever passes about six tables.
