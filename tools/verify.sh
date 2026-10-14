#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The full local gate, one verdict. Stages, cheapest first, stopping at the
# first red because later stages build on earlier ones:
#
#   1. identity   tools/identity-scan.sh over the tree and the branch's commits
#   2. sources    skipped-test markers, band thresholds outside CentsBand,
#                 heap allocation, locking or logging on the render path
#   3. U tier     :core:test
#   4. N tier     host-tests/ (cmake, ninja, gtest) when present
#   5. H tier     Robolectric tests, lint, assembleDebug, manifest allowlist
#
# Every test tier must leave JUnit XML with at least one test case and no
# skipped case; a tier that produced nothing is as red as a failing one.
#
#   tools/verify.sh [--base REV] [--skip-h]
#
# --base    the commit the branch grew from (default: merge base with main)
# --skip-h  stop after the N tier; for the inner loop, never before a push
#
# Environment: JAVA_HOME (default /usr/lib/jvm/java-21-openjdk, must be 21),
# ANDROID_HOME, TICKTOCK_DENYLIST (see identity-scan.sh), TICKTOCK_RANGE_INPUTS
# (optional; the range generators' input directory, see tools/gen-ranges).
# Gradle runs offline unless CI is set, because every artifact is already
# pinned and cached.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root" || exit 2

base=""
skip_h=0
while [ $# -gt 0 ]; do
    case "$1" in
        --base) base="${2:?--base needs a revision}"; shift ;;
        --skip-h) skip_h=1 ;;
        -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
        *) echo "verify: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done

logdir="$root/build/verify"
mkdir -p "$logdir"
started=$(date +%s)

red() {
    # $1 stage, $2 reason. The verdict line is the last thing printed.
    echo
    echo "verify: RED at stage '$1': $2" >&2
    echo "VERDICT: RED ($1) after $(( $(date +%s) - started )) s"
    exit 1
}

stage() { echo; echo "== $1 =="; }

# --- environment ---------------------------------------------------------------

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\..*/\1/')
[ "$java_version" = "21" ] || red environment "JAVA_HOME=$JAVA_HOME is Java '$java_version', the build needs 21"

gradle_flags=(--console=plain)
[ -n "${CI:-}" ] || gradle_flags+=(--offline)

run_gradle() {
    # $1 log name, rest: tasks. On failure prints every failed test with its
    # first message line, then the log tail. The tail alone is not enough: a
    # JUnit stack trace plus Gradle's epilogue can push the "FAILED" line and
    # its reason above any fixed tail length, and the self-test reads the
    # reason from this output.
    local log="$logdir/$1.log"; shift
    echo "./gradlew ${gradle_flags[*]} $*"
    if ! ./gradlew "${gradle_flags[@]}" "$@" > "$log" 2>&1; then
        grep -E -A1 '^[^ ].* > .* FAILED$' "$log" || true
        tail -60 "$log"
        return 1
    fi
    grep -E '^(> Task .*(FAILED)|BUILD )' "$log" || true
}

check_results() {
    # $1 tier label, $2 results directory. Counts the <testsuite> headers.
    local tier="$1" dir="$2" summary
    [ -d "$dir" ] || red "$tier" "missing tier output: no results directory at ${dir#"$root"/}"
    summary=$(cat "$dir"/TEST-*.xml 2>/dev/null | awk '
        /<testsuite / {
            match($0, /tests="[0-9]+"/);    t += substr($0, RSTART + 7, RLENGTH - 8)
            match($0, /skipped="[0-9]+"/);  s += substr($0, RSTART + 9, RLENGTH - 10)
            match($0, /failures="[0-9]+"/); f += substr($0, RSTART + 10, RLENGTH - 11)
            match($0, /errors="[0-9]+"/);   e += substr($0, RSTART + 8, RLENGTH - 9)
            n++
        }
        END { printf "%d %d %d %d %d", n + 0, t + 0, s + 0, f + 0, e + 0 }')
    read -r suites tests skipped failures errors <<< "$summary"
    # The directory exists, so the tier ran; no suites means it found nothing.
    [ "$suites" -gt 0 ] && [ "$tests" -gt 0 ] || red "$tier" "zero tests found in ${dir#"$root"/}"
    [ "$skipped" -eq 0 ] || red "$tier" "$skipped skipped test(s): skips are rejected, use @KnownGap"
    [ $((failures + errors)) -eq 0 ] || red "$tier" "$failures failure(s), $errors error(s)"
    echo "$tier: $tests tests in $suites suites, none skipped"
}

# --- 1. identity ---------------------------------------------------------------

stage "identity"
tools/identity-scan.sh --tree || red identity "the working tree matched an identity rule"
if [ -n "$base" ] && ! git rev-parse -q --verify "$base^{commit}" > /dev/null 2>&1; then
    echo "base '$base' is not a commit here; using the merge base with main instead"
    base=""
fi
if [ -z "$base" ]; then
    for candidate in origin/main main; do
        if git rev-parse -q --verify "$candidate^{commit}" > /dev/null 2>&1; then
            base=$(git merge-base HEAD "$candidate") && break
        fi
    done
fi
if [ -n "$base" ] && [ "$(git rev-parse "$base")" != "$(git rev-parse HEAD)" ]; then
    tools/identity-scan.sh --range "$base..HEAD" || red identity "a commit in $base..HEAD matched an identity rule"
else
    echo "no commits above the base; only the tree was scanned"
fi

# --- 2. source guards ----------------------------------------------------------

stage "sources"

# Lines of code only: a comment may explain a rule without triggering it.
code_lines() { grep -n -H -E -v '^[[:space:]]*(//|\*|/\*|#)' "$@" 2>/dev/null; }

test_sources=$(find . -path ./build -prune -o \( -path '*/src/test/*' -o -path '*/src/androidTest/*' -o -path './host-tests/*' \) -type f \( -name '*.kt' -o -name '*.java' -o -name '*.cpp' -o -name '*.cc' -o -name '*.h' \) -print | grep -v '/build/' || true)
if [ -n "$test_sources" ]; then
    # shellcheck disable=SC2086  # the list is newline-separated file names without spaces
    hits=$(code_lines $test_sources | grep -E '@Ignore\b|@Disabled\b|GTEST_SKIP|TEST(_F|_P)?\([A-Za-z_]+, *DISABLED_' || true)
    [ -z "$hits" ] || { echo "$hits"; red sources "skipped tests are rejected; a gap is a @KnownGap test that fails"; }
fi
echo "no skip markers in test sources"

# BND-04: the band thresholds live in CentsBand and nowhere else. Any other
# main-source file that declares a cents constant, compares cents to a
# literal, or compares anything at all against a band literal (5, 12, or the
# 1.x values 3, 15, 10, 25) is a second threshold in the making, whatever the
# variable is called. Unrelated uses are named in tools/band-literal-allowlist.txt.
# The BND-04 test in :core applies the same rule from Kotlin.
band_file="core/src/main/kotlin/ticktock/core/music/CentsBand.kt"
[ -f "$band_file" ] || red sources "$band_file is missing; the band guard has nothing to exclude"
band_literal='(3|5|10|12|15|25)(\.0+)?'
main_sources=$(find . -path ./build -prune -o -path '*/src/main/*' -type f -name '*.kt' -print | grep -v '/build/' | grep -v "^\./$band_file\$" || true)
if [ -n "$main_sources" ]; then
    grep -v -e '^#' -e '^[[:space:]]*$' tools/band-literal-allowlist.txt > "$logdir/band-allow" || true
    # shellcheck disable=SC2086
    hits=$(code_lines $main_sources | grep -E \
        -e '\bval [A-Za-z_]*(Cents|CENTS)[A-Za-z_]*( *: *[A-Za-z]+)? *= *-?[0-9]' \
        -e '\b(IN_TUNE_MAX|CLOSE_MAX) *=' \
        -e '[Cc]ents\)? *(<=?|>=?|==) *-?[0-9]' \
        -e '[0-9] *(<=?|>=?) *(abs\()?[A-Za-z_.]*[Cc]ents\b' \
        -e "(^|[^-=<>!])(<=|>=|==|<|>) *-?$band_literal([^0-9.A-Za-z_]|\$)" \
        -e "(^|[^0-9A-Za-z_.])$band_literal *(<=|>=|<|>)([^>=-]|\$)" \
        | sed -E 's/^\.\/([^:]+):[0-9]+:[[:space:]]*/\1:/' | sed -E 's/[[:space:]]+$//' \
        | grep -v -E -f "$logdir/band-allow" || true)
    [ -z "$hits" ] || { echo "$hits"; red sources "a cents threshold outside $band_file"; }
fi
echo "no cents threshold outside ${band_file##*/}"

# The render callback has a deadline: no heap, no locks, no logging (spec 2.6).
# Everything under engine/src/main/cpp/render is the render path; the engine
# builder keeps the callback and the synth there and the control thread outside.
render_dir="engine/src/main/cpp/render"
if [ -d "$render_dir" ]; then
    render_sources=$(find "$render_dir" -type f \( -name '*.cpp' -o -name '*.cc' -o -name '*.h' -o -name '*.hpp' \) || true)
    if [ -n "$render_sources" ]; then
        # shellcheck disable=SC2086
        hits=$(code_lines $render_sources | grep -E \
            -e '\b(new|delete|malloc|calloc|realloc|free|strdup)\b' \
            -e 'std::(vector|string|map|unordered_map|set|list|deque|function|shared_ptr|unique_ptr|make_shared|make_unique)\b' \
            -e 'std::(mutex|lock_guard|unique_lock|lock|condition_variable)\b' \
            -e '__android_log|\bprintf\(|std::(cout|cerr)' || true)
        [ -z "$hits" ] || { echo "$hits"; red sources "allocation, locking or logging on the render path"; }
    fi
    echo "render path is free of allocation, locks and logging"
else
    echo "render path: $render_dir absent (the engine builder adds it)"
fi

# RNG-03: the committed range layers reproduce from their inputs. The inputs
# live outside the repository, so the check runs only where they are present.
if [ -n "${TICKTOCK_RANGE_INPUTS:-}" ]; then
    tools/gen-ranges/check.sh "$TICKTOCK_RANGE_INPUTS" || red sources "the range layers do not reproduce from $TICKTOCK_RANGE_INPUTS"
else
    echo "range inputs: TICKTOCK_RANGE_INPUTS unset; reproduction not checked here (hashes are checked by RangeLayerDataTest)"
fi

# --- 3. U tier -----------------------------------------------------------------

stage "U tier: :core:test"
rm -rf core/build/test-results
run_gradle u-tier :core:test || red "U tier" ":core:test failed"
check_results "U tier" core/build/test-results/test

# --- 4. N tier -----------------------------------------------------------------

stage "N tier: host-tests"
if [ -f host-tests/CMakeLists.txt ]; then
    rm -rf host-tests/build/results
    n_log="$logdir/n-tier.log"
    {
        cmake -S host-tests -B host-tests/build -G Ninja -DCMAKE_BUILD_TYPE=Release &&
        cmake --build host-tests/build &&
        (cd host-tests/build && ctest --output-on-failure --output-junit results/TEST-host.xml)
    } > "$n_log" 2>&1 || { tail -40 "$n_log"; red "N tier" "host-tests failed"; }
    check_results "N tier" host-tests/build/results
else
    echo "host-tests/ absent (M2 adds it); nothing to run"
fi

if [ "$skip_h" = 1 ]; then
    echo
    echo "VERDICT: GREEN through the N tier (--skip-h) after $(( $(date +%s) - started )) s"
    exit 0
fi

# --- 5. H tier, lint, APK, manifest -------------------------------------------

stage "H tier: Robolectric, lint, assembleDebug"
if ! (cd tools/robolectric-deps && sha256sum -c --quiet MANIFEST.sha256) 2>/dev/null; then
    red "H tier" "the Robolectric jar in tools/robolectric-deps is missing or does not match MANIFEST.sha256"
fi
for m in engine data design app; do rm -rf "$m/build/test-results"; done
run_gradle h-tier --continue \
    :engine:testDebugUnitTest :data:testDebugUnitTest :design:testDebugUnitTest :app:testDebugUnitTest \
    :app:lintDebug :app:assembleDebug \
    || red "H tier" "a Robolectric test, lint or assembleDebug failed"
for m in engine data design app; do
    check_results "H tier ($m)" "$m/build/test-results/testDebugUnitTest"
done
tools/check-manifest.sh || red manifest "the merged manifest differs from tools/manifest-allowlist.txt"

# --- verdict -------------------------------------------------------------------

echo
gaps=$(grep -r -h -o '@KnownGap("[^"]*"' --include='*.kt' --exclude='KnownGapRuleTest.kt' --exclude-dir=build . 2>/dev/null | sed 's/@KnownGap("//; s/"$//' | sort | uniq -c | awk '{ printf "%s x%d ", $2, $1 }')
echo "known gaps: ${gaps:-none}"
echo "VERDICT: GREEN after $(( $(date +%s) - started )) s"
