import hashlib
import json
from pathlib import Path, PurePosixPath
import os
import shutil
import stat
import sys
import tarfile
import zipfile

base = Path(sys.argv[1]).resolve()
records = json.loads((base / 'logs/downloads.json').read_text())
extracted = base / sys.argv[2]
extracted.mkdir(mode=0o700)


def destination(root, name):
    p = PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts:
        raise ValueError(f'unsafe path {name}')
    return root.joinpath(*p.parts)

for r in records:
    if not r['verified']:
        raise ValueError('archive not verified')
    archive = Path(r['archive'])
    if hashlib.sha256(archive.read_bytes()).hexdigest() != r['expected_sha256']:
        raise ValueError('archive changed')
    root = extracted / r['name']
    root.mkdir(mode=0o700)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for m in z.infolist():
                p = destination(root, m.filename)
                mode = m.external_attr >> 16
                if stat.S_ISLNK(mode):
                    continue
                if m.is_dir():
                    p.mkdir(parents=True, exist_ok=True)
                else:
                    p.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(m) as src, p.open('xb') as out:
                        shutil.copyfileobj(src, out)
                    p.chmod(mode & 0o777 or 0o600)
            for m in z.infolist():
                if stat.S_ISLNK(m.external_attr >> 16):
                    p = destination(root, m.filename)
                    p.parent.mkdir(parents=True, exist_ok=True)
                    os.symlink(z.read(m).decode(), p)
    else:
        with tarfile.open(archive) as t:
            members = t.getmembers()
            for m in members:
                p = destination(root, m.name)
                if m.isdir():
                    p.mkdir(parents=True, exist_ok=True)
                elif m.isfile():
                    p.parent.mkdir(parents=True, exist_ok=True)
                    with t.extractfile(m) as src, p.open('xb') as out:
                        shutil.copyfileobj(src, out)
                    p.chmod(m.mode & 0o777)
                elif not (m.issym() or m.islnk()):
                    raise ValueError(f'unsupported tar member {m.name}')
            # Links last: nothing is subsequently extracted through a link.
            for m in members:
                p = destination(root, m.name)
                if m.islnk():
                    target = destination(root, m.linkname)
                    p.parent.mkdir(parents=True, exist_ok=True)
                    os.link(target, p, follow_symlinks=False)
            for m in members:
                p = destination(root, m.name)
                if m.issym():
                    p.parent.mkdir(parents=True, exist_ok=True)
                    os.symlink(m.linkname, p)
    print(r['name'], root)
