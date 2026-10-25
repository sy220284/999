# 法律联网搜索调用规范


## 输入
只有搜索方法：query为关键词或问题，fetch_content请求同时抓取结果正文。已知指定URL用web-page-fetch，不把URL搜索当直接打开网页。

```json
{
  "query": "最高人民法院 劳动争议 经济补偿 官方",
  "count": 10,
  "fetch_content": false,
  "max_tokens": 500,
  "domain_filter": null,
  "recency": "no_limit",
  "preferred_source_types": ["court", "government"]
}
```

| 字段 | 类型 | 必填 | 取值与默认值 |
|---|---|---:|---|
| `query` | string | 是 | 1–4096字符 |
| `count` | integer | 否 | 1–50，默认10 |
| `fetch_content` | boolean | 否 | 默认`false`；为`true`时向搜索服务请求同时抓取结果页正文 |
| `max_tokens` | integer | 否 | 每条正文最多token数，仅`fetch_content=true`时使用，默认500 |
| `domain_filter` | string/null | 否 | 纯域名，不带协议和路径 |
| `recency` | string | 否 | `one_day`、`one_week`、`one_month`、`one_year`、`no_limit`；默认`no_limit` |
| `preferred_source_types` | string[] | 否 | `government`、`legislature`、`court`、`procuratorate`、`regulator`、`company_disclosure`、`other`；默认仅作排序偏好，不代表后端硬过滤 |

## 输出

```json
{
  "status": "ok",
  "query": "",
  "results": [
    {
      "title": "",
      "url": "https://example.gov.cn/page",
      "snippet": "",
      "content": "",
      "site_name": "",
      "source_type": "other",
      "published_at": null,
      "is_first_party": null
    }
  ],
  "warnings": [],
  "trace_id": ""
}
```

`status`取值：`ok`、`no_result`、`partial`、`unavailable`、`error`。`source_type`和`is_first_party`若无法可靠识别，应返回`other`和`null`，不得凭域名外观伪造。

### 统一状态的使用

| 状态 | 含义 | 主Agent处理 |
|---|---|---|
| `ok` | 搜索成功并返回真实URL | 有指定URL读取能力时继续核验；本期也可重新搜索并启用`fetch_content`，但须说明不是指定URL抓取 |
| `no_result` | 搜索成功但无结果 | 调整关键词一次，仍无结果则如实说明 |
| `partial` | 返回结果但过滤或字段不完整 | 只使用真实字段并展示警告 |
| `unavailable` | 搜索能力或权限不可用 | 不生成替代链接，说明未完成搜索 |
| `error` | 请求或返回处理失败 | 不使用本次结果；按警告决定是否重试一次 |


## 当前能力限制

count仅截取已返回结果；domain_filter、recency、preferred_source_types不执行后端硬过滤，相关警告必须保留。fetch_content仅请求抓取本次搜索结果正文，不用于指定URL读取；指定网页用web-page-fetch。正文只在真实返回时可用。若结果URL只有网站根地址，输出`result_url_is_site_root`警告，只能作为站点线索，不得表述为具体文章链接或已核验来源。

## 执行方式

在本Skill目录运行脚本，提供统一业务JSON：

```bash
python3 scripts/client.py --input '业务JSON'
```

`--input`可直接接收内联结构化文本；较长输入也按结构化文本解析，不再先当文件路径检查。需要从文件读取时使用`--input @/path/input.json`；为兼容旧调用，现有的普通文件路径仍可使用。

服务连接由脚本内置配置管理；正常任务不传环境地址、不覆盖连接参数、不修改脚本。无法访问时说明未完成，交由平台维护人员排查。调用者不提供供应商Key。检索后使用真实返回ID取详情，摘要不能代替原文。未知结构、空结果或失败遵守输出状态，不从模型记忆补齐。
