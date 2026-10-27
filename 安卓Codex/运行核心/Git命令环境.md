# 原版 Git 命令环境

原版 `worktree/src/git.rs`、`git-utils/src/info.rs` 直接创建 Git 子进程，不能用 JGit 页面替代。`构建安卓Git.sh` 固定官方 v2.56.0 的提交 `a018953688f1b10bddf91bff8747068f5f4746a4`；本阶段仅编写脚本，未执行构建、目标程序或设备测试。

后续在 Linux x86_64 上以 NDK 28.2.13676358/API 35 为两个 ABI 分别构建，使用 Android 公开 libz、上游默认 SHA-1 碰撞检测和内置 SHA-256。启用 PIE 与 16 KB 最大页对齐；`llvm-readelf` 输出仅供后续核对，不代替真机验收。Git 2.56 的可选 Rust 功能当前关闭，保留 C 内置命令。

产物安装为 `nativeLibraryDir/libcodex_git.so`，应用创建名称 `git`、`git-upload-pack`、`git-receive-pack`、`git-upload-archive` 的符号链接，不复制 ELF 到可写目录。安装缺少程序时删除旧链接。环境提供真实命令路径与空模板目录；原版 core 继承策略通过独立 `shell_environment_policy.set.GIT_*` 参数携带无敏感工具位置，保留默认密钥过滤。

## 两条 Git 路径的范围

| 路径 | 已写入代码 | 明确未包含 |
|---|---|---|
| Git 页面/JGit | 本地常用操作、HTTPS clone/fetch/pull/push、仓库来源隔离的加密凭证 | SSH、多个目标、原版 CLI 凭证桥 |
| 原生 Git CLI | 本地内置命令的固定源码构建入口、安装命名、环境与 worktree/Unicode 测试源码 | curl/OpenSSL HTTP(S) helper、SSH、Perl/Tcl/Python 扩展与其脚本安装；当前没有实际产物 |

内置命令不代表每条 Git 子命令可用：外部脚本、编辑器、pager、hooks、签名工具及远程传输仍取决于手机工具环境。失败按真实退出码显示，不返回模拟成功。不将 CLI 网络缺口标成 Android 永久限制。

## 恢复构建后的验证

1. 分 ABI 构建并检查 ELF 解释器/公开依赖/PIE/16 KB LOAD。
2. 安装后验证别名通过 SELinux 的真实执行、非 ASCII 路径与参数、SHA-1/SHA-256 init/status/diff/commit、原版 ghost snapshot 和 worktree。
3. 验证进程树停止、后台中断后不重放，缺少 helper 的错误可见。
4. 后续补 curl/OpenSSL 固定源、系统信任证书与 Git credential helper 的 Keystore 交互；不能把令牌放进 URL、命令参数或环境列表。
5. Git 使用 GPL-2.0；正式分发原生产物前应交付准确对应源码、改动和构建入口，保留第三方许可。本阶段未生成或分发 Git 二进制。
