# Acceptance ledger

One row per criterion in the behaviour spec. A row turns green only when a test that was seen failing before the fix passes after it; a test only ever seen passing is a comment. Known gaps are strict-failing tests that turn red when the gap closes.

Tiers: **U** pure JVM (`:core:jvmTest`), **N** host C++ (gtest, offline render), **H** Robolectric harness (`:app`, `:data`), **D** emulator or the owner's phone. Status values: `open`, `red` (test written, failing on the base), `green`, `gap` (strict known gap, named), `deferred` (ships after 2.0).


## Pitch detection accuracy over the full range

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DET-01 | search band | U | 2.0 | YinTest `DET-01` (25 to 4570 Hz: half a semitone past A0 at A4 = 415 and C8 at A4 = 466, the sweep ends of decision 16, derived from `Pitch.A4_HZ` in the test) | green |
| DET-02 | interpolation on the raw difference function | U | 2.0 | YinTest `DET-02` (`Yin.parabolicOffset` on the raw difference function, three-positive guard) | green |
| DET-03 | multi-period refinement | U | 2.0 | YinTest `DET-03` | green |
| DET-04 | interpolated threshold | U | 2.0 | YinTest `DET-04` | green |
| DET-05 | FFT length | U | 2.0 | FftTest `DET-05` | green |
| DET-06 | full-range sweep, sine | U | 2.0 | YinSweepTest `reads the note within tolerance[DET-06 sine MIDI n (label) at rate Hz]`, 176 cases, every one within 0.6 c; through the production estimator (YIN and the harmonic refinement): EstimatorSweepTest `DET-06 and DET-07` (all 528 cases, and MIDI 21 to 40 at eight starting phases) | green |
| DET-07 | full-range sweep, instrument-like timbres | U | 2.0 | YinSweepTest `reads the note within tolerance[DET-07 sax-like ...]` and `[DET-07 weak-fundamental ...]`, 352 cases, every one within 1 c, no strict gap; plus DetectorEndToEndTest (the real YIN behind HopAnalyser and Detector on frames from FakeAudioInput): a 33-note chromatic sax-like run at A4 = 442 at both rates, every saved reading on the right note within 1 c of its detune; through the production estimator: EstimatorSweepTest, same two tests | green |
| DET-08 | weak-fundamental high C | U | 2.0 | YinTest `DET-08` | green |
| DET-09 | top octave, regression pin | U | 2.0 | YinTest `DET-09` | green |
| DET-10 | low A at 430 | U | 2.0 | YinTest `DET-10` (two tests: A4 = 415, 430, 435, 440, 442, 445, 450, 466; A0 at A4 = 415 read 40 c flat) | green |
| DET-11 | C8 at 450 | U | 2.0 | YinTest `DET-11` (two tests: A4 = 450 and 466; C8 at A4 = 466 read 40 c sharp) | green |
| DET-12 | rate invariance | U | 2.0 | YinTest `DET-12` | green |
| DET-13 | ceiling test | U | 2.0 | YinTest `DET-13` (two tests: 4600 Hz; every 100 Hz from 4600 Hz to 10 kHz, clean and at 10 dB SNR, no sub-harmonic at either rate) + YinTest `DET-08` at 10 dB SNR pinning the other side of the sub-period rule | green |
| DET-14 | window long enough for the floor | U | 2.0 | YinTest `DET-14` (`Yin.WINDOW_SECONDS` reads `Hop.SECONDS`, the one definition the analyser cuts by) | green |
| DET-15 | non-finite input | U | 2.0 | YinTest `DET-15`, YinEstimatorTest `DET-15` (no candidate at level 0, counted) and DetectorEndToEndTest `DET-15` (a NaN hop mid-note is skipped and counted, the held note goes on) | green |
| DET-16 | off the UI thread | H, D | 2.0 | AnalysisThreadTest `DET-16` (decisions arrive on the thread named `analysis`, promoted to audio priority, on audio pushed from another thread); D: `tools/device/readout.sh --tone 442 --a4 442 --expect A4` on the API 36 emulator reads A4 +0 c 442.0 Hz off the UI thread (screenshot kept outside the tree) | green |
| DET-17 | per-hop budget | H, D | 2.0 | YinTest `DET-17` prints the JVM per-hop cost (informational; the harmonic refinement of decision 19 adds about 0.2 ms per window on the desktop JVM, measured by a probe rather than a test; after the half-length real transforms and the silence skip: sax-like A4 median about 0.4 ms, white noise about 0.7 ms at 48 kHz, from about 1.7 and 1.9 before); FftTest `DET-17` (two tests: the real transform matches the complex one bin for bin, and the YIN length is even 5-smooth) and HopAnalyserTest `DET-17` (a window under the gate's floor never reaches the estimator and is the same silence reject with its level); D: the API 36 x86_64 emulator under swiftshader shows 0.8 to 4.1 ms per hop in the debug readout, median about 2.5 ms over eleven samples (from 10.5 to 14.6 before; the samples are taken by uiautomator dumps that perturb the figure); the reference-phone figure is the M2 gate | open |

## Simulated playing

Scored passages on modelled wind instruments (alto, bari and contrabass reeds, clarinet, flute, trumpet; MIDI 22 to 96) in a room, rendered with their exact pitch track and played through the real capture path, YIN, the harmonic refinement and the detector at 44.1 and 48 kHz; every saved reading is graded against the played pitch over its own window. PlayTest writes `core/build/play-report/report.md` and the takes as WAV files.

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| PLAY-01 | no reading names a note that was not sounding, in any take (attacks, slurs, sixteenths, reverb tails) | U | 2.0 | PlayTest, all nine tests | green |
| PLAY-02 | a held note in a practice room reads within 0.25 c of the played pitch, and its printed whole cent is within 1 c of it (measured worst 0.19 c, contrabass) | U | 2.0 | PlayTest `long tones`, `chromatic`, `slurred arpeggio`, `sags flat`, `quiet and loud`, `every A4`, `sixteenths` | green |
| PLAY-03 | a note's mean, what the table keeps, is within 0.05 c in a practice room (measured worst 0.01 c) | U | 2.0 | PlayTest, same tests | green |
| PLAY-04 | under ±15 c vibrato the note's centre is within 0.3 c and each reading within 4 c of its window's mean | U | 2.0 | PlayTest `vibrato long tones keep their centre` | green |
| PLAY-05 | in a live hall with hiss and mains hum 38 dB down, each held reading is within 1 c and each note's mean within 0.25 c (measured worst 0.74 c and 0.10 c, bari) | U | 2.0 | PlayTest `a noisy room with hum, hiss and a live hall` | green |
| PLAY-06 | every note of 0.45 s or longer reaches the readout within 400 ms of its onset and history within 500 ms (measured worst 230 ms and 462 ms, slurred) | U | 2.0 | PlayTest, all nine tests | green |
| PLAY-07 | the refinement holds a breathy tone 20 dB over noise within 0.2 c where YIN alone is worse, reads a weak fundamental from A0 within 0.01 c, ignores a microphone offset, and stays within its search span; no note a hair under the quarter-tone changes note | U | 2.0 | HarmonicRefinerTest (five tests), EstimatorSweepTest `a note a hair under the quarter-tone` | green |

## Detector policy

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| POL-01 | first-reading latency | U | 2.0 | DetectorPolicyTest `POL-01` | green |
| POL-02 | legato note-change latency | U | 2.0 | DetectorPolicyTest `POL-02`; DetectorEndToEndTest holds the readout non-blank across 32 legato changes on real audio | green |
| POL-03 | no long-memory vote | U | 2.0 | DetectorPolicyTest `POL-03` | green |
| POL-04 | unknown mode | U | 2.0 | ResponseModeTableTest `POL-04` (`ResponseMode.fromStored`) + PrefsSchemaTest `POL-04` (the normaliser reads an unknown mode as Normal; the Response control shows the normalised value) | green |
| POL-05 | mode switch resets | U | 2.0 | DetectorPolicyTest `POL-05` (two tests: the reset; the change returns the release of the note it forgot) | green |
| POL-06 | attack edge | U | 2.0 | DetectorPolicyTest `POL-06` | green |
| POL-07 | release edge | U | 2.0 | DetectorPolicyTest `POL-07` (three tests: silence, note change, the droop and re-attack scoop around a gap shorter than the window) | green |
| POL-08 | re-attack of the same note | U | 2.0 | DetectorPolicyTest `POL-08` (two tests: a long silence; every gap from one hop to the window, in every mode: silence longer than the release edge ends the note and the re-attack confirms on its own hops) | green |
| POL-09 | time in hops | U | 2.0 | HopAnalyserTest `POL-09` (400 hops in bursts of 8 with 0–200 ms of fake-clock jitter) | green |
| POL-10 | aperiodicity on every path | U | 2.0 | DetectorPolicyTest `POL-10` (two tests) + HopAnalyserTest `POL-10` (white noise on frames) + DetectorEndToEndTest `POL-10` (10 s of white noise through the real YIN: nothing saved, no note shown) | green |
| POL-11 | RMS gate | U | 2.0 | DetectorPolicyTest `POL-11` | green |
| POL-12 | mic gain scope | U | 2.0 | DetectorPolicyTest `POL-12` + HopAnalyserTest `POL-12` (the window reaches the estimator unscaled) | green |
| POL-13 | detector window gate | U | 2.0 | DetectorPolicyTest `POL-13` | green |
| POL-14 | leaps of any size are saved | U | 2.0 | DetectorPolicyTest `POL-14` (octave up, fifth down, sixth up) | green |
| POL-15 | one window per hop | U | 2.0 | HopAnalyserTest `POL-15`; after a ring gap the first hop is context only, so no window that never existed is analysed (HopAnalyserTest `POL-16 after an overflow`) | green |
| POL-16 | oversized block | U | 2.0 | HopAnalyserTest `POL-16` (three tests: analyser, ring, and after an overflow every window is contiguous audio and hop numbering follows sounding time) | green |
| POL-17 | restart replaces the stream | U | 2.0 | HopAnalyserTest `POL-17` (two tests) with FakeAudioInput | green |
| POL-18 | counters visible | U | 2.0 | HopAnalyserTest `POL-18` (`AnalysisCounters`; the Diagnostics page reads them in M6) | green |
| POL-19 | readings carry their A4 | U | 2.0 | DetectorA4Test `POL-19` | green |
| POL-20 | A4 change discards stale readings | U | 2.0 | DetectorA4Test `POL-20` (two tests: the discard; the change returns the release of the note it forgot) | green |
| POL-21 | same-value change | U | 2.0 | DetectorA4Test `POL-21` | green |
| POL-22 | new run on A4 change | U | 2.0 | DetectorA4Test `POL-22` (the reading stream is partitioned by generation; the run row itself opens under SES-02 in M3) | green |
| POL-23 | table after an A4 change | U | 2.0 | DetectorA4Test `POL-23` (`Pitch.centsFrom` re-derives from stored Hz, and every reading carries its A4) | green |
| POL-24 | bleed is excluded | N + U | 2.0 | U: BleedPolicyTest `POL-24` on scripted estimates, and `POL-28 leakage after the player stops` (leakage after a unison is never saved); N fixtures land with the engine | open |
| POL-25 | an excluded hop keeps the player's note | N + U | 2.0 | U: BleedPolicyTest `POL-25`; N fixtures land with the engine | open |
| POL-26 | duck on suspicion | N + U | 2.0 | U: BleedPolicyTest `POL-26` (two tests); N fixtures land with the engine | open |
| POL-27 | unison is measured | N + U | 2.0 | U: BleedPolicyTest `POL-27` (three tests: admission, a dip restarts the count, one weak hop in unison is skipped and three end the admission); N fixtures land with the engine | open |
| POL-28 | pure leakage is never re-admitted | N + U | 2.0 | U: BleedPolicyTest `POL-28` (two tests: leakage alone; leakage after the player stops, where the admission lapses and nothing is saved); N fixtures land with the engine | open |
| POL-29 | no duck, no re-admission | N + U | 2.0 | U: BleedPolicyTest `POL-29`; N fixtures land with the engine | open |
| POL-30 | pipes are duck consumers | N + U | 2.0 | U: BleedPolicyTest `POL-30`; N fixtures land with the engine | open |
| POL-31 | render scope | H | 2.0 | LiveCaptureTest `POL-31 a held note moves the state a few times, the level every hop and the diagnostics about once a second`; TuneScreenTest `POL-31 five seconds of a held note recompose the frame not once` | green |

## Colour bands and rounding

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BND-01 | one rounding function | U | 2.0 | CentsBandTest `BND-01` | green |
| BND-02 | one band rule | U | 2.0 | CentsBandTest `BND-02` | green |
| BND-03 | colour agrees with digits | U | 2.0 | CentsBandTest `BND-03` | green |
| BND-04 | no other thresholds exist | U + source grep | 2.0 | CentsBandTest `BND-04` (three tests) + `tools/verify.sh` sources stage, shared allowlist `tools/band-literal-allowlist.txt`; SourceGuardTest `band plots receive their edges without declaring local thresholds`; the app supplies both plot edges from CentsBand | green |
| BND-05 | no data | U | 2.0 | CentsBandTest `BND-05` | green |
| BND-06 | whole cents only | U | 2.0 |  | open |

## Instrument ranges and transpositions

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| RNG-01 | two tiers per key | U | 2.0 | RangeGoldenTest `RNG-01` | green |
| RNG-02 | what each tier drives | U | 2.0 |  | open |
| RNG-03 | data source | U | 2.0 | RangeLayerDataTest (source hashes) + `tools/gen-ranges/check.sh` (byte-for-byte reproduction where the inputs are present) | green |
| RNG-04 | golden file | U | 2.0 | RangeGoldenTest `RNG-04` | green |
| RNG-05 | policy test | U | 2.0 | RangeGoldenTest `RNG-05`; the twenty decisions were drafted during M0 and await the owner's confirmation (see `tools/gen-ranges/README.md`) | green |
| RNG-06 | saxophones | U | 2.0 | RangeGoldenTest `RNG-06` | green |
| RNG-07 | other saxophones | U | 2.0 | RangeGoldenTest `RNG-07` | green |
| RNG-08 | corrected families | U | 2.0 | RangeGoldenTest `RNG-08` | green |
| RNG-09 | mappings | U | 2.0 | CatalogueTest `RNG-09` | green |
| RNG-10 | catch-all keys | U | 2.0 | RangeResolverTest `RNG-10` | green |
| RNG-11 | always show cents | U | 2.0 |  | open |
| RNG-12 | played out-of-range notes in the table | U | 2.0 |  | open |
| RNG-13 | narrowing a range | U | v1.1 |  | deferred |
| RNG-14 | instrument switch | U | 2.0 |  | open |
| RNG-15 | an override can only widen | U | v1.1 | RangeResolverTest `RNG-15` (the resolver rule landed with the layers; the editor ships in v1.1) | green |
| RNG-16 | strict read before write | U | v1.1 |  | deferred |
| RNG-17 | save reports the truth | U | v1.1 |  | deferred |
| RNG-18 | envelope v2 | U | v1.1 |  | deferred |
| RNG-19 | editor input | U | v1.1 |  | deferred |
| RNG-20 | editor limits | U | v1.1 |  | deferred |
| RNG-21 | say where ranges come from | U | v1.1 |  | deferred |
| TRN-01 | the four the 1.x app has wrong | U | 2.0 | CatalogueTest `TRN-01` | green |
| TRN-02 | end to end | U | 2.0 | CatalogueTest `TRN-02` | green |
| TRN-03 | migrating stored data | U | resolved: no 1.x migration |  | closed |
| TRN-04 | octave-clef convention | U | 2.0 | CatalogueTest `TRN-04` | green |
| TRN-05 | sign convention is global | U | 2.0 | CatalogueTest `TRN-05` | green |

## The wrong-instrument hint

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| HNT-01 | three different notes | U | 2.0 | StrayCounterTest `HNT-01` | green |
| HNT-02 | the window is playing time | U | 2.0 | StrayCounterTest `HNT-02` | green |
| HNT-03 | single-note cases never trigger it | U | 2.0 | StrayCounterTest `HNT-03` | green |
| HNT-04 | spread-out strays never trigger it | U | 2.0 | StrayCounterTest `HNT-04` | green |
| HNT-05 | it offers the fix | U | v1.1 |  | deferred |
| HNT-06 | editing forgets covered strays | U | v1.1 |  | deferred |
| HNT-07 | scope | U | 2.0 | StrayCounterTest `HNT-07` | green |
| HNT-08 | cents keep showing | U | 2.0 | StrayCounterTest `HNT-08` | green |

## Sessions and runs, history bounds and storage

The scope revision adds columns to the M3 schema before it freezes: attack counts (ATK-01), capture
context and target (CTX-01), equipment (EQP-01) and the paused-capture state (CAP-02); they are
ledgered under their own prefixes below.

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SES-01 | the first run starts with saved prefs | H | 2.0 | HistoryStoreTest `SES-01`; AppGraphTest `SES-01` (cold start from a saved prefs file labels the first run, a prefs change relabels the next); PrefsStoreTest `SES-01`; AVD: a 442 Hz tone stored 206 readings in one run at A4 = 442, and a relaunch kept the session and opened a second | green |
| SES-02 | new-run triggers | H | 2.0 | HistoryStoreTest `SES-02` (nickname, A4 generation, instrument, transposition, cleared nickname) | green |
| SES-03 | SESSION means this session | H | 2.0 | HistoryStoreTest `SES-03` (the summaries query; the table screen lands in M6) | green |
| SES-04 | per-nickname separation | H | 2.0 | HistoryStoreTest `SES-04` | green |
| SES-05 | validate before storing | H | 2.0 | HistoryStoreTest `SES-05`; the rule is `HistorySink.storable` in :core, SmallContractsTest | green |
| SES-06 | batched writes | H | 2.0 | HistoryStoreTest `SES-06` (a burst is one transaction; 3.2 s of continuous readings at about 200 a second is at most one transaction a second) | green |
| SES-07 | write errors surface | H | 2.0 | HistoryStoreTest `SES-07 a failing flush keeps the buffer, counts, raises the banner after three, and recovers` and `SES-07 a session change whose flush fails is refused and files nothing under the next session`; AppNotices renders `notice-history`, but no UI failure-injection test proves that persistent failures reach the player | open |
| SES-08 | summaries and indexes | H | 2.0 | HistoryStoreTest `SES-08`; SchemaTest `SES-08` (indexes); `StoreStatus.flushes` is the refresh signal | green |
| SES-09 | retention | H | 2.0 | HistoryStoreTest `SES-09` (two tests: raw readings of unsaved sessions age out with summaries kept; a saved session keeps its readings, decision 5) | green |
| SES-10 | storage budget | H | 2.0 | HistoryStoreTest `SES-10` (a simulated year: 53 MB against 100 MB) | green |
| SES-11 | one writer | H | 2.0 | the field table and the normaliser derived from it: PrefsSchemaTest `SES-11`; the noise-floor range tops out where the gate stays reachable under every preset (ResponseModeTableTest `the top of the noise-floor range`); the one writer lands with `PrefsStore` in M3; PrefsStoreTest `SES-11` (two threads updating different fields lose nothing) | green |
| SES-12 | unknown and newer fields | H | 2.0 | the normaliser's round trip of unknown and newer-version fields: PrefsSchemaTest `SES-12`; PrefsStoreTest `SES-12` (the store keeps unknown and newer-version fields through a save) | green |
| SES-13 | zero stays zero | H | 2.0 | the normaliser keeps a gain of 0: PrefsSchemaTest `SES-13`; PrefsStoreTest `SES-13` (restart path) | green |
| SES-14 | golden stored data | H | 2.0 | SchemaTest `SES-14` (the created schema matches `data/src/test/resources/db/schema-v1.sql`); 2.0 is the first shipped schema and prefs version, so upgrade goldens start with the next version | green |
| SES-15 | CSV and TXT export | H | v1.1 |  | deferred |

## Output lifecycle and the microphone

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| OUT-01 | output-user registry | H | 2.0 (decision 12 removes the focus clause: the registry opens the stream on the first tool and closes it on the last, and its two transitions drive BGH-01) | OutputRegistryTest `OUT-01` (the registry's transitions), AudioControlThreadTest `OUT-01` (the real control thread opens the fake backend's stream on the first tool and closes it on the last, Opened then Closed) and AndroidAudioControlTest `OUT-01` (the same over the native backend and a fake of the bridge: the first tool opens one native handle, the last destroys it); N: `OutputController.theFirstRegistrationOpensSharedLowLatencyAtTwoBurstsAndTheLastCloses`; D: the emulator output run shows Opened on the first Register and Closed on the last Unregister | green |
| OUT-02 | truthful state | H | 2.0 | engine half: every loss of output is a Parked event carrying its reason: AudioControlThreadTest `OUT-03` (headphones disconnected), `OUT-17` (output failed, with the device and the refused rates), `CAL-02` (incoming call), `OUT-11` (background), and `OUT-02` (two tests: a stream that keeps ending with its device present is output failing after three reopens in place, with a Failed naming the device; a stream that lives a second resets the budget); a parked tool under a partial background policy stops sounding (`OUT-11`); the control that shows off with the reason lands in M5 | open |
| OUT-03 | unplug | H, D | 2.0 | H green: AudioControlThreadTest `OUT-03` (two tests: the noisy broadcast, and a device vanishing without one; every registered tool parks, the stream closes with its engine kept, and nothing opens on the speaker; the dead stream's late disconnect is ignored); over the bridge: AndroidAudioControlTest `OUT-03` (three tests: a native disconnect with the device gone parks and keeps the native handle until the tools are switched off; the platform reporting the device removed parks with no noisy broadcast and no stream error, matched against the device captured at open; an input device appearing is not a replug, so parked tools never come out on the speaker); D: the owner's phone check is pending | open |
| OUT-04 | drone AUTO on unplug | H | v1.1 |  | deferred |
| OUT-05 | replug | H, D | 2.0 | H green: AudioControlThreadTest `OUT-05 OUT-13 and MET-09` (replug at 44.1 kHz restarts exactly the parked set on the engine the unplug kept: A4 and the voices re-sent, the metronome continued as an edit), `OUT-05` (two more: a speaker appearing is not a replug; a tool the player starts while unplugged brings the parked set back on the route in hand) and `OUT-06` (what the player switches off while unplugged is switched off in the continued engine); over the bridge: AndroidAudioControlTest `OUT-05 and MET-09` (a replug resumes the parked native handle: one handle, one resume, no second MetroStart); D: the owner's phone check is pending | open |
| OUT-06 | off stays off | H | 2.0 | AudioControlThreadTest `OUT-06` (two tests: a tool switched off while unplugged is not in the Resumed set; when every parked tool is switched off the registry empties and a replug restarts nothing) | green |
| OUT-07 | late devices | H | 2.0 | AudioControlThreadTest `OUT-07` (a headset plugged in after start-up becomes the route when the speaker stream ends: Resumed, no Parked) and AndroidAdaptersTest `OUT-07` (the platform adapter lists outputs and hears late devices and the noisy broadcast); 2.0 has no manual output setting (scope revision), so the late device is observed and used rather than listed | green |
| OUT-08 | permanent loss | H | superseded by decisions 4 and 12: another app taking audio never parks the tools; the contract has no focus reason |  | closed |
| OUT-09 | transient loss | H | superseded by decisions 4 and 12: an answered call parks every tool (`ParkReason.INCOMING_CALL`) and nothing restarts on its own; ringing is CAL-01; no duck on focus | OutputPortContractTest (the contract) and AudioControlThreadTest `CAL-02` (the real control thread parks every tool with `INCOMING_CALL` when the mode becomes in-call and restarts nothing when it ends) | green |
| OUT-10 | mute reasons | H | 2.0 | MuteReasonsTest `OUT-10` (two tests: overlap in any order, clearing an unset reason) and AudioControlThreadTest `OUT-10` (two tests: a recording mute and the ringing reason overlap on the real stream and the sounding notes empty while muted; a mute set before the stream opens is in force when it opens) | green |
| OUT-11 | background policy | H | 2.0 (v1 policy: stop everything) | AudioControlThreadTest `OUT-11` (four tests: `BackgroundPolicy.StopEverything` parks every tool with `BACKGROUND` and the foreground restarts nothing; a policy that keeps the metronome parks the drone and silences every voice slot; one that keeps the drone stops the parked metronome; the background with nothing registered is nothing) | green |
| OUT-12 | route from the routed device | H, D | 2.0 | H green: RouteClassifierTest `OUT-12` (four tests over every `AudioDeviceInfo` type: speakers, wired and USB, Bluetooth classic, LE audio and hearing aids, unknowns) and AudioControlThreadTest `OUT-12` (the route class comes from the stream's routed device; an API 37 routing change re-classifies and re-reads latency without a disconnect, SHM-03); D: the owner's phone check is pending | open |
| OUT-13 | rate change | H | 2.0 | control half: ToolStateTest `OUT-13 and MET-09` (the replay sends A4 first, every voice with its latest gain, then the metronome as MetroUpdate, and a continued engine is first told what was switched off meanwhile) and AudioControlThreadTest `OUT-05 OUT-13 and MET-09` and `OUT-07` (a reopen in place keeps the engine); native half: host-tests `Voices.aHeldVoiceKeepsItsPitchAcrossARateChange`, `Metronome.afterAnOutputSwitchTheNextClickComesOneBeatLater` (MET-09) and `OutputController.theReplayIntoAResumedEngineDisturbsNeitherTheVoiceNorTheBeat` (the control thread's replay into a resumed engine leaves the drone's phase and the beat count alone); over the bridge: AndroidAudioControlTest `OUT-13` (a native disconnect with the device still present resumes the same handle at the new rate; the voice is re-sent and the metronome edited, never restarted) and NativeStreamBackendTest `OUT-05 and MET-09` (a park keeps the handle and the next open resumes it) | green |
| OUT-14 | rate peg | H | 2.0 | AudioControlThreadTest `SHM-01 and OUT-14` (the request leaves the rate unspecified; `OutputInfo.sampleRate` is the rate the backend granted, 44.1 kHz in the test) | green |
| OUT-15 | start and stop never block | H | 2.0 | AudioControlThreadTest `OUT-15` (two tests: with the backend stuck inside open, send has returned and nothing has reached the backend, and the open ran on the `audio-control` thread; a close that times out drops the handle and the next start opens a fresh stream) | green |
| OUT-16 | render thread | H | 2.0 | N: host-tests `Allocation.renderNeverAllocatesUnderEveryCommand` and `Allocation.theDeviceCallbackPathNeverAllocates` (a counting `operator new` over `render()` and the device callback path reads zero under every command; `Allocation.theCounterSeesAnAllocation` proves the counter) plus the `tools/verify.sh` render-path source lint (no allocation, containers, locks or logging under `engine/src/main/cpp/render/`, nor in `OutputController::onRender`, `AAudioDevice::onData` and the bridge's `onWake`, the three callback functions outside it; the self-test plants an allocation in each place); the tier is N rather than H because the render thread is C++ | green |
| OUT-17 | error names | H | 2.0 | AudioControlThreadTest `OUT-17` (a refused open yields `Failed("USB DAC", [48000, 44100])`, the tool parks with `OUTPUT_FAILED`, and the next start tries again) | green |
| IN-01 | one owner and release | H | 2.0 | AudioRecordSourceTest `IN-01` (three tests: a recorder that errors mid-stream is released and the failure names the device; a restart replaces the stream with at most one recorder open at any point; a late recorder from a hung open is released) | green |
| IN-02 | busy or missing | H | 2.0 | AudioRecordSourceTest `IN-02` (Busy names the device and the recorder is released; an absent saved interface is reported as Disconnected by name while the session opens on the built-in microphone) | green |
| IN-03 | saved interface stays saved | H | 2.0 | AudioRecordSourceTest `IN-03` (the saved name is a read-only input to the source; present it is used, absent the default serves and the listener is told which, and the next open with the interface back uses it) | green |
| IN-04 | replug recovers | H, D | 2.0 | AudioRecordSourceTest `IN-04` (three tests: unplug stops with a reason and replug resumes on the same sink; a saved interface that returns takes over from the built-in microphone with one recorder open throughout; after stop nothing resumes) + AndroidRecorderPlatformTest (the AudioManager device callback reaches the listener); device half open | green (H) |
| IN-05 | honest silence | H | 2.0 | AudioRecordSourceTest `IN-05` (a recorder stopped behind our back is declared dead and released; the sink receives no block it did not record) | green |
| IN-06 | a hung driver costs one timeout | H | 2.0 (the fallback trigger is the property check, SRC-01) | AudioRecordSourceTest `IN-06` (every attempt in the chain shares one deadline; a hung open ends the chain within one timeout with the rates tried named, and what the hung call returns later is released) | green |

## Metronome timing

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| MET-01 | sample clock | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.tenThousandBeatsAt300BpmLandOnTheFormulaToTheFrame` (48 k and 44.1 k) and `Metronome.theFirstNonZeroSampleLandsOnTheOnsetFrame` | green |
| MET-02 | zero drift | N | 2.0 (a muted beat keeps its subdivisions audible, GAP-03) | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.aFractionalPeriodNeverDrifts` (onset = A + round(k x sr x 60 / bpm), no accumulated error over 10 000 beats); the muted-beat subdivisions are GAP-03 | green |
| MET-03 | in-block placement | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.theFirstNonZeroSampleLandsOnTheOnsetFrame` (onsets placed at their exact offset inside the block, block by block) | green |
| MET-04 | late events | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.aBeatMoreThanTwoMillisecondsLateIsDroppedAndCounted` and `Metronome.aBeatUnderTwoMillisecondsLatePlaysAtTheNextBlockAndIsNotCounted`; the count reaches Diagnostics as `OutputController.aLateBeatShowsUpInCounters`; a subdivision point the new grid places in the past is skipped and never counted as a late beat (`Metronome.aSubdivisionPointAlreadyPassedAtTheEditIsSkippedAndNotCountedLate`), and a late subdivision has a counter of its own | green |
| MET-05 | exactly once | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.liveEditsNeverDoubleABeatOrBunchClicks` (the generator holds exactly one next beat, so a dispatched beat has no second path), `Metronome.liveEditsWithSubdivisionsNeverDoubleABeat`, `Metronome.raisingTheSubdivisionNeverRefiresThePointThatJustSounded` and `Metronome.aTempoEditRegridsTheSubdivisionsFromTheLastBeat` (an edit re-grids the subdivisions under the last beat and takes the first point still to come), and `Metronome.stopLeavesNoFurtherBeats` | green |
| MET-06 | re-anchor on the beat that sounded | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.aTempoChangeReanchorsOnTheBeatThatSounded` | green |
| MET-07 | metre change | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.aMetreChangeResetsTheBar`; denominator-only edit: `Metronome.changingTheBeatUnitResetsTheBar` | green |
| MET-08 | 64-beat scenarios | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.sixtyFourBeatScenarioMatchesTheExpectedFrames` | green |
| MET-09 | output switch | N, H | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.afterAnOutputSwitchTheNextClickComesOneBeatLater` (the anchor converts through time on a rate change), `OutputController.aDisconnectParksOnTheControlThreadAndResumeReopensAtTheNewRate` and `OutputController.theReplayIntoAResumedEngineDisturbsNeitherTheVoiceNorTheBeat`; H, the device path: the control thread parks the stream with its engine kept and the native backend resumes the same handle, so the beat count and bar continue and the next click comes one beat after the new stream's first frame on a device too (NativeStreamBackendTest `OUT-05 and MET-09`, AudioControlThreadTest `OUT-05 OUT-13 and MET-09`, AndroidAudioControlTest `OUT-05 and MET-09` and `OUT-13`) | green |
| MET-10 | range and diagnostics | N, H | 2.0 | N: `Metronome.acceptsThirtyToThreeHundredAndClampsOutside` and `OutputController.aLateBeatShowsUpInCounters`; H: AndroidAudioControlTest `MET-10` (the setup reaches the native generator through the bridge with its ordinals and the beats come back as events), NativeStreamBackendTest `MET-10` (the native counters of the open stream are readable); D: `tools/device/output.sh --drone 69 --metro 120` on the API 36 emulator, 17 beats in 8 s spaced exactly 24 000 frames at 48 kHz, 0 late, 0 dropped | green |

## Drone and pitch pipes follow A4

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TON-01 | saved A4 at start-up | H | 2.0 | engine half: ToolStateTest `TON-01` (clear keeps A4 and every replay starts with it) and AudioControlThreadTest `TON-01` (a drone started after the registry emptied opens its stream with SetA4 first); the saved value itself arrives with M3's storage and M4's settings | open |
| TON-02 | accuracy | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Voices.everyVoiceIsInTuneAt442` (7 notes x 3 voices x 2 rates, FFT peak within 0.5 c, sine within 0.1 c) | green |
| TON-03 | held notes retune | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Voices.aHeldNoteRetunesInPlaceWhenA4Changes` (no gap, no re-strike) and `Voices.aHeldVoiceKeepsItsPitchAcrossARateChange` | green |
| TON-04 | volume is gain | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Voices.volumeIsAGainChangeAndNotARestrike` (ramped gain, constant voice count) and `Mixer.duckIsARampedGainWithAFloor` | green |
| TON-05 | sine voice and fallback | N | 2.0 |  | open |
| TON-06 | transposition persists | H | 2.0 |  | open |

## Deck recording and save location

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DCK-01 | headphone monitoring | H | v1.1 |  | deferred |
| DCK-02 | speaker route mute | H | v1.1 |  | deferred |
| DCK-03 | survives process death | H | v1.1 |  | deferred |
| DCK-04 | a real destination | H | v1.1 |  | deferred |
| DCK-05 | failed save | H | v1.1 |  | deferred |
| DCK-06 | playback after a rate change | H | v1.1 |  | deferred |
| DCK-07 | share | H | v1.1 |  | deferred |

## Crash and error handling

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ERR-01 | error boundary per screen | H | 2.0 | Compose has no composition error boundary; per-screen recovery remains an owner design decision | open |
| ERR-02 | fatal errors | H | 2.0 | CrashLogTest `ERR-02` and `the cap is the 256 KiB the storage names promise`; AppNoticesTest `ERR-02 a crash last time is offered once, and Dismiss puts it away for good` prove stored JVM crash offers and dismissal; native fatal-error capture remains unproved | open |
| ERR-03 | no silent catches | H | 2.0 |  | open |
| ERR-04 | corrupt prefs | H | 2.0 | PrefsStoreTest `ERR-04 a corrupt blob is copied before the first write and the player is told once` and `ERR-04 when the corrupt blob cannot be copied, nothing overwrites it and the player is not told it was kept`; AppNoticesTest `ERR-04 a corrupt settings file says so once, keeps the player out of first run, and Dismiss clears it` | green |
| ERR-05 | unreadable prefs | H | 2.0 | PrefsStoreTest `ERR-05` (two tests: never a first run, and a change made while unreadable is replayed onto the saved prefs once they can be read, never written over them) | green |
| ERR-06 | prefs save failure | H | 2.0 | PrefsStoreTest `ERR-06` | green |
| ERR-07 | analysis errors | H | 2.0 |  | open |

## Accessibility (font scale)

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| A11Y-01 | tuner readout | H | 2.0 | FrameScreenshotTest `tune at font scale 1_0`, `1_3`, `1_5`, `2_0` cover the ghost readout; sounding note, cents and Hz clipping at every scale still need assertions | open |
| A11Y-02 | every screen | H | 2.0 (supplemented by CLR-01..03) | AdaptiveRowsTest `denied notices keep the message and action on readable lines` and `setting rows keep labels whole and long values inside the row` assert text fit at four font scales and two widths; FrameScreenshotTest covers Tune, Drone, Metronome and Settings at four scales; ComponentZooTest `labels fit, targets are 48 dp and every control is labelled` covers components in four themes and two widths; unfinished screens and all dialogs remain unproved | open |
| A11Y-03 | colour is never alone | H | 2.0 | TuneScreenTest `a played A4 on the default tenor sax reads written B5, in tune, concert A4, and the pane is listening` and `a sharp note shows the sharp cue, the close band and its cents` prove signed digits for in-tune and close; off-band and every other band-bearing surface remain unproved | open |
| A11Y-04 | touch targets | H | 2.0 | ComponentZooTest `labels fit, targets are 48 dp and every control is labelled`; ControlsTest `a button fires once per tap and meets the touch target` prove component targets; an inventory of every screen and dialog control remains unproved | open |

## Scope revision, 2026-10-14

Criteria added by the adopted scope revision (decisions 12 to 16). The text of each lives in the
revision document; the IDs are claimed here like any other.

### Capture control

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CAP-01 | paused capture drives the needle and reaches no store | H | 2.0 | TuneScreenTest `CAP-01 paused capture keeps listening and live plots while history waits until On` proves Listening plus Capture paused and the Tune tab running indicator, live needle/trace/level, unchanged stored count while paused and resumed writes on On; HistoryStoreTest `CAP-01 and CAP-03 paused readings are counted and only the empty run is recorded` | green |
| CAP-02 | paused state survives process death | H | 2.0 | prefs field `capturePaused`, device-bound | open |
| CAP-03 | a run opened while paused counts skipped readings | H | 2.0 | HistoryStoreTest `CAP-01 and CAP-03` | green |

### Attacks as the unit

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ATK-01 | attack columns in note_hist | H | 2.0 | SchemaTest `SES-01` and the golden schema | green |
| ATK-02 | one attack per attack edge, landing cents from the first settled reading | U, H | 2.0 | U: DetectorPolicyTest `ATK-02` (two tests: one `Attack` per attack edge carrying the landing cents of the confirming hops, and the first settled reading flagged `firstSettled`); H: the store counts them in M3; H: HistoryStoreTest `ATK-02`, LiveCaptureTest (the first settled reading reaches history marked) | green |
| ATK-03 | held note versus sweep | H | 2.0 |  | open |
| ATK-04 | attacks shown beside readings | H | 2.0 |  | open |

### Run context and target

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CTX-01 | context and target columns | H | 2.0 | SchemaTest `CTX-01` and the golden schema; the drone note is a `runs.drone_midi` column rather than a `meta` row | green |
| CTX-02 | drone start and stop open runs | H | 2.0 | HistoryStoreTest `CTX-02 to CTX-04` (the store half; the drone sets the context when the drone pane lands in M5) | open |
| CTX-03 | drone runs enter All-history by toggle only | H | 2.0 | prefs field `includeDroneRuns`, default off; HistoryStoreTest `CTX-02 to CTX-04` | green |
| CTX-04 | no 2.0 path writes `just` | H | 2.0 | HistoryStoreTest `CTX-02 to CTX-04` (no code path writes `just`) | green |

### Equipment

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EQP-01 | equipment column | H | 2.0 | SchemaTest `SES-01` and the golden schema | green |
| EQP-02 | equipment field on the session sheet | H | 2.0 | prefs field `lastEquipment` for the prefill | open |

### Tuning presets

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CHK-01 | ensemble and check-set pads marked | H | 2.0 | the marks per pad: TuningNotesTest `CHK-01`; the grid lands in M5 | open |
| CHK-02 | check sets per instrument, golden over all 73 keys | U | 2.0 | TuningNotesTest `CHK-02` (seven tests: the clarinet and saxophone sets, every key's set, one band's Bb per instrument, a set agrees with its preferred ensemble note, every trumpet climbs concert F to Bb, the golden) + `core/src/test/resources/catalogue/tuning-notes-golden.json` | green |
| CHK-03 | Next check retunes in place | N, H | 2.0 | the walk through the set: TuningNotesTest `CHK-03`; the retune is TON-03 in M2 | open |

### A4 range

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| A4W-01 | A4 steps 415 to 466 Hz (decision 16; the sweep ends follow with the detector) | U, H | 2.0 | U: `Pitch.A4_HZ`, PitchMathTest, DetectorA4Test and HopAnalyserTest `A4W-01`, PrefsSchemaTest `A4W-01`, PersonalConfigTest `A4W-01` (415 and 466 accepted, 414.9 and 466.1 refused); H: the stepper in M4 | open |

### Ears first

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EAR-01 | readout hidden, capture unchanged | H | 2.0 | TuneScreenTest `EAR-01 ears first hides the needle and preserves every held history reading` compares ten saved hops in Show with ten in Ears first, asserts hidden cents/Hz and the blank needle label; `tune-ears-first-hidden-needle.png` verifies the ghost pointer; the existing ears-first reveal test and hidden readout golden cover sounding feedback | green |
| EAR-02 | reveal after the note | H | 2.0 | TuneScreenTest `ears first hides the cents, Hz and needle while a note sounds, shows its mean for two seconds after it ends, then hides`, `EAR-02 enabling ears first never replays a note ended in Show`, and `EAR-02 a new note without readings cancels the previous reveal permanently`; LiveCaptureTest `EAR-02 duration ends after the last accepted hop and excludes release silence`, held-mean/settled/serial tracking, same-hop note changes, and A4/mode release tests; LiveCaptureTest `EAR-02 last note preserves rejected and dropped hops through its last reading`; TuneScreenTest `EAR-02 a short note reveals a ghost attack when no settled reading exists`; TracePlotTest `a singleton reading and an isolated reading between gaps both draw a visible point`; reveal golden and round-one reveal mutation | green |
| EAR-03 | setting persists | H | 2.0 | PrefsSchemaTest `SES-12 a full round trip keeps every field and every unknown field` includes `earsFirst = true`; PrefsStoreTest `SES-01 saved prefs come back on a cold start` exercises other fields; an ears-first cold restart test remains | open |

### Pitch trace

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TRC-01 | five-second trace from a fixed ring, no root recomposition | H | 2.0 | LiveCaptureTest `TRC-01 dropped hops occupy time and old attacks leave the five second ring` proves capacity, missing-hop padding and attack expiry; TuneScreenTest `POL-31 five seconds of a held note recompose the frame not once`; ComponentZooTest goldens cover the five-second labels and band rules supplied by CentsBand through the app | green |
| TRC-02 | silence breaks the line | H | 2.0 | LiveCaptureTest `TRC-02 rejected hops break the trace while the readout holds its note` proves two silent NaN points during an incumbent hold and a fresh point after the gap; LiveCaptureTest `EAR-02 last note preserves rejected and dropped hops through its last reading` proves matching gaps in Last note; Trace starts a new path after NaN | green |
| TRC-03 | TalkBack reads the trace once | H | 2.0 | TuneScreenTest `TRC-03 the live trace exposes one stable accessibility label` checks the exact Pitch trace, last five seconds description, no semantic children or text, and no live region, before and during a note | green |

### Gap bars and offbeats

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| GAP-01 | N bars on, M bars off (the fields and the on/off rule landed in M0: MetroSetupTest; the generator lands in M2) | N, H | 2.0 | M0: MetroSetupTest (the fields and the on/off rule); N: `Metronome.gapBarsSilenceWholeBarsWhileBeatsKeepComing` and `Metronome.aSilentBarHasNoSubdivisionClicks` (the generator follows `MetroParams.barSounds`, the mirror of `MetroSetup.barSounds`); the beat row's outline is M5; event metadata: `OutputController.beatMetadataKeepsSilentBarsSeparateFromMutedOnsets`, `beatMetadataKeepsTheGapBarWhenAMetreEditCutsABarShort`; pane pending | green (N; the H half is the beat row, M5) |
| GAP-02 | the cycle keeps its bar position across a tempo change | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.gapBarsKeepTheirBarPositionAcrossATempoChange` | green |
| GAP-03 | a muted beat keeps its subdivisions | N | 2.0 | host-tests (N, three variants: release, ASan+UBSan, allocation counter): `Metronome.aMutedBeatKeepsItsSubdivisionsAudible` and `Metronome.accentLevelsAreGainMultipliersAndMuteIsSilent` | green |
| GAP-04 | odd-metre presets | H | 2.0 |  | open |

### Calls without audio focus

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CAL-01 | ringing adds the mute reason within 500 ms | H | 2.0 | AudioControlThreadTest `CAL-01` (two tests: on API 28 the 2 Hz poll adds the ringing reason within 500 ms and a declined call removes it with no park; on API 31 the listener mutes at once) and `OUT-10` | green |
| CAL-02 | an answered call parks with "Paused: phone call" | H | 2.0 | AudioControlThreadTest `CAL-02` (ringtone then in-call parks every tool with `INCOMING_CALL`, the owner hears stopped, and neither the mode returning to normal nor a replug restarts anything; a later start replays no stale voice) and CallWatcherTest `CAL-02` (in-call, in-communication and call-screening are all answered) | green |
| CAL-03 | mode observed by poll on 28–30 and by listener on 31+; no READ_PHONE_STATE | H | 2.0 | CallWatcherTest `CAL-03` (two tests: API 28 polls at 2 Hz and sees a change within a second, and stops polling on stop; API 31 registers the listener once and never polls) and AndroidAdaptersTest `CAL-03` (the platform adapter hears the mode-changed listener); the manifest half is `tools/check-manifest.sh` in the gate (DST-02) | green |
| CAL-04 | another app's music changes nothing | H, D | 2.0 | H: NoAudioFocusGuardTest `CAL-04` (the engine's production sources contain no focus request, focus listener, playback callback or music-active query, so another app's playback has no path into the control thread; the guard was seen failing on a planted hit); D: the owner's phone check with music playing is pending | open |
| CAL-05 | a silenced client is named in the status sentence | H | 2.0 |  | open |

### Input source

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SRC-01 | source chosen by the UNPROCESSED property | H | 2.0 | AudioRecordSourceTest `SRC-01` (three tests: the property decides the first preset, UNPROCESSED is never asked for without it, a refused preset falls through at every rate and the opened one is reported, every preset refused is one Refused failure) + AndroidRecorderPlatformTest (the framework answers "true" or "false" rather than the null the compatibility definition names; only "true" opens UNPROCESSED) | green |
| SRC-02 | Diagnostics shows source and effects | H | 2.0 | AudioRecordSourceTest `SRC-02` (the recording configuration report, source, effects and silenced, reaches the source's state flow and clears on stop) + DebugReadoutTest (the M2 debug readout shows the input and the platform report; the Diagnostics page reads the same flow in M6) | green |
| SRC-03 | floors shift by the source offset | U | 2.0 | ResponseModeTableTest `SRC-03` (the table) + HopAnalyserTest `SRC-03` (the preset the input opened with reaches the gate through the analyser) | green |

### Output stream mode

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SHM-01 | SHARED, LOW_LATENCY, two bursts, granted values reported (decision 13) | H, D | 2.0 | H: AudioControlThreadTest `SHM-01 and OUT-14` (the control thread's `StreamRequest` is SHARED, LOW_LATENCY, rate and frames-per-callback unspecified, two bursts, and `OutputInfo` carries the granted rate, burst, route and latency), NativeStreamBackendTest `SHM-01` (a request the bridge cannot honour is refused before the library is touched); N: `OutputController.theFirstRegistrationOpensSharedLowLatencyAtTwoBurstsAndTheLastCloses` against the fake device; D (emulator, API 36): Opened rate=48000 burst=960 class=SPEAKER, 2 bursts, latency 29 to 57 ms, `verification/2026-10-14/output-*.log`; the owner's phone reading is still listed under the device checks | green (H, N, emulator); phone pending |
| SHM-02 | buffer grows on xruns up to four bursts | H | 2.0 | H: AudioControlThreadTest `SHM-02` (two tests: a rising xrun count grows the buffer one burst at a time, 2 to 3 to 4, then stops, each step a Diagnostics line; two steps the render callback took inside one poll are two lines, and a refused step is none); N: `OutputController.theBufferGrowsOneBurstPerXrunUpToFour` (the growth the render callback performs itself, decision 13) and `OutputController.bufferGrowthCountsOnlyTheStepsTheDeviceGranted` (a step counts when the device took it; a clamped or erroring device grows nothing); the Kotlin side reads what the native side holds (NativeStreamBackendTest `SHM-02`); D: one underrun on the emulator grew the buffer 2 to 3 bursts, shown in the debug output pane | green |
| SHM-03 | routing-changed callback on API 37 | H, D | 2.0 | H green: AudioControlThreadTest `OUT-12` (the routing-changed callback re-classifies the route and re-reads latency within the same control-thread turn, with no Parked and no new stream); native registration is blocked: NDK 28.2's AAudio.h has no `AAudioStreamBuilder_setRoutingChangedCallback`, so `AAudioDevice` registers none and the bridge never calls `StreamListener.onRoutingChanged`; needs an NDK that exposes it (an install checkpoint) and then a D check on an API 37 device | open (NDK) |

### Bluetooth calibration

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BTC-01 | tap calibration on Bluetooth routes | H, D | 2.0 | Native bound: `Metronome.calibrationEndsAfterEightClicksWithoutAStopCommand`, `completedCalibrationStaysFinishedThroughReplayAndRestartsOnlyOnStart`, `finiteRunKeepsItsRemainingCountThroughRateAndTempoChanges`; route identity: `AudioControlThreadTest` equal output names; queued explicit restart: `ToolStateTest` and `AudioControlThreadTest` BTC-01 cases; tap UI and device check pending | open |
| BTC-02 | one offset per route class | H | 2.0 | prefs fields `visualOffsetMs` for wired and speaker, `bluetoothVisualOffsetMs` for Bluetooth | open |

### TalkBack

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TLK-01 | needle state description, read on focus only | H | 2.0 |  | open |
| TLK-02 | opt-in settled-note live region | H | 2.0 | prefs field `announceSettledNotes`, default off, portable | open |
| TLK-03 | table rows read as sentences | H | 2.0 |  | open |
| TLK-04 | beats never announced; stepper actions | H | 2.0 |  | open |

### Colour and shape

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CLR-01 | `attention-ink` token at 4.5:1 on white and 3:1 on gray | U | 2.0 | ThemeContrastTest `every text and mark clears its contrast threshold in all four themes` checks attentionInk on field and surface at 4.5:1; TokensGeneratedTest `the committed Tokens kt matches the generator output` ties the palettes to generated tokens | green |
| CLR-02 | small close-band text uses `attention-ink` | H | 2.0 | ThemeContrastTest `every text and mark clears its contrast threshold in all four themes` proves token contrast; TuneScreenTest `a sharp note shows the sharp cue, the close band and its cents` checks text only; a rendered close-band colour assertion remains | open |
| CLR-03 | a geometric cue beside every band, drawn by `CentsBand` only | U, H | 2.0 | TuneScreen draws cues from CentsBand with GlIcon; no test proves every cue, and cue selection is still in the screen rather than centralized with the band rule | open |

### Export

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EXP-01 | CSV through the share sheet | H | 2.0 |  | open |
| EXP-02 | CSV rows round-trip to 0.1 c | U | 2.0 |  | open |

### Distribution

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DST-01 | AAB, universal APK and SHA256SUMS from one build | CI | 2.0 |  | open |
| DST-02 | merged manifest equals `tools/manifest-allowlist-2.0.txt` | CI | 2.0 | `tools/check-manifest.sh` in `tools/verify.sh` (every push); `verify-selftest.sh` case `stray-permission` proves it goes red | green |
| DST-03 | every `.so` passes the 16 KB alignment check | CI | 2.0 | `tools/check-alignment.sh` in `tools/verify.sh` on the debug APK (every push); `verify-selftest.sh` case `misaligned-library` was seen failing before the stage existed; the release job runs the same script on the release artifacts once `release.yml` lands (M7) | open |
| DST-04 | About shows version, licence, THIRD_PARTY and the privacy policy link | H | 2.0 |  | open |

### Entitlement seam

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ENT-01 | `Entitlement` with one implementation and no call sites outside the three gated features | U | 2.0 | EntitlementContractTest (four tests; the call-site grep covers every module) | green |

### Background hook

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BGH-01 | `ForegroundOwner.onToolsStarted()` on empty-to-one, `onToolsStopped()` on one-to-empty, both no-ops in 2.0 | H | 2.0 | contract shape and the fake registry's call order: OutputPortContractTest (M0); the real registry: OutputRegistryTest `BGH-01` (two tests: started before the open and stopped after the close; a tool that never registered is not a transition) and AudioControlThreadTest `BGH-01` (the owner's hooks bracket the real stream: no stream open at either) | green |

## Totals

215 criteria: 160 from the behaviour spec and 55 from the scope revision. Update the Test column with the fully qualified test name when the red commit lands, and the Status column in the same PR as the green commit.
