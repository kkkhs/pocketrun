# PocketRun minimal data-only runtime generator, 2026-11-19.
# SPDX-License-Identifier: GPL-2.0-or-later
# This script is build/source material, not an additional executable in the APK.
# Run from a trusted directory with python -I and pass a NEW EMPTY output directory.
import gzip
import hashlib
import io
from pathlib import Path
import sys
import tarfile

output = Path(sys.argv[1]).resolve()
if not output.is_dir() or any(output.iterdir()):
    raise ValueError('output must be an existing NEW EMPTY directory')
raw = io.BytesIO()
with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
    for name in ['bin', 'lib', 'etc', 'root', 'tmp', 'workspace', 'proc', 'dev', 'usr', 'usr/bin']:
        member = tarfile.TarInfo(name)
        member.type = tarfile.DIRTYPE
        member.mode = 0o700
        archive.addfile(member)
    files = {
        'etc/alpine-release': b'3.22.8\n',
        'etc/passwd': b'root:x:0:0:root:/root:/bin/sh\nnobody:x:65534:65534:nobody:/:/bin/sh\n',
        'etc/group': b'root:x:0:\nnobody:x:65534:\n',
        'bin/busybox': b'',
        'lib/ld-musl-aarch64.so.1': b'',
        'dev/null': b'',
    }
    for name, data in files.items():
        member = tarfile.TarInfo(name)
        member.mode = 0o600
        member.size = len(data)
        archive.addfile(member, io.BytesIO(data))
    for applet in ['sh', 'ash', 'cat', 'wc', 'printf', 'sleep', 'id', 'pwd', 'ls', 'head', 'yes', 'tty', 'stty', 'kill', 'true', 'false', 'uname']:
        member = tarfile.TarInfo('bin/' + applet)
        member.type = tarfile.SYMTYPE
        member.linkname = 'busybox'
        member.mode = 0o777
        archive.addfile(member)
    member = tarfile.TarInfo('lib/libc.musl-aarch64.so.1')
    member.type = tarfile.SYMTYPE
    member.linkname = 'ld-musl-aarch64.so.1'
    member.mode = 0o777
    archive.addfile(member)
data = gzip.compress(raw.getvalue(), compresslevel=9, mtime=0)
path = output / 'alpine-data.tar.gz'
path.write_bytes(data)
print('bytes', len(data), 'sha256', hashlib.sha256(data).hexdigest())
