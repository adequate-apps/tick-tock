#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reject randomized SDK metadata in an otherwise reproducible signed APK."""
from pathlib import Path
import struct
import sys


def check(path):
    data = Path(path).read_bytes()
    end = data.rfind(b'PK\x05\x06')
    if end < 0 or end + 22 > len(data):
        raise ValueError('APK ZIP end is missing')
    central = struct.unpack_from('<I', data, end + 16)[0]
    if central < 32 or central > end or data[central - 16:central] != b'APK Sig Block 42':
        raise ValueError('APK signing block is missing')
    length = struct.unpack_from('<Q', data, central - 24)[0]
    start = central - length - 8
    if start < 0 or length < 24 or struct.unpack_from('<Q', data, start)[0] != length:
        raise ValueError('APK signing block size differs')
    position = start + 8
    while position < central - 24:
        size = struct.unpack_from('<Q', data, position)[0]
        if size < 4 or position + 8 + size > central - 24:
            raise ValueError('APK signing entry is invalid')
        identifier = struct.unpack_from('<I', data, position + 8)[0]
        if identifier == 0x504b4453:
            raise ValueError('APK carries randomized SDK dependency metadata')
        position += size + 8
    if position != central - 24:
        raise ValueError('APK signing entries are incomplete')
    print('APK signing metadata is reproducible')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: check-apk-metadata.py APK')
        check(sys.argv[1])
    except (OSError, ValueError, struct.error) as failure:
        sys.exit(str(failure))
