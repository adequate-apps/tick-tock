#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Identity scan: refuses text that would tie this public repository to a
# machine, an account, an assistant or a private project.
#
# Two rule sources:
#   1. tools/identity-rules.txt, public patterns (home paths, tailnet names,
#      attribution trailers, assistant names, review-role names, worm IOCs).
#   2. A private denylist outside the repository, one extended regex per line,
#      read from $TICKTOCK_DENYLIST (default ~/.config/ticktock/denylist). In CI
#      it is written from a secret to a temporary file. Its lines are never
#      printed: a denylist hit reports only "denylist rule N matched in LOC".
#
# Modes (exactly one):
#   --staged                 pre-commit: added lines and names of the staged diff
#   --range BASE..HEAD       pre-push: added lines, author, committer and message
#                            of every commit in the range
#   --pre-push               reads the pre-push hook's stdin and scans each
#                            pushed range plus the branch name
#   --history [REV]          every commit reachable from REV (default HEAD)
#   --tree                   every tracked and untracked-but-not-ignored file
#   --text FILE              a plain text file (CI uses it for the PR title and body)
#   --branch NAME            a branch name
#
# Options:
#   --allow-missing-denylist  warn instead of fail when the denylist is absent
#
# Exit status: 0 clean, 1 a rule matched, 2 usage or setup error. The scan
# fails closed: a tool error while matching is exit 2, never "clean".

set -euo pipefail

# POSIX awk only (GNU or mawk both serve), so the CI image needs nothing
# beyond git, grep, sed and coreutils.
for tool in git grep sed cut awk sort mktemp; do
    command -v "$tool" > /dev/null 2>&1 || { echo "identity-scan: $tool is not installed" >&2; exit 2; }
done

EXPECTED_IDENTITY='knelsonb <222288362+knelsonb@users.noreply.github.com>'

self_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
rules_file="$self_dir/identity-rules.txt"
rules_rel="tools/identity-rules.txt"
denylist="${TICKTOCK_DENYLIST:-$HOME/.config/ticktock/denylist}"

mode=""
arg=""
allow_missing_denylist=0

usage() {
    sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --staged|--pre-push|--tree) mode="${1#--}" ;;
        --range|--text|--branch) mode="${1#--}"; arg="${2:-}"; [ -n "$arg" ] || usage; shift ;;
        --history)
            mode="history"; arg="HEAD"
            case "${2:-}" in ''|--*) ;; *) arg="$2"; shift ;; esac
            ;;
        --allow-missing-denylist) allow_missing_denylist=1 ;;
        -h|--help) usage ;;
        *) echo "identity-scan: unknown argument '$1'" >&2; usage ;;
    esac
    shift
done
[ -n "$mode" ] || usage

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
corpus="$work/corpus"      # location <TAB> text
identities="$work/identities"
: > "$corpus"
: > "$identities"

# --- corpus builders --------------------------------------------------------

# Added lines of a unified diff, located as FILE:LINE. Removed lines are
# ignored on purpose: deleting a leak is the fix, never a new leak.
added_lines_of_diff() {
    awk '
        /^\+\+\+ / { file = substr($0, 5); sub(/^b\//, "", file); next }
        /^--- /    { next }
        /^@@ /     { match($0, /\+[0-9]+/); line = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
        /^\+/      { printf "%s:%d\t%s\n", file, line, substr($0, 2); line++; next }
        /^ /       { line++ }
    '
}

add_file_names() {
    # The path itself is a line of text: a private name in a file name leaks too.
    while IFS= read -r path; do
        printf '%s (name)\t%s\n' "$path" "$path"
    done
}

add_file_contents() {
    while IFS= read -r path; do
        [ -f "$path" ] || continue
        # grep -I drops binaries; the empty pattern matches every text line.
        grep -I -n -H -e '' -- "$path" 2>/dev/null | sed 's/^\([^:]*:[0-9]*\):/\1\t/' || true
    done
}

add_commit() {
    local sha="$1"
    local author committer
    author=$(git log -1 --format='%an <%ae>' "$sha")
    committer=$(git log -1 --format='%cn <%ce>' "$sha")
    printf 'commit %s (author)\t%s\n' "$sha" "$author" >> "$corpus"
    printf 'commit %s (committer)\t%s\n' "$sha" "$committer" >> "$corpus"
    printf '%s\t%s\t%s\n' "$sha" "$author" "$committer" >> "$identities"
    git log -1 --format='%B' "$sha" | awk -v sha="$sha" '{ printf "commit %s (message line %d)\t%s\n", sha, NR, $0 }' >> "$corpus"
    git show --no-color --unified=0 --format= --diff-filter=AMCR "$sha" | added_lines_of_diff \
        | sed "s/^/commit ${sha:0:12} /" >> "$corpus"
}

add_range() {
    local range="$1"
    local sha
    git rev-list --reverse "$range" > "$work/shas" || { echo "identity-scan: git rev-list failed for '$range'" >&2; exit 2; }
    while IFS= read -r sha; do
        [ -n "$sha" ] && add_commit "$sha"
    done < "$work/shas"
}

case "$mode" in
    staged)
        git diff --cached --no-color --unified=0 --diff-filter=AMCR | added_lines_of_diff >> "$corpus"
        git diff --cached --name-only --diff-filter=AMCR | add_file_names >> "$corpus"
        ;;
    range)
        add_range "$arg"
        ;;
    history)
        add_range "$arg"
        ;;
    pre-push)
        # Each stdin line: <local ref> <local sha> <remote ref> <remote sha>.
        zero=0000000000000000000000000000000000000000
        # shellcheck disable=SC2034  # remote_ref is part of the hook protocol, unused here
        while read -r local_ref local_sha remote_ref remote_sha; do
            [ "$local_sha" = "$zero" ] && continue   # a deletion pushes nothing
            printf 'branch %s\t%s\n' "$local_ref" "${local_ref#refs/heads/}" >> "$corpus"
            if [ "$remote_sha" = "$zero" ]; then
                # New remote branch: every commit not already on some remote.
                git rev-list --reverse "$local_sha" --not --remotes > "$work/shas" || { echo "identity-scan: git rev-list failed" >&2; exit 2; }
                while IFS= read -r sha; do add_commit "$sha"; done < "$work/shas"
            else
                add_range "$remote_sha..$local_sha"
            fi
        done
        ;;
    tree)
        { git ls-files; git ls-files --others --exclude-standard; } | sort -u > "$work/files"
        add_file_names < "$work/files" >> "$corpus"
        add_file_contents < "$work/files" >> "$corpus"
        ;;
    text)
        [ -f "$arg" ] || { echo "identity-scan: no such file '$arg'" >&2; exit 2; }
        awk -v f="$arg" '{ printf "%s:%d\t%s\n", f, NR, $0 }' "$arg" >> "$corpus"
        ;;
    branch)
        printf 'branch (name)\t%s\n' "$arg" >> "$corpus"
        ;;
esac

# --- rule application ---------------------------------------------------------

# Rules match the text alone, so that anchors such as ^ apply to the scanned
# line. The location column is kept beside it, line for line.
cut -f1 "$corpus" > "$work/loc"
cut -f2- "$corpus" > "$work/text"

hits=0

report_builtin() {
    # $1 rule name, $2 matched corpus line (location TAB text). Text is
    # truncated: enough to recognise, never a full dump of a long line.
    local name="$1" line="$2" loc text
    loc=${line%%$'\t'*}
    text=${line#*$'\t'}
    printf 'rule %s matched in %s: %.120s\n' "$name" "$loc" "$text"
}

matches() {
    # $1 regex, $2 regex on the location to skip (empty: skip nothing). Writes
    # the matching corpus lines to $work/matches. Any failure other than
    # "no match" is a setup error: a loop that reads nothing because a tool
    # died would otherwise report a leaking tree as clean.
    local regex="$1" skip="$2" status=0
    grep -n -i -E -e "$regex" "$work/text" > "$work/grep" || status=$?
    [ "$status" -le 1 ] || { echo "identity-scan: grep failed (status $status) on rule '$regex'" >&2; exit 2; }
    cut -d: -f1 "$work/grep" > "$work/want" || { echo "identity-scan: cut failed" >&2; exit 2; }
    awk -v skip="$skip" 'NR == FNR { want[$1] = 1; next }
                         (FNR in want) && (skip == "" || $0 !~ skip)' "$work/want" "$corpus" > "$work/matches" \
        || { echo "identity-scan: awk failed while applying a rule" >&2; exit 2; }
}

# Built-in rules skip the rules file itself, whose every line is a pattern.
skip_rules_file="^(commit [0-9a-f]+ )?${rules_rel}[: ]"

while IFS= read -r rule; do
    case "$rule" in ''|'#'*) continue ;; esac
    name=${rule%%[[:space:]]*}
    regex=${rule#"$name"}
    regex=${regex#"${regex%%[![:space:]]*}"}
    [ -n "$regex" ] || continue
    matches "$regex" "$skip_rules_file"
    while IFS= read -r match; do
        [ -n "$match" ] || continue
        report_builtin "$name" "$match"
        hits=$((hits + 1))
    done < "$work/matches"
done < "$rules_file"

if [ -f "$denylist" ]; then
    n=0
    while IFS= read -r regex || [ -n "$regex" ]; do
        case "$regex" in ''|'#'*) continue ;; esac
        n=$((n + 1))
        matches "$regex" ""
        while IFS= read -r match; do
            [ -n "$match" ] || continue
            printf 'denylist rule %d matched in %s\n' "$n" "${match%%$'\t'*}"
            hits=$((hits + 1))
        done < "$work/matches"
    done < "$denylist"
    [ "$n" -gt 0 ] || { echo "identity-scan: the denylist at $denylist has no rules" >&2; exit 2; }
elif [ "$allow_missing_denylist" = 1 ]; then
    echo "identity-scan: warning: no denylist at $denylist; only the built-in rules ran" >&2
else
    echo "identity-scan: no denylist at $denylist (set TICKTOCK_DENYLIST, or pass --allow-missing-denylist)" >&2
    exit 2
fi

# Commit identity: every commit in a range or history carries the owner's
# noreply identity as both author and committer.
if [ -s "$identities" ]; then
    while IFS=$'\t' read -r sha author committer; do
        if [ "$author" != "$EXPECTED_IDENTITY" ]; then
            printf 'rule commit-identity matched in commit %s (author): expected %s\n' "$sha" "$EXPECTED_IDENTITY"
            hits=$((hits + 1))
        fi
        if [ "$committer" != "$EXPECTED_IDENTITY" ]; then
            printf 'rule commit-identity matched in commit %s (committer): expected %s\n' "$sha" "$EXPECTED_IDENTITY"
            hits=$((hits + 1))
        fi
    done < "$identities"
fi

lines=$(wc -l < "$corpus")
if [ "$hits" -gt 0 ]; then
    echo "identity-scan ($mode): $hits hit(s) in $lines scanned line(s)" >&2
    exit 1
fi
echo "identity-scan ($mode): clean, $lines line(s) scanned"
