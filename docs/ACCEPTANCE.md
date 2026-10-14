# Acceptance ledger

One row per criterion in the behaviour spec. A row turns green only when a test that was seen failing before the fix passes after it; a test only ever seen passing is a comment. Known gaps are strict-failing tests that turn red when the gap closes.

Tiers: **U** pure JVM (`:core:test`), **N** host C++ (gtest, offline render), **H** Robolectric harness (`:app`, `:data`), **D** emulator or the owner's phone. Status values: `open`, `red` (test written, failing on the base), `green`, `gap` (strict known gap, named), `deferred` (ships after 2.0).


## Pitch detection accuracy over the full range

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DET-01 | search band | U | 2.0 | | open |
| DET-02 | interpolation on the raw difference function | U | 2.0 | | open |
| DET-03 | multi-period refinement | U | 2.0 | | open |
| DET-04 | interpolated threshold | U | 2.0 | | open |
| DET-05 | FFT length | U | 2.0 | | open |
| DET-06 | full-range sweep, sine | U | 2.0 | | open |
| DET-07 | full-range sweep, instrument-like timbres | U | 2.0 | | open |
| DET-08 | weak-fundamental high C | U | 2.0 | | open |
| DET-09 | top octave, regression pin | U | 2.0 | | open |
| DET-10 | low A at 430 | U | 2.0 | | open |
| DET-11 | C8 at 450 | U | 2.0 | | open |
| DET-12 | rate invariance | U | 2.0 | | open |
| DET-13 | ceiling test | U | 2.0 | | open |
| DET-14 | window long enough for the floor | U | 2.0 | | open |
| DET-15 | non-finite input | U | 2.0 | | open |
| DET-16 | off the UI thread | H, D | 2.0 | | open |
| DET-17 | per-hop budget | H, D | 2.0 | | open |

## Detector policy

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| POL-01 | first-reading latency | U | 2.0 | DetectorPolicyTest `POL-01` | green |
| POL-02 | legato note-change latency | U | 2.0 | DetectorPolicyTest `POL-02` | green |
| POL-03 | no long-memory vote | U | 2.0 | DetectorPolicyTest `POL-03` | green |
| POL-04 | unknown mode | U | 2.0 | ResponseModeTableTest `POL-04` (`ResponseMode.fromStored`; the prefs loader calls it in M3) | green |
| POL-05 | mode switch resets | U | 2.0 | DetectorPolicyTest `POL-05` | green |
| POL-06 | attack edge | U | 2.0 | DetectorPolicyTest `POL-06` | green |
| POL-07 | release edge | U | 2.0 | DetectorPolicyTest `POL-07` (two tests: silence, note change) | green |
| POL-08 | re-attack of the same note | U | 2.0 | DetectorPolicyTest `POL-08` | green |
| POL-09 | time in hops | U | 2.0 | HopAnalyserTest `POL-09` (400 hops in bursts of 8 with 0–200 ms of fake-clock jitter) | green |
| POL-10 | aperiodicity on every path | U | 2.0 | DetectorPolicyTest `POL-10` (two tests) + HopAnalyserTest `POL-10` (white noise on frames) | green |
| POL-11 | RMS gate | U | 2.0 | DetectorPolicyTest `POL-11` | green |
| POL-12 | mic gain scope | U | 2.0 | DetectorPolicyTest `POL-12` + HopAnalyserTest `POL-12` (the window reaches the estimator unscaled) | green |
| POL-13 | detector window gate | U | 2.0 | DetectorPolicyTest `POL-13` | green |
| POL-14 | leaps of any size are saved | U | 2.0 | DetectorPolicyTest `POL-14` (octave up, fifth down, sixth up) | green |
| POL-15 | one window per hop | U | 2.0 | HopAnalyserTest `POL-15` | green |
| POL-16 | oversized block | U | 2.0 | HopAnalyserTest `POL-16` (two tests: analyser, ring) | green |
| POL-17 | restart replaces the stream | U | 2.0 | HopAnalyserTest `POL-17` (two tests) with FakeAudioInput | green |
| POL-18 | counters visible | U | 2.0 | HopAnalyserTest `POL-18` (`AnalysisCounters`; the Diagnostics page reads them in M6) | green |
| POL-19 | readings carry their A4 | U | 2.0 | DetectorA4Test `POL-19` | green |
| POL-20 | A4 change discards stale readings | U | 2.0 | DetectorA4Test `POL-20` | green |
| POL-21 | same-value change | U | 2.0 | DetectorA4Test `POL-21` | green |
| POL-22 | new run on A4 change | U | 2.0 | DetectorA4Test `POL-22` (the reading stream is partitioned by generation; the run row itself opens under SES-02 in M3) | green |
| POL-23 | table after an A4 change | U | 2.0 | DetectorA4Test `POL-23` (`Pitch.centsFrom` re-derives from stored Hz, and every reading carries its A4) | green |
| POL-24 | bleed is excluded | N + U | 2.0 | U: BleedPolicyTest `POL-24` on scripted estimates; N fixtures land with the engine | open |
| POL-25 | an excluded hop keeps the player's note | N + U | 2.0 | U: BleedPolicyTest `POL-25`; N fixtures land with the engine | open |
| POL-26 | duck on suspicion | N + U | 2.0 | U: BleedPolicyTest `POL-26` (two tests); N fixtures land with the engine | open |
| POL-27 | unison is measured | N + U | 2.0 | U: BleedPolicyTest `POL-27` (two tests); N fixtures land with the engine | open |
| POL-28 | pure leakage is never re-admitted | N + U | 2.0 | U: BleedPolicyTest `POL-28`; N fixtures land with the engine | open |
| POL-29 | no duck, no re-admission | N + U | 2.0 | U: BleedPolicyTest `POL-29`; N fixtures land with the engine | open |
| POL-30 | pipes are duck consumers | N + U | 2.0 | U: BleedPolicyTest `POL-30`; N fixtures land with the engine | open |
| POL-31 | render scope | H | 2.0 | | open |

## Colour bands and rounding

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BND-01 | one rounding function | U | 2.0 | CentsBandTest `BND-01` | green |
| BND-02 | one band rule | U | 2.0 | CentsBandTest `BND-02` | green |
| BND-03 | colour agrees with digits | U | 2.0 | CentsBandTest `BND-03` | green |
| BND-04 | no other thresholds exist | U + source grep | 2.0 | CentsBandTest `BND-04` (three tests) + `tools/verify.sh` sources stage, shared allowlist `tools/band-literal-allowlist.txt` | green |
| BND-05 | no data | U | 2.0 | CentsBandTest `BND-05` | green |
| BND-06 | whole cents only | U | 2.0 | | open |

## Instrument ranges and transpositions

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| RNG-01 | two tiers per key | U | 2.0 | RangeGoldenTest `RNG-01` | green |
| RNG-02 | what each tier drives | U | 2.0 | | open |
| RNG-03 | data source | U | 2.0 | RangeLayerDataTest (source hashes) + `tools/gen-ranges/check.sh` (byte-for-byte reproduction where the inputs are present) | green |
| RNG-04 | golden file | U | 2.0 | RangeGoldenTest `RNG-04` | green |
| RNG-05 | policy test | U | 2.0 | RangeGoldenTest `RNG-05`; the twenty decisions were drafted during M0 and await the owner's confirmation (see `tools/gen-ranges/README.md`) | green |
| RNG-06 | saxophones | U | 2.0 | RangeGoldenTest `RNG-06` | green |
| RNG-07 | other saxophones | U | 2.0 | RangeGoldenTest `RNG-07` | green |
| RNG-08 | corrected families | U | 2.0 | RangeGoldenTest `RNG-08` | green |
| RNG-09 | mappings | U | 2.0 | CatalogueTest `RNG-09` | green |
| RNG-10 | catch-all keys | U | 2.0 | RangeResolverTest `RNG-10` | green |
| RNG-11 | always show cents | U | 2.0 | | open |
| RNG-12 | played out-of-range notes in the table | U | 2.0 | | open |
| RNG-13 | narrowing a range | U | v1.1 | | deferred |
| RNG-14 | instrument switch | U | 2.0 | | open |
| RNG-15 | an override can only widen | U | v1.1 | RangeResolverTest `RNG-15` (the resolver rule landed with the layers; the editor ships in v1.1) | green |
| RNG-16 | strict read before write | U | v1.1 | | deferred |
| RNG-17 | save reports the truth | U | v1.1 | | deferred |
| RNG-18 | envelope v2 | U | v1.1 | | deferred |
| RNG-19 | editor input | U | v1.1 | | deferred |
| RNG-20 | editor limits | U | v1.1 | | deferred |
| RNG-21 | say where ranges come from | U | v1.1 | | deferred |
| TRN-01 | the four the 1.x app has wrong | U | 2.0 | CatalogueTest `TRN-01` | green |
| TRN-02 | end to end | U | 2.0 | CatalogueTest `TRN-02` | green |
| TRN-03 | migrating stored data | U | resolved: no 1.x migration | | closed |
| TRN-04 | octave-clef convention | U | 2.0 | CatalogueTest `TRN-04` | green |
| TRN-05 | sign convention is global | U | 2.0 | CatalogueTest `TRN-05` | green |

## The wrong-instrument hint

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| HNT-01 | three different notes | U | 2.0 | StrayCounterTest `HNT-01` | green |
| HNT-02 | the window is playing time | U | 2.0 | StrayCounterTest `HNT-02` | green |
| HNT-03 | single-note cases never trigger it | U | 2.0 | StrayCounterTest `HNT-03` | green |
| HNT-04 | spread-out strays never trigger it | U | 2.0 | StrayCounterTest `HNT-04` | green |
| HNT-05 | it offers the fix | U | v1.1 | | deferred |
| HNT-06 | editing forgets covered strays | U | v1.1 | | deferred |
| HNT-07 | scope | U | 2.0 | StrayCounterTest `HNT-07` | green |
| HNT-08 | cents keep showing | U | 2.0 | StrayCounterTest `HNT-08` | green |

## Sessions and runs, history bounds and storage

The scope revision adds columns to the M3 schema before it freezes: attack counts (ATK-01), capture
context and target (CTX-01), equipment (EQP-01) and the paused-capture state (CAP-02); they are
ledgered under their own prefixes below.

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SES-01 | the first run starts with saved prefs | H | 2.0 | | open |
| SES-02 | new-run triggers | H | 2.0 | | open |
| SES-03 | SESSION means this session | H | 2.0 | | open |
| SES-04 | per-nickname separation | H | 2.0 | | open |
| SES-05 | validate before storing | H | 2.0 | | open |
| SES-06 | batched writes | H | 2.0 | | open |
| SES-07 | write errors surface | H | 2.0 | | open |
| SES-08 | summaries and indexes | H | 2.0 | | open |
| SES-09 | retention | H | 2.0 | | open |
| SES-10 | storage budget | H | 2.0 | | open |
| SES-11 | one writer | H | 2.0 | | open |
| SES-12 | unknown and newer fields | H | 2.0 | | open |
| SES-13 | zero stays zero | H | 2.0 | | open |
| SES-14 | golden stored data | H | 2.0 | | open |
| SES-15 | CSV and TXT export | H | v1.1 | | deferred |

## Output lifecycle and the microphone

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| OUT-01 | output-user registry | H | 2.0 (decision 12 removes the focus clause: the registry opens the stream on the first tool and closes it on the last, and its two transitions drive BGH-01) | | open |
| OUT-02 | truthful state | H | 2.0 | | open |
| OUT-03 | unplug | H, D | 2.0 | | open |
| OUT-04 | drone AUTO on unplug | H | v1.1 | | deferred |
| OUT-05 | replug | H, D | 2.0 | | open |
| OUT-06 | off stays off | H | 2.0 | | open |
| OUT-07 | late devices | H | 2.0 | | open |
| OUT-08 | permanent loss | H | superseded by decisions 4 and 12: another app taking audio never parks the tools; the contract has no focus reason | | closed |
| OUT-09 | transient loss | H | superseded by decisions 4 and 12: an answered call parks every tool (`ParkReason.INCOMING_CALL`) and nothing restarts on its own; ringing is CAL-01; no duck on focus | OutputPortContractTest | red |
| OUT-10 | mute reasons | H | 2.0 | | open |
| OUT-11 | background policy | H | 2.0 (v1 policy: stop everything) | | open |
| OUT-12 | route from the routed device | H, D | 2.0 | | open |
| OUT-13 | rate change | H | 2.0 | | open |
| OUT-14 | rate peg | H | 2.0 | | open |
| OUT-15 | start and stop never block | H | 2.0 | | open |
| OUT-16 | render thread | H | 2.0 | | open |
| OUT-17 | error names | H | 2.0 | | open |
| IN-01 | one owner and release | H | 2.0 | | open |
| IN-02 | busy or missing | H | 2.0 | | open |
| IN-03 | saved interface stays saved | H | 2.0 | | open |
| IN-04 | replug recovers | H, D | 2.0 | | open |
| IN-05 | honest silence | H | 2.0 | | open |
| IN-06 | a hung driver costs one timeout | H | 2.0 (the fallback trigger is the property check, SRC-01) | | open |

## Metronome timing

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| MET-01 | sample clock | N | 2.0 | | open |
| MET-02 | zero drift | N | 2.0 (a muted beat keeps its subdivisions audible, GAP-03) | | open |
| MET-03 | in-block placement | N | 2.0 | | open |
| MET-04 | late events | N | 2.0 | | open |
| MET-05 | exactly once | N | 2.0 | | open |
| MET-06 | re-anchor on the beat that sounded | N | 2.0 | | open |
| MET-07 | metre change | N | 2.0 | | open |
| MET-08 | 64-beat scenarios | N | 2.0 | | open |
| MET-09 | output switch | N | 2.0 | | open |
| MET-10 | range and diagnostics | N, H | 2.0 | | open |

## Drone and pitch pipes follow A4

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TON-01 | saved A4 at start-up | H | 2.0 | | open |
| TON-02 | accuracy | N | 2.0 | | open |
| TON-03 | held notes retune | N | 2.0 | | open |
| TON-04 | volume is gain | N | 2.0 | | open |
| TON-05 | sine voice and fallback | N | 2.0 | | open |
| TON-06 | transposition persists | H | 2.0 | | open |

## Deck recording and save location

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DCK-01 | headphone monitoring | H | v1.1 | | deferred |
| DCK-02 | speaker route mute | H | v1.1 | | deferred |
| DCK-03 | survives process death | H | v1.1 | | deferred |
| DCK-04 | a real destination | H | v1.1 | | deferred |
| DCK-05 | failed save | H | v1.1 | | deferred |
| DCK-06 | playback after a rate change | H | v1.1 | | deferred |
| DCK-07 | share | H | v1.1 | | deferred |

## Crash and error handling

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ERR-01 | error boundary per screen | H | 2.0 | | open |
| ERR-02 | fatal errors | H | 2.0 | | open |
| ERR-03 | no silent catches | H | 2.0 | | open |
| ERR-04 | corrupt prefs | H | 2.0 | | open |
| ERR-05 | unreadable prefs | H | 2.0 | | open |
| ERR-06 | prefs save failure | H | 2.0 | | open |
| ERR-07 | analysis errors | H | 2.0 | | open |

## Accessibility (font scale)

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| A11Y-01 | tuner readout | H | 2.0 | | open |
| A11Y-02 | every screen | H | 2.0 (supplemented by CLR-01..03) | | open |
| A11Y-03 | colour is never alone | H | 2.0 | | open |
| A11Y-04 | touch targets | H | 2.0 | | open |

## Scope revision, 2026-10-14

Criteria added by the adopted scope revision (decisions 12 to 16). The text of each lives in the
revision document; the IDs are claimed here like any other.

### Capture control

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CAP-01 | paused capture drives the needle and reaches no store | H | 2.0 | | open |
| CAP-02 | paused state survives process death | H | 2.0 | | open |
| CAP-03 | a run opened while paused counts skipped readings | H | 2.0 | | open |

### Attacks as the unit

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ATK-01 | attack columns in note_hist | H | 2.0 | | open |
| ATK-02 | one attack per attack edge, landing cents from the first settled reading | U, H | 2.0 | U: DetectorPolicyTest `ATK-02` (two tests: one `Attack` per attack edge carrying the landing cents of the confirming hops, and the first settled reading flagged `firstSettled`); H: the store counts them in M3 | open |
| ATK-03 | held note versus sweep | H | 2.0 | | open |
| ATK-04 | attacks shown beside readings | H | 2.0 | | open |

### Run context and target

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CTX-01 | context and target columns | H | 2.0 | | open |
| CTX-02 | drone start and stop open runs | H | 2.0 | | open |
| CTX-03 | drone runs enter All-history by toggle only | H | 2.0 | | open |
| CTX-04 | no 2.0 path writes `just` | H | 2.0 | | open |

### Equipment

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EQP-01 | equipment column | H | 2.0 | | open |
| EQP-02 | equipment field on the session sheet | H | 2.0 | | open |

### Tuning presets

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CHK-01 | ensemble and check-set pads marked | H | 2.0 | | open |
| CHK-02 | check sets per instrument, golden over all 73 keys | U | 2.0 | | open |
| CHK-03 | Next check retunes in place | N, H | 2.0 | | open |

### A4 range

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| A4W-01 | A4 steps 415 to 466 Hz (decision 16; the personal-config range and the sweep ends follow in M1) | U, H | 2.0 | U: PitchMathTest, DetectorA4Test and HopAnalyserTest `A4W-01`, PersonalConfigTest (415 and 466 accepted, 414.9 and 466.1 refused); H: the stepper in M4 | open |

### Ears first

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EAR-01 | readout hidden, capture unchanged | H | 2.0 | | open |
| EAR-02 | reveal after the note | H | 2.0 | | open |
| EAR-03 | setting persists | H | 2.0 | | open |

### Pitch trace

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TRC-01 | five-second trace from a fixed ring, no root recomposition | H | 2.0 | | open |
| TRC-02 | silence breaks the line | H | 2.0 | | open |
| TRC-03 | TalkBack reads the trace once | H | 2.0 | | open |

### Gap bars and offbeats

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| GAP-01 | N bars on, M bars off (the fields and the on/off rule landed in M0: MetroSetupTest; the generator lands in M2) | N, H | 2.0 | | open |
| GAP-02 | the cycle keeps its bar position across a tempo change | N | 2.0 | | open |
| GAP-03 | a muted beat keeps its subdivisions | N | 2.0 | | open |
| GAP-04 | odd-metre presets | H | 2.0 | | open |

### Calls without audio focus

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CAL-01 | ringing adds the mute reason within 500 ms | H | 2.0 | | open |
| CAL-02 | an answered call parks with "Paused: phone call" | H | 2.0 | | open |
| CAL-03 | mode observed by poll on 28–30 and by listener on 31+; no READ_PHONE_STATE | H | 2.0 | | open |
| CAL-04 | another app's music changes nothing | H, D | 2.0 | | open |
| CAL-05 | a silenced client is named in the status sentence | H | 2.0 | | open |

### Input source

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SRC-01 | source chosen by the UNPROCESSED property | H | 2.0 | | open |
| SRC-02 | Diagnostics shows source and effects | H | 2.0 | | open |
| SRC-03 | floors shift by the source offset | U | 2.0 | ResponseModeTableTest `SRC-03` | green |

### Output stream mode

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| SHM-01 | SHARED, LOW_LATENCY, two bursts, granted values reported (decision 13) | H, D | 2.0 | | open |
| SHM-02 | buffer grows on xruns up to four bursts | H | 2.0 | | open |
| SHM-03 | routing-changed callback on API 37 | H, D | 2.0 | | open |

### Bluetooth calibration

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BTC-01 | tap calibration on Bluetooth routes | H, D | 2.0 | | open |
| BTC-02 | one offset per route class | H | 2.0 | | open |

### TalkBack

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| TLK-01 | needle state description, read on focus only | H | 2.0 | | open |
| TLK-02 | opt-in settled-note live region | H | 2.0 | | open |
| TLK-03 | table rows read as sentences | H | 2.0 | | open |
| TLK-04 | beats never announced; stepper actions | H | 2.0 | | open |

### Colour and shape

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| CLR-01 | `attention-ink` token at 4.5:1 on white and 3:1 on gray | U | 2.0 | | open |
| CLR-02 | small close-band text uses `attention-ink` | H | 2.0 | | open |
| CLR-03 | a geometric cue beside every band, drawn by `CentsBand` only | U, H | 2.0 | | open |

### Export

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| EXP-01 | CSV through the share sheet | H | 2.0 | | open |
| EXP-02 | CSV rows round-trip to 0.1 c | U | 2.0 | | open |

### Distribution

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| DST-01 | AAB, universal APK and SHA256SUMS from one build | CI | 2.0 | | open |
| DST-02 | merged manifest equals `tools/manifest-allowlist-2.0.txt` | CI | 2.0 | `tools/check-manifest.sh` in `tools/verify.sh` (every push); `verify-selftest.sh` case `stray-permission` proves it goes red | green |
| DST-03 | every `.so` passes the 16 KB alignment check | CI | 2.0 | `tools/check-alignment.sh` in `tools/verify.sh` on the debug APK (every push); `verify-selftest.sh` case `misaligned-library` was seen failing before the stage existed; the release job runs the same script on the release artifacts once `release.yml` lands (M7) | open |
| DST-04 | About shows version, licence, THIRD_PARTY and the privacy policy link | H | 2.0 | | open |

### Entitlement seam

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| ENT-01 | `Entitlement` with one implementation and no call sites outside the three gated features | U | 2.0 | EntitlementContractTest (four tests; the call-site grep covers every module) | green |

### Background hook

| ID | Criterion | Tier | Ships | Test | Status |
|---|---|---|---|---|---|
| BGH-01 | `ForegroundOwner.onToolsStarted()` on empty-to-one, `onToolsStopped()` on one-to-empty, both no-ops in 2.0 | H | 2.0 | contract shape and the fake registry's call order: OutputPortContractTest (M0); the real registry's test lands in M2 | open |

## Totals

215 criteria: 160 from the behaviour spec and 55 from the scope revision. Update the Test column with the fully qualified test name when the red commit lands, and the Status column in the same PR as the green commit.
