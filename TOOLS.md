# TOOLS.md - Local Notes

Skills define _how_ tools work. This file is for _your_ specifics — the stuff that's unique to your setup.

## What Goes Here

Things like:

- Camera names and locations
- SSH hosts and aliases
- Preferred voices for TTS
- Speaker/room names
- Device nicknames
- Anything environment-specific

## Examples

```markdown
### Cameras

- living-room → Main area, 180° wide angle
- front-door → Entrance, motion-triggered

### SSH

- home-server → 192.168.1.100, user: admin

### TTS

- Preferred voice: "Nova" (warm, slightly British)
- Default speaker: Kitchen HomePod
```

## Why Separate?

Skills are shared. Your setup is yours. Keeping them apart means you can update skills without losing your notes, and share skills without leaking your infrastructure.

---

## 环境升级记录 (2026-10-24)

- npm 12.2.1 装在用户空间 `~/.npm-global/bin`，已写入 `~/.bashrc` PATH 优先；系统 `/usr/bin/npm`(11.x) 仍在但被覆盖
- venv: `/home/z/.venv`，pip 26.2.2 / setuptools 84.2.1 / wheel 0.48.0
- 全局 npm 工具（`~/.npm-global`）：mermaid-cli 12.0.0、playwright 1.64.1、docx 9.14.0、pdf-lib 1.17.1、pptxgenjs 4.0.1
- puppeteer（mermaid-cli 依赖）Chrome 156 已下载至 `~/.cache/puppeteer`；npm 12 默认拦截安装脚本，已设 `allow-scripts=puppeteer`
- 无 root/sudo：apt、/usr/bin 下的 git(2.47.3)、node(24.21.0) 本体不可升级
- venv 内约 276 个 Python 库有新版本，未批量升级（存在 major 跳版风险），按需单个升级
- 工作区配置文件（TOOLS.md 等）归 root 所有，bash 直接写会 Permission denied，需用 Edit 工具修改
- 长耗时命令（npm 全局安装等）会触发 Bash 工具 504/会话中断：改用后台执行 `(...cmd > /tmp/xx.log 2>&1 &)`，再用 `pgrep -f` + 日志轮询判断进度；勿直接前台跑大安装

---

Add whatever helps you do your job. This is your cheat sheet.
