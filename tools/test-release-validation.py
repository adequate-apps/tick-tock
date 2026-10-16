#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise artifact checks against a successfully built temporary-key release."""
import hashlib
import importlib.util
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
import zipfile

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('release_checks', root / 'tools/verify-release.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)
fixture = Path(sys.argv[1])
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text())[1]
code = re.search(r'versionCode = (\d+)', (root / 'app/build.gradle.kts').read_text())[1]
app_cert = hashlib.sha256((fixture / 'app.der').read_bytes()).hexdigest()
upload_cert = hashlib.sha256((fixture / 'upload.der').read_bytes()).hexdigest()
manifest = root / 'app/build/tmp/releaseArtifacts/bundle-manifest.xml'
signature = re.compile(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC)|SIG-[^/]+)', re.I)


def payload(path):
    with zipfile.ZipFile(path) as archive:
        return {entry.filename: hashlib.sha256(archive.read(entry)).hexdigest() for entry in archive.infolist()
                if not signature.fullmatch(entry.filename)}


bundle = root / 'build/release' / ('adequate-metronome-v' + version + '.aab')
if payload(bundle) != payload(root / 'app/build/outputs/bundle/release/app-release.aab'):
    sys.exit('FAIL: separate upload signature changed bundle payload')
checks.verify(root / 'build/release', version, code, app_cert, upload_cert, manifest)
print('PASS: upload signature preserved every payload entry')

for case in ['checksum', 'missing', 'extra', 'app-certificate', 'upload-certificate', 'apk-tamper', 'bundle-tamper', 'manifest']:
    with tempfile.TemporaryDirectory(prefix='release-validation-') as scratch:
        directory = Path(scratch) / 'release'
        shutil.copytree(root / 'build/release', directory)
        expected_app, expected_upload, checked_manifest = app_cert, upload_cert, manifest
        apk = next(directory.glob('*.apk'))
        aab = next(directory.glob('*.aab'))
        if case == 'checksum':
            (directory / 'SHA256SUMS').write_text('0' * 64 + '\n')
        elif case == 'missing':
            apk.rename(Path(scratch) / 'missing.apk')
        elif case == 'extra':
            (directory / 'extra.txt').write_text('unreviewed')
        elif case == 'app-certificate':
            expected_app = upload_cert
        elif case == 'upload-certificate':
            expected_upload = app_cert
        elif case in ['apk-tamper', 'bundle-tamper']:
            path = apk if case == 'apk-tamper' else aab
            if case == 'apk-tamper':
                # Keep the complete signing block so the signature verifier must
                # reject changed signed bytes, even with updated outer checksums.
                with zipfile.ZipFile(path) as source:
                    entry = source.getinfo('assets/THIRD_PARTY.md')
                contents = bytearray(path.read_bytes())
                name_size, extra_size = struct.unpack_from('<HH', contents, entry.header_offset + 26)
                contents[entry.header_offset + 30 + name_size + extra_size] ^= 1
                path.write_bytes(contents)
            else:
                replacement = Path(scratch) / 'tampered.zip'
                with zipfile.ZipFile(path) as source, zipfile.ZipFile(replacement, 'w') as output:
                    for entry in source.infolist():
                        contents = source.read(entry)
                        if entry.filename.endswith('assets/THIRD_PARTY.md'):
                            contents += b' altered'
                        output.writestr(entry, contents)
                replacement.replace(path)
            (directory / 'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in [apk, aab]))
        else:
            checked_manifest = Path(scratch) / 'manifest.xml'
            checked_manifest.write_text(manifest.read_text().replace('android.permission.RECORD_AUDIO', 'android.permission.INTERNET'))
        try:
            checks.verify(directory, version, code, expected_app, expected_upload, checked_manifest)
        except ValueError as failure:
            if case == 'apk-tamper' and 'apksigner rejected' not in str(failure):
                sys.exit('FAIL: changed signed APK failed before signature verification')
            print('PASS: rejected ' + case)
        else:
            sys.exit('FAIL: accepted ' + case)
