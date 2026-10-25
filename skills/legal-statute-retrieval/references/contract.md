# 法规检索调用规范


## 输入
### 方法选择

| 方法 | 作用 | 关键输入 |
|---|---|---|
| semantic | 根据自然语言事实/争点寻找候选法条 | query |
| search_article | 关键词检索条文 | query或脚本支持的精确筛选条件 |
| search_regulation | 定位法规 | query或law_name等有效条件 |
| article_detail | 取得条文原文 | 优先record_id，否则law_name＋article_number |
| regulation_detail | 取得法规详情与版本信息 | 优先record_id，否则law_name；历史适用可传refer_date |

以真实返回字段核对效力；未执行的筛选条件须展示warnings。

```json
{
  "operation": "semantic",
  "query": "劳动合同解除经济补偿",
  "record_id": null,
  "law_name": null,
  "article_number": null,
  "refer_date": null,
  "filters": {
    "validity_statuses": ["effective"],
    "effect_levels": [],
    "region": "CN",
    "publish_department": null,
    "publish_date_start": null,
    "publish_date_end": null,
    "effective_date_start": null,
    "effective_date_end": null
  },
  "search_mode": "AND",
  "top_k": 10
}
```

### 字段规则

| 字段 | 类型 | 必填 | 取值与默认值 |
|---|---|---:|---|
| `operation` | string | 是 | `semantic`、`search_article`、`search_regulation`、`article_detail`、`regulation_detail` |
| `query` | string | 条件必填 | 检索操作必填；详情操作可不填 |
| `record_id` | string/null | 否 | 详情查询优先使用上一步搜索返回的稳定记录编号 |
| `record_type` | string/null | 条件必填 | 使用`record_id`时必填，只能是`article`或`regulation`；必须沿用上一步返回值，不自行猜测 |
| `law_name` | string/null | 否 | 法规名称 |
| `article_number` | string/null | 条件必填 | 按条号详情查询时必填，例如“第四十七条” |
| `refer_date` | string/null | 否 | `YYYY-MM-DD`，表示待适用的历史时点；空值表示查询当前版本 |
| `filters.validity_statuses` | string[] | 否 | `effective`、`amended`、`repealed`、`not_yet_effective`、`unknown`；默认`["effective"]` |
| `filters.effect_levels` | string[] | 否 | `law`、`administrative_regulation`、`judicial_interpretation`、`department_rule`、`local_regulation`、`local_rule`、`other` |
| `filters.region` | string/null | 否 | 默认`CN`；地方规则可传省市名称或标准区域码，映射由适配器完成 |
| `filters.publish_department` | string/null | 否 | 发布机关 |
| 日期区间字段 | string/null | 否 | `YYYY-MM-DD` |
| `search_mode` | string | 否 | `AND`或`OR`，默认`AND`；仅关键词检索使用 |
| `top_k` | integer | 否 | 1–50，默认10 |

详情操作应优先携带上一步结果的`record_id`和`record_type`；不得把法规编号传给条文详情，也不得把条文编号传给法规详情。没有可靠编号类型时，具体条文改用法规全称和条号定位。

## 输出

```json
{
  "status": "ok",
  "operation": "article_detail",
  "query": "劳动合同法第四十七条",
  "records": [
    {
      "record_id": "provider-record-id",
      "record_type": "article",
      "law_name": "中华人民共和国劳动合同法",
      "article_number": "第四十七条",
      "text": "",
      "validity_status": "effective",
      "effect_level": "law",
      "publish_department": "",
      "publish_date": "",
      "effective_date": "",
      "region": "CN",
      "version_at": null,
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

`status`取值：`ok`、`no_result`、`partial`、`unavailable`、`error`。只有取得详情正文时，`detail_verified`才能为`true`。不得把供应商密钥、内部签名或原始报错栈返回模型。

### 统一状态的使用

| 状态 | 含义 | 主Agent处理 |
|---|---|---|
| `ok` | 请求成功且返回可用结果 | 继续分析；引用前仍检查`detail_verified` |
| `no_result` | 请求成功但无匹配结果 | 调整检索条件或说明未找到 |
| `partial` | 返回部分结果或部分过滤条件未执行 | 只使用已核验字段并展示警告 |
| `unavailable` | 执行器、权限或服务暂不可用 | 按SP选择官方网页补核或说明未核验 |
| `error` | 请求或返回处理失败 | 不使用本次结果；按`warnings`判断是否可重试一次 |


## 执行方式

在本Skill目录运行脚本，提供统一业务JSON：

```bash
python3 scripts/client.py --input '业务JSON'
```

`--input`可直接接收内联结构化文本；较长输入也按结构化文本解析，不再先当文件路径检查。需要从文件读取时使用`--input @/path/input.json`；为兼容旧调用，现有的普通文件路径仍可使用。

服务连接由脚本内置配置管理；正常任务不传环境地址、不覆盖连接参数、不修改脚本。无法访问时说明未完成，交由平台维护人员排查。调用者不提供供应商Key。检索后使用真实返回ID取详情，摘要不能代替原文。未知结构、空结果或失败遵守输出状态，不从模型记忆补齐。
