#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check a clean release source and extract its curated changelog section."""
from pathlib import Path
import re
import subprocess
import sys


def release_notes(tag, version, changelog):
    if not re.fullmatch(r'v\d+\.\d+\.\d+', tag) or tag != 'v' + version:
        raise ValueError('release tag and application version differ')
    match = re.search(r'^## \[' + re.escape(version) + r'\][^\n]*\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError('curated release notes are missing')
    return match[1].strip() + '\n'


def check(root, tag, revision):
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
    if git('rev-parse', 'HEAD') != revision:
        raise ValueError('release source revision differs')
    if git('status', '--porcelain', '--untracked-files=all'):
        raise ValueError('release source must be clean')
    version = re.search(r'^\s*versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text(), re.M)[1]
    return release_notes(tag, version, (root / 'CHANGELOG.md').read_text())


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3:
            raise ValueError('usage: release-source.py TAG REVISION')
        print(check(Path(__file__).resolve().parent.parent, *sys.argv[1:]), end='')
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        sys.exit(str(failure))
