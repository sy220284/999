#!/usr/bin/env python3
"""核对固定CLI业务链和差异确认补丁；只解析源文件，无构建/目标执行。"""
import argparse,subprocess,tempfile,json,csv,hashlib,re,sys
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--parser-path',required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1];sys.path.insert(0,a.parser_path)
from tree_sitter import Parser,Language
import tree_sitter_kotlin,tree_sitter_rust,tree_sitter_c
kt=Parser(Language(tree_sitter_kotlin.language()));rust=Parser(Language(tree_sitter_rust.language()));c=Parser(Language(tree_sitter_c.language()))
files=['core/src/main/kotlin/cn/codex/core/original/OriginalCliCommands.kt','core/src/test/kotlin/cn/codex/core/original/OriginalCliCommandsTest.kt']+[f'app/src/main/java/cn/codex/android/{x}.kt'for x in ['OriginalCliRunner','OriginalCliController','OriginalCliScreen','NativeCoreProcess','OriginalCoreHost','OriginalCoreScreen','OriginalMcpScreen']]+[f'app/src/androidTest/java/cn/codex/android/{x}.kt'for x in ['OriginalCliControllerTest','NativeCliProcessTest']]
for path in files:assert not kt.parse((r/path).read_bytes()).root_node.has_error,path
cpp=(r/'app/src/main/cpp/process.c').read_bytes();cpp=re.sub(rb'\b(?:JNIEXPORT|JNICALL)\b',b' ',cpp);assert not c.parse(cpp).root_node.has_error
assert b'WNOWAIT' in cpp and b'waitid(P_PID' in cpp
with tempfile.TemporaryDirectory()as temporary:
 root=Path(temporary)
 for name in ['codex-rs/cloud-tasks/src/cli.rs','codex-rs/cloud-tasks/src/lib.rs']:
  p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes((a.source/name).read_bytes())
 subprocess.run(['git','apply','--unidiff-zero',str(r/'运行核心/安卓CLI差异确认.patch')],cwd=root,check=True)
 for p in root.rglob('*.rs'):assert not rust.parse(p.read_bytes()).root_node.has_error,str(p)
 text=(root/'codex-rs/cloud-tasks/src/lib.rs').read_text()
 assert 'expected == selected.diff.as_bytes()' in text and 'run_environments_command().await' in text
 assert text.index('expected == selected.diff.as_bytes()')<text.index('let outcome = codex_cloud_tasks_client::CloudBackend::apply_task(')
source=(a.source/'codex-rs/cloud-tasks/src/cli.rs').read_text()
for command in ['Exec','Status','List','Apply','Diff']:assert re.search(r'\b'+command+r'\(',source)
plans=(r/files[0]).read_text();controller=(r/'app/src/main/java/cn/codex/android/OriginalCliController.kt').read_text();runner=(r/'app/src/main/java/cn/codex/android/OriginalCliRunner.kt').read_text()
for name in ['cloudEnvironments','cloudCreate','cloudList','cloudStatus','cloudDiff','cloudApply','doctor','rollouts','nfsMigration','completion','execPolicy','stdioToUds']:assert f'fun {name}(' in plans
for token in ['approved.token','preview=null','require(tasks.size<=20)','all.size<=200','lifecycle.finish(ticket,confirmed)']:assert token in controller,token
for token in ['vault.authorize(child.pid)','--expected-diff-file','child.pollExit()','withTimeout(plan.timeoutMs)','NonCancellable','vault.close()','expected?.delete()']:assert token in runner,token
assert ' cli) package=codex-cli;' in (r/'运行核心/构建安卓核心.sh').read_text()
assert 'OriginalCliCommands.stdioToUds(socket)' in (r/'app/src/main/java/cn/codex/android/OriginalMcpScreen.kt').read_text()
assert 'OriginalCliScreen(host)' in (r/'app/src/main/java/cn/codex/android/OriginalCoreScreen.kt').read_text()
assert 'cli.state.value.busy' in (r/'app/src/main/java/cn/codex/android/OriginalCoreHost.kt').read_text()
print('CLI环境/云任务五操作/冻结差异/迁移/诊断/策略/补全、凭证/退出/取消和中文入口静态核对通过；11 Kotlin、JNI C和补丁后2 Rust语法通过，未编译或运行目标程序。')
