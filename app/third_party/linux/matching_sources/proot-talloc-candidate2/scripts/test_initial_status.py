"""Pure status-function regression only; does not launch PRoot or Android."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

base = Path(sys.argv[1]).resolve()
source = base / 'patched/proot-5.1.107.102/src/tracee/event.c'
text = source.read_text()
start = text.index('/* Initial guest status is independent of descendant/cleanup statuses. */')
end = text.index('/* Whether the kernel lets PTRACE_GETEVENTMSG',start)
actual_function = text[start:end]
assert text.count('record_initial_tracee_status(tracee->pid, tracee_status);') == 2
assert '\t\tinitial_tracee_pid = pid;' in text
assert '\treturn initial_tracee_exit_status;' in text
assert 'last_exit_status' not in text
assert text.index('record_initial_tracee_status(tracee->pid, tracee_status);', text.index('if (WIFEXITED(tracee_status))')) < text.index('terminate_tracee(tracee);', text.index('if (WIFEXITED(tracee_status))'))
# Exercise the actual exact helper text, not a reimplementation.
test = '''#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
''' + actual_function + '''
static void reset(void) {
 initial_tracee_pid=100;
 initial_tracee_exit_status=EXIT_FAILURE;
 initial_tracee_status_recorded=false;
}
static void expected(int value, bool recorded) {
 assert(initial_tracee_exit_status==value);
 assert(initial_tracee_status_recorded==recorded);
}
int main(void) {
 reset(); expected(1,false);
 reset(); record_initial_tracee_status(101,0); expected(1,false); record_initial_tracee_status(100,0); expected(0,true);
 reset(); record_initial_tracee_status(100,42<<8); record_initial_tracee_status(101,0); expected(42,true);
 reset(); record_initial_tracee_status(101,0); record_initial_tracee_status(100,SIGKILL); expected(137,true);
 reset(); record_initial_tracee_status(100,0); record_initial_tracee_status(101,SIGKILL); record_initial_tracee_status(102,SIGKILL); expected(0,true);
 reset(); record_initial_tracee_status(100,42<<8); record_initial_tracee_status(101,SIGKILL); expected(42,true);
 reset(); record_initial_tracee_status(100,SIGKILL); record_initial_tracee_status(101,0); expected(137,true);
 reset(); record_initial_tracee_status(100,(SIGSTOP<<8)|0x7f); expected(1,false); record_initial_tracee_status(100,0); expected(0,true);
 reset(); record_initial_tracee_status(100,SIGSEGV|0x80); expected(139,true);
 reset(); record_initial_tracee_status(100,0xffff); expected(1,false); record_initial_tracee_status(100,SIGTERM); expected(143,true);
 reset(); record_initial_tracee_status(100,0); record_initial_tracee_status(100,SIGKILL); expected(0,true);
 reset(); record_initial_tracee_status(100,255<<8); expected(255,true);
 puts("PASS 12 pure initial-guest status sequence cases; no PRoot process or Android execution");
 return 0;
}
'''
d = base / sys.argv[2]
d.mkdir(mode=0o700)
p = d / 'initial_status_test.c'
p.write_text(test)
command = ['cc','-std=c11','-Wall','-Wextra','-Werror','-O2',str(p),'-o',str(d/'initial_status_test')]
c = subprocess.run(command,cwd=base/'scripts',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(d/'compile.txt').write_bytes(c.stdout)
if c.returncode:
    print(c.stdout.decode()); sys.exit(c.returncode)
r = subprocess.run([str(d/'initial_status_test')],cwd=base/'scripts',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(d/'run.txt').write_bytes(r.stdout)
result = {'scope':'pure exact-source helper, not PRoot/native-device acceptance','event_c_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'extracted_helper_sha256':hashlib.sha256(actual_function.encode()).hexdigest(),'test_c_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'compile_exit':c.returncode,'run_exit':r.returncode,'sequence_cases':12,'stdout':r.stdout.decode(),'compile_command':command,'device_run':False,'proot_run':False}
(d/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
sys.exit(r.returncode)
