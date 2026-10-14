#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Proves that tools/verify.sh goes red for the right reason. Each case gets a
# scratch worktree holding the current tree (HEAD plus uncommitted changes),
# plants one failure, runs the gate and checks both the verdict and the stage
# that produced it. A control run on the unmodified tree must be green first,
# otherwise a red proves nothing.
#
# The seven planted failures:
#   1. missing tier output     the U tier task is disabled, so it writes no XML
#   2. zero tests found        the U tier runs with a filter that matches nothing
#   3. a passing known gap     a @KnownGap test whose body passes
#   4. allocation in render    a heap allocation in engine/src/main/cpp/render
#   5. a threshold outside CentsBand   a cents constant in another core file
#   6. a denylisted string     one denylist entry pasted into README.md
#   7. a wrong commit author   a commit on the branch by someone else
#
# The denylisted string is read from the denylist at run time, written into
# the scratch tree only and never printed.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root" || exit 2
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
denylist="${TICKTOCK_DENYLIST:-$HOME/.config/ticktock/denylist}"
[ -f "$denylist" ] || { echo "verify-selftest: no denylist at $denylist" >&2; exit 2; }

base=""
for candidate in origin/main main; do
    if git rev-parse -q --verify "$candidate^{commit}" > /dev/null 2>&1; then
        base=$(git merge-base HEAD "$candidate") && break
    fi
done
[ -n "$base" ] || { echo "verify-selftest: no base branch found" >&2; exit 2; }

scratch=""
make_scratch() {
    scratch=$(mktemp -d "${TMPDIR:-/tmp}/verify-selftest.XXXXXX")
    git worktree add --detach "$scratch" HEAD > /dev/null 2>&1 || { echo "cannot create worktree" >&2; exit 2; }
    # Carry uncommitted work so the gate under test is the one being edited.
    git diff HEAD --binary | git -C "$scratch" apply --allow-empty
    git ls-files --others --exclude-standard | while IFS= read -r f; do
        mkdir -p "$scratch/$(dirname "$f")" && cp "$f" "$scratch/$f"
    done
    for jar in tools/robolectric-deps/*.jar; do
        [ -f "$jar" ] && ln -s "$root/$jar" "$scratch/$jar"
    done
    [ -f local.properties ] && cp local.properties "$scratch/local.properties"
    git -C "$scratch" config user.name knelsonb
    git -C "$scratch" config user.email 222288362+knelsonb@users.noreply.github.com
}
drop_scratch() {
    git worktree remove --force "$scratch" > /dev/null 2>&1 || rm -rf "$scratch"
    git worktree prune
}

results=()
failed=0

# run_case NAME EXPECTED_VERDICT EXPECTED_TEXT PLANT_FUNCTION
run_case() {
    local name="$1" want_verdict="$2" want_text="$3" plant="$4" log verdict ok=0
    make_scratch
    "$plant"
    log="$root/build/verify/selftest-$name.log"
    mkdir -p "$(dirname "$log")"
    (cd "$scratch" && tools/verify.sh --base "$base") > "$log" 2>&1
    verdict=$(grep -o '^VERDICT: [A-Z]*' "$log" | tail -1 | sed 's/VERDICT: //')
    if [ "$verdict" = "$want_verdict" ] && grep -q -F -e "$want_text" "$log"; then ok=1; fi
    if [ $ok = 1 ]; then
        results+=("ok    $name: $verdict, '$want_text' seen")
    else
        results+=("FAIL  $name: got '${verdict:-no verdict}', wanted $want_verdict with '$want_text' (see ${log#"$root"/})")
        failed=1
    fi
    drop_scratch
}

plant_nothing() { :; }

plant_missing_output() {
    printf '\ntasks.test { enabled = false }\n' >> "$scratch/core/build.gradle.kts"
}

plant_zero_tests() {
    printf '\ntasks.test { filter { includeTestsMatching("ticktock.nothing.*"); isFailOnNoMatchingTests = false } }\n' >> "$scratch/core/build.gradle.kts"
}

plant_passing_gap() {
    mkdir -p "$scratch/core/src/test/kotlin/ticktock/core"
    cat > "$scratch/core/src/test/kotlin/ticktock/core/PlantedGapTest.kt" <<'EOF'
// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ticktock.core.testing.KnownGap
import ticktock.core.testing.KnownGapRule

class PlantedGapTest {
    @get:Rule
    val gaps = KnownGapRule()

    @Test
    @KnownGap("SELFTEST-01", "planted by verify-selftest")
    fun `a gap that quietly closed`() {
        assertEquals(4, 2 + 2)
    }
}
EOF
}

plant_render_alloc() {
    mkdir -p "$scratch/engine/src/main/cpp/render"
    cat > "$scratch/engine/src/main/cpp/render/planted.cpp" <<'EOF'
// SPDX-License-Identifier: GPL-3.0-or-later
float* planted() { return new float[64]; }
EOF
}

plant_threshold() {
    mkdir -p "$scratch/core/src/main/kotlin/ticktock/core"
    cat > "$scratch/core/src/main/kotlin/ticktock/core/Planted.kt" <<'EOF'
// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core

const val PLANTED_CLOSE_CENTS = 12.0
EOF
}

plant_denylisted() {
    # The first plain entry (no regex metacharacters) is a literal string that
    # matches itself. It is written into the scratch tree only.
    local entry
    entry=$(grep -v -e '^#' -e '^[[:space:]]*$' "$denylist" | grep -v '[][\\.*+?^$|(){}]' | head -1)
    [ -n "$entry" ] || { echo "verify-selftest: the denylist has no literal entry to plant" >&2; exit 2; }
    printf '\nPlanted note: %s\n' "$entry" >> "$scratch/README.md"
}

plant_wrong_author() {
    printf '\nPlanted line for the author check.\n' >> "$scratch/README.md"
    git -C "$scratch" -c user.name='Somebody Else' -c user.email='somebody@example.org' \
        commit -q -a -m 'docs: planted commit by another author' --no-verify
}

echo "verify-selftest: base ${base:0:12}, $(git rev-parse --short HEAD) plus uncommitted changes"
run_case control GREEN "VERDICT: GREEN" plant_nothing
run_case missing-tier-output RED "missing tier output" plant_missing_output
run_case zero-tests RED "zero tests found" plant_zero_tests
run_case passing-known-gap RED "SELFTEST-01 (a gap that quietly closed) passed" plant_passing_gap
run_case render-allocation RED "allocation, locking or logging on the render path" plant_render_alloc
run_case threshold-outside-band RED "a cents threshold outside" plant_threshold
run_case denylisted-string RED "denylist rule" plant_denylisted
run_case wrong-commit-author RED "rule commit-identity matched" plant_wrong_author

echo
printf '%s\n' "${results[@]}"
if [ $failed = 1 ]; then
    echo "verify-selftest: FAIL: a planted failure did not turn the gate red for its reason" >&2
    exit 1
fi
echo "verify-selftest: every planted failure turned the gate red for its reason"
