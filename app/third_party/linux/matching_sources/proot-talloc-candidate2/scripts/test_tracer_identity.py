"""Exact-source helper tests only; no PRoot, Android or device execution."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

base = Path(sys.argv[1]).resolve()
source = base / 'patched/proot-5.1.107.102/src/cli/cli.c'
text = source.read_text()
start = text.index('/* Read field 22 without treating spaces')
end = text.index('int main(int argc, char *const argv[])',start)
helper = text[start:end]
assert text.index('write_tracer_identity() != 0',end) < text.index('talloc_enable_leak_report();',end)
assert text.index('require_live_app_parent() != 0',end) < text.index('write_tracer_identity() != 0',end)
d = base / sys.argv[2]
d.mkdir(mode=0o700)
(d/'owned').mkdir(mode=0o700)
(d/'public').mkdir(mode=0o755)
(d/'public').chmod(0o755)
os.symlink(d/'owned',d/'parent-link')
code = '''#define _GNU_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
''' + helper + '''
static int cases=0;
static void passed(const char *name) { printf("PASS %s\\n",name); cases++; }
static void synthetic(char *buf, const char *ticks) {
 int len=snprintf(buf,4096,"123 (nested ) name with spaces) S");
 for(int field=4;field<=21;field++) len+=snprintf(buf+len,4096-len," %d",field);
 snprintf(buf+len,4096-len," %s 23 24\\n",ticks);
}
int main(int argc, char **argv) {
 char buf[4096], path[PATH_MAX], existing[512], after[512];
 unsigned long long ticks=0;
 struct stat st;
 int fd; ssize_t n;
 assert(argc==2);
 synthetic(buf,"987654321"); assert(parse_self_start_ticks(buf,&ticks)==0 && ticks==987654321ULL); passed("comm parentheses/spaces and field22");
 synthetic(buf,"18446744073709551615"); assert(parse_self_start_ticks(buf,&ticks)==0 && ticks==18446744073709551615ULL); passed("uint64 max");
 synthetic(buf,"18446744073709551616"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("overflow fail");
 synthetic(buf,"-1"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("negative fail");
 synthetic(buf,"+1"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("plus fail");
 synthetic(buf,"0"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("zero fail");
 strcpy(buf,"missing delimiter"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("delimiter missing fail");
 strcpy(buf,"123 (comm) S 4"); assert(parse_self_start_ticks(buf,&ticks)!=0); passed("truncated fields fail");
 unsetenv("POCKETRUN_TRACER_RECORD"); assert(write_tracer_identity()==0); passed("unset ENV compatibility");
 setenv("POCKETRUN_TRACER_RECORD","relative/file",1); assert(write_tracer_identity()!=0); passed("relative path fail");
 snprintf(path,sizeof(path),"%s/owned/tracer.identity",argv[1]); setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()==0); assert(getenv("POCKETRUN_TRACER_RECORD")==NULL); assert(stat(path,&st)==0 && (st.st_mode&0777)==0600 && st.st_uid==getuid());
 fd=open(path,O_RDONLY); assert(fd>=0); n=read(fd,existing,sizeof(existing)-1); assert(n>0); existing[n]='\\0'; assert(close(fd)==0); printf("IDENTITY %s",existing); printf("EXPECTED %ld %ld %lu\\n",(long)getpid(),(long)getppid(),(unsigned long)getuid()); passed("fresh file complete JSON mode0600");
 setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()!=0); fd=open(path,O_RDONLY); assert(fd>=0); n=read(fd,after,sizeof(after)-1); assert(n>0); after[n]='\\0'; assert(close(fd)==0); assert(strcmp(after,existing)==0); passed("O_EXCL retains existing identity");
 snprintf(path,sizeof(path),"%s/owned/symlink.identity",argv[1]); assert(symlink("tracer.identity",path)==0); setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()!=0); passed("symlink record fail");
 snprintf(path,sizeof(path),"%s/public/tracer.identity",argv[1]); setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()!=0); passed("nonprivate parent fail");
 snprintf(path,sizeof(path),"%s/parent-link/new.identity",argv[1]); setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()!=0); passed("final parent symlink fail");
 snprintf(path,sizeof(path),"%s/owned/..",argv[1]); setenv("POCKETRUN_TRACER_RECORD",path,1); assert(write_tracer_identity()!=0); passed("dotdot basename fail");
 setenv("POCKETRUN_TRACER_RECORD","",1); assert(write_tracer_identity()!=0); passed("empty ENV fail");
 printf("TOTAL %d helper cases; no PRoot process/Android execution\\n",cases);
 return 0;
}
'''
p = d/'tracer_identity_test.c'
p.write_text(code)
command = ['cc','-std=c11','-Wall','-Wextra','-Werror','-O2',str(p),'-o',str(d/'tracer_identity_test')]
c = subprocess.run(command,cwd=base/'scripts',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(d/'compile.txt').write_bytes(c.stdout)
if c.returncode:
    print(c.stdout.decode()); sys.exit(c.returncode)
r = subprocess.run([str(d/'tracer_identity_test'),str(d)],cwd=base/'scripts',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(d/'run.txt').write_bytes(r.stdout)
stdout = r.stdout.decode()
if r.returncode == 0:
    identity = json.loads(next(line[len('IDENTITY '):] for line in stdout.splitlines() if line.startswith('IDENTITY ')))
    expected = next(line.split()[1:] for line in stdout.splitlines() if line.startswith('EXPECTED '))
    assert identity['schemaVersion']==1 and identity['pid']==int(expected[0]) and identity['ppid']==os.getpid()==int(expected[1]) and identity['uid']==os.getuid()==int(expected[2]) and identity['startTicks']>0
result = {'scope':'exact-source identity/parser helper only; no PRoot/native-device acceptance','cli_c_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'helper_sha256':hashlib.sha256(helper.encode()).hexdigest(),'compile_exit':c.returncode,'run_exit':r.returncode,'cases':stdout.count('PASS '),'stdout':stdout,'compile_command':command,'proot_run':False,'device_run':False}
(d/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
sys.exit(r.returncode)
