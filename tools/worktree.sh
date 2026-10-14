#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Creates a worktree for one task, sets the owner's noreply identity and
# installs the scan hooks, so that the first commit in it is already gated.
#
#   tools/worktree.sh PATH BRANCH [START]
#
# BRANCH is created from START (default: the current HEAD) and must follow the
# naming rule m<N>/<module>-<criterion>-<slug>, for example
# m2/engine-MET-06-reanchor. The branch name goes through the identity scan.
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: tools/worktree.sh PATH BRANCH [START]" >&2; exit 2; }
path="$1"
branch="$2"
start="${3:-HEAD}"

self_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Milestone prefix first; the rest is module, criterion and slug. Milestone-wide
# branches such as m0-tests are allowed as well.
if ! printf '%s' "$branch" | grep -q -E '^m[0-9]+([/-][A-Za-z0-9._-]+)?$'; then
    echo "worktree: branch '$branch' does not start with a milestone (m<N>/<module>-<CRITERION>-<slug>)" >&2
    exit 2
fi
"$self_dir/identity-scan.sh" --branch "$branch"

git worktree add -b "$branch" "$path" "$start"
(cd "$path" && tools/install-hooks.sh)
echo "worktree ready at $path on $branch"
