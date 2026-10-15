# Rules for coding agents

This file is the whole brief for an agent working in this repository, together with the
specification documents the owner hands over. It links to no external site.

## Clean room

- Implement from the specification documents and from the data files the owner supplies.
- Never open the desktop application by the third-party upstream author, or any copy of it. Its
  code must never enter this tree, in any form.
- The previous 1.x Android app may be read for **behaviour only**. Copy no line, no comment, no
  identifier list. Cite it in a PR as "1.x behaviour".
- Design inspiration material the owner provides is read-only: learn the patterns, re-implement
  them, copy no code and no text, and never name the source project anywhere in this repository.
- No third-party GPL code. MuseScore range data is used as credited factual data only.

## Identity and history

- Commit as the owner's noreply identity, set with `git config` in every worktree. Plain
  conventional commit messages (`feat(core): ...`, `fix(engine): ...`, `test: ...`, `docs: ...`).
- No AI attribution of any kind: no co-author trailers, no generated-by lines, no session links,
  in commits, PR bodies, tags or release notes.
- Nothing committed may contain a home path, a host name, a tailnet name, a reviewer's name, an
  assistant or model name, or any string on the private denylist. The denylist lives outside the
  repository; agents check their work against it and never copy its contents anywhere.
- Branch names carry the milestone, module and criterion ID, for example `m2/engine-MET-06-reanchor`,
  and never a person, agent or model name.
- Never push, tag or change repository settings. Commit locally; the owner's release engineer
  pushes.

## Installs

- Every new build artifact is a checkpoint. Before proposing one, state: package, exact version,
  source, purpose, transitive impact, lifecycle scripts, release age, provenance and rollback. Then
  stop and wait for the owner.
- Never run `npx`, `pip install`, `sdkmanager --install`, a global install, or a Gradle task that
  would resolve an artifact missing from `gradle/verification-metadata.xml`. The build runs in
  strict verification mode; a missing hash is a failure, never a prompt to fetch.
- Build with JDK 21 only: `JAVA_HOME` set explicitly; Gradle never downloads a JDK.
- Pin every GitHub Action to a full commit SHA with the tag in a comment.

## Ownership

| Role | Owns |
|---|---|
| Architect | `core/src/commonMain/kotlin/ticktock/core/contracts`, `core/.../detect/Reading.kt`, `core/.../metro/MetroSetup.kt`, the JNI shape, `docs/DECISIONS.md`, `docs/ACCEPTANCE.md`, the module-wall tests |
| Core builder | `core/` |
| Engine builder | `engine/`, `host-tests/` |
| Data builder | `data/` |
| Surface builder | `design/`, `app/src/main/kotlin/ticktock/app/screens`, screenshot tests |
| Test author | `app/src/test` harness, `tools/`, `.github/workflows`, the manifest allowlist |
| Release engineer | `gradle/`, `private/` conventions, release workflow, `CHANGELOG.md` |

A PR touches only its owner's directories. Cross-module needs go through an architect interface PR
first. Any change to `OutputCommand`, `OutputEvent`, a contract interface or the JNI shape updates
the native side, the Kotlin side, the fake and the round-trip test in the same PR.

## Tests

- Every fix lands as two commits: `test:` adds a test that fails on the base, then `feat:` or
  `fix:` turns it green. A test only ever seen passing is a comment.
- Known gaps are named strict-failing tests. `@Ignore` and skipped tests are rejected.
- The fast tiers run on every edit: `./gradlew :core:jvmTest` and the host C++ tests. The full suite
  runs before every PR.
- `docs/ACCEPTANCE.md` is the ledger. Claim criterion IDs in the PR title; two open PRs never claim
  the same ID.

## Gridline, everywhere

Every screen, component, dialog, sheet, notification, empty state and the project site uses the
Gridline design system, built from Compose Foundation only:

- square corners; System Gray `#C6C6C6` structure with one-unit bevel relief; white content fields
  with ink `#101010` borders; Active Blue `#0000A8` title strips and active states;
- green, amber and cyan only for their meanings (in tune, close, live);
- IBM Plex Sans and Mono, bundled;
- a visible status bar of one- or two-word facts ("Mic off", "Bluetooth");
- show, don't tell: no prose about the interface; empty and gated states are dimmed ghost layouts with an icon;
- no Material components, gradients, shadows, pills, ripples or emoji. The `:design` module exposes
  no API for any of these, so drift is a compile error or a screenshot diff.

UI copy describes what a thing is and what an action does. Status text is a plain fact
("Listening", "Microphone is delivering silence", "Paused: headphones disconnected"). Error text
names the device and what was refused. Empty states say what will appear and how to make it appear.
No slogans, no marketing, no explanatory negatives.

## The gate

- A worktree's first command is `tools/install-hooks.sh` (or create it with
  `tools/worktree.sh PATH BRANCH`): it installs the pre-commit and pre-push identity scans
  and sets the commit identity.
- `tools/verify.sh` is the full local gate and must be green before every PR: identity scan,
  source guards, the U, N and H tiers, lint, the debug APK, the manifest allowlist and the 16 KB
  page alignment of every native library, with one verdict. `--skip-h` stops after the N tier
  for the inner loop.
- `tools/prove-red.sh` shows the PR's tests fail on the base; its output goes in the PR body.
- The render callback lives under `engine/src/main/cpp/render/`, where allocation, locks and
  logging are refused. The band thresholds live in `CentsBand.kt` and nowhere else.
- `tools/README.md` describes every script.

## Build and test commands

```sh
export JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=/path/to/android-sdk
./gradlew help
./gradlew :core:jvmTest
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :design:testDebugUnitTest :data:testDebugUnitTest :engine:testDebugUnitTest
```

Robolectric runs offline from `tools/robolectric-deps/`; stage the jar named in its manifest and
verify it with `sha256sum -c` first.
