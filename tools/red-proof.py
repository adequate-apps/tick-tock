#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Require test evidence from a changed test source for a red-on-base run."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def evidence(root, changed, log):
    classes = set()
    native = set()
    sources = []
    for relative in changed:
        path = root / relative
        if not path.is_file() or path.suffix not in ('.kt', '.java', '.cpp', '.cc'):
            continue
        source = path.read_text()
        sources.append(relative)
        if path.suffix in ('.kt', '.java'):
            package = re.search(r'^\s*package\s+([\w.]+)', source, re.M)
            prefix = package.group(1) + '.' if package else ''
            classes.update(prefix + name for name in re.findall(r'\bclass\s+(\w+)', source))
        else:
            native.update(f'{suite}.{test}' for suite, test in
                          re.findall(r'\bTEST(?:_F|_P)?\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)', source))

    infrastructure = re.compile(
        r'NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|'
        r'ExceptionInInitializerError|InitializationError|OutOfMemoryError|'
        r'TestSuiteExecutionException')
    reports = list(root.glob('*/build/test-results/*/TEST-*.xml'))
    reports += list(root.glob('host-tests/build/results/*.xml'))
    for report in sorted(reports):
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in suite.iter('testcase'):
            failures = case.findall('failure')
            if not failures or case.find('error') is not None:
                continue
            name = case.get('name', '')
            classname = case.get('classname', '')
            details = ' '.join(str(f.attrib) + (f.text or '') for f in failures)
            if infrastructure.search(details) or name in ('initializationError', 'executionError'):
                continue
            if any(classname == item or classname.startswith(item + '$') for item in classes):
                return f'failed changed test {classname}.{name}'
            output = case.findtext('system-out', '')
            if any(re.search(r'(?:^|\.)' + re.escape(item) + r'(?:/|$)', name) for item in native):
                if re.search(r'\.(?:cpp|cc):\d+: Failure', output):
                    return f'failed changed native test {name}'

    # New tests may name an API that the base does not yet implement. Require
    # a compiler diagnostic located in that changed source, with an API error.
    missing_api = re.compile(r'Unresolved reference|cannot find symbol|no member named|'
                             r'was not declared in this scope|use of undeclared identifier')
    for line in log.splitlines():
        if not missing_api.search(line):
            continue
        for relative in sources:
            if re.search(re.escape(str(root / relative)) + r':\d+(?::\d+)?', line):
                return f'new API absent at changed test {relative}: {line.strip()}'
    return None


def main():
    root = Path(sys.argv[1]).resolve()
    changed = Path(sys.argv[2]).read_text().splitlines()
    result = evidence(root, changed, Path(sys.argv[3]).read_text(errors='replace'))
    if result is None:
        print('prove-red: REFUSED: no failed changed-test assertion or missing test API; inspect the build failure')
        return 1
    print('prove-red: evidence: ' + result)
    return 0


if __name__ == '__main__':
    sys.exit(main())
