# Qingyan Word test adapter

当前状态：脚本完成清言入口及响应适配。2026-09-21测试环境中，初始化、修复、校验、提取文字、转PDF和渲染已真实通过；编译使用初始化服务自己返回的`Program.cs`连续两次仍返回服务器错误，所以从零生成Word的主链未通过。随包模板、参考资料、C#/OpenXML生成和质量门禁保留。Word服务源码、部署与运行隔离由研发负责，本Skill不是服务端源码。

## 实际执行方式

服务连接由本Skill执行脚本管理；正常任务不覆盖地址或连接配置。失败时说明未完成，联系平台维护人员排查。
- 自动追加 `/legal/auto_file/api/legal-dotnet|legal-validator|legal-docx`。详情见 [contract.md](contract.md)。不再读取外部服务Token、`.docx-service-capability.json`或旧服务地址变量。
- 后端注入`X-Dept-Api-Key`。Skill不持有Key，也不自动重试编译/文件写入，避免重复操作。
- 每个任务使用独立工作目录；示例：`python3 <Skill目录>/scripts/docx_http.py --work-dir <任务目录> init --fresh`。后续build、render等使用同一工作目录。Python启动器按平台实际环境选择。
- 全局`--dry-run`放在子命令之前，只输出配置，不发请求或执行DLL。它不证明接口可用。

## 真实测试状态与剩余条件

1. `init`已确认支持空POST，返回项目ZIP，内含`Program.cs`、工程文件和`result.json`。
2. `compile`字段`source`已按文档发送，但当前连续返回服务器错误；在该接口回归通过前，不得声称`build`可用，也不得用其他生成路线冒充本链路成功。
3. `fix/validate/extract-text`已确认返回结果ZIP；`render`已确认接受`file`与`dpi`并返回PDF、页面图片和`result.json`；`convert-to-pdf`已确认直接返回PDF。
4. Skill运行环境仍须具备.NET 8 Runtime及隔离执行条件；原客户端在运行环境执行编译返回的程序集，并非编译服务直接生成Word。若部署行为不同须改协议，不猜测。
5. 字体/渲染结果、原件不覆盖、任务目录隔离、输出文件读取与发送仍需平台验证。
6. 正文结构检查、渲染和逐页视觉检查缺一不可。使用平台实际可用视觉能力，不假定特定工具名存在。没有能力时标记质量检查未完成，不静默跳过、不配置任意外部视觉能力。
7. 平台现有`docx`同名Skill不得直接覆盖；上传测试时确认使用此版本。必要时由产品与平台确定唯一名称，并同步主Agent路由。

## 交付状态

- JSON/文本解统一外层；二进制原样接收；HTTP200不等于成功，检查`X-Docx-Command-Passed`及ZIP内部报告。
- 编译通过不等于Word生成；build通过不等于法律准确或版式通过。平台发送真实文件后才能声明已交付，不把本地路径当下载链接。
- 缺事实的初稿可以保留明确待补字段，不补造事实；模板TODO、示例残留仍需清理。
- 来源链接、核验状态、免责声明放对话交付说明，必要法律引用保留正文；`check_docx.py`保持现有检查脚本。文件失败可交付文本初稿，但要明确Word未完成。
