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
