#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Two clean clones at one commit must produce identical complete signed files.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[ $# -eq 2 ] || { echo 'usage: reproduce-release.sh COMMIT SIGNING_PROPERTIES' >&2; exit 2; }
commit=$(git -C "$root" rev-parse "$1^{commit}")
signing=$(realpath "$2")
[ -f "$signing" ] || { echo 'signing properties missing' >&2; exit 2; }
mkdir -p "$root/build/reproduction"
evidence=$(mktemp -d "$root/build/reproduction/run.XXXXXX")
printf '%s\n' "$commit" > "$evidence/COMMIT"
"$JAVA_HOME/bin/java" -version > "$evidence/toolchain.log" 2>&1
for name in first second-with-a-different-path; do
    checkout="$evidence/$name"
    git clone --quiet --no-local --no-checkout "$root" "$checkout"
    git -C "$checkout" checkout --quiet --detach "$commit"
    (
        cd "$checkout"
        tools/install-hooks.sh
        printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
        ./gradlew --offline --no-build-cache --no-configuration-cache \
            "-Pticktock.signingProperties=$signing" :app:releaseArtifacts
    ) > "$evidence/$name.log" 2>&1 || { tail -50 "$evidence/$name.log"; exit 1; }
    echo "PASS: clean release build $name"
done
diff -u "$evidence/first/build/release/SHA256SUMS" "$evidence/second-with-a-different-path/build/release/SHA256SUMS"
cmp "$evidence/first/build/release/"*.apk "$evidence/second-with-a-different-path/build/release/"*.apk
cmp "$evidence/first/build/release/"*.aab "$evidence/second-with-a-different-path/build/release/"*.aab
echo 'PASS: complete signed APK and AAB are byte-identical across both clean clones'
printf 'Evidence: %s\n' "$evidence"
