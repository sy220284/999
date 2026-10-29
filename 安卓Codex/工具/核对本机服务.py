#!/usr/bin/env python3
"""在临时源码副本按实际顺序组合补丁，核对服务契约/中文入口；不构建或运行目标。"""
import argparse,ast,hashlib,importlib.util,json,re,subprocess,sys,tempfile,tomllib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--parser-path',required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1];native=r/'运行核心';sys.path.insert(0,a.parser_path)
from tree_sitter import Parser,Language
import tree_sitter_rust
rust=Parser(Language(tree_sitter_rust.language()))
spec=importlib.util.spec_from_file_location('cli_patch',native/'应用CLI补丁.py');patch=importlib.util.module_from_spec(spec);spec.loader.exec_module(patch)
manifest=json.loads((native/'安卓服务补丁来源.json').read_text())
patches=[native/x for x in ['安卓依赖适配.patch','安卓用户验证.patch','安卓CLI差异确认.patch','安卓本机服务.patch','安卓诊断连接.patch','安卓远程执行状态.patch','安卓Unix共享核心.patch','安卓执行链补齐.patch']]
paths={'codex-rs/Cargo.lock'}
for item in patches:
 paths.update(re.findall(r'^\+\+\+ b/(.+)$',item.read_text(),re.M))
with tempfile.TemporaryDirectory() as temporary:
 root=Path(temporary)
 for name in paths:
  target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((a.source/name).read_bytes())
 for item in patches[:2]:subprocess.run(['git','apply',str(item)],cwd=root,check=True)
 assert patch.apply(root)=='applied'
 assert patch.apply(root,native/'安卓本机服务.patch',native/'安卓服务补丁来源.json')=='applied'
 assert patch.apply(root,native/'安卓本机服务.patch',native/'安卓服务补丁来源.json')=='already_applied'
 for diff,record in [('安卓诊断连接.patch','安卓诊断补丁来源.json'),('安卓远程执行状态.patch','安卓远程执行补丁来源.json'),('安卓Unix共享核心.patch','安卓Unix补丁来源.json'),('安卓执行链补齐.patch','安卓执行链补丁来源.json')]:
  assert patch.apply(root,native/diff,native/record)=='applied'
  assert patch.apply(root,native/diff,native/record)=='already_applied'
 parsed=0
 for name in sorted(paths):
  target=root/name
  if name.endswith('.rs'):assert not rust.parse(target.read_bytes()).root_node.has_error,name;parsed+=1
  elif name.endswith('.toml') or name.endswith('.lock'):tomllib.loads(target.read_text())
 lib=(root/'codex-rs/responses-api-proxy/src/lib.rs').read_text()
 assert lib.index('!local_client_authorized(request.headers()')<lib.index('if http_shutdown')
 assert lib.index('body.len() > limit')<lib.index('.post(config.upstream_url.clone())')
 assert 'ConstantTimeEq' in lib and 'header.field.equiv("Origin")' in lib
 latest=(root/'codex-rs/chatgpt/src/apply_command.rs').read_text()
 assert latest.index('reviewed.as_slice() == diff.as_bytes()')<latest.index('apply_diff_from_task(task_response, cwd).await')
 assert 'conflicts_with = "expected_diff_file"' in latest
 lock=tomllib.loads((root/'codex-rs/Cargo.lock').read_text())
 package=next(x for x in lock['package']if x['name']=='codex-responses-api-proxy');assert 'subtle' in package['dependencies']
 assert next(x for x in lock['package']if x['name']=='subtle')['version']=='2.6.1'
 print(f'组合八份补丁通过；{parsed}份Rust语法、TOML/Cargo.lock解析及现有依赖增量一致。')
def read(name):return (r/name).read_text()
manager=read('app/src/main/java/cn/codex/android/NativeEndpoints.kt')
for term in ['CoroutineStart.LAZY','startReserved','stopOwned','lease.owner==owner','lease.id','AUTH_UPDATED','process.pollExit()','v.authorize(process.pid)','verification?.authorize(process.pid)','cleanupProcess(lease)','lease.payload.fill(0)','lease.localToken.fill(0)','daemonConnection(config:ApiConfig)']:
 assert term in manager,term
control=(a.source/'codex-rs/tcp-tunnel/src/lib.rs').read_text();assert '"AUTH_UPDATED"' in control and '"LISTENING {}"' in control
for kind in ['PROXY','TUNNEL','EXECUTOR','DAEMON']:assert kind in read('core/src/main/kotlin/cn/codex/core/original/NativeEndpointPlans.kt')
ui=read('app/src/main/java/cn/codex/android/OriginalCoreScreen.kt')
for page in ['NativeEndpointsScreen(host)','OriginalAgentsScreen(host)','AndroidUpdateScreen']:assert page in ui,page
factory=read('app/src/main/java/cn/codex/android/AndroidOriginalEngineFactory.kt')
for term in ['Proxy.NO_PROXY','followRedirects(false)','Authorization','ArrayBlockingQueue<ByteArray>(64)','32L*1024*1024','wire::close','daemon.id']:assert term in factory,term
update=read('app/src/main/java/cn/codex/android/AndroidAppUpdates.kt')
for term in ['GET_SIGNING_CERTIFICATES','candidate.longVersionCode>installed.longVersionCode','signers(installed.signingInfo)==signers(candidate.signingInfo)','canRequestPackageInstalls','Intent.ACTION_VIEW','approved.sha256']:assert term in update,term
assert 'REQUEST_INSTALL_PACKAGES' in read('app/src/main/AndroidManifest.xml')
assert 'app.originalCore.cli.stop()' in read('app/src/main/java/cn/codex/android/ConfigurationExecutionService.kt')
print('本机与远程服务/确切资源所有权/本机WS客户端/逐字节应用/签名更新/中文入口静态核对通过；非类型、链接或运行证明。')

# 新路径必须连接原版实现；以下仍是静态契约检查，不替代类型或运行测试。
helper=read('app/src/main/java/cn/codex/android/AndroidDebugCore.kt')
for term in ['v.authorize(child.pid)','verification.authorize(child.pid)','NativeEndpointPlans.daemon(hash,0,false,true)']:
 assert term in helper,term
assert 'CODEX_ANDROID_DEBUG_CONNECTION_FILE' in helper
runner=read('app/src/main/java/cn/codex/android/OriginalCliRunner.kt')
assert 'helper.start();environment.putAll(helper.environment)' in runner and 'debug?.close()' in runner
for path in ['app-server-test-client/src/lib.rs','exec-server/src/remote.rs','exec-server/src/remote/direct.rs','cli/src/exec_server_credential.rs','app-server-transport/src/transport/unix_socket.rs']:
 assert any(x['path']=='codex-rs/'+path for metadata in ['安卓诊断补丁来源.json','安卓远程执行补丁来源.json','安卓Unix补丁来源.json']for x in json.loads((native/metadata).read_text())['files'])
sandbox=read('运行核心/补充源码/android_project_sandbox.c')
for term in ['if(abi<6)return fail','PR_SET_NO_NEW_PRIVS','__NR_landlock_restrict_self','SECCOMP_MODE_FILTER','__NR_socket','__NR_connect','__NR_io_uring_setup','__NR_ptrace','__NR_pidfd_getfd','SECCOMP_RET_KILL_PROCESS','if(filter())return fail','execvp(argv[command]']:
 assert term in sandbox,term
assert sandbox.index('if(filter())return fail')<sandbox.index('execvp(argv[command]')
subprocess.run(['bash','-n',str(native/'构建安卓沙箱.sh')],check=True)
worktree=read('运行核心/补充源码/android_worktree_cli.rs')
for term in ['WorktreeManager','default_worktree_base','manager.create','manager.bind_thread','manager.remove']:assert term in worktree,term
assert 'androidToolRoots[message]=root' in read('app/src/main/java/cn/codex/android/OriginalCoreHost.kt')
print('诊断确切PID/原版工作树库/远程执行连接事件与顺序凭证确认/Unix控制代理/条件式沙箱拒绝路径已静态核对，未运行目标。')

# 公共启动附加限制不等于完整核心隔离；保持原版SandboxType，未知输入拒绝。
spawn=read('运行核心/补充源码/android_restricted_spawn.rs')
for term in ['std::env::var_os("CODEX_ANDROID_COMMAND_SANDBOX")','request.sandbox == SandboxType::None','request.inherited_fds.as_slice().is_empty()','request.arg0.is_none()','cwd.starts_with(&root)','android_command_environment','ChildFds::Inherited(&[])']:
 assert term in spawn,term
assert 'CODEX_ACCESS_TOKEN' not in spawn.split('#[cfg(all(test',1)[0]
for term in ['checkDependency(lease)','it.dependency==lease.id','CODEX_ANDROID_FORWARD_AUTH','owned?.id']:
 assert term in manager,term
assert 'authorization: Option<http::HeaderValue>' in (native/'安卓执行链补齐.patch').read_text()
assert 'header.set_sensitive(true)' in (native/'安卓执行链补齐.patch').read_text()
worktrees=read('core/src/main/kotlin/cn/codex/core/original/OriginalWorktrees.kt')
for term in ['WorktreeConfiguration.from(api.config(source).jsonObject)','state.value.preview===approved','configuration==approved.configuration','config(api)==configuration','managedRows']:
 assert term in worktrees,term
assert 'projectRoot(OriginalCodexClient(selectionConnection)' in read('app/src/main/java/cn/codex/android/OriginalCoreHost.kt')
assert 'beforeThreadId requires a non-null sectionId' in (a.source/'codex-rs/app-server/src/request_processors/thread_processor.rs').read_text()
assert 'section_position' in read('app/src/main/java/cn/codex/android/OriginalCoreHost.kt')
print('工作树配置/批准预览、真实段落排序、本机目标认证与依赖退出、公共命令附加限制的静态契约通过；未进行类型/设备/真实服务验收。')
