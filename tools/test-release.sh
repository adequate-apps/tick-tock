#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Temporary keys prove the release path without production credentials.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
mkdir -p build/release-tests
fixture=$(mktemp -d "$root/build/release-tests/fixture.XXXXXX")
export TT_TEST_PASSWORD='temporary release fixture only'
export TT_TEST_DIRECTORY="$fixture"
for alias in app upload; do
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$fixture/$alias.p12" -storetype PKCS12 \
        -storepass:env TT_TEST_PASSWORD -keypass:env TT_TEST_PASSWORD -alias "$alias" \
        -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Temporary Release Test' > "$fixture/$alias.log" 2>&1
    "$JAVA_HOME/bin/keytool" -exportcert -keystore "$fixture/$alias.p12" \
        -storepass:env TT_TEST_PASSWORD -alias "$alias" -file "$fixture/$alias.der" >> "$fixture/$alias.log" 2>&1
done
python3 - <<'PY'
import hashlib, os, pathlib
directory = pathlib.Path(os.environ['TT_TEST_DIRECTORY'])
values = {}
for prefix, alias in [('', 'app'), ('upload', 'upload')]:
    for field, value in {
        'storeFile': str(directory / (alias + '.p12')),
        'storePassword': os.environ['TT_TEST_PASSWORD'],
        'keyPassword': os.environ['TT_TEST_PASSWORD'], 'keyAlias': alias,
        'certificateSha256': hashlib.sha256((directory / (alias + '.der')).read_bytes()).hexdigest(),
    }.items():
        values[prefix + field[0].upper() + field[1:] if prefix else field] = value
def write(name, data):
    path = directory / name
    path.touch(mode=0o600)
    path.write_text(''.join(k + '=' + v.replace('\\', '\\\\').replace(' ', '\\ ') + '\n' for k, v in data.items()))
write('valid.properties', values)
for name, field, value in [('partial', 'keyAlias', ''), ('password', 'storePassword', 'incorrect'),
                            ('alias', 'keyAlias', 'missing'), ('certificate', 'certificateSha256', '00'),
                            ('upload', 'uploadCertificateSha256', '00')]:
    write(name + '.properties', dict(values, **{field: value}))
PY
for case in partial password alias certificate upload; do
    if ./gradlew --offline --no-configuration-cache "-Pticktock.signingProperties=$fixture/$case.properties" \
        :app:checkReleaseSigning > "$fixture/$case.log" 2>&1; then
        echo "FAIL: release signing accepted $case fixture" >&2; exit 1
    fi
    grep -Eq 'Release signing (is required|material is invalid|certificate differs)' "$fixture/$case.log" \
        || { echo "FAIL: unrelated failure in $case" >&2; exit 1; }
    echo "PASS: rejected $case fixture"
done
./gradlew --offline --no-configuration-cache "-Pticktock.signingProperties=$fixture/valid.properties" \
    :app:releaseArtifacts > "$fixture/build.log" 2>&1 \
    || { tail -45 "$fixture/build.log"; exit 1; }
echo "PASS: signed release artifacts validated with separate temporary app and upload keys"
printf 'Temporary fixture: %s\n' "$fixture"
