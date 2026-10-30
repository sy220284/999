#!/usr/bin/env python3
"""源码对照/语法/供应记录检查，不执行任何构建、下载或设备调用。"""
import argparse,ast,csv,hashlib,json,re,subprocess,sys
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--parser-path',required=True);p.add_argument('--base',default='5ec1392431c8554b6915b30165cef3b8dd4fa2d2');a=p.parse_args()
r=Path(__file__).resolve().parents[1];repo=r.parent
sys.path.insert(0,a.parser_path)
from tree_sitter import Language,Parser
import tree_sitter_kotlin,tree_sitter_rust,tree_sitter_c
parsers={'.kt':Parser(Language(tree_sitter_kotlin.language())),'.rs':Parser(Language(tree_sitter_rust.language())),'.c':Parser(Language(tree_sitter_c.language()))}
tracked=subprocess.check_output(['git','diff','--name-only','-z',a.base,'--','安卓Codex'],cwd=repo).decode().split('\0')
untracked=subprocess.check_output(['git','ls-files','-z','--others','--exclude-standard','--','安卓Codex'],cwd=repo).decode().split('\0')
counts={suffix:0 for suffix in parsers}
for name in sorted(set(tracked+untracked)-{''}):
 path=repo/name
 if path.suffix in parsers:
  data=path.read_bytes()
  if path.suffix=='.c':data=re.sub(rb'\b(?:JNIEXPORT|JNICALL)\b',b' ',data) # 标准JNI声明宏，语法解析不展开头文件
  assert not parsers[path.suffix].parse(data).root_node.has_error,name+' 语法错误'
  counts[path.suffix]+=1
assert counts['.kt']>=1,'变更语法扫描范围为空'
if a.base=='5ec1392431c8554b6915b30165cef3b8dd4fa2d2':assert counts['.kt']>=30 and counts['.rs']>=1 and counts['.c']>=2
for path in (r/'工具').glob('*.py'):ast.parse(path.read_text(),filename=str(path))
for path in (r/'运行核心').glob('*.py'):ast.parse(path.read_text(),filename=str(path))
for name in ['构建安卓核心.sh','构建安卓Git.sh','构建安卓语言环境.sh','构建安卓网络工具.sh']:
 subprocess.run(['bash','-n',str(r/'运行核心'/name)],check=True)
ET.parse(r/'app/src/main/AndroidManifest.xml')
subprocess.run(['git','apply','--check',str(r/'运行核心/安卓用户验证.patch')],cwd=a.source,check=True)
# 原始来源始终只读；补丁与宿主入口同时存在，不把按钮计作原生实现。
def read(path):return (r/path).read_text()
host=read('app/src/main/java/cn/codex/android/OriginalCoreHost.kt')
factory=read('app/src/main/java/cn/codex/android/OriginalEngineFactory.kt')
provider=read('app/src/main/java/cn/codex/android/OriginalAndroidVerification.kt')
bridge=read('运行核心/补充源码/android_user_verification.rs')
for token in ['CODEX_ANDROID_VERIFICATION_SOCKET','verifier.authorize(process.pid)','verifier.close()']:assert token in factory,token
for token in ['BIOMETRIC_STRONG','CryptoObject']:assert token in read('app/src/main/java/cn/codex/android/OriginalBiometricPrompts.kt'),token
for token in ['userAuthenticationValidityDurationSeconds==-1','isUserAuthenticationRequirementEnforcedBySecureHardware','SHA256withECDSA','secp256r1']:assert token in provider,token
for token in ['SO_PEERCRED','guard.check()?','stream.read','self.namespace.label']:assert token in bridge,token
assert 'fun verifyElicitation' in host and 'put("content",proof)' in host
rtc=read('app/src/main/java/cn/codex/android/OriginalWebRtcAudio.kt')
for token in ['"oai-events"','ordered=true','setMicrophoneMute(true)','setAudioRecording(false)','setAudioPlayout(false)','onWebRtcAudioRecordError','onWebRtcAudioTrackError','withTimeout(15_000)','restoreAudioMode()']:assert token in rtc,token
assert 'webRtc.answer(' in host and 'webRtc.close()' in host
for token in ['GIT_SSL_CAINFO','SSL_CERT_FILE','CURL_CA_BUNDLE']:assert token in read('app/src/main/java/cn/codex/android/AndroidTrustBundle.kt')
assert 'StrictHostKeyChecking=yes' in read('app/src/main/java/cn/codex/android/AndroidSshTrust.kt')
assert 'NINJA_SOURCE_REVISION' in read('运行核心/准备V8源码.py') and 'pinned-official-source-bootstrap' in read('运行核心/准备V8源码.py')
assert 'requestAttestation = false' in read('core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt') or 'put("requestAttestation", false)' in read('core/src/main/kotlin/cn/codex/core/original/OriginalCodexClient.kt')
rows=json.loads((r/'app/src/main/assets/原版协议进度.json').read_text())+json.loads((r/'app/src/main/assets/实验协议进度.json').read_text())
assert len(rows)==277 and len({(x['kind'],x['method'])for x in rows})==277
assert sum(x['phase']=='本机验证运行验收'for x in rows)==5
assert next(x for x in rows if x['method']=='thread/realtime/sdp')['phase']=='WebRTC运行验收'
assert sum('外部提供者' in x['status']for x in rows)==2
assert sum('有限复杂' in x['status']for x in rows)==1
inventory=json.loads((r/'文档/源码包模块索引.json').read_text())
for item in inventory['members']+[inventory['cli_source']]:
 assert hashlib.sha256((a.source/item['path']).read_bytes()).hexdigest()==item['sha256'],item['path']
cli=(a.source/'codex-rs/cli/src/main.rs').read_text().split('enum Subcommand {',1)[1].split('\n}',1)[0]
names=set(re.findall(r'^    ([A-Z]\w*)(?:\([^\n]*\))?,\s*$',cli,re.M))
clirows=list(csv.DictReader((r/'文档/独立CLI功能对照.csv').open()))
assert names=={x['variant']for x in clirows} and len(names)==len(clirows)
assert len(json.loads((r/'app/src/main/assets/非协议能力进度.json').read_text()))==13
print('变更语法：',counts,'；Python/XML/Shell/验证补丁静态检查通过。')
print('277协议、163源码模块证据、31 CLI顶层命令、13非协议范围一致；这不是类型/链接/运行证明。')
