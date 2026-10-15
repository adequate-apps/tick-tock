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

## 4. Background playback ships in v1.1, with a media-style notification

Moved from v1.2 to v1.1 on 2026-10-14 when the scope revision was adopted: competing metronomes ship
it free, and a metronome that stops when the screen locks is the likeliest one-star review.

- The app coexists with music players: someone may play along with a track while tuning or using
  the metronome or drone. TickTock requests no exclusive audio focus and never treats another app's
  playback as a reason to stop.
- An incoming call still stops output (decision 12 has the detail).
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

## 6. Application id: a placeholder until the brand was chosen

Superseded on 2026-10-14 by decision 17: the id is now `com.adequateapps.metronome`. Until then the
id was `com.example.ticktock.placeholder`. Stores reject the `com.example` namespace, so an
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

The product is **Adequate Metronome**, by **AdequateApps** (decision 17). TickTock stays the codename
for the repository, the Kotlin packages and internal documents. The user-visible name lives in
exactly one string resource and one Kotlin constant, so a rename is a two-line change.

## 12. No audio focus; a call is detected and parks the tools

Decisions 9 to 11 are the resolved questions at the end of this file. The scope revision of
2026-10-14 adds the five below.

- TickTock requests no audio focus, in any mode. Android mutes a focus-holding app during a call
  and at no other time, so focus bought nothing that decision 4 allows and cost coexistence with
  music players. The focus request, the LOSS and CAN_DUCK handling and the criteria OUT-01's
  focus clause, OUT-08 and OUT-09 are gone. The duck ramp stays as an engine command for the
  bleed policy only.
- Calls are detected from the audio mode, on the control thread: a mode-changed listener on
  API 31 and later, a 2 Hz poll of the mode on API 28 to 30. No telephony callback, because that
  needs READ_PHONE_STATE and the manifest stays at RECORD_AUDIO.
- Ringing silences: the ringtone mode adds a mute reason within 500 ms and removes it when the
  mode returns to normal, so a call the player declines costs nothing.
- An answered call parks: the in-call, in-communication and call-screening modes park every
  registered tool with "Paused: phone call", and nothing restarts on its own; the player starts
  the tools again. A call during a recording therefore never unmutes practice sounds into the
  take.
- The microphone side is told the truth: the recording callback reports when the client is
  silenced by a call or another app, and the status sentence says so.
- The output registry's empty-to-one and one-to-empty transitions carry the `ForegroundOwner`
  hooks, the only places a media-playback service may start and stop in the release that ships
  background playback. 2.0 wires a no-op.

## 13. The output stream opens in SHARED mode

SHARED sharing mode with LOW_LATENCY performance mode; sample rate and frames per callback left
unspecified so the device chooses; buffer size two bursts, grown one burst at a time on xruns up
to four, each step recorded in Diagnostics. Exclusive MMAP has a documented unplug bug on API 28
and 29 and saves perhaps 10 to 20 ms that the tuner never feels. On API 37 the routing-changed
callback re-classifies the route and re-measures latency without a disconnect.

## 14. 2.0 ships on Google Play, with the same build free on GitHub

- M7 is a Play release: an AAB and a universal APK from one build invocation; the fresh-clone
  rule holds for both.
- The owner generates the app-signing key offline and uploads it to Play App Signing, so the
  Play build and the GitHub APK share one signature and an install from either channel updates
  from the other.
- A closed test with twelve testers over fourteen days precedes production. The fourteen days are
  wall clock and start when M6 exits.
- The Data safety form declares no data collected. The privacy policy lives at a stable URL under
  `docs/` and is linked from Settings › About. The target audience is 13 and up unless the owner
  opts into Families.
- The GitHub APK channel needs the owner's developer-verification registration before it is
  required for every app in 2027.
- The manifest allowlist is one file per release (`tools/manifest-allowlist-<major.minor>.txt`),
  and every 64-bit native library must be linked for 16 KB pages, both checked by the gate.

## 15. One price: USD 5.99, everything included

- One paid-upfront listing at USD 5.99. No in-app purchases, no subscription, no Billing Library,
  no licence check, no network, no account. The source and a signed APK of the identical build are
  free on GitHub under decision 7's licence.
- A licence check in GPL code can be removed by any recipient, so a check buys nothing and would
  cost a backend the app otherwise never needs. What the buyer pays for is the Play channel and the
  author's continued work.
- The `Entitlement` interface in `core/contracts` has one implementation, `AlwaysEntitled`, and no
  call sites in 2.0; the three gated features are the all-history scope, named sessions and export.
  It exists so that the reserve plan, a free listing with a single non-consumable unlock at the
  same price, stays a one-module change. The reserve plan is built only if 2.0's sales say so, and
  anything free at launch stays free.
- Play's fee change for new versus existing installs (from 2026-06-30 in the US, EEA and UK) is to
  be read before the listing goes live; the price stands until that reading says otherwise.

## 16. A4 ranges 415 to 466 Hz

The A4 stepper runs 415 to 466 Hz in 1 Hz steps with 440 as the default. 415 covers baroque pitch;
466 is the upper end that keeps every A4 within one semitone of 440, so note naming stays
unambiguous. A run opens on every change as before. The code lands in M1 (the personal-config
range, the stepper, the sweep ends); this replaces the 430 to 450 range in the resolved
questions below.

## Resolved questions

- **Signing.** 1.x was never on a store and had no release keystore, so 2.0 starts with a new
  upload key under the new application id. 1.x users uninstall the old app.
  Custody of that key (where the keystore lives, its two encrypted copies and the name of the CI
  secret that carries it) is an owner action still to be recorded here; the M0 exit criterion
  "upload-key custody recorded" stays open until it is.
- **Data migration from 1.x.** None. A new application id cannot read the old app's private storage,
  and the old history carried breath noise and four wrong transpositions.
- **A4 range** was 430–450 Hz until decision 16 widened it to 415–466 Hz. **Half-cent rounding** is
  away from zero. **Sine voice:** an output open failure names the device and the refused rates;
  sine is a selectable voice.
- **Detector language.** Kotlin in `:core`, gated by a per-hop cost measurement on the reference
  phone at M2, with a C++ move behind the same interface as the fallback.
- **Storage engine.** Framework SQLite with hand-written migrations and one JSON prefs file. Room
  and DataStore are the upgrade path if the schema ever passes about six tables.

## 17. Brand: AdequateApps, product: Adequate Metronome

- The company brand is **AdequateApps** (adequateapps.com); this app is **Adequate Metronome**, served
  at adequateapps.com/metronome. Later products follow the pattern "Adequate X".
- Names by place: the app drawer and the in-app title say "Metronome"; the store listing and the
  splash say "Adequate Metronome". While running, the title gets out of the way most of the time.
  Store keywords (tuner, drone) go in the short description.
- Play developer name: "AdequateApps". Another studio already publishes as "Adequate".
- Application id: `com.adequateapps.metronome`, permanent once published. The com.example release
  guard stays, and CI proves it with a planted id.
- The handles are `adequate-apps` on GitHub and @adequateapps elsewhere.
- Trademark: no filing until a clearance search covers the live ADEQUATE RECORDS registration in
  class 9. The product launches under the owner's own name without a registered mark.

## 18. Kotlin Multiplatform, iOS after Android 2.0

- `:core` becomes a Kotlin Multiplatform module before M3. Its `commonMain` holds the detector,
  pitch detection, ranges, catalogue, settings schema, portable configuration and statistics, with no
  `java.*` APIs.
- Done in M3 with a JVM target only. Android modules consume the JVM target; an Android target
  would compile the same code against the Android SDK and add nothing until a platform piece differs.
  The iOS target arrives with iOS work.
- With the JVM as the only target, the compiler resolves `commonMain` against the JDK. Until iOS
  arrives, `ModuleWallTest` enforces the no-JDK rule from source. Platform pieces sit behind
  `expect`/`actual`; today that is one function, which reads the bundled range data.
- The plugin is applied by id without a version. The Kotlin Gradle plugin is already on the build
  classpath, so the change resolves no new artifact.
- `HopRing` and `HopAnalyser` lock with `@Synchronized`, which compiles to nothing on a native target.
  The iOS target replaces it with an `expect`/`actual` lock before those classes run there.
- The fakes and the KnownGap rule moved from `:core`'s test fixtures to a `:core-testing` module,
  because a multiplatform module cannot carry `java-test-fixtures`.
- The C++ audio engine stays portable; only its AAudio layer is Android-specific.
- The screens move to Compose Multiplatform when iOS work starts, after Android 2.0.
- iOS ships under the same brand and price. Any contributor signs a licence agreement first, because
  App Store terms conflict with the GPL for code the owner does not hold.

## 19. Harmonic refinement after YIN; simulated playing is the accuracy bench

- YIN names the period and the note's neighbourhood; `HarmonicRefiner` then settles the
  fundamental within 7 c of YIN's estimate by the least-squares fit of the first twelve partials
  (up to 4 kHz) and the window's DC together: the maximum-likelihood pitch of a harmonic tone in
  noise. The joint fit matters at the bottom of the range, where a note's partials sit a few
  frequency bins apart and scoring each on its own tilted the peak by up to 4 c.
- Measured by the simulated play tests (`core/src/jvmTest/.../play`) and the production-path sweep
  (`EstimatorSweepTest`): a breathy flute's worst reading went from 2.3 c to 0.13 c, a noisy hall's
  from 1.4 c to 0.3 c, pianissimo from 0.4 c to 0.05 c; clean tones from A0 to C8 read within
  0.01 c of the played pitch. Without the refiner five of the play tests fail.
- Cost, measured by a probe on the desktop JVM: about 0.2 ms per window against YIN's 0.6 ms. The
  owner's device check for the per-hop median (decision 3) covers the phone.
- Under vibrato a reading lands where the pitch dwells in its window, up to about 3 c from the
  window's plain mean; the note's centre stays within 0.1 c.

## 20. Output timing facts and finite calibration runs

- Beat events carry the generator's bar, silent-bar flag and setup generation. A metre edit
  may finish a partial bar, so the global beat index cannot recover the gap cycle's position.
  The onset flag describes the configured accent before master mute and gain.
- The event time is already a monotonic presentation timestamp. Visual scheduling adds the
  route's chosen offset once; it does not add the stream latency a second time.
- A runtime beat limit bounds calibration inside the audio callback. Zero retains continuous
  playback. A completed finite run stays complete through setup replay and a route reopen;
  an explicit start begins a new run. A start accepted while parked survives later setup edits
  and replays once as a start. Stop and clear cancel that pending request. The final click retains
  its sample tail.
- Beat limits and generations are runtime setup fields; persisted preferences contain the
  player's metronome choices. JNI ABI 3 carries both fields and four event integers for the
  64-bit bar, onset/silent-bar flags and generation. Native, Kotlin and fake round-trips pin
  this layout together.
- Output information carries routed-device identity and wireless classification together.
  Same-name devices remain distinguishable for notice dismissal and calibration cancellation.


## 21. Unison proof follows presented duck audio

- Each requested duck can carry a generation. The render callback acknowledges that
  generation only when the master duck reaches its target. The control side maps that
  frame to monotonic presentation time using a device timestamp. Unknown presentation
  timing leaves the acknowledgement queued; queueing a command supplies no acknowledgement.
  Stream restart discards pending duck requests and acknowledgements while preserving
  queued voice, mute and metronome edits.
- JNI ABI 4 adds the duck generation and the DuckSettled event. Generation zero keeps
  fire-and-forget callers compatible. Superseded ramps need no acknowledgement; missing
  or stale acknowledgements leave matching drone notes excluded from unison proof.
- A restarted or changed output first restores full gain and fences capture through its
  presented acknowledgement. A still-ducked old stream cannot establish the next probe's
  reference level. The same fence then protects the reduced-gain unison evidence.
- Analysis dispatches duck transitions before evaluating the next buffered hop. Its
  evidence fence resumes only after presentation and after all audio already delivered
  or still buffered upstream has passed. Both halves of an analysis window must clear
  that fence. InputInfo reports the platform buffer plus one in-flight capture block.
- Zero-gain voices retain their phase and requested settings while contributing no
  sounding notes to bleed exclusion or recording context.
