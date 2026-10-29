#define _GNU_SOURCE
#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <termios.h>
#include <sys/ioctl.h>

JNIEXPORT jintArray JNICALL Java_cn_codex_android_NativeShell_spawn(JNIEnv *env, jobject self, jstring command, jstring directory) {
    (void)self;
    const char *cmd = (*env)->GetStringUTFChars(env, command, 0);
    const char *cwd = (*env)->GetStringUTFChars(env, directory, 0);
    int output[2] = {-1,-1}, ready[2] = {-1,-1};
    pid_t pid = -1;
    if (pipe2(output, O_CLOEXEC) == 0 && pipe2(ready, O_CLOEXEC) == 0) {
        pid = fork();
        if (pid == 0) {
            // fork 后只执行异步信号安全的系统调用，不接触 JVM。
            close(output[0]); close(ready[0]);
            if (setsid() < 0 || chdir(cwd) != 0) _exit(126);
            int nullfd = open("/dev/null", O_RDONLY);
            if (nullfd < 0 || dup2(nullfd, STDIN_FILENO) < 0 || dup2(output[1], STDOUT_FILENO) < 0 || dup2(output[1], STDERR_FILENO) < 0) _exit(126);
            close(nullfd); close(output[1]);
            char ok = 1;
            if (write(ready[1], &ok, 1) != 1) _exit(126);
            close(ready[1]);
            char *const args[] = {"sh", "-c", (char *)cmd, 0};
            execv("/system/bin/sh", args);
            _exit(127);
        }
    }
    if (output[1] >= 0) close(output[1]);
    if (ready[1] >= 0) close(ready[1]);
    char ok = 0;
    if (pid > 0 && read(ready[0], &ok, 1) != 1) { kill(-pid, SIGKILL); kill(pid, SIGKILL); waitpid(pid, 0, 0); pid = -1; }
    if (ready[0] >= 0) close(ready[0]);
    (*env)->ReleaseStringUTFChars(env, command, cmd);
    (*env)->ReleaseStringUTFChars(env, directory, cwd);
    if (pid < 0) { if (output[0] >= 0) close(output[0]); (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "无法创建本机命令进程"); return 0; }
    jint values[2] = {(jint)pid, output[0]};
    jintArray result = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}
JNIEXPORT jint JNICALL Java_cn_codex_android_NativeShell_poll(JNIEnv *env, jobject self, jint pid) {
    (void)env; (void)self;
    int status;
    pid_t done;
    do { done = waitpid(pid, &status, WNOHANG); } while (done < 0 && errno == EINTR);
    if (done == 0) return -1000;
    if (done < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
}
JNIEXPORT void JNICALL Java_cn_codex_android_NativeShell_killGroup(JNIEnv *env, jobject self, jint pid) {
    (void)env; (void)self;
    kill(-pid, SIGKILL);
    // SIGKILL 后回收直接子进程；若已被 poll 回收，waitpid 返回 ECHILD。
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}

/* Android 交互终端：/dev/ptmx -> /dev/pts/N，独立会话、前台控制终端。 */
JNIEXPORT jintArray JNICALL Java_cn_codex_android_NativePty_openSession(JNIEnv *env, jobject self, jstring directory, jint rows, jint columns) {
    (void)self;
    const char *cwd = (*env)->GetStringUTFChars(env, directory, 0);
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        (*env)->ReleaseStringUTFChars(env, directory, cwd);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "无法打开安卓 PTY");
        return NULL;
    }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
        (*env)->ReleaseStringUTFChars(env, directory, cwd);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "无法确定 PTY 设备");
        return NULL;
    }
    struct winsize size = { (unsigned short)rows, (unsigned short)columns, 0, 0 };
    pid_t pid = fork();
    if (pid == 0) {
        if (setsid() < 0) _exit(126);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0 || ioctl(slave, TIOCSWINSZ, &size) < 0) _exit(126);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) _exit(126);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(cwd) != 0) _exit(126);
        setenv("TERM", "dumb", 1);
        char *const argv[] = {"sh", "-i", NULL};
        execv("/system/bin/sh", argv);
        _exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, directory, cwd);
    if (pid < 0) {
        close(master);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "无法创建 PTY 进程");
        return NULL;
    }
    ioctl(master, TIOCSWINSZ, &size);
    jint result[2] = {(jint)pid, (jint)master};
    jintArray array = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, array, 0, 2, result);
    return array;
}

JNIEXPORT jboolean JNICALL Java_cn_codex_android_NativePty_resizeFd(JNIEnv *env, jobject self, jint fd, jint pid, jint rows, jint columns) {
    (void)env; (void)self;
    struct winsize size = {(unsigned short)rows, (unsigned short)columns, 0, 0};
    if (ioctl(fd, TIOCSWINSZ, &size) != 0) return JNI_FALSE;
    kill(-pid, SIGWINCH);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_cn_codex_android_NativePty_terminateGroup(JNIEnv *env, jobject self, jint pid) {
    (void)env; (void)self;
    kill(-pid, SIGKILL);
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}

// 原版核心需要互相独立的 stdin/stdout/stderr。fork 后只做系统调用和 execve。
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <sys/prctl.h>
#include <poll.h>
#include <time.h>
static char *core_bytes(JNIEnv *env, jbyteArray value) {
    jsize count = (*env)->GetArrayLength(env, value);
    char *result = malloc((size_t)count + 1);
    if (!result) return NULL;
    (*env)->GetByteArrayRegion(env, value, 0, count, (jbyte *)result);
    result[count] = 0;
    return result;
}
static char **core_strings(JNIEnv *env, jobjectArray values) {
    jsize count = (*env)->GetArrayLength(env, values);
    char **result = calloc((size_t)count + 1, sizeof(char *));
    if (!result) return NULL;
    for (jsize i = 0; i < count; i++) {
        jbyteArray value = (*env)->GetObjectArrayElement(env, values, i);
        result[i] = core_bytes(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (!result[i] || (*env)->ExceptionCheck(env)) { for (jsize j = 0; j <= i; j++) free(result[j]); free(result); return NULL; }
    }
    return result;
}
static void core_free(char **values) { if (values) { for (int i = 0; values[i]; i++) free(values[i]); free(values); } }
// exec 确认有单调时钟期限，避免 JNI 阻塞让协程启动超时无法收尾。
static int core_wait_exec(int fd, int timeout_ms) {
    struct timespec stamp;
    if (clock_gettime(CLOCK_MONOTONIC, &stamp) != 0) return -1;
    long long deadline = (long long)stamp.tv_sec * 1000 + stamp.tv_nsec / 1000000 + timeout_ms;
    for (;;) {
        if (clock_gettime(CLOCK_MONOTONIC, &stamp) != 0) return -1;
        long long remaining = deadline - ((long long)stamp.tv_sec * 1000 + stamp.tv_nsec / 1000000);
        if (remaining <= 0) { errno = ETIMEDOUT; return 0; }
        struct pollfd watched = {.fd = fd, .events = POLLIN | POLLHUP};
        int ready = poll(&watched, 1, (int)remaining);
        if (ready > 0) return ready;
        if (ready == 0) { errno = ETIMEDOUT; return 0; }
        if (errno != EINTR) return -1;
    }
}
JNIEXPORT jintArray JNICALL Java_cn_codex_android_NativeCoreBridge_spawn(JNIEnv *env, jclass cls, jobjectArray arguments, jobjectArray environment, jbyteArray directory) {
    (void)cls;
    char **argv = core_strings(env, arguments), **envp = core_strings(env, environment);
    char *cwd = core_bytes(env, directory);
    int pipes[4][2] = {{-1,-1},{-1,-1},{-1,-1},{-1,-1}};
    pid_t pid = -1;
    int failure = ENOMEM;
    if (argv && argv[0] && envp && cwd) {
        int count = 0;
        for (; count < 4; count++) if (pipe2(pipes[count], O_CLOEXEC) < 0) break;
        if (count == 4) {
            pid = fork();
            if (pid == 0) {
                close(pipes[0][1]); close(pipes[1][0]); close(pipes[2][0]); close(pipes[3][0]);
                // 仅影响核心进程：收留脱离中间父进程的工具，退出时可按进程树清理。
                (void)prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
                if (setsid() >= 0 && chdir(cwd) == 0 && dup2(pipes[0][0], STDIN_FILENO) >= 0 && dup2(pipes[1][1], STDOUT_FILENO) >= 0 && dup2(pipes[2][1], STDERR_FILENO) >= 0) {
                    close(pipes[0][0]); close(pipes[1][1]); close(pipes[2][1]);
                    execve(argv[0], argv, envp);
                }
                int code = errno;
                (void)write(pipes[3][1], &code, sizeof(code));
                _exit(127);
            }
            if (pid < 0) failure = errno;
        } else failure = errno;
    }
    core_free(argv); core_free(envp);
    free(cwd);
    if (pid > 0) {
        close(pipes[0][0]); pipes[0][0] = -1;
        close(pipes[1][1]); pipes[1][1] = -1;
        close(pipes[2][1]); pipes[2][1] = -1;
        close(pipes[3][1]); pipes[3][1] = -1;
        int child_error = 0;
        ssize_t got = -1;
        if (core_wait_exec(pipes[3][0], 10000) > 0) {
            do { got = read(pipes[3][0], &child_error, sizeof(child_error)); } while (got < 0 && errno == EINTR);
        } else child_error = errno;
        close(pipes[3][0]); pipes[3][0] = -1;
        if (got == 0) {
            jint data[4] = {pid, pipes[0][1], pipes[1][0], pipes[2][0]};
            jintArray result = (*env)->NewIntArray(env, 4);
            if (result) { (*env)->SetIntArrayRegion(env, result, 0, 4, data); return result; }
        }
        failure = child_error ? child_error : EIO;
        kill(-pid, SIGKILL); kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    }
    for (int i = 0; i < 4; i++) for (int j = 0; j < 2; j++) if (pipes[i][j] >= 0) close(pipes[i][j]);
    if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), strerror(failure));
    return NULL;
}
typedef struct { pid_t pid; unsigned long long started; } CoreProcessIdentity;
static int core_identity(pid_t pid, pid_t *parent, unsigned long long *started) {
    char path[64], buffer[4096];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *file = fopen(path, "r");
    if (!file) return 0;
    char *line = fgets(buffer, sizeof(buffer), file);
    fclose(file);
    if (!line) return 0;
    // comm 允许空格/括号，从最后一个右括号后解析第 3..22 字段。
    char *tail = strrchr(buffer, ')');
    if (!tail) return 0;
    char *save = NULL, *token = strtok_r(tail + 1, " ", &save);
    for (int field = 3; token; field++, token = strtok_r(NULL, " ", &save)) {
        if (field == 4) *parent = (pid_t)strtol(token, NULL, 10);
        if (field == 22) { *started = strtoull(token, NULL, 10); return 1; }
    }
    return 0;
}
static int core_same(CoreProcessIdentity identity) {
    pid_t parent = 0; unsigned long long started = 0;
    return core_identity(identity.pid, &parent, &started) && started == identity.started;
}
static void core_kill_tree(pid_t pid) {
    CoreProcessIdentity *nodes = calloc(4096, sizeof(CoreProcessIdentity));
    pid_t parent = 0; unsigned long long started = 0;
    if (nodes && core_identity(pid, &parent, &started)) {
        nodes[0] = (CoreProcessIdentity){pid, started};
        size_t count = 1;
        kill(pid, SIGSTOP);
        for (int pass = 0; pass < 32 && count < 4096; pass++) {
            size_t before = count;
            DIR *directory = opendir("/proc");
            if (!directory) break;
            struct dirent *entry;
            while ((entry = readdir(directory)) && count < 4096) {
                pid_t child = (pid_t)strtol(entry->d_name, NULL, 10);
                if (child <= 0 || !core_identity(child, &parent, &started)) continue;
                int known = 0, descendant = 0;
                for (size_t i = 0; i < count; i++) { if (nodes[i].pid == child) known = 1; if (nodes[i].pid == parent) descendant = 1; }
                if (!known && descendant) {
                    CoreProcessIdentity identity = {child, started};
                    if (core_same(identity)) { kill(child, SIGSTOP); nodes[count++] = identity; }
                }
            }
            closedir(directory);
            if (count == before) break;
        }
        // 子工具可能自行创建独立进程组，不能仅凭 kill(-enginePid) 清理。
        for (size_t i = count; i > 1; i--) if (core_same(nodes[i-1])) kill(nodes[i-1].pid, SIGKILL);
        if (core_same(nodes[0])) { kill(-pid, SIGKILL); kill(pid, SIGKILL); }
    } else { kill(-pid, SIGKILL); kill(pid, SIGKILL); }
    free(nodes);
}
// WNOWAIT保留直接子进程身份到close；轮询退出后PID不会被复用后再误杀。
JNIEXPORT jint JNICALL Java_cn_codex_android_NativeCoreBridge_poll(JNIEnv *env, jclass cls, jint pid) {
    (void)cls; siginfo_t info = {0}; int result;
    do { result = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT); } while (result < 0 && errno == EINTR);
    if (result < 0) {
        jclass error = (*env)->FindClass(env, "java/io/IOException");
        if (error) (*env)->ThrowNew(env, error, "Cannot observe owned child process");
        return (-2147483647 - 1);
    }
    if (!info.si_pid) return (-2147483647 - 1);
    return info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status;
}
JNIEXPORT void JNICALL Java_cn_codex_android_NativeCoreBridge_terminate(JNIEnv *env, jclass cls, jint pid) {
    (void)env; (void)cls;
    core_kill_tree(pid);
    while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
}

JNIEXPORT void JNICALL Java_cn_codex_android_TerminalProcessTree_terminate(JNIEnv *env, jclass cls, jint pid) {
    (void)env; (void)cls;
    core_kill_tree(pid);
    // TerminalSession 的 waiter 回收根进程，不能在这里抢走退出状态。
}
