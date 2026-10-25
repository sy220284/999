# 平台网页读取契约 v0.3

## 调用方式

使用scripts/client.py调用清言的serp-moe网页读取代理。实际请求地址按“服务根地址＋`/legal/auto_search/api/glm/r/`＋目标URL”拼接，例如目标为`https://chatglm.cn`时，请求路径为`/legal/auto_search/api/glm/r/https://chatglm.cn`。调用方不索取供应商Key；缺执行权限时返回unavailable。

## 输入

| 参数 | 处理 | 上游支持 |
|---|---|---|
| --url | 必填，公开HTTP/HTTPS网页，无凭据、非内网、非附件或文件下载地址，允许域名检查 | 只传url |
| --allow-host | 追加具体域名；默认gov.cn及子域，按本任务来源授权 | 本地，不传上游 |
| --timeout-seconds | 默认60，1–60；客户端等待限制，不能保证远端取消 | 本地 |
| --max-bytes | 默认2MiB、最多2MiB；限制客户端读取响应大小 | 本地，不证明上游下载/解压限制 |
| --max-chars | 默认50000、最多100000；本地正文截断，不减少已消耗token | 本地 |
| --base-url | 默认清言测试环境根地址；预发/正式环境可由平台环境变量覆盖 | 清言代理配置 |
| --response-file | 保存响应离线解析，跳过远程调用/DNS，不认证来源 | 不调用 |
| --dry-run | 显示计划，不发起网络请求 | 不调用 |

旧候选extract_mode不再作为执行参数；不能把本地限制说成上游支持的字段。

## 输出映射

兼容清言统一外层`status/message/result/rid`；`result`可为网页对象、既有page_reader对象或正文字符串。未知包装、非成功状态、空正文报错。新地址的真实返回字段仍需目标平台联调确认。

| 输出 | 来源/规则 |
|---|---|
| status | ok/partial/blocked/unavailable/error；ok只证明正文可读 |
| requested_url / final_url | 输入及真实返回url；缺少最终URL报错，不用输入冒充 |
| http_status / content_type | 上游明确页面字段，未知null，不用网关200推定页面成功 |
| title / text | 原标题，优先清洗html，无html时用text；缺标题警告、不补造 |
| publisher / published_at | 初始null，主Agent另行核对正文标示，不根据域名/时间推定 |
| upstream_time / time_semantics | publishedTime或publish_time原值，语义unknown |
| truncated | 本地截断或上游明确标记；false不保证远端全文完整 |
| retrieved_at | 本次处理时间，离线时不代表远端抓取时刻 |
| warnings / trace_id | 不完整、时间/安全待核实；真实rid/request_id，没有就空 |
| token_usage | meta.usage或data.usage中的tokens，不重复累加 |
| execution_mode | live/saved_response，离线解析不能当实际读取 |

页面或代理401/403、40001及安全阻断→blocked；429、服务/环境失败→unavailable；未知结构、空正文、明显错误页→error；正文截断→partial。

## 安全与验收边界

本地拒绝回环、私网、链路本地及云元数据IP、非HTTP协议、非80/443端口、URL内凭据。输入/最终域名检查；live做DNS检查；参数数组调用CLI，shell=False，不执行网页HTML。

本技能只读取公开网页正文。`pfiles.chatglm.cn`及其子域属于文件存储地址，不能通过`--allow-host`放行；路径解码后以常见文档、表格、演示文稿或压缩包后缀结尾的地址也会在发起请求前返回blocked。此限制能覆盖明确文件地址，但无后缀下载地址不能仅凭URL全部识别，不宣称可过滤所有文件。

这不是完整SSRF保证：上游与本地DNS可能不同；检查不能阻止重绑定；最终URL事后检查不能阻止此前内网访问。逐跳验证、远端大小与解压限制仍需平台确认。政法域名限定减少暴露面但不能代替平台防护，不通过curl/浏览器绕过。

离线用保存响应覆盖格式、时间陷阱、清洗、错误页、截断和安全阻断。法律Agent真实验收需法院文章、政府政策正文、缺失页面、正常跳转、授权/配额和原文匹配。内网恶意跳转仅由平台在获授权隔离环境测试，不让业务Agent探测。
