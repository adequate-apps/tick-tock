#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Compares the permissions of a merged manifest with the release's allowlist,
# tools/manifest-allowlist-<major.minor>.txt (DST-02).
#
#   tools/check-manifest.sh [MERGED_MANIFEST] [ALLOWLIST]
#
# Default manifest: the debug variant's merged manifest under app/build. Run
# after :app:assembleDebug (or :app:processDebugManifest). Default allowlist:
# the file named by the major.minor of versionName in app/build.gradle.kts, so
# bumping the version to a release without an allowlist is itself a finding.
# The comparison is an exact set match in both directions; see the allowlist
# for why.
set -euo pipefail

self_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$self_dir/.." && pwd)
manifest="${1:-$root/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml}"

if [ $# -ge 2 ]; then
    allowlist="$2"
else
    release=$(sed -n 's/^[[:space:]]*versionName *= *"\([0-9]*\.[0-9]*\).*/\1/p' "$root/app/build.gradle.kts" | head -1)
    [ -n "$release" ] || { echo "check-manifest: no versionName of the form major.minor in app/build.gradle.kts" >&2; exit 2; }
    allowlist="$self_dir/manifest-allowlist-$release.txt"
fi

[ -f "$manifest" ] || { echo "check-manifest: no merged manifest at $manifest (run :app:assembleDebug first)" >&2; exit 2; }
[ -f "$allowlist" ] || { echo "check-manifest: no allowlist at $allowlist; every release has its own reviewed file" >&2; exit 2; }

package=$(grep -o -m1 'package="[^"]*"' "$manifest" | sed 's/package="//; s/"$//')
[ -n "$package" ] || { echo "check-manifest: no package attribute in $manifest" >&2; exit 2; }

actual=$(grep -o '<uses-permission[^>]*android:name="[^"]*"' "$manifest" \
    | sed 's/.*android:name="//; s/"$//' \
    | sed "s/^${package//./\\.}\\./<applicationId>./" \
    | sort -u)
expected=$(grep -v -e '^#' -e '^[[:space:]]*$' "$allowlist" | sort -u)

if [ "$actual" = "$expected" ]; then
    echo "check-manifest: permissions match ${allowlist#"$root"/} ($(printf '%s\n' "$actual" | wc -l) entries)"
    exit 0
fi

echo "check-manifest: the merged manifest differs from ${allowlist#"$root"/}" >&2
diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | sed 's/^</  allowlist only: /; s/^>/  manifest only:  /' | grep -v '^[0-9]' >&2 || true
exit 1
