# 合同审查执行要求

先确认代表方、偏好与必要交易背景；从材料提取事实，缺口普通对话集中询问，参考intake-questions.md。主Agent按需用legal-statute-retrieval取条文、legal-web-search定位官方信息、web-page-fetch读真实URL，不假设Skill直接互调。摘要不代替原文；商业基线不冒充强制法律，金额与日期参数不补造。

## 规则查询方法

使用lib/rules_client.py，连接由脚本管理，模型不拼HTTP请求、不覆盖环境、不索取供应商Key。

| 方法 | 参数 | 作用 |
|---|---|---|
| list-catalog | 无 | 取类型、立场、父类目录 |
| list-types | 无 | 提取真实类型名称 |
| load-by-types | 一个JSON类型名称数组 | 加载选定规则，核对父子类型与立场 |
| get-baseline | 准确类型名 | 获取审核基线 |
| get-clause | 类型名、条款名 | 提取已加载规则的条款 |

例如：python3 lib/rules_client.py load-by-types '["通用采购合同"]'。真实目录名称先查询，不造合同类型。

统一输出status/operation/data/warnings/trace_id；状态ok/no_result/error/unavailable，data保留真实目录/立场/要点/条款/合并规则/选定与解析类型。缺字段不补造，未知结构报错，不按无结果。失败说明未完成，不用本地CSV自动替代；预览与保存样例解析不算真实查询，多类型Mock不证明真实合并行为。

## 修订交付

用户要求改文件时读取docx-revisor说明：read → 真实key构造edits → validate → save新版本 → inspect修订/批注，再核对位置、内容及Word/WPS显示。保存仍待核验，普通生成或意见清单不等于带痕文件。权限隔离/原子性不因脚本存在就宣称通过。

精准修订中商务条款和非必要改动仅批注的例外保留，不强迫每条批注改正文。投资方专项发现仅在用户要求文件修订时衔接，不把清单冒充Word。版本只依据可核对文件与对话确定；原件保留，真实来源按SP放交付说明或实际支持的批注，失败不称完成。

目录立场、多类型合并、空结果、错误及修订效果仍需目标环境验证；离线通过不替代真实验收。
