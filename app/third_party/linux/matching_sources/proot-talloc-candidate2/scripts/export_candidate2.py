import ast
import difflib
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile

base = Path(sys.argv[1]).resolve()
old = Path(sys.argv[2]).resolve()
vendor = Path(sys.argv[3]).resolve()
ndk = Path(sys.argv[4]).resolve()
out = base / sys.argv[5]
out.mkdir(mode=0o700)
(out/'elf').mkdir()
(out/'licenses').mkdir()
upstream = base/'extracted-attempt-2/proot/proot-5.1.107.102'
patched = base/'patched/proot-5.1.107.102'

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

# Reuse the already authored pure ELF decoder, without executing its driver.
module = ast.parse((old/'scripts/finalize.py').read_text())
fn = next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='elf')
namespace = {'struct':struct}
exec(compile(ast.Module(body=[fn],type_ignores=[]),'elf-decoder','exec'),namespace)
elf = namespace['elf']
expected_vendor_bundle = 'eb5d7c20067c7c14e1ffb5047408a96270b95ae4682dd34bc80dc526252165ef'
expected_vendor_diff = 'c5caed68d2619164e48cbbfad004fe5e5f2728cf8a76f4a0be66d394b9245d1f'
if sha(vendor/'matching_sources/proot-talloc/proot-talloc-corresponding-source-candidate.tar.gz') != expected_vendor_bundle or sha(vendor/'matching_sources/proot-talloc/proot-full.diff') != expected_vendor_diff:
    raise ValueError('vendor baseline changed')
changes = []
full_diff = []
delta = []
for root,dirs,files in os.walk(patched,followlinks=False):
    for name in sorted(files):
        p = Path(root)/name
        if p.is_symlink():
            continue
        rel = p.relative_to(patched)
        u = upstream/rel
        v = old/'patched/proot-5.1.107.102'/rel
        if p.read_bytes() != u.read_bytes():
            changes.append({'file':rel.as_posix(),'upstream_sha256':sha(u),'candidate2_sha256':sha(p)})
            full_diff.extend(difflib.unified_diff(u.read_text().splitlines(keepends=True),p.read_text().splitlines(keepends=True),fromfile='a/'+str(rel),tofile='b/'+str(rel)))
        if p.read_bytes() != v.read_bytes():
            delta.extend(difflib.unified_diff(v.read_text().splitlines(keepends=True),p.read_text().splitlines(keepends=True),fromfile='candidate1/'+str(rel),tofile='candidate2/'+str(rel)))
(out/'proot-full.diff').write_text(''.join(full_diff))
(out/'candidate2-incremental.diff').write_text(''.join(delta))
artifacts = []
for name,src in [('libproot.so',base/'build-candidate2-final/proot/proot'),('libproot_loader.so',base/'build-candidate2-final/proot/loader/loader')]:
    p = out/'elf'/name
    shutil.copyfile(src,p)
    p.chmod(0o444)
    data = elf(p)
    log = out/(name+'.readelf.txt')
    with log.open('wb') as f:
        r = subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'),'-h','-l','-d','-r','--wide',str(p)],cwd=base/'scripts',stdout=f,stderr=subprocess.STDOUT)
    if r.returncode or not data['static_structure_ok'] or data['elf_type'] != 3:
        raise ValueError('ELF structure failed')
    if name=='libproot_loader.so' and (data['interp'] is not None or data['needed'] or data['relocation_bytes']):
        raise ValueError('loader no longer freestanding relocation-free PIE')
    artifacts.append({'name':name,'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p),'elf':data,'readelf_exit':r.returncode,'readelf_log':str(log)})
licenses = []
for name in ['PRoot-COPYING.txt','talloc-LICENSE.txt','GPL-3.0.txt','proot-source-copyright-notices.txt','talloc-source-copyright-notices.txt']:
    src = vendor/'licenses'/name
    shutil.copyfile(src,out/'licenses'/name)
    licenses.append({'filename':name,'vendor_path':str(src),'sha256':sha(src),'copy_sha256':sha(out/'licenses'/name)})
# Verify the primary texts against the actual matching immutable source.
assert sha(upstream/'COPYING') == sha(out/'licenses/PRoot-COPYING.txt')
assert sha(base/'extracted-attempt-2/talloc/talloc-2.5.0/LICENSE') == sha(out/'licenses/talloc-LICENSE.txt')
modifications = '''PocketRun modified PRoot candidate2, 2026-11-19.
Upstream fixed tag: termux/proot v5.1.107.102, not an unmodified binary.
Version string: v5.1.107.102-pocketrun-candidate2.
PRoot GPLv2-or-later permission is exercised at GPLv3 for the executable statically combined with LGPLv3-or-later talloc. Original source licenses and copyright notices are retained; local patch/adapter/scripts are conveyed under GPLv3 as build material.
The original six modified files remain listed in the full unified diff. Candidate2 adds initial guest host-PID status latching (normal exit code or 128+signal only from initial guest, once, before descendant cleanup), and optional tracer-self identity registration before any guest fork. SIGTERM/SIGINT policy remains upstream; SIGQUIT killall and explicit parent-side SIGKILL/reap are separate operations.
POCKETRUN_TRACER_RECORD is optional. If set, it must name a fresh file inside a caller-owned private directory. The tracer opens the parent without following its final symlink, checks owner and no group/other access, and creates a 0600 O_EXCL|O_NOFOLLOW JSON file containing schemaVersion=1, pid, ppid, uid and startTicks from /proc/self/stat field22. Complete write/fsync/close is required before any guest creation. Failure exits without launching a guest. Success also clears POCKETRUN_TRACER_RECORD from the guest exec environment; this is not a guarantee against same-UID /proc inspection or tampering. The existing expected App parent/PDEATHSIG requirement stays unchanged.
The identity is data within a shared App UID, not a cryptographic attestation or security sandbox. The parent must verify/cache PID, UID, PPid, startTicks and kernel executable identity for this signed immutable PRoot before targeting a signal; never trust arbitrary guest JSON/PIDs or use a JS arbitrary-PID interface.
Build with NDK27.1.12297006 aarch64-linux-android34-clang (requested compilation API34 floor), preserving App targetSdk36. Both link stages retain separate 16KiB maximum/common page-size flags; no 32-bit loader or executable-data workaround is added.
After safely unpacking this archive into a NEW EMPTY directory, run python -I /absolute/unpacked/scripts/reproduce.py /absolute/unpacked /absolute/new-private-workspace /absolute/android-ndk/27.1.12297006. It verifies both pinned original archives, applies patches/proot-full.diff, and cross-builds the modified executable/loader without running PRoot or Android. It does not rebuild or redistribute the separate Alpine payload.
Device/native acceptance is not claimed by these source/helper/static checks. The coordinator must integrate the new ELF, matching source/diff/notices and new hashes together, then rerun the true D9 scenarios. Prior failed device results remain failed historical trials.
'''
(out/'MODIFICATIONS.txt').write_text(modifications)
status_tests = json.loads((base/'status-tests-1/result.json').read_text())
identity_tests = json.loads((base/'identity-tests-2/result.json').read_text())
assert status_tests['event_c_sha256']==sha(patched/'src/tracee/event.c')
assert identity_tests['cli_c_sha256']==sha(patched/'src/cli/cli.c')
assert status_tests['run_exit']==identity_tests['run_exit']==0
build_result = json.loads((base/'logs/build-candidate2-final-result.json').read_text())
assert len(build_result)==4 and all(r['exit_code']==0 for r in build_result)
sources = []
for r in json.loads((base/'logs/downloads.json').read_text()):
    p=Path(r['archive'])
    if sha(p) != r['expected_sha256']:
        raise ValueError('fixed archive changed')
    sources.append({'name':r['name'],'url':r['url'],'archive':str(p),'bytes':p.stat().st_size,'sha256':sha(p)})
source_archive = out/'proot-talloc-corresponding-source-candidate2.tar.gz'
with source_archive.open('xb') as raw, gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as gz, tarfile.open(fileobj=gz,mode='w',format=tarfile.PAX_FORMAT) as tar:
    for src in sources:
        tar.add(src['archive'],arcname='upstream/'+Path(src['archive']).name)
    tar.add(out/'proot-full.diff',arcname='patches/proot-full.diff')
    tar.add(out/'candidate2-incremental.diff',arcname='patches/candidate2-incremental.diff')
    tar.add(out/'MODIFICATIONS.txt',arcname='MODIFICATIONS.txt')
    tar.add(out/'licenses',arcname='licenses')
    tar.add(base/'config',arcname='config')
    tar.add(base/'scripts',arcname='scripts')
    tar.add(base/'logs/build-candidate2-final-result.json',arcname='build/build-result.json')
verification = {'version':'v5.1.107.102-pocketrun-candidate2','scope':'private cross-build/source/helper/static preparation; NOT_ANDROID_ACCEPTANCE','upstream_sources':sources,'vendor_baseline':{'source_bundle_sha256':expected_vendor_bundle,'full_diff_sha256':expected_vendor_diff},'source_changes':changes,'full_diff':{'path':str(out/'proot-full.diff'),'sha256':sha(out/'proot-full.diff')},'incremental_diff':{'path':str(out/'candidate2-incremental.diff'),'sha256':sha(out/'candidate2-incremental.diff')},'source_bundle':{'path':str(source_archive),'bytes':source_archive.stat().st_size,'sha256':sha(source_archive)},'license_provenance':licenses,'modifications_notice':{'path':str(out/'MODIFICATIONS.txt'),'sha256':sha(out/'MODIFICATIONS.txt')},'artifacts':artifacts,'build_result_path':str(base/'logs/build-candidate2-final-result.json'),'build_exit_codes':[r['exit_code'] for r in build_result],'ndk':'27.1.12297006','compile_api':34,'app_target_sdk_required':36,'helper_tests':{'initial_status':status_tests,'identity':identity_tests},'source_reproduction':'PENDING','registered_env':'POCKETRUN_TRACER_RECORD','registered_schema':{'schemaVersion':1,'pid':'getpid()','ppid':'getppid()','uid':'getuid()','startTicks':'/proc/self/stat field22, uint64 nonzero'},'constraints_unchanged':['EXITKILL failclosed initial SIGSTOP and default/seccomp-fallback options','tracer expected App PID+PDEATHSIG and first-child PDEATHSIG','unbundled freestanding PIE loader, no 32-bit loader','no W+X / executable stack / executable anonymous loader mappings','SIGTERM/SIGINT remain upstream; SIGQUIT unchanged'],'external_unknowns':['Candidate2 has not been integrated into the project APK or run on D9. GuestSIGKILL137/root0+background cleanup and tracer death/reap must be rerun on real App UID.','Identity is same-App-UID data, not authentication. Parent must verify/cache live PID/UID/PPid/startTicks and executable identity before Os.kill; no public arbitrary-PID API.','Parent-side forced SIGKILL/reap and ProcessBuilder thread/PDEATHSIG lifetimes are outside this private builder scope.','Alpine minimal BusyBox/musl payload and its existing main-owned license/source audit are unchanged; this bundle supplies PRoot+talloc only.','No new public source delivery, APK install, device lease, Git action or Chapter7 completion is asserted.'],'device_run':False,'proot_run':False,'project_written':False,'published':False}
(out/'verification.json').write_text(json.dumps(verification,indent=2)+'\n')
print(json.dumps({'output':str(out),'artifacts':[{k:a[k] for k in ['name','bytes','sha256']} for a in artifacts],'full_diff_sha256':sha(out/'proot-full.diff'),'incremental_diff_sha256':sha(out/'candidate2-incremental.diff'),'source_bundle_sha256':sha(source_archive),'build_exit_codes':verification['build_exit_codes'],'source_changes':len(changes),'helper_cases':[status_tests['sequence_cases'],identity_tests['cases']]},indent=2))
