# Tools

The gate scripts. Each one runs from any directory and finds the repository
root on its own. They need bash, git, GNU awk and coreutils; the test tiers
need JDK 21 (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`) and the Robolectric
jar staged under `robolectric-deps/`.

| Script | Purpose |
|---|---|
| `identity-scan.sh` | Refuses text that ties the repository to a machine, an account, an assistant or a private project. Modes: `--staged`, `--range A..B`, `--pre-push`, `--history`, `--tree`, `--text FILE`, `--branch NAME`. Rules come from `identity-rules.txt` plus the private denylist at `$TICKTOCK_DENYLIST` (default `~/.config/ticktock/denylist`), whose lines are never printed. Every commit in a range must carry the owner's noreply identity as author and committer. |
| `install-hooks.sh` | Installs the pre-commit (staged diff) and pre-push (pushed ranges) scans and sets the commit identity. Run once per clone. |
| `worktree.sh PATH BRANCH [START]` | Creates a task worktree, scans the branch name, sets the identity and installs the hooks. |
| `verify.sh [--base REV] [--skip-h]` | The full local gate with one verdict: identity, source guards, U tier, N tier, H tier, lint, debug APK, manifest allowlist. About 20 s warm, a few minutes cold. |
| `verify-selftest.sh` | Proves `verify.sh` goes red for the right reason on seven planted failures, after a green control run. Rebuilds the tree per case; about ten minutes. |
| `prove-red.sh [--base REV] [--head REV]` | Copies the branch's test files onto the base commit in a scratch worktree and refuses the branch if they pass there. CI runs it on every PR. |
| `check-manifest.sh [MANIFEST]` | Compares the merged manifest's permissions with `manifest-allowlist.txt`, exact set match. |

## Source guards in `verify.sh`

- **Skipped tests.** `@Ignore`, `@Disabled`, `GTEST_SKIP` and `DISABLED_` test
  names are refused. A behaviour the code does not deliver yet is a
  `@KnownGap("ID", "reason")` test under `KnownGapRule` (in `:core`'s test
  fixtures): it keeps failing on its merits and turns the build red the day it
  passes.
- **Band thresholds.** The cents thresholds live in
  `core/src/main/kotlin/ticktock/core/band/CentsBand.kt` only. Any other
  `src/main` Kotlin file that declares a `...Cents` constant with a literal or
  compares cents to a literal is refused.
- **Render path.** Files under `engine/src/main/cpp/render/` run inside the
  audio callback. Heap allocation, standard containers, locks and logging are
  refused there by a source lint; the host-tests allocation counter adds the
  runtime check.

Every test tier must leave JUnit XML with at least one test case and no
skipped case; a tier that produced nothing is as red as one that failed.

## Known gaps

The registry is the set of `@KnownGap` annotations in the test sources;
`verify.sh` lists them in its verdict. Adding one is an owner decision
(see `AGENTS.md`); removing one happens when the gap closes and the build
turns red.
