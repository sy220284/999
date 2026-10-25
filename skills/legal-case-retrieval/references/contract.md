# 案例检索调用规范


## 输入
### 方法选择

| 方法 | 作用 | 关键输入 |
|---|---|---|
| semantic | 寻找与事实/争点相似的案例 | query |
| keyword | 根据关键词、案号、标题或筛选定位案例 | 至少一个有效查询条件；filters.authoritative_only决定是否限权威检索 |
| detail | 获取裁判详情 | 优先record_id；无ID时用实际案号；source_type保留真实检索路径 |

权威属性和全文只能依据实际返回，不从摘要推定。

```json
{
  "operation": "semantic",
  "query": "员工持欠条追索拖欠工资",
  "record_id": null,
  "case_number": null,
  "title": null,
  "source_type": null,
  "filters": {
    "causes": [],
    "courts": [],
    "court_level": null,
    "provinces": [],
    "city": null,
    "document_types": [],
    "case_category": null,
    "judgment_date_start": null,
    "judgment_date_end": null,
    "authoritative_only": false,
    "authority_sources": []
  },
  "search_mode": "AND",
  "top_k": 10
}
```

| 字段 | 类型 | 必填 | 取值与默认值 |
|---|---|---:|---|
| `operation` | string | 是 | `semantic`、`keyword`、`detail` |
| `query` | string | 条件必填 | 语义检索必填；关键词检索至少有一个查询字段 |
| `record_id` | string/null | 条件必填 | 详情查询优先使用搜索结果ID |
| `case_number` | string/null | 否 | 案号；无ID的详情查询可使用 |
| `title` | string/null | 否 | 案件名称关键词 |
| `source_type` | string/null | 否 | 详情查询可传`ptal`普通案例或`qwal`权威案例；不传时根据检索路径判断 |
| `filters.causes` | string[] | 否 | 使用完整案由名称，如“买卖合同纠纷” |
| `filters.courts` | string[] | 否 | 法院名称 |
| `filters.court_level` | string/null | 否 | `supreme`、`high`、`intermediate`、`basic`、`specialized`、`unknown` |
| `filters.provinces` | string[] | 否 | 省级区域；语义检索只允许一个省份，关键词检索可传多个 |
| `filters.city` | string/null | 否 | 城市 |
| `filters.document_types` | string[] | 否 | `judgment`、`ruling`、`mediation`、`decision` |
| `filters.case_category` | string/null | 否 | `civil`、`criminal`、`administrative`、`enforcement`、`state_compensation`、`other` |
| 裁判日期区间 | string/null | 否 | `YYYY-MM-DD` |
| `filters.authoritative_only` | boolean | 否 | 默认`false` |
| `filters.authority_sources` | string[] | 否 | 供应商支持的权威来源标准值；未知值不得假映射 |
| `search_mode` | string | 否 | `AND`或`OR`，默认`AND` |
| `top_k` | integer | 否 | 1–50，默认10 |

脚本已适配普通案例、语义案例和新版权威案例的不同字段与返回结构。普通关键词检索出现服务器错误时最多重试一次；重试仍失败应返回不可用，不得改写为“没有案例”。

## 输出

```json
{
  "status": "ok",
  "operation": "detail",
  "records": [
    {
      "record_id": "",
      "case_name": "",
      "case_number": "",
      "court": "",
      "judgment_date": "",
      "cause": "",
      "case_category": "",
      "document_type": "",
      "authority_labels": [],
      "facts_summary": "",
      "reasoning_summary": "",
      "decision_summary": "",
      "full_text": "",
      "cited_laws": [],
      "source_provider": "",
      "source_url": "",
      "retrieved_at": "",
      "detail_verified": true
    }
  ],
  "warnings": [],
  "next_action": null,
  "trace_id": ""
}
```

`status`取值：`ok`、`no_result`、`partial`、`unavailable`、`error`。摘要字段必须由真实返回内容归纳；原始结果没有的信息保留空值。

### 统一状态的使用

| 状态 | 含义 | 主Agent处理 |
|---|---|---|
| `ok` | 请求成功且返回可用结果 | 继续分析；引用前仍检查`detail_verified` |
| `no_result` | 请求成功但无匹配结果 | 调整检索条件或说明未找到 |
| `partial` | 只有摘要、部分正文或部分筛选未执行 | 降低证据等级并展示警告 |
| `unavailable` | 执行器、权限或服务暂不可用 | 说明无法完成案例核验，不生成替代案例 |
| `error` | 请求或返回处理失败 | 不使用本次结果；按`warnings`判断是否可重试一次 |


## 执行方式

在本Skill目录运行脚本，提供统一业务JSON：

```bash
python3 scripts/client.py --input '业务JSON'
```

服务连接由脚本内置配置管理；正常任务不传环境地址、不覆盖连接参数、不修改脚本。无法访问时说明未完成，交由平台维护人员排查。调用者不提供供应商Key。检索后使用真实返回ID取详情，摘要不能代替原文。未知结构、空结果或失败遵守输出状态，不从模型记忆补齐。
