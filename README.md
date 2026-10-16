# Adequate Metronome

Adequate Metronome, by AdequateApps (codename TickTock), is a native Android practice app for wind players: a tuner that keeps
per-note intonation statistics, a drone and reference tones, and a metronome. This repository is a
ground-up rebuild in Kotlin, Jetpack Compose Foundation and a small C++ audio engine.

The app includes Tune, Drone, Metronome and Table, saved practice sessions, CSV sharing, personal
settings import/export and local diagnostics. Four themes and large-text layouts use the Gridline
design system. Audio processing and history stay on the device. Playback and listening stop in the
background. Android 9 or later and a supported 64-bit device are required.

Version 2.0 is in release preparation. Production signing, physical-device acceptance and store
enrollment remain pending. See the [acceptance ledger](docs/ACCEPTANCE.md),
[release runbook](docs/release.md), [privacy policy](docs/privacy.md), and
[download page source](docs/index.html). Published assets will appear in
[Releases](https://github.com/adequate-apps/tick-tock/releases).

## Layout

| Module | Role |
|---|---|
| `core` | Pure Kotlin JVM: contracts, music maths, detector, instrument catalogue, layered ranges (`tools/gen-ranges`), the personal configuration (`docs/personal-config.md`). Imports nothing from Android. |
| `engine` | Android library: capture and analysis threads in Kotlin, render callback and synth in C++. |
| `data` | Android library: SQLite history, prefs file, crash log. |
| `design` | Android library: the Gridline design system in Compose Foundation, with bundled IBM Plex. |
| `app` | The application: `MainActivity`, navigation, screens. |

Dependencies point one way: `app` depends on the other four, `engine` and `data` depend on `core`,
`design` depends on Compose only.

## Building

Requirements, all local: JDK 21, the Android SDK with platform 37, build-tools 36.0.0,
NDK 28.2.13676358 and CMake 3.22.1. Point `JAVA_HOME` at the JDK and `ANDROID_HOME` at the SDK
(or write `sdk.dir` into a gitignored `local.properties`).

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew --offline help
./gradlew --offline :core:jvmTest :app:assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. The application id is
`com.adequateapps.metronome` (decision 17); a release build with a `com.example` id fails on
purpose, see `docs/DECISIONS.md`.

The build never fetches a JDK (`org.gradle.java.installations.auto-download=false`) and refuses
any dependency that is missing from `gradle/verification-metadata.xml` or whose sha256 differs.
Changing a version in `gradle/libs.versions.toml` therefore needs a reviewed metadata update.

## Testing

| Tier | Command | What it covers |
|---|---|---|
| U | `./gradlew --offline :core:jvmTest` | Pure functions, detector, music maths and contract round trips |
| N | `tools/verify.sh --skip-h` | Core tests and host C++ audio-engine tests |
| H | `./gradlew --offline :app:testDebugUnitTest :design:testDebugUnitTest :data:testDebugUnitTest :engine:testDebugUnitTest` | Robolectric: screens, semantics, state, storage, routes and screenshot assertions |
| D | `./gradlew --offline :app:connectedDebugAndroidTest` | Instrumented tests on an emulator or device |

The H tier runs Robolectric offline. Stage the android-all jar named in
`tools/robolectric-deps/MANIFEST.sha256` into that directory and check it with `sha256sum -c`
before running; a missing jar fails the run instead of downloading.

Screenshot goldens for the screens and themes live in `app/src/test/screenshots/`:

```sh
./gradlew --offline :app:verifyRoborazziDebug   # fail on any pixel change against the goldens
./gradlew --offline :app:recordRoborazziDebug   # rewrite the goldens after a reviewed visual change
```

`tools/verify.sh` runs U, N and H plus lint, the debug APK, the manifest allowlist and the
identity scan, ABI completeness and native alignment, and prints one verdict; `tools/README.md`
describes the gate scripts. Signed release tests use temporary keys; production credentials are
unnecessary for development checks. The release task produces and verifies an APK, AAB and checksums
in one invocation and the reproduction script compares two fresh clones.

`docs/ACCEPTANCE.md` lists every behaviour criterion with its tier and status.
`docs/design/gridline-android.md` is the component inventory and the messaging rules every screen
follows.

## Design tokens and fonts

`design/.../Tokens.kt` is generated from the Gridline token sheet by `tools/gen-tokens/gen_tokens.py`
(see its README); a test fails when the file drifts. The IBM Plex weights the type scale uses are
staged into `design/src/main/res/font/` by `tools/fonts/stage.sh` and pinned by
`tools/fonts/MANIFEST.sha256`.

## Icon

`assets/icon.svg` and `assets/icon-monochrome.svg` are the sources. `python3 scripts/gen_icon.py`
renders every launcher, adaptive, themed, splash and web size from them. It needs `rsvg-convert`
(librsvg) and ImageMagick's `magick`. The launcher layers under `app/src/main/res/drawable-nodpi/`
are copies of the rendered adaptive PNGs.

## Licence

GPL-3.0-or-later; see `LICENSE`. Third-party data and fonts are credited in `THIRD_PARTY.md`.
