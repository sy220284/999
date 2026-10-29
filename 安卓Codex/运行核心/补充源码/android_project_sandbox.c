#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* UAPI ABI6固定布局；更低内核不退化为普通执行。 */
#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#define __NR_landlock_add_rule 445
#define __NR_landlock_restrict_self 446
#endif
struct policy_rules { uint64_t fs, net, scoped; };
struct path_rule { uint64_t access; int32_t parent; } __attribute__((packed));
#define FS_ALL ((1ULL << 16) - 1)
#define READ_EXEC ((1ULL << 0) | (1ULL << 2) | (1ULL << 3))
#define WRITE_TREE (FS_ALL & ~((1ULL << 6) | (1ULL << 9) | (1ULL << 11) | (1ULL << 15)))
#define DENY_NR(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,(n),0,1), BPF_STMT(BPF_RET | BPF_K,SECCOMP_RET_ERRNO | EPERM)
static int fail(const char *reason) { fprintf(stderr,"sandbox refused: %s\n",reason); return 78; }
static int path(int rules,const char *name,uint64_t access,int optional) {
    int fd=open(name,O_PATH|O_CLOEXEC);
    if(fd<0)return optional && errno==ENOENT ? 0 : -1;
    struct stat status; if(fstat(fd,&status)) { close(fd);return -1; }
    if(!S_ISDIR(status.st_mode))access &= (1ULL<<0)|(1ULL<<1)|(1ULL<<2)|(1ULL<<14)|(1ULL<<15);
    struct path_rule rule={.access=access,.parent=fd};
    int result=(int)syscall(__NR_landlock_add_rule,rules,1,&rule,0);close(fd);return result;
}
static int filter(void) {
#if defined(__aarch64__)
#define EXPECTED_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define EXPECTED_ARCH AUDIT_ARCH_X86_64
#else
#error unsupported Android ABI
#endif
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,EXPECTED_ARCH,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        /* x86 x32号段不能绕过原生ABI过滤。 */
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,0x40000000,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        DENY_NR(__NR_socket), DENY_NR(__NR_connect), DENY_NR(__NR_bind),
        DENY_NR(__NR_sendto), DENY_NR(__NR_sendmsg), DENY_NR(__NR_sendmmsg),
        DENY_NR(__NR_ptrace), DENY_NR(__NR_process_vm_readv), DENY_NR(__NR_process_vm_writev),
        DENY_NR(__NR_mount), DENY_NR(__NR_umount2), DENY_NR(__NR_pivot_root),
        DENY_NR(__NR_setns), DENY_NR(__NR_unshare), DENY_NR(__NR_open_by_handle_at),
        DENY_NR(__NR_bpf), DENY_NR(__NR_perf_event_open),
        DENY_NR(__NR_io_uring_setup), DENY_NR(__NR_io_uring_register), DENY_NR(__NR_io_uring_enter),
        DENY_NR(__NR_pidfd_getfd),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program={.len=(unsigned short)(sizeof(code)/sizeof(code[0])),.filter=code};
    return prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program,0,0);
}
int main(int argc,char **argv) {
    /* 关闭所有非stdio继承描述符，避免已有文件/套接字绕过新规则。 */
    struct rlimit descriptors;if(getrlimit(RLIMIT_NOFILE,&descriptors))return fail("descriptor budget unavailable");
    if(descriptors.rlim_cur>1048576)return fail("descriptor budget exceeds limit");
    for(int fd=3;fd<(int)descriptors.rlim_cur;fd++)close(fd);
    int abi=(int)syscall(__NR_landlock_create_ruleset,NULL,0,1);
    if(argc==2 && !strcmp(argv[1],"probe")) { printf("{\"landlockAbi\":%d,\"minimumAbi\":6,\"established\":false}\n",abi);return abi>=6 ? 0 : 78; }
    if(argc<6 || strcmp(argv[1],"run") || strcmp(argv[4],"--"))return fail("invalid command");
    if(abi<6)return fail("Landlock ABI6 unavailable; command was not executed");
    char *project=realpath(argv[2],NULL),*programs=realpath(argv[3],NULL);
    if(!project || !programs || !strcmp(project,"/") || !strcmp(programs,"/"))return fail("invalid roots");
    struct stat status;if(stat(project,&status) || !S_ISDIR(status.st_mode))return fail("project is not a directory");
    if(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0))return fail("no_new_privs unavailable");
    prctl(PR_SET_DUMPABLE,0,0,0,0);
    struct policy_rules policy={.fs=FS_ALL,.net=3,.scoped=3};
    int rules=(int)syscall(__NR_landlock_create_ruleset,&policy,sizeof(policy),0);
    if(rules<0)return fail("ruleset creation failed");
    if(path(rules,project,WRITE_TREE,0) || path(rules,programs,READ_EXEC,0) ||
       path(rules,"/system",READ_EXEC,0) || path(rules,"/apex",READ_EXEC,0) || path(rules,"/vendor",READ_EXEC,1) ||
       path(rules,"/dev/null",(1ULL<<1)|(1ULL<<2),0) || path(rules,"/dev/urandom",1ULL<<2,0))return fail("path policy failed");
    if(chdir(project) || syscall(__NR_landlock_restrict_self,rules,0))return fail("filesystem restriction failed");
    close(rules);free(project);free(programs);
    if(filter())return fail("syscall policy failed");
    puts("{\"sandboxReady\":true,\"network\":\"denied\",\"projectWrite\":true}");fflush(stdout);
    execv(argv[5],&argv[5]);return fail("program could not start");
}
