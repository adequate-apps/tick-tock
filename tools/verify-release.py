#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate the exact deliverable pair, their signatures, identity and native code."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APP_ID = 'com.adequateapps.metronome'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise ValueError(f'{Path(args[0]).name} rejected release artifact\n{result.stdout}{result.stderr}')
    return result.stdout


def verify(directory, version, version_code, app_certificate, upload_certificate, manifest):
    stem = 'adequate-metronome-v' + version
    names = [stem + '-universal.apk', stem + '.aab']
    if {p.name for p in directory.iterdir()} != set(names + ['SHA256SUMS']):
        raise ValueError('release directory must contain exactly APK, AAB and SHA256SUMS')
    expected_sums = ''.join(hashlib.sha256((directory / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in names)
    if (directory / 'SHA256SUMS').read_text() != expected_sums:
        raise ValueError('release checksums differ')
    apk, bundle = [directory / name for name in names]
    sdk = Path(os.environ['ANDROID_HOME'])
    build_tools = sdk / 'build-tools' / '36.0.0'
    signing = run(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\w+)$', signing, re.M)
    if certs != [app_certificate.lower()]:
        raise ValueError('APK certificate differs')
    run(Path(os.environ['JAVA_HOME']) / 'bin/java', ROOT / 'tools/VerifyBundle.java', bundle, upload_certificate)
    run(build_tools / 'zipalign', '-c', '-P', '16', '4', apk)
    badging = run(build_tools / 'aapt2', 'dump', 'badging', apk)
    identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not identity or identity.groups() != (APP_ID, version_code, version) or 'application-debuggable' in badging:
        raise ValueError('APK identity, version or debuggable flag differs')
    allowlist = {line.strip().replace('<applicationId>', APP_ID) for line in (ROOT / 'tools/manifest-allowlist-2.0.txt').read_text().splitlines() if line.strip() and not line.startswith('#')}
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    if permissions != allowlist:
        raise ValueError('APK permissions differ')
    document = ET.parse(manifest).getroot()
    application = document.find('application')
    if (document.get('package'), document.get(ANDROID + 'versionCode'), document.get(ANDROID + 'versionName')) != (APP_ID, version_code, version):
        raise ValueError('bundle identity or version differs')
    if application is None or application.get(ANDROID + 'debuggable', 'false') != 'false':
        raise ValueError('bundle application is absent or debuggable')
    if {node.get(ANDROID + 'name') for node in document if node.tag.startswith('uses-permission')} != allowlist:
        raise ValueError('bundle permissions differ')
    for artifact in [apk, bundle]:
        run(sys.executable, ROOT / 'tools/check-abis.py', artifact)
        with tempfile.TemporaryDirectory(prefix='release-native-') as scratch:
            with zipfile.ZipFile(artifact) as archive:
                if len(archive.namelist()) != len(set(archive.namelist())):
                    raise ValueError('duplicate archive entries')
                for entry in archive.infolist():
                    if re.fullmatch(r'(base/)?lib/(arm64-v8a|x86_64)/[^/]+\.so', entry.filename):
                        target = Path(scratch) / Path(entry.filename).parent.name / Path(entry.filename).name
                        target.parent.mkdir(exist_ok=True)
                        target.write_bytes(archive.read(entry))
            run(ROOT / 'tools/check-alignment.sh', scratch)
    print('release artifacts: signatures, identity, permissions, ABIs, 16 KB alignment and checksums verified')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 7:
            raise ValueError('usage: verify-release.py DIRECTORY VERSION CODE APP_CERT UPLOAD_CERT BUNDLE_MANIFEST')
        verify(Path(sys.argv[1]), *sys.argv[2:])
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as failure:
        sys.exit(str(failure))
