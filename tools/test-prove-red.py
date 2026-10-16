#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise the red-proof entry point with deterministic build outcomes."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
WRAPPER = r'''#!/usr/bin/env python3
from pathlib import Path
import os
import sys
mode = os.environ['RED_PROOF_FIXTURE']
if mode == 'passing':
    sys.exit(0)
if mode == 'dependency':
    print('Could not resolve a required dependency in offline mode')
elif mode in ('test-compile', 'production-compile'):
    source = 'core/src/jvmTest/kotlin/sample/ExampleTest.kt' if mode == 'test-compile' else 'core/src/commonMain/kotlin/sample/Feature.kt'
    print(f"e: file://{Path.cwd() / source}:4:9 Unresolved reference 'newBehavior'.")
else:
    name = 'sample.OtherTest' if mode == 'unrelated' else 'sample.ExampleTest'
    kind = 'java.lang.NoClassDefFoundError' if mode == 'classpath' else 'java.lang.AssertionError'
    report = Path('core/build/test-results/jvmTest/TEST-example.xml')
    report.parent.mkdir(parents=True)
    report.write_text(f'<testsuite tests="1" failures="1"><testcase classname="{name}" name="new behavior"><failure type="{kind}" message="expected behavior"/></testcase></testsuite>')
sys.exit(1)
'''


class RedProofTest(unittest.TestCase):
    def run_proof(self, mode):
        with tempfile.TemporaryDirectory(prefix='red-proof-test-') as directory:
            checkout = Path(directory)

            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True,
                                               stderr=subprocess.DEVNULL).strip()

            git('init', '-q')
            identity = subprocess.check_output(['git', '-C', str(ROOT), 'log', '-1', '--format=%an%n%ae'], text=True).splitlines()
            git('config', 'user.name', identity[0])
            git('config', 'user.email', identity[1])
            (checkout / 'tools').mkdir()
            shutil.copy2(ROOT / 'tools/prove-red.sh', checkout / 'tools/prove-red.sh')
            helper = ROOT / 'tools/red-proof.py'
            if helper.exists():
                shutil.copy2(helper, checkout / 'tools/red-proof.py')
            wrapper = checkout / 'gradlew'
            wrapper.write_text(WRAPPER)
            wrapper.chmod(0o755)
            test = checkout / 'core/src/jvmTest/kotlin/sample/ExampleTest.kt'
            test.parent.mkdir(parents=True)
            test.write_text('package sample\nclass ExampleTest {}\n')
            git('add', '.')
            git('commit', '-qm', 'test: establish red proof fixture')
            base = git('rev-parse', 'HEAD')
            test.write_text('package sample\nclass ExampleTest { fun newBehavior() = Unit }\n')
            git('add', '.')
            git('commit', '-qm', 'test: change the fixture assertion')
            env = {**os.environ, 'RED_PROOF_FIXTURE': mode}
            result = subprocess.run(['bash', 'tools/prove-red.sh', '--base', base], cwd=checkout,
                                    env=env, capture_output=True, text=True)
            return result.returncode, result.stdout + result.stderr

    def test_accepts_failure_in_changed_test(self):
        code, output = self.run_proof('assertion')
        self.assertEqual(0, code, output)

    def test_accepts_missing_new_api_in_changed_test(self):
        code, output = self.run_proof('test-compile')
        self.assertEqual(0, code, output)

    def test_rejects_infrastructure_unrelated_and_passing_results(self):
        for mode in ('dependency', 'classpath', 'unrelated', 'production-compile', 'passing'):
            with self.subTest(mode=mode):
                code, output = self.run_proof(mode)
                self.assertNotEqual(0, code, output)
                self.assertIn('REFUSED', output)


if __name__ == '__main__':
    unittest.main()
