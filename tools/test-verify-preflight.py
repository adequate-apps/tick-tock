#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""The mandatory native suite cannot disappear behind a green inner gate."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class NativePreflightTest(unittest.TestCase):
    def test_missing_native_suite_refuses_an_otherwise_passing_inner_gate(self):
        root = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory(prefix='native-preflight-') as directory:
            fixture = Path(directory)
            (fixture / 'tools').mkdir()
            shutil.copy2(root / 'tools/verify.sh', fixture / 'tools/verify.sh')
            identity = fixture / 'tools/identity-scan.sh'
            identity.write_text('#!/bin/sh\nexit 0\n')
            identity.chmod(0o755)
            band = fixture / 'core/src/commonMain/kotlin/ticktock/core/music/CentsBand.kt'
            band.parent.mkdir(parents=True)
            shutil.copy2(root / band.relative_to(fixture), band)
            wrapper = fixture / 'gradlew'
            wrapper.write_text('''#!/usr/bin/env python3
from pathlib import Path
report = Path('core/build/test-results/jvmTest/TEST-control.xml')
report.parent.mkdir(parents=True, exist_ok=True)
report.write_text('<testsuite tests="1" skipped="0" failures="0" errors="0"><testcase name="control"/></testsuite>')
print('BUILD SUCCESSFUL')
''')
            wrapper.chmod(0o755)
            env = dict(os.environ)
            env.pop('TICKTOCK_RANGE_INPUTS', None)
            result = subprocess.run(['bash', 'tools/verify.sh', '--skip-h'], cwd=fixture,
                                    env=env, capture_output=True, text=True)
            output = result.stdout + result.stderr
            self.assertNotEqual(0, result.returncode, output)
            self.assertIn('mandatory native test suite is missing', output)


if __name__ == '__main__':
    unittest.main()
