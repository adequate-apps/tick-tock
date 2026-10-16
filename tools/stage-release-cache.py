#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage the two approved AARs absent from CI's debug dependency graph."""
import hashlib
import os
from pathlib import Path
import tempfile
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ('activity', 'activity-ktx')
VERSION = '1.7.0'
NS = {'v': 'https://schema.gradle.org/dependency-verification'}


def approved_hashes(metadata):
    tree = ET.parse(metadata)
    hashes = {}
    for artifact in ARTIFACTS:
        node = tree.find(
            f".//v:component[@group='androidx.activity'][@name='{artifact}'][@version='{VERSION}']/"
            f"v:artifact[@name='{artifact}-{VERSION}.aar']/v:sha256", NS)
        digest = node.get('value', '') if node is not None else ''
        if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            raise ValueError(f'{artifact} needs an approved SHA-256 in verification metadata')
        hashes[artifact] = digest
    return hashes


def stage(gradle_home, artifact, expected):
    name = f'{artifact}-{VERSION}.aar'
    directory = gradle_home / 'caches/modules-2/files-2.1/androidx.activity' / artifact / VERSION
    existing = sorted(directory.glob(f'*/{name}'))
    for path in existing:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'{name} cached checksum differs')
    if existing:
        return existing[0]
    url = f'https://dl.google.com/dl/android/maven2/androidx/activity/{artifact}/{VERSION}/{name}'
    with urlopen(url, timeout=60) as response:
        payload = response.read()
    if hashlib.sha256(payload).hexdigest() != expected:
        raise ValueError(f'{name} downloaded checksum differs')
    # Gradle's existing artifact store uses SHA-1 for paths; trust uses SHA-256 above.
    destination = directory / hashlib.sha1(payload).hexdigest() / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(payload)
    temporary.replace(destination)
    return destination


if __name__ == '__main__':
    home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    for artifact, expected in approved_hashes(ROOT / 'gradle/verification-metadata.xml').items():
        stage(home, artifact, expected)
        print(f'PASS: approved {artifact} {VERSION} cached for offline release tests')
