#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <thread>
#include <unordered_map>
#include <unistd.h>
#include <vector>

namespace {

constexpr int kMaximumRead = 8 * 1024;
constexpr int kMaximumWrite = 4 * 1024;
constexpr int kMinimumCells = 16;
constexpr int kMaximumCells = 400;
constexpr int kReadyTimeoutMillis = 1000;
constexpr int kCloseTimeoutMillis = 1000;
constexpr size_t kMaximumPostExitBytes = 64U * 1024U;

struct ExitResult {
    int kind = 0;  // 1 exited, 2 signaled.
    int code = 0;
};

struct ProcessIdentity {
    uid_t uid = static_cast<uid_t>(-1);
    unsigned long long start_ticks = 0;
};

struct PtyHandle {
    std::mutex mutex;
    std::condition_variable condition;
    int master_fd = -1;
    int wake_read_fd = -1;
    int wake_write_fd = -1;
    pid_t child_pid = -1;
    pid_t session_id = -1;
    uid_t expected_uid = static_cast<uid_t>(-1);
    unsigned long long start_ticks = 0;
    bool launch_done = false;
    bool worker_finished = false;
    bool waited = false;
    bool root_signal_issued = false;
    size_t post_exit_bytes = 0;
    bool close_in_progress = false;
    bool closed = false;
    ExitResult exit;
    std::string error;
    std::thread launch_reaper;
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<PtyHandle>> g_registry;
std::atomic<jlong> g_next_handle{1};

void Throw(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

void ThrowIo(JNIEnv* env, const std::string& message) { Throw(env, "java/io/IOException", message); }

std::string ErrnoMessage(const char* operation) {
    const int saved_errno = errno;
    return std::string(operation) + ": " + std::strerror(saved_errno);
}

void CloseOwned(int* fd) {
    const int owned = *fd;
    *fd = -1;  // Never retry close(EINTR): the descriptor number may already have been reused.
    if (owned >= 0) (void)close(owned);
}

void CloseLocal(int fd) { if (fd >= 0) (void)close(fd); }

int MoveAboveStdio(int fd) {
    if (fd < 0 || fd >= 3) return fd;
    const int moved = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    const int saved_errno = errno;
    (void)close(fd);
    errno = saved_errno;
    return moved;
}

bool MovePipeAboveStdio(int pipe_fds[2]) {
    pipe_fds[0] = MoveAboveStdio(pipe_fds[0]);
    if (pipe_fds[0] < 0) return false;
    pipe_fds[1] = MoveAboveStdio(pipe_fds[1]);
    return pipe_fds[1] >= 0;
}

bool ReadJniPath(JNIEnv* env, jstring value, const char* label, std::string* output) {
    if (value == nullptr) {
        Throw(env, "java/lang/IllegalArgumentException", std::string(label) + " is null.");
        return false;
    }
    const jsize utf_length = env->GetStringUTFLength(value);
    if (utf_length <= 0 || utf_length >= PATH_MAX) {
        Throw(env, "java/lang/IllegalArgumentException", std::string(label) + " length is invalid.");
        return false;
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return false;
    output->assign(chars, static_cast<size_t>(utf_length));
    env->ReleaseStringUTFChars(value, chars);
    if (output->find('\0') != std::string::npos || output->front() != '/') {
        Throw(env, "java/lang/IllegalArgumentException", std::string(label) + " must be an absolute path.");
        return false;
    }
    for (const unsigned char byte : *output) {
        if (byte < 0x20 || byte > 0x7e) {
            Throw(env, "java/lang/IllegalArgumentException", std::string(label) + " must use canonical ASCII path bytes.");
            return false;
        }
    }
    char canonical[PATH_MAX];
    if (realpath(output->c_str(), canonical) == nullptr) { ThrowIo(env, ErrnoMessage(label)); return false; }
    if (*output != canonical) {
        Throw(env, "java/lang/IllegalArgumentException", std::string(label) + " is not canonical.");
        return false;
    }
    return true;
}

bool ValidateDirectory(JNIEnv* env, const std::string& path, uid_t uid, bool exact_private, const char* label) {
    struct stat status {};
    if (lstat(path.c_str(), &status) != 0) { ThrowIo(env, ErrnoMessage(label)); return false; }
    if (!S_ISDIR(status.st_mode) || status.st_uid != uid || (status.st_mode & (S_IWGRP | S_IWOTH)) != 0 ||
        (exact_private && (status.st_mode & 0777) != 0700)) {
        Throw(env, "java/lang/SecurityException", std::string(label) + " ownership or mode is invalid.");
        return false;
    }
    return true;
}

bool ValidateReadOnlyFile(JNIEnv* env, const std::string& path, const char* label) {
    struct stat status {};
    if (lstat(path.c_str(), &status) != 0) { ThrowIo(env, ErrnoMessage(label)); return false; }
    const bool writable = access(path.c_str(), W_OK) == 0;
    if (!S_ISREG(status.st_mode) || status.st_nlink < 1 ||
        (status.st_mode & (S_IWGRP | S_IWOTH | S_ISUID | S_ISGID)) != 0 || writable) {
        Throw(env, "java/lang/SecurityException", std::string(label) + " must be a read-only regular APK artifact.");
        return false;
    }
    return true;
}

bool IsDirectChildPath(const std::string& parent, const std::string& child) {
    return child.size() > parent.size() + 1 && child.compare(0, parent.size(), parent) == 0 && child[parent.size()] == '/';
}

bool ReadIdentity(pid_t pid, ProcessIdentity* identity) {
    char path[64];
    int path_length = std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(path)) return false;
    const int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    char buffer[4097];
    ssize_t total = 0;
    while (total < static_cast<ssize_t>(sizeof(buffer) - 1)) {
        const ssize_t count = read(fd, buffer + total, sizeof(buffer) - 1 - static_cast<size_t>(total));
        if (count > 0) total += count;
        else if (count == 0) break;
        else if (errno != EINTR) { total = -1; break; }
    }
    CloseLocal(fd);
    if (total <= 0 || total >= static_cast<ssize_t>(sizeof(buffer) - 1)) return false;
    buffer[total] = '\0';
    char* cursor = std::strrchr(buffer, ')');
    if (cursor == nullptr || cursor[1] != ' ') return false;
    cursor += 2;
    unsigned long long start_ticks = 0;
    for (int index = 0; index <= 19; ++index) {
        while (*cursor == ' ') ++cursor;
        if (*cursor == '\0') return false;
        char* end = cursor;
        while (*end != '\0' && *end != ' ') ++end;
        if (index == 19) {
            const char saved = *end;
            *end = '\0';
            char* number_end = nullptr;
            errno = 0;
            start_ticks = std::strtoull(cursor, &number_end, 10);
            *end = saved;
            if (errno != 0 || number_end != end || start_ticks == 0) return false;
            break;
        }
        cursor = end;
    }
    path_length = std::snprintf(path, sizeof(path), "/proc/%d/status", pid);
    if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(path)) return false;
    const int status_fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (status_fd < 0) return false;
    char status_buffer[16385];
    ssize_t status_total = 0;
    while (status_total < static_cast<ssize_t>(sizeof(status_buffer) - 1)) {
        const ssize_t count = read(status_fd, status_buffer + status_total,
            sizeof(status_buffer) - 1 - static_cast<size_t>(status_total));
        if (count > 0) status_total += count;
        else if (count == 0) break;
        else if (errno != EINTR) { status_total = -1; break; }
    }
    CloseLocal(status_fd);
    if (status_total <= 0 || status_total >= static_cast<ssize_t>(sizeof(status_buffer) - 1)) return false;
    status_buffer[status_total] = '\0';
    const char* uid_line = std::strstr(status_buffer, "\nUid:");
    if (uid_line == nullptr) return false;
    unsigned int real_uid = 0, effective_uid = 0, saved_uid = 0, filesystem_uid = 0;
    if (std::sscanf(uid_line + 1, "Uid:%u%u%u%u", &real_uid, &effective_uid, &saved_uid, &filesystem_uid) != 4 ||
        real_uid != effective_uid || real_uid != saved_uid || real_uid != filesystem_uid) return false;
    identity->uid = static_cast<uid_t>(real_uid);
    identity->start_ticks = start_ticks;
    return true;
}

void WakeLocked(PtyHandle* handle) {
    if (handle->wake_write_fd < 0) return;
    const unsigned char marker = 1;
    const ssize_t ignored = write(handle->wake_write_fd, &marker, sizeof(marker));
    (void)ignored;
}

void StoreWaitStatusLocked(PtyHandle* handle, int status) {
    if (WIFEXITED(status)) { handle->exit.kind = 1; handle->exit.code = WEXITSTATUS(status); }
    else if (WIFSIGNALED(status)) { handle->exit.kind = 2; handle->exit.code = WTERMSIG(status); }
    else { handle->error = "waitpid returned neither WIFEXITED nor WIFSIGNALED."; return; }
    handle->waited = true;
}

void ChildRawClose(int fd) { if (fd >= 0) (void)syscall(SYS_close, fd); }

void ChildCloseFromExcept(int maximum_fd, int keep_fd) {
#if defined(__NR_close_range)
    bool first_closed = keep_fd <= 3 || syscall(__NR_close_range, 3U, static_cast<unsigned int>(keep_fd - 1), 0U) == 0;
    bool second_closed = syscall(__NR_close_range, static_cast<unsigned int>(keep_fd + 1), ~0U, 0U) == 0;
    if (first_closed && second_closed) return;
#endif
    for (int fd = 3; fd < maximum_fd; ++fd) {
        if (fd != keep_fd) (void)syscall(SYS_close, fd);
    }
}

void LaunchAndReap(
    PtyHandle* handle,
    int slave,
    int ready_read,
    int ready_write,
    int exec_read,
    int exec_write,
    int expected_parent_pid,
    int maximum_fd,
    std::vector<std::string> arguments,
    std::vector<std::string> environment) {
    std::vector<char*> argv;
    std::vector<char*> envp;
    argv.reserve(arguments.size() + 1);
    envp.reserve(environment.size() + 1);
    for (std::string& item : arguments) argv.push_back(const_cast<char*>(item.c_str()));
    for (std::string& item : environment) envp.push_back(const_cast<char*>(item.c_str()));
    argv.push_back(nullptr);
    envp.push_back(nullptr);
    const char* const executable = arguments.front().c_str();
    char* const* const argv_pointer = argv.data();
    char* const* const envp_pointer = envp.data();

    const pid_t child = fork();
    const int fork_errno = errno;
    if (child == 0) {
        // No JNI, allocation, C++ synchronization, stdio or Bionic close wrapper after fork.
        ChildRawClose(handle->master_fd);
        ChildRawClose(handle->wake_read_fd);
        ChildRawClose(handle->wake_write_fd);
        ChildRawClose(ready_read);
        ChildRawClose(exec_read);
        if (syscall(SYS_prctl, PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0 ||
            getppid() != expected_parent_pid || setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) != 0) _exit(126);
        const pid_t child_group = getpid();
        if (ioctl(slave, TIOCSPGRP, &child_group) != 0 || dup2(slave, STDIN_FILENO) < 0 ||
            dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) _exit(126);
        const unsigned char ready = 1;
        if (write(ready_write, &ready, sizeof(ready)) != static_cast<ssize_t>(sizeof(ready))) _exit(126);
        ChildCloseFromExcept(maximum_fd, exec_write);
        execve(executable, argv_pointer, envp_pointer);
        const int exec_errno = errno;
        (void)write(exec_write, &exec_errno, sizeof(exec_errno));
        _exit(127);
    }

    CloseLocal(slave);  // The worker owns these launch-only descriptors in the parent process.
    CloseLocal(ready_write);
    CloseLocal(exec_write);
    std::string launch_error;
    ProcessIdentity identity;
    if (child < 0) {
        errno = fork_errno;
        launch_error = ErrnoMessage("fork");
    }
    else {
        struct pollfd ready_descriptor {ready_read, POLLIN | POLLHUP | POLLERR, 0};
        int ready_poll;
        do { ready_poll = poll(&ready_descriptor, 1, kReadyTimeoutMillis); } while (ready_poll < 0 && errno == EINTR);
        unsigned char ready = 0;
        const ssize_t ready_count = ready_poll > 0 ? read(ready_read, &ready, sizeof(ready)) : -1;
        if (ready_count != 1 || ready != 1) launch_error = "PTY child did not establish its owned session.";
        else {
            struct pollfd exec_descriptor {exec_read, POLLIN | POLLHUP | POLLERR, 0};
            int exec_poll;
            do { exec_poll = poll(&exec_descriptor, 1, kReadyTimeoutMillis); } while (exec_poll < 0 && errno == EINTR);
            int exec_errno = 0;
            const ssize_t exec_count = exec_poll > 0 ? read(exec_read, &exec_errno, sizeof(exec_errno)) : -1;
            if (exec_count != 0) launch_error = exec_count == static_cast<ssize_t>(sizeof(exec_errno))
                ? std::string("PTY execve failed: ") + std::strerror(exec_errno)
                : "PTY exec-success handshake was incomplete.";
            else if (!ReadIdentity(child, &identity) || identity.uid != handle->expected_uid || getsid(child) != child) {
                launch_error = "PTY exec child UID, start ticks, or session identity is invalid.";
            }
        }
    }
    CloseLocal(ready_read);
    CloseLocal(exec_read);

    if (!launch_error.empty()) {
        if (child > 1) {
            (void)kill(child, SIGKILL);
            int status = 0;
            pid_t result;
            do { result = waitpid(child, &status, 0); } while (result < 0 && errno == EINTR);
            (void)result;
        }
        std::lock_guard<std::mutex> guard(handle->mutex);
        handle->error = launch_error;
        handle->launch_done = true;
        handle->worker_finished = true;
        WakeLocked(handle);
        handle->condition.notify_all();
        return;
    }

    {
        std::lock_guard<std::mutex> guard(handle->mutex);
        handle->child_pid = child;
        handle->session_id = child;
        handle->start_ticks = identity.start_ticks;
        handle->launch_done = true;
        handle->condition.notify_all();
    }

    // waitid observes a terminal direct child but WNOWAIT keeps it unreaped, so its PID cannot
    // be reused before the same mutex commits the real waitpid status.
    siginfo_t information {};
    int waitid_result;
    do { waitid_result = waitid(P_PID, static_cast<id_t>(child), &information, WEXITED | WNOWAIT); }
    while (waitid_result < 0 && errno == EINTR);

    std::lock_guard<std::mutex> guard(handle->mutex);
    if (waitid_result == 0) {
        int status = 0;
        const pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) StoreWaitStatusLocked(handle, status);
        else handle->error = "waitid observed exit but waitpid did not reap the native child.";
    } else handle->error = ErrnoMessage("waitid");
    handle->worker_finished = true;
    WakeLocked(handle);
    handle->condition.notify_all();
}

std::shared_ptr<PtyHandle> FindHandle(JNIEnv* env, jlong id) {
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    const auto iterator = g_registry.find(id);
    if (id <= 0 || iterator == g_registry.end()) {
        Throw(env, "java/lang/IllegalStateException", "PTY handle is closed or invalid.");
        return nullptr;
    }
    return iterator->second;
}

bool RequestRootSignal(const std::shared_ptr<PtyHandle>& handle, int signal_number, bool wake_io) {
    std::lock_guard<std::mutex> guard(handle->mutex);
    if (wake_io) WakeLocked(handle.get());
    if (handle->waited) return true;
    if (!handle->launch_done || handle->child_pid <= 1) return false;
    // This process is the native launcher's own unreaped direct child. The dedicated reaper is
    // the only waitpid caller, and shares this mutex, so PID reuse cannot occur before this kill.
    if (kill(handle->child_pid, signal_number) == 0) {
        if (signal_number == SIGKILL) handle->root_signal_issued = true;
        return true;
    }
    return errno == ESRCH && handle->worker_finished;
}

ExitResult WaitForRoot(JNIEnv* env, const std::shared_ptr<PtyHandle>& handle) {
    std::unique_lock<std::mutex> lock(handle->mutex);
    handle->condition.wait(lock, [&handle] { return handle->waited || handle->worker_finished; });
    if (!handle->waited) {
        ThrowIo(env, handle->error.empty() ? "Native PTY child exit is unproven." : handle->error);
        return ExitResult();
    }
    return handle->exit;
}

bool CloseHandle(const std::shared_ptr<PtyHandle>& handle) {
    (void)RequestRootSignal(handle, SIGKILL, true);
    bool owner = false;
    {
        std::unique_lock<std::mutex> lock(handle->mutex);
        if (handle->closed) return true;
        if (handle->close_in_progress) {
            return handle->condition.wait_for(lock, std::chrono::milliseconds(kCloseTimeoutMillis),
                [&handle] { return handle->closed || !handle->close_in_progress; }) && handle->closed;
        }
        handle->close_in_progress = true;
        owner = true;
        const bool confirmed = handle->condition.wait_for(lock, std::chrono::milliseconds(kCloseTimeoutMillis),
            [&handle] { return handle->waited || handle->worker_finished; });
        if (!confirmed || !handle->waited) {
            handle->close_in_progress = false;
            handle->condition.notify_all();
            return false;
        }
        WakeLocked(handle.get());
        CloseOwned(&handle->master_fd);
        CloseOwned(&handle->wake_read_fd);
        CloseOwned(&handle->wake_write_fd);
        handle->closed = true;
        handle->close_in_progress = false;
        handle->condition.notify_all();
    }
    if (owner && handle->launch_reaper.joinable()) handle->launch_reaper.join();
    return true;
}

int DuplicateLocked(int fd) { return fd < 0 ? -1 : fcntl(fd, F_DUPFD_CLOEXEC, 3); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeOpen(
    JNIEnv* env, jclass, jstring native_directory_value, jstring root_value, jstring workspace_value,
    jstring run_value, jstring temporary_value, jint expected_uid_value, jint expected_parent_pid_value) {
    if (expected_uid_value <= 0 || static_cast<uid_t>(expected_uid_value) != getuid() ||
        expected_parent_pid_value <= 1 || expected_parent_pid_value != getpid()) {
        Throw(env, "java/lang/SecurityException", "PTY App UID or parent PID does not match the current process.");
        return 0;
    }
    std::string native_directory, root, workspace, run_directory, temporary;
    if (!ReadJniPath(env, native_directory_value, "nativeLibraryDir", &native_directory) ||
        !ReadJniPath(env, root_value, "rootfs", &root) || !ReadJniPath(env, workspace_value, "workspace", &workspace) ||
        !ReadJniPath(env, run_value, "runDirectory", &run_directory) ||
        !ReadJniPath(env, temporary_value, "temporaryDirectory", &temporary)) return 0;
    const uid_t uid = static_cast<uid_t>(expected_uid_value);
    if (!ValidateDirectory(env, root, uid, true, "rootfs") || !ValidateDirectory(env, workspace, uid, true, "workspace") ||
        !ValidateDirectory(env, run_directory, uid, true, "runDirectory") ||
        !ValidateDirectory(env, temporary, uid, true, "temporaryDirectory")) return 0;
    if (!IsDirectChildPath(run_directory, temporary) || temporary != run_directory + "/tmp") {
        Throw(env, "java/lang/SecurityException", "PTY temporary directory must be the owned run tmp directory."); return 0;
    }
    struct stat native_status {};
    if (lstat(native_directory.c_str(), &native_status) != 0 || !S_ISDIR(native_status.st_mode) ||
        (native_status.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        Throw(env, "java/lang/SecurityException", "nativeLibraryDir mode is invalid."); return 0;
    }
    const std::string proot = native_directory + "/libproot.so";
    const std::string loader = native_directory + "/libproot_loader.so";
    const std::string busybox = native_directory + "/libalpine_bin_busybox.so";
    const std::string musl = native_directory + "/libalpine_lib_ld_musl_aarch64_so_1.so";
    if (!ValidateReadOnlyFile(env, proot, "libproot.so") || !ValidateReadOnlyFile(env, loader, "libproot_loader.so") ||
        !ValidateReadOnlyFile(env, busybox, "Alpine BusyBox") || !ValidateReadOnlyFile(env, musl, "Alpine musl loader")) return 0;
    const std::string tracer_record = run_directory + "/tracer.identity";
    struct stat record_status {};
    if (lstat(tracer_record.c_str(), &record_status) == 0 || errno != ENOENT) {
        Throw(env, "java/lang/SecurityException", "PTY tracer identity record must be fresh."); return 0;
    }

    std::vector<std::string> arguments = {
        proot, "--kill-on-exit", "-r", root, "-w", "/workspace",
        "-b", busybox + ":/bin/busybox", "-b", musl + ":/lib/ld-musl-aarch64.so.1",
        "-b", workspace + ":/workspace", "-b", temporary + ":/tmp",
        "-b", "/proc:/proc", "-b", "/dev/null:/dev/null", "/bin/busybox", "sh", "-l"
    };
    // BusyBox ash falls back to $HOME/.ash_history only when HISTFILE is unset. A fixed empty
    // value keeps in-memory editing but makes its bounded load/append fopen fail before any path write.
    std::vector<std::string> environment = {
        "PROOT_LOADER=" + loader, "POCKETRUN_EXPECTED_PARENT_PID=" + std::to_string(expected_parent_pid_value),
        "POCKETRUN_TRACER_RECORD=" + tracer_record, "PROOT_TMP_DIR=" + temporary,
        "HOME=/root", "HISTFILE=",
        "PATH=/bin:/usr/bin", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp"
    };

    int master = MoveAboveStdio(open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK));
    if (master < 0) { ThrowIo(env, ErrnoMessage("open /dev/ptmx")); return 0; }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        const std::string error = ErrnoMessage("grantpt/unlockpt"); CloseLocal(master); ThrowIo(env, error); return 0;
    }
    char slave_name[PATH_MAX];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        const std::string error = ErrnoMessage("ptsname_r"); CloseLocal(master); ThrowIo(env, error); return 0;
    }
    int slave = MoveAboveStdio(open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC));
    if (slave < 0) { const std::string error = ErrnoMessage("open PTY slave"); CloseLocal(master); ThrowIo(env, error); return 0; }
    struct winsize initial_size {};
    initial_size.ws_col = 80; initial_size.ws_row = 24;
    if (ioctl(slave, TIOCSWINSZ, &initial_size) != 0) {
        const std::string error = ErrnoMessage("initial TIOCSWINSZ"); CloseLocal(slave); CloseLocal(master); ThrowIo(env, error); return 0;
    }
    int wake_pipe[2] = {-1, -1};
    int ready_pipe[2] = {-1, -1};
    int exec_pipe[2] = {-1, -1};
    if (pipe2(wake_pipe, O_CLOEXEC | O_NONBLOCK) != 0 || !MovePipeAboveStdio(wake_pipe) ||
        pipe2(ready_pipe, O_CLOEXEC) != 0 || !MovePipeAboveStdio(ready_pipe) ||
        pipe2(exec_pipe, O_CLOEXEC) != 0 || !MovePipeAboveStdio(exec_pipe)) {
        const std::string error = ErrnoMessage("pipe2");
        CloseLocal(wake_pipe[0]); CloseLocal(wake_pipe[1]); CloseLocal(ready_pipe[0]); CloseLocal(ready_pipe[1]);
        CloseLocal(exec_pipe[0]); CloseLocal(exec_pipe[1]);
        CloseLocal(slave); CloseLocal(master); ThrowIo(env, error); return 0;
    }
    long open_max = sysconf(_SC_OPEN_MAX);
    if (open_max < 64 || open_max > (1 << 20)) open_max = 65536;

    auto handle = std::make_shared<PtyHandle>();
    handle->master_fd = master;
    handle->wake_read_fd = wake_pipe[0];
    handle->wake_write_fd = wake_pipe[1];
    handle->expected_uid = uid;
    PtyHandle* const raw_handle = handle.get();
    try {
        handle->launch_reaper = std::thread(LaunchAndReap, raw_handle, slave, ready_pipe[0], ready_pipe[1],
            exec_pipe[0], exec_pipe[1], expected_parent_pid_value, static_cast<int>(open_max),
            std::move(arguments), std::move(environment));
    } catch (const std::exception& error) {
        CloseLocal(slave); CloseLocal(ready_pipe[0]); CloseLocal(ready_pipe[1]);
        CloseLocal(exec_pipe[0]); CloseLocal(exec_pipe[1]);
        std::lock_guard<std::mutex> cleanup(handle->mutex);
        CloseOwned(&handle->master_fd); CloseOwned(&handle->wake_read_fd); CloseOwned(&handle->wake_write_fd);
        ThrowIo(env, std::string("PTY launch/reaper thread: ") + error.what());
        return 0;
    }
    {
        std::unique_lock<std::mutex> lock(handle->mutex);
        handle->condition.wait(lock, [&handle] { return handle->launch_done; });
        if (!handle->error.empty()) {
            const std::string error = handle->error;
            lock.unlock();
            if (handle->launch_reaper.joinable()) handle->launch_reaper.join();
            std::lock_guard<std::mutex> cleanup(handle->mutex);
            CloseOwned(&handle->master_fd); CloseOwned(&handle->wake_read_fd); CloseOwned(&handle->wake_write_fd);
            ThrowIo(env, error);
            return 0;
        }
    }
    const jlong id = g_next_handle.fetch_add(1);
    if (id <= 0) {
        (void)CloseHandle(handle);
        ThrowIo(env, "PTY handle space exhausted."); return 0;
    }
    { std::lock_guard<std::mutex> guard(g_registry_mutex); g_registry.emplace(id, handle); }
    return id;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeRead(JNIEnv* env, jclass, jlong id, jint maximum_bytes) {
    if (maximum_bytes <= 0 || maximum_bytes > kMaximumRead) {
        Throw(env, "java/lang/IllegalArgumentException", "PTY read bound must be 1..8192."); return nullptr;
    }
    const auto handle = FindHandle(env, id);
    if (!handle) return nullptr;
    int master = -1, wake = -1;
    size_t read_bound = static_cast<size_t>(maximum_bytes);
    {
        std::lock_guard<std::mutex> guard(handle->mutex);
        if (handle->waited) {
            if (handle->post_exit_bytes >= kMaximumPostExitBytes) return nullptr;
            read_bound = std::min(read_bound, kMaximumPostExitBytes - handle->post_exit_bytes);
        }
        master = DuplicateLocked(handle->master_fd);
        wake = DuplicateLocked(handle->wake_read_fd);
    }
    if (master < 0 || wake < 0) { CloseLocal(master); CloseLocal(wake); ThrowIo(env, "PTY read descriptors are closed."); return nullptr; }
    std::vector<jbyte> bytes(read_bound);
    while (true) {
        struct pollfd descriptors[2] = {{master, POLLIN | POLLHUP | POLLERR, 0}, {wake, POLLIN | POLLHUP | POLLERR, 0}};
        int result;
        do { result = poll(descriptors, 2, -1); } while (result < 0 && errno == EINTR);
        if (result < 0) { const std::string error = ErrnoMessage("PTY read poll"); CloseLocal(master); CloseLocal(wake); ThrowIo(env, error); return nullptr; }
        // Drain master data before honoring wake, so terminal status cannot discard buffered PTY output.
        if (descriptors[0].revents != 0) {
            ssize_t count;
            do { count = read(master, bytes.data(), bytes.size()); } while (count < 0 && errno == EINTR);
            if (count > 0) {
                {
                    std::lock_guard<std::mutex> guard(handle->mutex);
                    if (handle->waited) handle->post_exit_bytes += static_cast<size_t>(count);
                }
                jbyteArray output = env->NewByteArray(static_cast<jsize>(count));
                if (output != nullptr) env->SetByteArrayRegion(output, 0, static_cast<jsize>(count), bytes.data());
                CloseLocal(master); CloseLocal(wake); return output;
            }
            if (count == 0 || errno == EIO) { CloseLocal(master); CloseLocal(wake); return nullptr; }
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                const std::string error = ErrnoMessage("PTY read"); CloseLocal(master); CloseLocal(wake); ThrowIo(env, error); return nullptr;
            }
        }
        if (descriptors[1].revents != 0) { CloseLocal(master); CloseLocal(wake); return nullptr; }
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeWrite(JNIEnv* env, jclass, jlong id, jbyteArray input) {
    if (input == nullptr) { Throw(env, "java/lang/IllegalArgumentException", "PTY input is null."); return 0; }
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > kMaximumWrite) { Throw(env, "java/lang/IllegalArgumentException", "PTY input must contain 1..4096 bytes."); return 0; }
    const auto handle = FindHandle(env, id);
    if (!handle) return 0;
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, bytes.data());
    if (env->ExceptionCheck()) return 0;
    int master = -1, wake = -1;
    { std::lock_guard<std::mutex> guard(handle->mutex); master = DuplicateLocked(handle->master_fd); wake = DuplicateLocked(handle->wake_read_fd); }
    if (master < 0 || wake < 0) { CloseLocal(master); CloseLocal(wake); ThrowIo(env, "PTY write descriptors are closed."); return 0; }
    size_t offset = 0;
    while (offset < bytes.size()) {
        struct pollfd descriptors[2] = {{master, POLLOUT | POLLHUP | POLLERR, 0}, {wake, POLLIN | POLLHUP | POLLERR, 0}};
        int result;
        do { result = poll(descriptors, 2, -1); } while (result < 0 && errno == EINTR);
        if (result < 0 || descriptors[1].revents != 0) break;
        if ((descriptors[0].revents & POLLOUT) == 0) break;
        ssize_t count;
        do { count = write(master, bytes.data() + offset, bytes.size() - offset); } while (count < 0 && errno == EINTR);
        if (count > 0) offset += static_cast<size_t>(count);
        else if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK) break;
    }
    CloseLocal(master); CloseLocal(wake);
    if (offset != bytes.size()) { ThrowIo(env, "PTY write was cancelled or incomplete."); return 0; }
    return static_cast<jint>(offset);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeResize(JNIEnv* env, jclass, jlong id, jint columns, jint rows) {
    if (columns < kMinimumCells || columns > kMaximumCells || rows < kMinimumCells || rows > kMaximumCells) {
        Throw(env, "java/lang/IllegalArgumentException", "PTY dimensions must remain in 16..400."); return;
    }
    const auto handle = FindHandle(env, id);
    if (!handle) return;
    std::lock_guard<std::mutex> guard(handle->mutex);
    if (handle->master_fd < 0 || handle->waited) { ThrowIo(env, "PTY is no longer active."); return; }
    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(columns); size.ws_row = static_cast<unsigned short>(rows);
    // TIOCSWINSZ itself delivers SIGWINCH to the kernel-held foreground process group.
    if (ioctl(handle->master_fd, TIOCSWINSZ, &size) != 0) ThrowIo(env, ErrnoMessage("TIOCSWINSZ"));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeSignalOwned(JNIEnv* env, jclass, jlong id, jint signal_number) {
    const auto handle = FindHandle(env, id);
    if (!handle) return;
    if (signal_number == SIGINT || signal_number == SIGQUIT || signal_number == SIGTSTP) {
        std::lock_guard<std::mutex> guard(handle->mutex);
        if (handle->master_fd < 0 || handle->waited || ioctl(handle->master_fd, TIOCSIG, signal_number) != 0) {
            ThrowIo(env, "TIOCSIG could not signal the owned PTY foreground group.");
        }
        return;
    }
    if (signal_number != SIGHUP && signal_number != SIGTERM && signal_number != SIGCONT) {
        Throw(env, "java/lang/IllegalArgumentException", "Signal is not in the fixed PTY allowlist."); return;
    }
    if (!RequestRootSignal(handle, signal_number, false)) ThrowIo(env, "Owned native root signal could not be delivered.");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeTerminateRoot(JNIEnv* env, jclass, jlong id) {
    const auto handle = FindHandle(env, id);
    if (!handle) return JNI_FALSE;
    return RequestRootSignal(handle, SIGKILL, true) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeWaitForExit(JNIEnv* env, jclass, jlong id) {
    const auto handle = FindHandle(env, id);
    if (!handle) return nullptr;
    const ExitResult exit = WaitForRoot(env, handle);
    if (env->ExceptionCheck()) return nullptr;
    jint values[4] = {exit.kind, exit.code, handle->child_pid, 1};
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kkkhs_pocketrun_PtyNative_nativeClose(JNIEnv*, jclass, jlong id) {
    std::shared_ptr<PtyHandle> handle;
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        const auto iterator = g_registry.find(id);
        if (iterator == g_registry.end()) return JNI_TRUE;  // Idempotent after confirmed removal.
        handle = iterator->second;
    }
    if (!CloseHandle(handle)) return JNI_FALSE;  // Keep registry ownership for a bounded retry.
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        const auto iterator = g_registry.find(id);
        if (iterator != g_registry.end() && iterator->second == handle) g_registry.erase(iterator);
    }
    return JNI_TRUE;
}

#ifndef NDEBUG
// Instrumentation-only namespace; no PTY entry point or release export is changed.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_kkkhs_pocketrun_Chapter9HoldTestNative_nativePidfdOpen(JNIEnv* env, jclass, jint pid) {
    if (pid <= 1 || pid == getpid()) {
        Throw(env, "java/lang/IllegalArgumentException", "Test pidfd requires a non-self process PID."); return -1;
    }
    const int fd = MoveAboveStdio(static_cast<int>(syscall(__NR_pidfd_open, pid, 0U)));
    if (fd < 0) ThrowIo(env, ErrnoMessage("test pidfd_open"));
    return fd;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kkkhs_pocketrun_Chapter9HoldTestNative_nativePidfdSendSignal(
    JNIEnv* env, jclass, jint fd, jint signal_number) {
    if (fd < 3 || (signal_number != SIGSTOP && signal_number != SIGCONT)) {
        Throw(env, "java/lang/IllegalArgumentException", "Test pidfd only allows STOP/CONT on an owned descriptor."); return JNI_FALSE;
    }
    if (syscall(__NR_pidfd_send_signal, fd, signal_number, nullptr, 0U) == 0) return JNI_TRUE;
    if (errno != ESRCH) ThrowIo(env, ErrnoMessage("test pidfd_send_signal"));
    return JNI_FALSE;  // Caller may interpret ESRCH as gone only after proving the original identity.
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kkkhs_pocketrun_Chapter9HoldTestNative_nativePidfdClose(JNIEnv* env, jclass, jint fd) {
    if (fd < 3) {
        Throw(env, "java/lang/IllegalArgumentException", "Test pidfd descriptor is invalid."); return;
    }
    // The test relinquishes ownership before this one-shot close; never retry a reused FD after EINTR.
    if (syscall(SYS_close, fd) != 0) ThrowIo(env, ErrnoMessage("test pidfd close"));
}
#endif
