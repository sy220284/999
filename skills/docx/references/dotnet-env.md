# 宿主 .NET 8 Runtime 探测（dotnet-env）

`docx build` / `docx compile` 需要在宿主机执行编译服务返回的 net8.0 程序集，因此要求本机可用的 **.NET 8 Runtime**（只需 Runtime，不需要 SDK）。`docx_http.py` 按以下顺序探测，命中即用：

1. **环境变量 `DOCX_HOST_DOTNET`**：显式指定 dotnet 可执行文件完整路径时优先使用。
2. **PATH 上的 `dotnet`**：运行 `dotnet --list-runtimes`，输出包含 `Microsoft.NETCore.App 8.` 即可用。产品桌面端已把内置 runtime 目录前置到 PATH（含 `DOTNET_ROOT`），通常直接命中。
3. **`~/.dotnet/dotnet`**：dotnet-install 脚本的默认用户级安装位置。

## 探测命令（判断可用性）

```bash
dotnet --list-runtimes 2>/dev/null | grep "Microsoft.NETCore.App 8." || echo MISSING
```

Windows（产品内置 runtime 生效时无需手动检查）：

```powershell
dotnet --list-runtimes | Select-String "Microsoft.NETCore.App 8."
```

## 缺失时的处置

- **不要静默跳过 build**。向用户说明需要 .NET 8 Runtime，并给出安装指引：
  - macOS/Linux：`curl -sSL https://dot.net/v1/dotnet-install.sh | bash /dev/stdin --channel 8.0 --runtime dotnet`（安装到 `~/.dotnet`，随后设 `DOCX_HOST_DOTNET=~/.dotnet/dotnet`）；
  - Windows：从 https://dotnet.microsoft.com/download/dotnet/8.0 安装 “.NET Runtime 8.x”。
- 安装后重试 `docx build`；仍失败时把 `dotnet --list-runtimes` 的输出附在报告里。

## 注意

- 只在宿主执行文档生成程序集；**禁止** `dotnet run`、禁止在编译服务容器里执行任何程序。
- 编译本身发生在云端 `/legal-dotnet` 服务，宿主无需 SDK、无需 NuGet 还原。
