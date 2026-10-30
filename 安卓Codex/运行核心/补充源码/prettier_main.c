/* Installed Android frontend: use the real Node binary with an explicitly selected project formatter. */
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
int main(int argc,char **argv){
    const char *requested=getenv("CODEX_ANDROID_PRETTIER_SCRIPT");
    if(!requested || !*requested || argc<1 || argc>65535){fputs("formatter script unavailable\n",stderr);return 78;}
    char *script=realpath(requested,NULL);struct stat status;
    if(!script || stat(script,&status) || !S_ISREG(status.st_mode)){free(script);fputs("formatter script is not a regular file\n",stderr);return 78;}
    char node[PATH_MAX];ssize_t count=readlink("/proc/self/exe",node,sizeof(node)-1);
    if(count<=0 || count>=(ssize_t)sizeof(node)-1){free(script);return 78;}node[count]=0;
    char *slash=strrchr(node,'/');if(!slash){free(script);return 78;}*slash=0;
    if(strlen(node)+sizeof("/libcodex_node.so")>=sizeof(node)){free(script);return 78;}
    strcat(node,"/libcodex_node.so");
    char **arguments=calloc((size_t)argc+2,sizeof(char*));if(!arguments){free(script);return 78;}
    arguments[0]=node;arguments[1]=script;for(int i=1;i<argc;i++)arguments[i+1]=argv[i];
    execv(node,arguments);free(arguments);free(script);fputs("Node formatter failed to start\n",stderr);return 78;
}
