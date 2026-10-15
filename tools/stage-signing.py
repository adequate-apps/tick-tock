#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage approved CI secrets in a new private directory without logging values."""
import base64
import binascii
import os
from pathlib import Path
import re
import sys


def escape(value):
    return (value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
            .replace('\t', '\\t').replace('\f', '\\f').replace(' ', '\\ ')
            .replace('=', '\\=').replace(':', '\\:').replace('#', '\\#').replace('!', '\\!'))


def stage(directory):
    values, stores = {}, {}
    for prefix in ['', 'UPLOAD_']:
        names = ['STORE_BASE64', 'STORE_PASSWORD', 'KEY_PASSWORD', 'KEY_ALIAS', 'CERTIFICATE_SHA256']
        fields = {name: os.environ.get('RELEASE_' + prefix + name, '') for name in names}
        if prefix and not any(fields.values()):
            continue
        if not all(fields.values()) or not re.fullmatch('[0-9a-fA-F]{64}', fields['CERTIFICATE_SHA256']):
            raise ValueError()
        encoded = ''.join(fields['STORE_BASE64'].split())
        stores['upload.p12' if prefix else 'app.p12'] = base64.b64decode(encoded, validate=True)
        if not stores['upload.p12' if prefix else 'app.p12']:
            raise ValueError()
        mapping = {'storeFile': str(directory / ('upload.p12' if prefix else 'app.p12')),
                   'storePassword': fields['STORE_PASSWORD'], 'keyPassword': fields['KEY_PASSWORD'],
                   'keyAlias': fields['KEY_ALIAS'], 'certificateSha256': fields['CERTIFICATE_SHA256']}
        values.update({('upload' + key[0].upper() + key[1:] if prefix else key): value for key, value in mapping.items()})
    directory.mkdir(mode=0o700)
    for name, content in stores.items():
        with (directory / name).open('xb') as output:
            os.chmod(output.fileno(), 0o600)
            output.write(content)
    with (directory / 'signing.properties').open('x', encoding='utf-8') as output:
        os.chmod(output.fileno(), 0o600)
        output.write(''.join(key + '=' + escape(value) + '\n' for key, value in values.items()))


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError()
        os.umask(0o077)
        stage(Path(sys.argv[1]).resolve())
    except (OSError, ValueError, binascii.Error):
        sys.exit('Signing secrets are missing or invalid; staging requires a new private directory.')
