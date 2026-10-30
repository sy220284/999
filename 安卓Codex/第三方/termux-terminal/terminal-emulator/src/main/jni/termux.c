#include <dirent.h>
#include <errno.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns, .ws_xpixel = (unsigned short) (columns * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height)};
    ioctl(ptm, TIOCSWINSZ, &sz);

    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 0) { close(ptm); return throw_runtime_exception(env, "Cannot read descriptor limit"); }
    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        if (setsid() < 0) _exit(126);

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(126);

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

#ifdef __NR_close_range
        if (syscall(__NR_close_range, 3U, ~0U, 0) < 0)
#endif
        { for (int fd = 3; fd < max_fd; ++fd) close(fd); }
        if (chdir(cwd) != 0) _exit(126);
        execve(cmd, argv, envp);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

static char* byte_string(JNIEnv* env, jbyteArray bytes) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    char* value = malloc((size_t)size + 1);
    if (!value) { throw_runtime_exception(env, "Cannot allocate UTF-8 string"); return NULL; }
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte*)value);
    if ((*env)->ExceptionCheck(env)) { free(value); return NULL; }
    value[size] = 0;
    return value;
}
JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocessBytes(
    JNIEnv* env, jclass TERMUX_UNUSED(clazz), jbyteArray cmd, jbyteArray cwd,
    jobjectArray args, jobjectArray envVars, jintArray processIdArray,
    jint rows, jint columns, jint cell_width, jint cell_height) {
    int argc = (*env)->GetArrayLength(env, args), envc = (*env)->GetArrayLength(env, envVars);
    char** argv = calloc((size_t)argc + 1, sizeof(char*));
    char** envp = calloc((size_t)envc + 1, sizeof(char*));
    char* command = NULL; char* directory = NULL;
    int procId = 0, result = -1;
    if (!argv || !envp) { throw_runtime_exception(env, "Cannot allocate process arguments"); goto done; }
    for (int i = 0; i < argc; i++) {
        jbyteArray item = (jbyteArray)(*env)->GetObjectArrayElement(env, args, i);
        argv[i] = byte_string(env, item); (*env)->DeleteLocalRef(env, item);
        if (!argv[i]) goto done;
    }
    for (int i = 0; i < envc; i++) {
        jbyteArray item = (jbyteArray)(*env)->GetObjectArrayElement(env, envVars, i);
        envp[i] = byte_string(env, item); (*env)->DeleteLocalRef(env, item);
        if (!envp[i]) goto done;
    }
    command = byte_string(env, cmd); if (!command) goto done;
    directory = byte_string(env, cwd); if (!directory) goto done;
    result = create_subprocess(env, command, directory, argv, envp, &procId, rows, columns, cell_width, cell_height);
    if (!(*env)->ExceptionCheck(env)) { jint value = procId; (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &value); }
done:
    if (argv) { for (int i = 0; i < argc; i++) free(argv[i]); free(argv); }
    if (envp) { for (int i = 0; i < envc; i++) free(envp[i]); free(envp); }
    free(command); free(directory);
    return result;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols, .ws_xpixel = (unsigned short) (cols * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return 127;
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}
