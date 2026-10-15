#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == '__main__':
    unittest.main()
