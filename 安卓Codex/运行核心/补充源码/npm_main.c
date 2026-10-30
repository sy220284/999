/* npm/npx interpreter dispatch: execute installed Node, never an asset or writable ELF. */
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
int main(int argc,char **argv){
    char executable[PATH_MAX],script[PATH_MAX];const char *home=getenv("CODEX_ANDROID_NODE_HOME");
    if(!home || !*home){fputs("当前包没有准备npm脚本\n",stderr);return 78;}
    ssize_t n=readlink("/proc/self/exe",executable,sizeof(executable)-1);
    if(n<=0 || n>=(ssize_t)sizeof(executable)-1)return 78;executable[n]=0;
    char *slash=strrchr(executable,'/');if(!slash)return 78;*slash=0;
    if(strlen(executable)+sizeof("/libcodex_node.so")>=sizeof(executable))return 78;
    strcat(executable,"/libcodex_node.so");
    const char *name=strrchr(argv[0],'/');name=name?name+1:argv[0];
    int size=snprintf(script,sizeof(script),"%s/bin/%s-cli.js",home,strncmp(name,"npx",3)==0?"npx":"npm");
    if(size<0 || size>=(int)sizeof(script))return 78;
    char **arguments=calloc((size_t)argc+2,sizeof(char*));if(!arguments)return 78;
    arguments[0]=executable;arguments[1]=script;for(int i=1;i<argc;i++)arguments[i+1]=argv[i];
    execv(executable,arguments);fputs("Node启动失败，请核查内置程序\n",stderr);free(arguments);return 78;
}
