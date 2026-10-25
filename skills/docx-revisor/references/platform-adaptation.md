# Qingyan DXE test adapter

当前状态：专业方法与protocol2保留，客户端已适配清言测试入口，真实validate/save/inspect及修订效果未验收。本目录不包含DXE服务端源码，不声明权限隔离或整批原子写入已经验证。

## 活跃路线

唯一活跃路线：read → 构造edits → validate → save → inspect revisions/comments → 检查文件 → 平台发送。其他操作实现/说明保留作参考但CLI不启用，不混用原word_revisor.py/plan.json路线。DXE执行在服务端，AM只需Python调用与文件读取，不依赖第四步本地.NET8生成链。

## 已适配内容

服务连接由本Skill执行脚本管理；正常任务不覆盖地址或连接配置。失败时说明未完成，联系平台维护人员排查。
- 删除旧外部服务Token/OPENCLAW_STATE_DIR/旧域名机制，不使用旧OSS服务；后端注入X-Dept-Api-Key，Skill不持有Key。
- JSON中发送docx_b64与filename，保留原编辑字段。代理统一外层status/message/result/rid；result必须是对象，内部ok必须true。未知结构按失败处理，不默认按零修改成功。
- .dxemeta保存doc_hash、part、本地SHA256；.dxevalidated绑定同一原件与edits的预检。文件/计划变化使记录失效；save必须有成功预检，发送前消耗记录，不自动重试。警告仍须人工/Agent复核，不因有记录就认为全部正确。
- validate由脚本发送protocol2/edits，不增加未经支持的字段；save发送读取获得的expect_hash。上游冲突保护仍须真实验证。
- 保存到新的独立任务目录/版本文件名；拒绝原件覆盖/已有输出。完整下载或解码、验证DOCX核心结构后才发布文件。保留edits方便复查，原件只读。
- out_url仅下载批准的HTTPS域名（默认publicapi.chatglm.cn、最新样例中的autoglm-agent.aminer.cn），不携带凭证、不跟随重定向。新产物域名由平台确认后可用全局--artifact-host追加；带鉴权的下载入口尚未适配，不拼假链接、不复用供应商Key。

## 保留的专业规则

真实视图key定位；原文连续片段；最小改动；正文为可签署条款，风险理由放批注；保留他人已有痕迹；不能用重新生成或标红冒充Track Changes。save前预检，save后修订/批注核验。修订计数不一定等于edit数量：替换可展开插入/删除，原件也可能已有修订，不能机械比较相等。

## 必须真实验收

1. 清言上游兼容protocol2编辑字段，validate响应含warnings列表、failed_edit等实际字段，inspect revisions/comments的计数/警告结构与原客户端一致。字段来源见contract.md；缺字段不猜测。
2. 最新研发MD read/save仅提供成功结构，save样例修改数为0，不证明实际带痕操作。需真实替换+批注、空/多匹配、hash冲突、整批中一项失败。
3. out_url能从AM访问，或out_b64能够解码；Word/WPS能打开、接受/拒绝修订，批注位置准确，他人轨迹保留。
4. 上游整批原子性、文件/租户权限隔离与授权范围由平台服务维护人员确认并测试；客户端不替代服务端安全机制。
5. 完整合同路径使用规则库+检索+DXE，再检查正文/版式/下载。第四步docx render可辅助渲染但需其服务可用；没有视觉能力或页面未检查就标注未完成，不悄悄删除检查。

失败只交付已完成的对话内建议并说明修订文件未完成；保存超时先排查服务调用记录，不自动反复写入。成功文件仍是待复核稿，不等于可直接签署。
