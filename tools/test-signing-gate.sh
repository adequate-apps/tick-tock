#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Exercise both real release entry points without signing material.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
[ ! -e private/signing.properties ] || { echo 'signing-gate test needs a checkout without private signing properties' >&2; exit 2; }
logdir="$root/build/signing-gate"
mkdir -p "$logdir"
missing="$logdir/absent.properties"
[ ! -e "$missing" ] || { echo 'signing-gate fixture must be absent' >&2; exit 2; }
failed=0
for task in assembleRelease bundleRelease; do
    log="$logdir/$task.log"
    if ./gradlew --offline --no-configuration-cache "-Pticktock.signingProperties=$missing" ":app:$task" > "$log" 2>&1; then
        echo "FAIL: $task accepted absent signing credentials" >&2
        failed=1
    elif ! grep -q 'Release signing is required' "$log"; then
        echo "FAIL: $task failed before proving the signing guard" >&2
        tail -25 "$log" >&2
        failed=1
    else
        echo "PASS: $task refused absent signing credentials"
    fi
done
exit "$failed"
