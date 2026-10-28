/* Private Android CLI frontend for the official embedded CPython library. */
#include <Python.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
static void checked(PyStatus value,PyConfig *config){if(PyStatus_Exception(value)){PyConfig_Clear(config);if(PyStatus_IsExit(value))exit(value.exitcode);fputs("Python初始化失败，请核查内置运行环境\n",stderr);exit(78);}}
int main(int argc,char **argv){
    const char *home=getenv("CODEX_ANDROID_PYTHON_HOME");
    if(!home || !*home){fputs("当前包没有准备Python标准库\n",stderr);return 78;}
    char executable[PATH_MAX];ssize_t n=readlink("/proc/self/exe",executable,sizeof(executable)-1);
    if(n<=0 || n>=(ssize_t)sizeof(executable)-1)return 78;executable[n]=0;
    PyConfig config;PyConfig_InitPythonConfig(&config);
    checked(PyConfig_SetBytesArgv(&config,argc,argv),&config);
    checked(PyConfig_SetBytesString(&config,&config.home,home),&config);
    /* A real executable prevents Android's embedded-only logcat stream replacement. */
    checked(PyConfig_SetBytesString(&config,&config.executable,executable),&config);
    checked(Py_InitializeFromConfig(&config),&config);PyConfig_Clear(&config);
    return Py_RunMain();
}
