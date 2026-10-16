#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location('source_check', Path(__file__).with_name('release-source.py'))
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class ReleaseSourceTest(unittest.TestCase):
    def test_exact_version_section_only(self):
        self.assertEqual('Current changes.\n', source.release_notes('v2.0.0', '2.0.0',
                         '# Changelog\n\n## [2.0.0]\n\nCurrent changes.\n\n## [1.0.0]\nOld changes.\n'))

    def test_reject_version_mismatch_and_missing_notes(self):
        for tag, version, notes in [('v1.0.0', '2.0.0', '## [2.0.0]\nChanges.'),
                                    ('v2.0.0-extra', '2.0.0', '## [2.0.0]\nChanges.'),
                                    ('v2.0.0', '2.0.0', '## [1.0.0]\nChanges.'),
                                    ('v2.0.0', '2.0.0', '## [2.0.0]\n\n## [1.0.0]\nChanges.')]:
            with self.subTest(tag=tag, notes=notes), self.assertRaises(ValueError):
                source.release_notes(tag, version, notes)

    def test_actual_checkout_identity_and_dirty_source(self):
        root = Path(__file__).resolve().parent.parent
        identity = subprocess.check_output(['git', '-C', str(root), 'log', '-1', '--format=%an%n%ae'], text=True).splitlines()
        with tempfile.TemporaryDirectory(prefix='release-source-') as scratch:
            checkout = Path(scratch)
            def git(*args):
                return subprocess.check_output(['git', '-C', scratch, *args], text=True, stderr=subprocess.DEVNULL).strip()
            git('init', '-q')
            git('config', 'user.name', identity[0])
            git('config', 'user.email', identity[1])
            (checkout / 'app').mkdir()
            build = checkout / 'app/build.gradle.kts'
            build.write_text('versionName = "2.0.0"\n')
            (checkout / 'CHANGELOG.md').write_text('## [2.0.0]\nCurrent changes.\n')
            git('add', '.')
            git('commit', '-qm', 'test: release source fixture')
            revision = git('rev-parse', 'HEAD')
            self.assertEqual('Current changes.\n', source.check(checkout, 'v2.0.0', revision))
            with self.assertRaisesRegex(ValueError, 'revision differs'):
                source.check(checkout, 'v2.0.0', '0' * 40)
            build.write_text('versionName = "2.0.1"\n')
            with self.assertRaisesRegex(ValueError, 'must be clean'):
                source.check(checkout, 'v2.0.0', revision)
            git('restore', 'app/build.gradle.kts')
            (checkout / 'unexpected').write_text('untracked source')
            with self.assertRaisesRegex(ValueError, 'must be clean'):
                source.check(checkout, 'v2.0.0', revision)


if __name__ == '__main__':
    unittest.main()
