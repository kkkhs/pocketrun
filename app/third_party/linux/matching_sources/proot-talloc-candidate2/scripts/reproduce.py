"""Rebuild only PRoot+talloc from the private corresponding-source bundle.
Usage: python -I reproduce.py UNPACKED_BUNDLE NEW_EMPTY_WORKSPACE NDK_PATH
No network, Android execution, APK/project/Gradle/git writes or publishing.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

bundle = Path(sys.argv[1]).resolve()
base = Path(sys.argv[2]).absolute()
ndk = Path(sys.argv[3]).resolve()
if base.exists():
    raise ValueError('reproduction workspace must not already exist')
base.mkdir(mode=0o700)
(base / 'logs').mkdir()
shutil.copytree(bundle / 'scripts', base / 'scripts')
shutil.copytree(bundle / 'config', base / 'config')
items = [
    ('proot','proot-v5.1.107.102.zip','30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852'),
    ('talloc','talloc-2.5.0.tar.gz','912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007'),
]
records = []
for name,filename,expected in items:
    p = bundle / 'upstream' / filename
    if hashlib.sha256(p.read_bytes()).hexdigest() != expected:
        raise ValueError('upstream SHA mismatch')
    records.append({'name':name,'archive':str(p),'expected_sha256':expected,'verified':True})
(base / 'logs/downloads.json').write_text(json.dumps(records,indent=2)+'\n')
subprocess.run([sys.executable,'-I',str(base / 'scripts/extract.py'),str(base),'extracted-attempt-2'],cwd=base / 'scripts',check=True)
(base / 'patched').mkdir()
shutil.copytree(base / 'extracted-attempt-2/proot/proot-5.1.107.102',base / 'patched/proot-5.1.107.102',symlinks=True)
with (base / 'logs/patch.txt').open('wb') as log:
    r = subprocess.run(['patch','--batch','--forward','-p1','-d',str(base / 'patched/proot-5.1.107.102'),'-i',str(bundle / 'patches/proot-full.diff')],cwd=base / 'scripts',stdout=log,stderr=subprocess.STDOUT)
if r.returncode:
    sys.exit(r.returncode)
subprocess.run([sys.executable,'-I',str(base / 'scripts/build.py'),str(base),str(ndk),'build-reproduced'],cwd=base / 'scripts',check=True)
for name in ['proot/proot','proot/loader/loader']:
    p = base / 'build-reproduced' / name
    print(name,hashlib.sha256(p.read_bytes()).hexdigest())
