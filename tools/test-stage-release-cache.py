#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Cache staging must verify bytes before an offline release can consume them."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('stage_cache', ROOT / 'tools/stage-release-cache.py')
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class ReleaseCacheTest(unittest.TestCase):
    def test_verified_bytes_use_gradle_content_address(self):
        payload = b'approved fixture artifact'
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(cache, 'urlopen', return_value=io.BytesIO(payload)) as fetch:
                path = cache.stage(Path(directory), 'activity', hashlib.sha256(payload).hexdigest())
            self.assertEqual(path.read_bytes(), payload)
            self.assertEqual(path.parent.name, hashlib.sha1(payload).hexdigest())
            self.assertEqual(path.name, 'activity-1.7.0.aar')
            self.assertEqual(fetch.call_args.args[0],
                             'https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.7.0/activity-1.7.0.aar')
            with patch.object(cache, 'urlopen', side_effect=AssertionError('cached bytes fetched again')):
                self.assertEqual(cache.stage(Path(directory), 'activity', hashlib.sha256(payload).hexdigest()), path)

    def test_changed_download_leaves_no_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(cache, 'urlopen', return_value=io.BytesIO(b'changed')):
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    cache.stage(Path(directory), 'activity', hashlib.sha256(b'approved').hexdigest())
            self.assertEqual(list(Path(directory).rglob('*.aar')), [])

    def test_corrupt_cache_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'caches/modules-2/files-2.1/androidx.activity/activity/1.7.0/bad/activity-1.7.0.aar'
            path.parent.mkdir(parents=True)
            path.write_bytes(b'changed')
            with patch.object(cache, 'urlopen', side_effect=AssertionError('corruption must be explicit')):
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    cache.stage(Path(directory), 'activity', hashlib.sha256(b'approved').hexdigest())

    def test_existing_verification_metadata_is_required(self):
        hashes = cache.approved_hashes(ROOT / 'gradle/verification-metadata.xml')
        self.assertEqual(set(hashes), {'activity', 'activity-ktx'})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'metadata.xml'
            path.write_text('<verification-metadata/>')
            with self.assertRaisesRegex(ValueError, 'approved SHA-256'):
                cache.approved_hashes(path)


if __name__ == '__main__':
    unittest.main()
