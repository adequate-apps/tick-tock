#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check that every packaged ABI has the audio engine and is supported."""
import sys
import zipfile

EXPECTED = {"arm64-v8a", "x86_64"}


def check(path):
    with zipfile.ZipFile(path) as archive:
        libraries = {}
        for name in archive.namelist():
            parts = name.split("/")
            if parts[0] == "base":
                parts = parts[1:]
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                libraries.setdefault(parts[1], set()).add(parts[2])
        if set(libraries) != EXPECTED:
            raise ValueError("packaged ABIs differ from supported arm64-v8a and x86_64")
        for abi, names in libraries.items():
            if "libticktock_engine.so" not in names:
                raise ValueError(f"audio engine missing for {abi}")
    print("check-abis: both supported ABIs contain the audio engine")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: check-abis.py APK_OR_AAB ...")
    try:
        for artifact in sys.argv[1:]:
            check(artifact)
    except (OSError, ValueError, zipfile.BadZipFile) as failure:
        sys.exit(f"check-abis: {failure}")
