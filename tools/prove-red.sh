#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Proves that a change's tests fail without the change. A test that has only
# ever been seen passing proves nothing, so this script checks out the base
# commit in a scratch worktree, copies only the branch's test files onto it,
# runs the touched modules' test tasks there and refuses the branch when they
# pass. A compile failure on the base counts as red: the test names code the
# base does not have.
#
#   tools/prove-red.sh [--base REV] [--head REV]
#
# base defaults to the merge base with main. Exit 0 when the branch's tests are
# red on the base (or when it changes no production code, or when the base has
# no Gradle build to run them with), 1 when they pass there or when production
# code changed without any test.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root" || exit 2

base=""
head="HEAD"
while [ $# -gt 0 ]; do
    case "$1" in
        --base) base="${2:?--base needs a revision}"; shift ;;
        --head) head="${2:?--head needs a revision}"; shift ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
        *) echo "prove-red: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done

if [ -z "$base" ]; then
    for candidate in origin/main main; do
        if git rev-parse -q --verify "$candidate^{commit}" > /dev/null 2>&1; then
            base=$(git merge-base "$head" "$candidate") && break
        fi
    done
fi
[ -n "$base" ] || { echo "prove-red: no base found; pass --base" >&2; exit 2; }
base=$(git rev-parse "$base")
head_sha=$(git rev-parse "$head")
echo "prove-red: base ${base:0:12}, head ${head_sha:0:12}"

# Test files are what the branch has to prove with; fixtures and androidTest
# are support code and device tests, run elsewhere.
changed_tests=$(git diff --name-only "$base" "$head_sha" -- '*/src/test/*' 'host-tests/*' | grep -v '^host-tests/build/' || true)
changed_prod=$(git diff --name-only "$base" "$head_sha" -- '*/src/main/*' || true)

if [ -z "$changed_tests" ]; then
    if [ -n "$changed_prod" ]; then
        printf '%s\n' "$changed_prod" | sed 's/^/  /'
        echo "prove-red: REFUSED: production code changed without a test" >&2
        exit 1
    fi
    echo "prove-red: no production code and no tests changed; nothing to prove"
    exit 0
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
gradle_flags=(--console=plain --continue)
[ -n "${CI:-}" ] || gradle_flags+=(--offline)

scratch=$(mktemp -d "${TMPDIR:-/tmp}/prove-red.XXXXXX")
cleanup() {
    git worktree remove --force "$scratch" > /dev/null 2>&1 || rm -rf "$scratch"
}
trap cleanup EXIT

git worktree add --detach "$scratch" "$base" > /dev/null 2>&1 || { echo "prove-red: cannot create a worktree at $base" >&2; exit 2; }

# Only the test files move; everything else stays at the base.
while IFS= read -r f; do
    if git cat-file -e "$head_sha:$f" 2>/dev/null; then
        mkdir -p "$scratch/$(dirname "$f")"
        git show "$head_sha:$f" > "$scratch/$f"
    else
        rm -f "$scratch/$f"
    fi
done <<< "$changed_tests"

# A base without the Gradle build cannot run any test, so every test the
# branch adds is red there by the same reasoning as a compile failure: the
# tests name a build the base does not have. This is the shape of the first
# milestone landing on the README-only root, and the reason is printed so a
# reader of the log sees why nothing was run.
if [ ! -x "$scratch/gradlew" ]; then
    echo "prove-red: OK: the base ${base:0:12} has no Gradle build, so the branch's tests cannot pass there; nothing to run"
    exit 0
fi

# Gitignored inputs the build needs: the Robolectric jar and the SDK path.
mkdir -p "$scratch/tools/robolectric-deps"
for jar in tools/robolectric-deps/*.jar; do
    [ -f "$jar" ] && ln -s "$root/$jar" "$scratch/$jar"
done
[ -f local.properties ] && cp local.properties "$scratch/local.properties"

tasks=()
native=0
while IFS= read -r f; do
    case "$f" in
        core/*) tasks+=(":core:test") ;;
        host-tests/*) native=1 ;;
        */*) tasks+=(":${f%%/*}:testDebugUnitTest") ;;
    esac
done <<< "$changed_tests"
mapfile -t tasks < <(printf '%s\n' "${tasks[@]}" | sort -u | grep -v '^$' || true)

echo "prove-red: running on the base: ${tasks[*]:-} $([ $native = 1 ] && echo host-tests)"
log="$root/build/verify/prove-red.log"
mkdir -p "$(dirname "$log")"
: > "$log"

passed=1
if [ ${#tasks[@]} -gt 0 ]; then
    if ! (cd "$scratch" && ./gradlew "${gradle_flags[@]}" "${tasks[@]}") >> "$log" 2>&1; then
        passed=0
    fi
fi
if [ $native = 1 ] && [ -f "$scratch/host-tests/CMakeLists.txt" ]; then
    if ! (cd "$scratch" && cmake -S host-tests -B host-tests/build -G Ninja -DCMAKE_BUILD_TYPE=Release &&
          cmake --build host-tests/build && cd host-tests/build && ctest --output-on-failure) >> "$log" 2>&1; then
        passed=0
    fi
fi

if [ $passed = 1 ]; then
    echo "prove-red: REFUSED: the branch's tests pass on the base ${base:0:12}; they prove nothing" >&2
    exit 1
fi
grep -E 'FAILED|error:|e: ' "$log" | head -20
echo "prove-red: OK: the branch's tests are red on the base ${base:0:12} (log: build/verify/prove-red.log)"
