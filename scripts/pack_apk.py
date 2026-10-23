#!/usr/bin/env python3
"""Build a normal Android ZIP using stronger DEFLATE, without runtime libraries.

Keep stored Android resources stored; zipalign and signing run after this step.
Every uncompressed entry is checked byte-for-byte before accepting the archive.
"""
import binascii
from pathlib import Path
import struct
import sys
import zipfile
import zlib

import zopfli.zlib


def pack(base, dex, target, native_dir=None):
    with zipfile.ZipFile(base) as source:
        entries = [(i.filename, source.read(i), i.compress_type) for i in source.infolist()]
    entries.append(("classes.dex", Path(dex).read_bytes(), zipfile.ZIP_DEFLATED))
    if native_dir:
        for library in sorted(Path(native_dir).rglob("*.so")):
            entries.append(("lib/" + library.relative_to(native_dir).as_posix(), library.read_bytes(), zipfile.ZIP_DEFLATED))
    if len({name for name, _, _ in entries}) != len(entries):
        raise ValueError("Duplicate APK entries")
    directory = bytearray()
    with open(target, "wb") as output:
        for name, data, method in entries:
            if len(data) > 128 * 1024 * 1024:
                raise ValueError("APK entry exceeds lightweight packer's 128 MB limit")
            name_bytes = name.encode("utf-8")
            if method not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ValueError("Unsupported ZIP compression method")
            payload = data
            if method == 8:
                payload = (zlib.compress(data, level=9) if len(data) > 1024 * 1024
                           else zopfli.zlib.compress(data, numiterations=15))[2:-4]
            crc = binascii.crc32(data) & 0xffffffff
            offset = output.tell()
            # Fixed DOS timestamp: 1980-01-01. No ZIP64 or data descriptors needed.
            output.write(struct.pack("<IHHHHHIIIHH", 0x04034b50, 20, 0x800,
                                     method, 0, 33, crc, len(payload), len(data), len(name_bytes), 0))
            output.write(name_bytes)
            output.write(payload)
            directory.extend(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014b50, 20, 20,
                                         0x800, method, 0, 33, crc, len(payload), len(data),
                                         len(name_bytes), 0, 0, 0, 0, 0, offset))
            directory.extend(name_bytes)
        start = output.tell()
        output.write(directory)
        output.write(struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, len(entries), len(entries),
                                 len(directory), start, 0))
    with zipfile.ZipFile(target) as check:
        assert check.testzip() is None
        for name, data, method in entries:
            assert check.read(name) == data, name
            assert check.getinfo(name).compress_type == method, name
    print("Verified APK entries:", len(entries))


if __name__ == "__main__":
    pack(*sys.argv[1:])
