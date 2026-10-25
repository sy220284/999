# 企业信息：方法与输入输出

使用scripts/client.py，输入JSON可内联或@文件路径。连接由脚本管理，模型不覆盖连接配置、不索取供应商Token。

| 方法/参数 | 作用/要求 |
|---|---|
| operation=search_company | 按keyword搜索企业，重名须确认主体再取详情 |
| operation=get_basic_info | 根据已确认的企业全称取基本信息 |
| keyword | 非空字符串，两个方法均必填 |
| page_size / page_number | 搜索分页，大小1–20默认10，页码正整数默认1 |

输出status、operation、records[]、total、warnings[]、trace_id。记录字段company_name、company_id、credit_code、legal_representative、registered_capital、establishment_date、registration_status、registered_address，实际缺字段不补造。资本保留货币与单位，日期按返回真实数据处理。企业信息不是法律结论，也不代表已查询司法风险。

未确认主体不把首个命中当对方；使用确认的企业全称查详情。空结果说明未找到，参数/权限/配额/格式错误或服务失败说明未完成，不臆造企业信息或绕过限制。调用方仅两种方法，不拼任意接口路径。

接口映射：搜索使用`GET /legal/tyc_search/api/open/search/2.0`；基本信息使用`GET /legal/tyc_search/api/open/ic/baseinfoV2/2.0`。旧的`/legal/tyc_search/api/v4/open/ic/baseinfo/normal`对应未开通接口，不再使用。新路径若失败，保留真实错误和调用编号，不在模型侧改用旧路径或重复付费试错。

运行例：python3 scripts/client.py --input '{"operation":"search_company","keyword":"用户提供的企业名称"}'。--response-file和--dry-run仅测试/预览，不算真实查询。
