# DXE input/output mapping

## 输入文件

使用scripts/dxe_client.py的四项操作，不直接拼接HTTP请求。连接由执行脚本管理，调用者不传供应商Key、不修改服务配置。

输入为JSON。源文件读为docx_b64（base64）+filename；不把本地路径当远程URL。请求体上限60MiB，编码会增大体积，原文件约45MiB就可能超过限制；最终按完整JSON字节数判断。当前客户端也限制响应/下载大小，较大out_b64需另评估文件传输。

## Four operations

| 方法 | 执行器处理的输入 | 成功结果及边界 |
|---|---|---|
| read | 本地file、part | 真实view、doc_hash，定位用key |
| validate | 原件、edits数组，编辑协议由脚本组装 | ok/warnings，警告须复核 |
| save | 原件、edits、author、明确out | 新文件、修订/批注计数；0/0不能证明非空修改完成 |
| inspect | 原件及what=revisions/comments | 明确整数计数，缺字段不按0成功 |

清言统一外层`{status,message,result,rid}`：status为整数0，result对象内ok=true才继续；保留rid用于排查。任一未知结构/业务失败/HTTP失败停止。inspect计数字段缺失不能默认按0成功。

## Editing protocol

edits.json是顶层数组，每项action+key；五动作replace/insert/delete/comment/format。old_string/new_string、anchor/text、批注范围与attrs遵守SKILL.md编辑说明。protocol、author、expect_hash由客户端组装，不放进edits.json。客户端仅做基本结构拒绝，精确匹配/语义组合仍由上游validate执行，不能假装已在本地全部校验。

## Local safety state (not new backend fields)

- `<原件>.dxemeta`：read成功产生doc_hash/part/local_sha256/trace_id，不上传local_sha256。没有读取记录或文件变化时禁止validate/save。
- `<原件>.dxevalidated`：validate产生本地源文件+edits指纹，保存前核对，发送前消耗。修改edits后须重新validate。不是上游令牌，不上传，不能证明服务端原子性或租户权限。
- validate warnings必须是列表，缺字段按协议待确认处理；有警告仍需复核。save返回两个非负整数计数，若非空计划实际修改/批注均0则不发布产物；计数不是法律/位置正确性的证明。
- save不覆盖原件/既有输出。响应文件验证DOCX后用临时文件完整发布，失败不发布半个文件。save后元数据清理及edits保留沿用原操作，重做时先read/validate；超时或下载失败先排查上游结果，不盲目重发。

## Download and errors

优先out_b64，否则下载out_url。默认只允许已说明的HTTPS产物域名；其他域名需平台批准并显式配置--artifact-host，不自动相信任意返回地址，不附供应商Key。文件发送通过平台真实发送能力完成，本地路径不等于用户可访问URL。

401/403是权限/白名单配置；404是入口/操作未开放；413是体积；502或超时为服务异常。保存超时属于结果不确定，需要研发/平台调用日志确认是否已成功；本版不自动重试。业务失败保留code/failed_edit/rid，不打印原始HTTP正文。
