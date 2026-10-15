#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Secret staging fails closed and preserves Java properties special characters."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('stage-signing.py')


class SigningStageTest(unittest.TestCase):
    def invoke(self, directory, changes=None):
        environment = dict(os.environ)
        for name in list(environment):
            if name.startswith('RELEASE_'):
                del environment[name]
        environment.update(RELEASE_STORE_BASE64=base64.b64encode(b'temporary-keystore-fixture').decode(),
                           RELEASE_STORE_PASSWORD=' leading:equals=\\unicode\u03bb\nline\rnext\tend',
                           RELEASE_KEY_PASSWORD='private-key-fixture', RELEASE_KEY_ALIAS='fixture',
                           RELEASE_CERTIFICATE_SHA256='a' * 64)
        environment.update(changes or {})
        return subprocess.run(['python3', str(SCRIPT), str(directory)], env=environment, capture_output=True, text=True)

    def test_missing_or_malformed_secrets_leave_no_configuration(self):
        for changes in [{'RELEASE_KEY_ALIAS': ''}, {'RELEASE_STORE_BASE64': 'invalid!'},
                        {'RELEASE_CERTIFICATE_SHA256': 'bad'}, {'RELEASE_UPLOAD_KEY_ALIAS': 'partial'}]:
            with self.subTest(changes=tuple(changes)), tempfile.TemporaryDirectory() as scratch:
                directory = Path(scratch) / 'signing'
                result = self.invoke(directory, changes)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Signing secrets are missing or invalid', result.stderr)
                self.assertFalse((directory / 'signing.properties').exists())

    def test_private_files_and_java_properties_round_trip(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch) / 'signing'
            result = self.invoke(directory)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn('private-key-fixture', result.stdout + result.stderr)
            self.assertEqual(0o700, directory.stat().st_mode & 0o777)
            for path in directory.iterdir():
                self.assertEqual(0o600, path.stat().st_mode & 0o777)
            java = Path(scratch) / 'ReadProperties.java'
            java.write_text('''import java.nio.file.*; import java.util.*;
class ReadProperties { public static void main(String[] args) throws Exception {
  var p = new Properties(); try (var r = Files.newBufferedReader(Path.of(args[0]))) { p.load(r); }
  if (!p.getProperty("storePassword").equals(System.getenv("EXPECTED_PASSWORD"))) throw new AssertionError("password changed");
  if (!Files.readString(Path.of(p.getProperty("storeFile"))).equals("temporary-keystore-fixture")) throw new AssertionError("key changed");
}}''')
            environment = dict(os.environ, EXPECTED_PASSWORD=' leading:equals=\\unicode\u03bb\nline\rnext\tend')
            checked = subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java'), str(java),
                                      str(directory / 'signing.properties')], env=environment, capture_output=True, text=True)
            self.assertEqual(0, checked.returncode, checked.stderr)


if __name__ == '__main__':
    unittest.main()
