#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the pre-commit and pre-push identity scans and sets the owner's
# noreply identity. Run it once per clone; tools/worktree.sh runs it for every
# new worktree. The hooks directory is shared by all worktrees of a clone, so
# the installed shims locate the scanner through the worktree they run in.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
hooks_dir=$(git rev-parse --git-path hooks)
mkdir -p "$hooks_dir"

for hook in pre-commit pre-push; do
    install -m 0755 "$root/tools/hooks/$hook" "$hooks_dir/$hook"
    echo "installed $hooks_dir/$hook"
done

# Commits carry one identity only; every scan checks for it.
git config user.name 'knelsonb'
git config user.email '222288362+knelsonb@users.noreply.github.com'
echo "identity set to $(git config user.name) <$(git config user.email)>"
