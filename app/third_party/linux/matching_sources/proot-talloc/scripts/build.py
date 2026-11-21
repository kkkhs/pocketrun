import json
import os
from pathlib import Path
import subprocess
import sys

base = Path(sys.argv[1]).resolve()
ndk = Path(sys.argv[2]).resolve()
# NDK r27 has platform stubs through API 35. This is a compilation API,
# not the App manifest targetSdk, which must remain 36.
llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
cc = str(llvm / 'aarch64-linux-android35-clang')
ar = str(llvm / 'llvm-ar')
source = base / 'patched/proot-5.1.107.102/src'
talloc = base / 'extracted-attempt-2/talloc/talloc-2.5.0'
build = base / sys.argv[3]
build.mkdir(mode=0o700)
proot_build = build / 'proot'
proot_build.mkdir()
lib = build / 'lib'
lib.mkdir()
results = []
env = dict(os.environ)
for name in ['CPATH', 'C_INCLUDE_PATH', 'CPLUS_INCLUDE_PATH', 'LIBRARY_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'MAKEFLAGS', 'MFLAGS']:
    env.pop(name, None)
env['LC_ALL'] = 'C'
env['PATH'] = str(llvm) + ':/usr/bin:/bin'


def run(label, args, cwd):
    log = base / 'logs' / f'{sys.argv[3]}-{label}.txt'
    with log.open('wb') as out:
        r = subprocess.run(args, cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT)
    rec = {'step': label, 'exit_code': r.returncode, 'command': args, 'cwd': str(cwd), 'log': str(log)}
    results.append(rec)
    (base / 'logs' / f'{sys.argv[3]}-result.json').write_text(json.dumps(results, indent=2) + '\n')
    print(label, r.returncode, log, flush=True)
    if r.returncode:
        sys.exit(r.returncode)

common = ['-O2', '-fPIE', '-D_GNU_SOURCE', '-D_FILE_OFFSET_BITS=64', '-ffile-prefix-map=' + str(base) + '=/pocketrun-candidate']
run('talloc', [cc, *common, '-fstack-protector-strong', '-I' + str(base / 'config'), '-I' + str(talloc), '-c', str(talloc / 'talloc.c'), '-o', str(lib / 'talloc.o')], build)
run('talloc-archive', [ar, 'rcs', str(lib / 'libtalloc.a'), str(lib / 'talloc.o')], build)
cppflags = '-D_GNU_SOURCE -D_FILE_OFFSET_BITS=64 -DVERSION=\\\"v5.1.107.102-pocketrun-candidate\\\" -I. -I' + str(source) + ' -I' + str(talloc)
cflags = ' '.join(common) + ' -Wall -Wextra -fstack-protector-strong -DPROOT_UNBUNDLE_LOADER=\\\"/nonexistent-pocketrun-loader\\\"'
linkflags = '-pie -Wl,-z,max-page-size=16384,-z,common-page-size=16384,-z,noexecstack,-z,relro,-z,now,--build-id=sha1 -L' + str(lib) + ' -ltalloc'
loaderflags = '-static-pie -nostdlib -Wl,-e,_start,--build-id=none,-z,max-page-size=16384,-z,common-page-size=16384,-z,noexecstack,-z,relro,-z,now'
args = ['make', '-f', str(source / 'GNUmakefile'), '-j4', 'V=1', 'CC=' + cc, 'LD=' + cc, 'GIT=false', 'STRIP=' + str(llvm / 'llvm-strip'), 'OBJCOPY=' + str(llvm / 'llvm-objcopy'), 'OBJDUMP=' + str(llvm / 'llvm-objdump'), 'CPPFLAGS=' + cppflags, 'CFLAGS=' + cflags, 'LDFLAGS=' + linkflags, 'LOADER_LDFLAGS=' + loaderflags, 'LOADER_CFLAGS=-fPIC -ffreestanding -fno-stack-protector', 'PROOT_UNBUNDLE_LOADER=/nonexistent-pocketrun-loader']
run('loader', [*args, 'loader/loader'], proot_build)
# Use the actual built ELF symbols, avoiding any host-architecture assumption.
readelf = subprocess.run([str(llvm / 'llvm-readelf'), '-s', str(proot_build / 'loader/loader')], cwd=build, env=env, stdout=subprocess.PIPE, check=True).stdout.decode()
values = {}
for line in readelf.splitlines():
    fields = line.split()
    if len(fields) >= 8 and fields[-1] in ('_start', 'pokedata_workaround'):
        values[fields[-1]] = int(fields[1], 16)
if set(values) != {'_start', 'pokedata_workaround'}:
    raise ValueError('missing loader entry/stub symbol')
(proot_build / 'loader/loader-info.c').write_text('#include <sys/types.h>\nconst ssize_t offset_to_pokedata_workaround=' + str(values['pokedata_workaround'] - values['_start']) + ';\n')
run('proot', [*args, 'proot'], proot_build)
print('build complete; no generated Android executable was run')
