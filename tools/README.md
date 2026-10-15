# Tools

The gate scripts. Each one runs from any directory and finds the repository
root on its own. They need bash, git, grep, POSIX awk (GNU or mawk) and coreutils; the test tiers
need JDK 21 (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`) and the Robolectric
jar staged under `robolectric-deps/`.

| Script | Purpose |
|---|---|
| `identity-scan.sh` | Refuses text that ties the repository to a machine, an account, an assistant or a private project. Modes: `--staged`, `--range A..B`, `--pre-push`, `--history`, `--tree`, `--text FILE`, `--branch NAME`. Rules come from `identity-rules.txt` plus the private denylist at `$TICKTOCK_DENYLIST` (default `~/.config/ticktock/denylist`), whose lines are never printed. Every commit in a range must carry the owner's noreply identity as author and committer. |
| `install-hooks.sh` | Installs the pre-commit (staged diff) and pre-push (pushed ranges) scans and sets the commit identity. Run once per clone. |
| `worktree.sh PATH BRANCH [START]` | Creates a task worktree, scans the branch name, sets the identity and installs the hooks. Branch names start with a milestone, `m2/engine-MET-06-reanchor`; git keeps `mN` and `mN/...` from coexisting, in either order, so a milestone-wide branch beside task branches is `mN-<role>`, as `m0-tests` and `m1-integrate`. |
| `verify.sh [--base REV] [--skip-h]` | The full local gate with one verdict: identity, source guards, U tier, N tier, H tier, lint, debug APK, manifest allowlist, 16 KB alignment and complete supported ABIs. About 20 s warm, a few minutes cold. |
| `verify-selftest.sh [--only NAME ...]` | Proves `verify.sh` goes red for the right reason on ten planted failures, after a green control run. Rebuilds the tree per case; about fifteen minutes. `--only` runs the control plus the named cases. |
| `prove-red.sh [--base REV] [--head REV]` | Copies the branch's test files onto the base commit in a scratch worktree and refuses the branch if they pass there. CI runs it on every PR. |
| `check-manifest.sh [MANIFEST] [ALLOWLIST]` | Compares the merged manifest's permissions with the release's allowlist, `manifest-allowlist-<major.minor>.txt` chosen from `versionName`, exact set match. |
| `check-abis.py APK_OR_AAB ...` | Requires exactly the supported arm64-v8a and x86_64 native trees, with the audio engine present in each. |
| `test-signing-gate.sh` | Runs both release entry points with absent signing properties and requires the signing guard to reject them. Use a checkout without private signing properties. |
| `check-alignment.sh [APK_OR_DIR ...]` | Proves every arm64-v8a and x86_64 `.so` in the debug APK (or any APK or directory given) has LOAD segments aligned to 16 KB (DST-03). No library found is a failure. |
| `device/readout.sh --tone HZ \| --live [--a4 HZ] [--seconds N] [--expect NOTE]` | Installs the debug APK on the connected device or emulator, opens the debug live readout on a synthesised tone (the only input path on a headless emulator) or the real microphone, and prints the readout lines the app logged; `--expect` makes the last line's note a pass or fail; `--a4` sets the A4 the detector measures against; `--burst-hops 64 --expect-drops` injects an oversized block and requires a nonzero dropped-hop count. Evidence lands in `verification/<date>/`. |
| `device/output.sh [--drone MIDI] [--metro BPM] [--seconds N] [--expect-beats K]` | Installs the debug APK, starts the shared Drone/Metronome owner through the real engine (control thread, JNI bridge, AAudio) and reads the output events back from logcat: the stream that opened, the beats, the counters and the render callback's frame clock. Passes when a stream opened, frames advanced, at least K beats arrived and the stream closed when the screen was left. Evidence lands in `verification/<date>/`. |
| `gen-ranges/check.sh INPUT_DIR` | Regenerates the two range layers from their inputs and compares byte for byte (RNG-03). `verify.sh` runs it when `TICKTOCK_RANGE_INPUTS` is set. |

## Source guards in `verify.sh`

- **Skipped tests.** `@Ignore`, `@Disabled`, `GTEST_SKIP` and `DISABLED_` test
  names are refused. A behaviour the code does not deliver yet is a
  `@KnownGap("ID", "reason")` test under `KnownGapRule` (in `:core`'s test
  fixtures): it keeps failing on its merits and turns the build red the day it
  passes.
- **Band thresholds.** The cents thresholds live in
  `core/src/commonMain/kotlin/ticktock/core/music/CentsBand.kt` only. Any other
  `src/main` Kotlin file that declares a `...Cents` constant with a literal,
  compares cents to a literal, or compares anything against a band literal
  (5, 12, or the 1.x values 3, 15, 10, 25) is refused, whatever the variable
  is called. A comparison that has nothing to do with cents is named in
  `band-literal-allowlist.txt` with its reason. The BND-04 test in `:core`
  applies the same rule and reads the same allowlist.
- **Render path.** Files under `engine/src/main/cpp/render/` run inside the
  audio callback, and so do three functions in files that otherwise belong to
  the control thread: `OutputController::onRender`, `AAudioDevice::onData` and
  the bridge's `onWake`. Heap allocation, standard containers, locks and
  logging are refused in all of them by a source lint, which fails if one of
  the three functions goes missing; the host-tests allocation counter adds the
  runtime check for the controller's callback.

Every test tier must leave JUnit XML with at least one test case and no
skipped case; a tier that produced nothing is as red as one that failed.

## Known gaps

The registry is the set of `@KnownGap` annotations in the test sources;
`verify.sh` lists them in its verdict. Adding one is an owner decision
(see `AGENTS.md`); removing one happens when the gap closes and the build
turns red.

`test-release.sh` creates temporary two-day signing fixtures, rejects incomplete or invalid
credentials, and exercises the signed APK/AAB artifact pipeline with separate app and upload keys.
It leaves its private fixtures and logs under the ignored `build/release-tests/` directory.

Release tooling (JDK 21 and the pinned Android SDK must already be staged):

- `stage-signing.py NEW_DIRECTORY` validates the `RELEASE_*` environment values and writes private
  PKCS12 files and UTF-8 Java properties. It refuses an existing destination and never prints secrets.
- `test-stage-signing.py` checks refusal cases, file permissions and special-character round trips.
- `:app:releaseArtifacts` builds and validates the universal APK and AAB in one offline invocation.
  `verify-release.py` checks exactly those two files and their checksums, application identity,
  manifest permissions, signatures, ABI completeness, ELF alignment and APK ZIP alignment.
  `VerifyBundle.java` verifies every signed bundle payload against the expected upload certificate.
- `test-release-validation.py FIXTURE_DIRECTORY` uses the fixture from `test-release.sh` to prove
  altered artifacts, wrong certificates, wrong checksums and an altered manifest are refused. It also
  compares every bundle payload entry before and after upload signing.
- `reproduce-release.sh COMMIT SIGNING_PROPERTIES` builds from two fresh clones with different paths,
  disables the task-output cache and compares the complete signed APK and AAB byte for byte.
  Evidence and clones remain in the ignored `build/reproduction/` directory.
- `release-source.py TAG REVISION` requires a clean source tree at the expected revision, matches
  the tag to the application version and extracts that version's curated changelog section.
  `test-release-source.py` checks version mismatch and section boundaries.
