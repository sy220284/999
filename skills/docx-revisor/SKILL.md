---
name: docx-revisor
description: "对已有 .docx 加修订和批注。通过云端 DXE 引擎 HTTP API，用 read / save / validate / inspect 改文件。"
user-invocable: true
---

> 清言测试版：脚本已适配测试入口及响应，专业编辑协议保留，真实带痕交付仍待联调。执行前读取[平台适配说明](references/platform-adaptation.md)和[调用规范](references/contract.md)。本期活跃操作仅read/validate/save/inspect；下文扩展方法保留作参考，不代表已接通。

# DOCX 修订工具

> 禁止用 read / cat 打开 .docx。读 docx 只用 `dxe_client.py read`。

所有命令通过 `dxe_client.py` 执行，经 HTTP 调云端 DXE 引擎，本地不安装引擎。`{python}` 代指当前网关的 Python 解释器。

## 清言环境配置

服务连接由本Skill执行脚本管理；正常任务不覆盖地址或连接配置。失败时说明未完成，联系平台维护人员排查。
服务连接由本Skill执行脚本管理；正常任务不覆盖地址或连接配置。失败时说明未完成，联系平台维护人员排查。
- 后端注入`X-Dept-Api-Key`，Skill不携带Key、不读取用户登录文件；旧`DXE_SERVER_URL`、`FILE_SERVICE_URL`不再使用。
- 输入使用本地文件编码为`docx_b64`；输出解`out_b64`或下载`out_url`，验证DOCX后保存新版本，拒绝覆盖原件和已有输出。产物下载入口若需要平台登录或返回不支持的域名，先确认安全下载方式，不将供应商Key附加到URL。
- 全局`--dry-run`仅显示实际地址，不读文件、不发请求。命令里的`skills/...`是示例路径，按实际安装目录解析。

## read — 读全文

```text
{python} skills/docx-revisor/scripts/dxe_client.py read --file 合同.docx
```

- 输出 DXE 视图到 stdout，每段带 key。定位与修改必须用 key。
- 同时写 `<file>.dxemeta` 存 `doc_hash`，save 自动用它检测 read 之后文件是否被修改。
- 清言适配版还在记录中保存本地SHA256；文件变化或缺记录时禁止validate/save，需要重新read。读取新视图会使旧预检记录失效。
- 长输出用 `--out view.xml` 保存为文件，再用 Read / Search 查看。禁止 shell pipe、重定向、`/tmp`、`/dev/null`、`grep`。
- 页眉页脚：`--part header:rIdN` / `--part footer:rIdN`，rId 从 `inspect --what headers_footers` 取。文档没有页眉页脚时先调 `/ensure-header` / `/ensure-footer`。
- 脚注尾注正文：`--part footnotes` / `--part endnotes`，段落带 `note="N"` 归属属性。
- 样式表：`--part styles`，`<style id="…">` 行带 `attr` 与 `para-attr`。改样式定义、新建样式前必须先读它取 styleId。

## save — 应用 edits.json

```text
{python} skills/docx-revisor/scripts/dxe_client.py save --file 合同.docx --edits edits.json --out 批注后.docx
```

- edits.json 是唯一工作文件：顶层数组，一条 edit 表达一个逻辑改动。禁止另建 comments.json。
- save 成功后 edits.json 默认保留。发现遗漏时修改同一文件重新 validate → save。交付确认后才用 `--clean-edits` 清理。
- validate生成`<file>.dxevalidated`本地预检记录，绑定原件、doc_hash及edits；修改计划改变后必须重做预检。save发送前消耗记录，不自动重试；超时不代表没保存，先核对调用记录再决定重试。
- `protocol: 2`、`author`、`expect_hash` 由 dxe_client 自动组装，禁止写进 edits.json。
- 一次 save 整批生效或整批不生效：任一条 edit 失败，输出文件不生成，错误带 edit[N] 下标，N 从 0 起。
- 合同审查必须传 `--workflow contract-review`，其他业务禁止冒用该值。脱敏禁止调 save，必须用 sanitize。
- 流程：上传 docx → 引擎按顺序应用 edits → 产出带修订与批注的 docx → 下载到 `--out`。
- 清言版不使用旧OSS上传：本地DOCX以base64写入JSON，经代理发送。保存文件只表示“已落盘待核验”，后续必须inspect修订与批注、确认位置及Word/WPS效果，再交付。

动作五选一：replace / insert / delete / comment / format。

```json
[
  {"action": "replace", "key": "P5", "old_string": "乙方", "new_string": "丙方", "comment": "统一合同主体称谓"},
  {"action": "replace", "key": "P36", "old_string": "两年", "new_string": "三年"},
  {"action": "delete", "key": "P50", "old_string": "作废的句子"},
  {"action": "delete", "key": "P51", "target": "paragraph"},
  {"action": "replace", "key": "P43", "old_string": "逐字复制的该段整段原文", "new_string": "全段更换后的整段新文字"},
  {"action": "insert", "key": "P9", "anchor": "验收合格", "position": "before", "text": "书面"},
  {"action": "insert", "key": "P43", "target": "paragraph", "text": "3.2 新增条款内容", "comment": "补验收期限"},
  {"action": "comment", "key": "P43", "text": "圈一个短语", "start_text": "保证人承担", "end_text": "保证人承担"},
  {"action": "comment", "key": "P43", "text": "同段跨句连圈", "start_text": "保证人承担", "end_text": "连带责任"},
  {"action": "comment", "key": "P20", "text": "跨段批注", "end_key": "P24"},
  {"action": "comment", "key": "P12", "text": "同上，此处同样适用", "reply_to": "@0"},
  {"action": "replace", "key": "P36", "old_string": "人民币贰拾万元", "new_string": "人民币叁拾万元", "attrs": {"b": "true"}, "comment": "调价，新金额加粗"},
  {"action": "insert", "key": "P43", "target": "paragraph", "text": "3.3 交付标准以封样为准", "attrs": {"b": "true"}},
  {"action": "format", "key": "P43", "old_string": "保证人承担连带责任", "attrs": {"b": "true", "color": "#C00000"}},
  {"action": "format", "key": "P51", "target": "paragraph", "attrs": {"line-spacing": "360", "ind-left": "480"}}
]
```

### 动作与字段

| 动作 | 必填 | 可选 | 语义 |
|---|---|---|---|
| replace | key + old_string + new_string；target=table / row 时改用 rows / cells | comment, attrs, link, bookmark, footnote, endnote | 文字替换；target=table / row 为整表整行替换，引擎展开为 insert + delete 两条修订。text 级全段更换：old_string 逐字复制整段原文，引擎裁掉未变化的公共前后缀 |
| insert | key + text；结构目标改用 cells / rows；带 field / image 时 text 可选 | target, anchor, position, comment, attrs, cells, rows, style, link, bookmark, footnote, endnote, field, image | 插入新文字或新段落 |
| delete | key；target=text 时还必填 old_string | target, comment | 删指定文字；target=paragraph 删整段。禁止带 attrs |
| comment | key + text | start_text, end_text, end_key, reply_to | 纯批注，不改文字。禁止带 attrs |
| format | key + attrs；target=text 时还必填 old_string | target | 只改格式不改文字，产出 Word 格式修订 |

字段说明：

- `key`：段落编号 P5、T2、S1，表格内段落同样是 P 编号。每条必填，缺 key 直接拒绝。
- `part`：可选，默认 `document`。页眉页脚用 `header:rIdN` / `footer:rIdN`；脚注尾注正文用 `footnotes` / `endnotes`。
- `target`：`text` 默认 / `paragraph`。replace 另支持 `table` / `row`，禁止带 `paragraph`。结构粒度 `section` / `cell` / `table` / `row` / `column` / `style` 见“结构与页面编辑”“样式定义编辑”。
- `old_string`：逐字匹配原文，支持全半角标点容错；同段多处匹配报错，不猜。format 的 target=text 用它选定文字，匹配规则与 replace 一致。
- `new_string`：替换后的文字。
- `text`：insert 的插入文字；comment 的批注正文。
- `anchor`：insert 且 target=text 时的段内锚文字，新文字插在锚文字前或后；省略时按 position 插段尾或段首。同段多处匹配报错。
- `position`：`after` 默认 / `before`。
- `start_text` / `end_text`：comment 的选定起点和终点文字，各自匹配，支持段内跨句；两端相同选中一个词；都省略选中整段。
- `end_key`：跨段批注的终点段落编号，省略时终点在 key 段内。
- `reply_to`：`@N` 回复同一 edits 数组中第 N 条批注，N 从 0 起；或填已有批注的数字编号。
- `comment`：绑定在 replace / insert / delete 上的批注。批注高亮范围默认是本次修改的范围，不是整段。
- `attrs`：可选格式覆盖。replace / insert 加在新文字上，format 加在选中文字或整段上。叠加：只改列出的项，其余保留。key 必须在 attrs 格式表允许范围内，value trim 后非空、禁止含 `;`。delete / comment 禁止带 attrs。
- `link` / `bookmark` / `footnote` / `endnote` / `field` / `image`：六个增强字段，见“链接、书签、脚注、域与图片”。

### format

| target | 必填 | 改什么 |
|---|---|---|
| text 默认 | key + old_string + attrs | 选中文字的字体格式 |
| paragraph | key + attrs，禁止带 old_string | 整段的段落格式 |

- 只改 attrs 列出的项，原有其他格式全部保留。
- old_string 匹配规则与 replace 一致：逐字复制、同段唯一。选区跨多个 run 时引擎按 run 分别生成格式修订，超链接等非文字元素原样保留。
- target=paragraph 的 key 必须指向 P 编号，指向 T 编号会被拒绝。
- 产出 Word 格式修订，可在 Word / WPS 里接受或拒绝；`inspect --what revisions` 计入格式类。
- format 禁止带 comment；要说明理由另起一条 comment 动作。text / new_string / anchor 同样禁止出现。
- 结构 target：`section` / `cell` / `table` / `row` / `style`，直接生效、无修订记录，见“结构与页面编辑”“样式定义编辑”。

### attrs 格式表

run 级 13 个 key，replace / insert 的新文字与 format 的 target=text 适用：

| key | 作用 | 值 |
|---|---|---|
| `b` / `i` / `strike` | 加粗 / 斜体 / 删除线 | `true` / `1` / `yes` / `on` 视为启用，其他值视为关闭 |
| `caps` / `smallCaps` | 全部大写 / 小型大写 | 同上 |
| `font-family` | 字体 | 字体名，含空格用单引号包住：`'Times New Roman'` |
| `font-size` | 字号 | 半磅，`24` = 12 磅 |
| `color` | 字色 | `#RRGGBB` 或 `theme(accentN)` |
| `highlight` | 荧光笔色 | Word 高亮色名，如 `yellow`、`green` |
| `shd` | 文字底纹填充色 | `#RRGGBB` |
| `u` | 下划线样式 | Word 下划线值，如 `single`、`double`、`wave` |
| `char-spacing` | 字间距 | twips |
| `vert` | 上 / 下标 | `superscript` / `subscript` |

段落级 key，format 的 target=paragraph 适用：

| key | 作用 | 值 |
|---|---|---|
| `class` | 段落样式 | /read 视图 `<styles>` 里的样式名或 styleId 直接填写，如 `normal`、`RiskWarning` |
| `jc` | 对齐 | `left` / `center` / `right` / `both` |
| `ind-left` / `ind-right` / `ind-first` / `ind-hanging` | 左 / 右 / 首行 / 悬挂缩进 | twips |
| `ind-first-chars` / `ind-left-chars` / `ind-right-chars` / `ind-hanging-chars` | 首行 / 左 / 右 / 悬挂缩进，字符单位，随字号自适应 | 百分之一字符：`200` = 2 字符；与同名 twips 键互斥，写一边清另一边 |
| `spacing-before` / `spacing-after` | 段前 / 段后间距 | twips |
| `spacing-before-lines` / `spacing-after-lines` | 段前 / 段后间距，行单位 | 百分之一行：`50` = 0.5 行；与同名 twips 键互斥 |
| `line-spacing` | 行距 | twips；`spacing-line-rule` 为 `auto` 时 `240` = 单倍、`360` = 1.5 倍、`480` = 两倍 |
| `spacing-line-rule` | 行距解释规则 | `auto` / `atLeast` / `exact` |
| `keep-next` | 与下段同页 | `true` / `1` / `yes` / `on` |
| `page-break-before` | 段前分页 | `true` / `1` / `yes` / `on` |
| `shd` | 段落底纹填充色 | `#RRGGBB` |
| `outline-level` | 大纲级别 | 数字，`0` 起 |
| `border-top` / `border-bottom` / `border-left` / `border-right` | 段落边框 | `single`，固定生成细单线 |
| `list` | 项目符号 / 编号列表 | /read 视图样式里的列表 id，如 `L0001` |
| `list-level` | 列表层级 | 数字，`0` 起 |

行级 3 个 key，format 的 target=row 适用。read 视图 `<tr>` 带同名属性，无值不发该属性，`header:"false"` 时整体移除：

| key | 作用 | 值 |
|---|---|---|
| `height` | 行高 | twips 正整数 |
| `height-rule` | 行高规则 | `atLeast` / `exact` / `auto` |
| `header` | 表头行跨页重复 | `true` / `false`，生成 `w:tblHeader` |

attrs 取值规则：

- attrs 的值必须先 `/read` 复制样本，禁止凭空编数值。read 视图顶部 `<styles>` 块列出全文档样式样本，重复样式只列一次，`<style attr="b:true;font-size:24" hint="…" name="…" type="p|r"/>`，`attr` 的 key:value 单位与含义与上表一致。想让目标与哪个样本一致，就把该样本 attr 里对应的项复制进 attrs。
- 要看某个样式的完整有效格式或取 styleId，用 `read --part styles`。
- 要让全文某样式的段落统一变样，用 format target=style 改样式定义，见“样式定义编辑”。

### 结构与页面编辑

`target` 取 `section` / `cell` / `table` / `row` / `column` 时只与下表 action 组合合法：

| target | action | key 指向 | 必填 | 可选 | 修订行为 |
|---|---|---|---|---|---|
| `section` | format | 节 S key | attrs | — | 直接生效，无修订记录 |
| `section` | insert | 锚段落 P key | `position:"after"`，唯一合法取值 | attrs，默认复制其后最近 S 块属性 | 直接生效，无修订记录 |
| `section` | delete | 内嵌节 S key | — | — | 直接生效；文档末节 S key 永不可删 |
| `cell` | format | 单元格内任一段落 P key | attrs | — | 直接生效，无修订记录 |
| `table` | format | 表 T key | attrs | — | 直接生效，无修订记录 |
| `table` | insert | 锚定块 P 或 T key | `rows`，每行列数一致且非空 | `position`、attrs 应用于全部新文字 | 进修订记录：每行 rev="ins"，可 accept/reject |
| `table` | delete | 表 T key | — | — | 进修订记录：全部行 rev="del"；accept 后删空的表自动清理 |
| `table` | replace | 表 T key | `rows`，每行列数一致且非空 | `comment` 绑定到新表 | 进修订记录：展开为 insert×table + delete×table，旧表标记删除、新表插入 |
| `row` | insert | 参照行内任一 P key | `cells`，每格一段文字，可空串 | `position`、attrs | 进修订记录：复制参照行格数，不足自动补空格子，超出拒绝 |
| `row` | delete | 目标行内任一 P key | — | — | 进修订记录：该行 rev="del" |
| `row` | format | 行内任一段落 P key | attrs | — | 直接生效，无修订记录；已带修订标记的行其 ins/del 标记保留 |
| `row` | replace | 目标行内任一 P key | `cells`，每格一段文字，可空串 | `comment` 绑定到新行 | 进修订记录：展开为 insert×row + delete×row，旧行标记删除、新行插入 |
| `column` | insert | 参照列任一格内 P key | — | `position`、`cells` 自上而下各行文字，默认空段落 | 直接生效，无修订记录 |
| `column` | delete | 目标列任一格内 P key | — | — | 直接生效，无修订记录 |

修订语义边界，交付前必须向用户说明：

- 进修订记录、可 accept/reject：row / table 的 insert、delete、replace；链接 / 书签 / 域 / 图片插入 run；脚注 / 尾注引用标记的插入；footnotes / endnotes 正文里的文字编辑。
- 直接生效、无修订记录：section / cell / table / row 的 format；column 的 insert / delete；样式定义修改与新建；分节符插删。这类改动没有痕迹、无法在 Word 里拒绝。

cells / rows 规则：

- `cells`：字符串数组。insert row 与 replace row 必填，每格一段文字，空串为空格子；长度不足参照行格数自动补空格子，超出拒绝。insert column 可选，自上而下各行新格的文字，默认整列空段落。
- `rows`：二维字符串数组。insert table 与 replace table 必填，行×格文字，必须每行列数一致且非空；每格一段文字，不许多段。
- replace 的 `table` / `row`：引擎在 prepare 阶段展开为同 key 的 insert + delete 两条带修订记录的修改，先 insert 后 delete，新表 / 新行回到原位；`comment` 绑定到 insert 条；`position` 可带但无效果。除 key / rows / cells / comment 外禁止携带其他字段，`old_string` / `new_string` / `text` / `attrs` 与六个增强字段均禁；错误消息报原始 `edit[N]` 下标，展开不改变对外下标。
- `cells` / `rows` 出现在上表之外的组合直接拒绝。结构目标与 `old_string` / `new_string` / `text` / `anchor` 互斥。format 一律禁止带 `comment`。

节 key 表，与 /read 视图 `<section id="S1" …/>` 属性同名同单位，叠加合并，未列出的属性保留、同 key 覆盖：

| key | 作用 | 值 |
|---|---|---|
| `pgW` / `pgH` | 页宽 / 页高 | twips，A4 竖版 11906 / 16838 |
| `orient` | 页面方向 | `portrait` / `landscape`；横版必须同时把 pgW / pgH 对调 |
| `marTop` / `marRight` / `marBottom` / `marLeft` | 上 / 右 / 下 / 左页边距 | twips |
| `marHeader` / `marFooter` / `marGutter` | 页眉距顶 / 页脚距底 / 装订线 | twips |
| `colNum` | 分栏数 | 整数 |
| `pgNumFmt` / `pgNumStart` | 页码格式 / 起始页码 | 如 `decimal`；整数 |
| `titlePg` | 首页不同页眉页脚 | `true` / `1` / `yes` / `on` |
| `sect` | 节类型 | `nextPage` / `continuous` 等 |

单元格 key 表：

| key | 作用 | 值 |
|---|---|---|
| `width` | 格宽 | dxa，等于 twips |
| `shd` | 底纹填充色 | `#RRGGBB` |
| `valign` | 垂直对齐 | `top` / `center` / `bottom` |
| `colspan` | 横向合并格数 | ≥1 整数，`1` 为取消合并 |
| `vmerge` | 纵向合并 | `restart` 首格 / `continue` 续格 / `none` 取消 |
| `border-top` / `border-bottom` / `border-left` / `border-right` | 单格边框 | `single` / `none` |

表 key 表：

| key | 作用 | 值 |
|---|---|---|
| `jc` | 整表水平对齐 | `left` / `center` / `right` |
| `width` | 整表宽度 | dxa 数字或 `auto` |
| `grid` | 列宽表 | 逗号分隔的 twips 列宽，如 `"2000,3000,3784"`；重建列宽并按列同步各格宽度，合并格取所跨列宽之和 |
| `borders` | 整表边框 | `true` / `1` / `yes` / `on` 生成上下左右与内部横竖的细单线；`false` 移除 |

单位规定：

- twips 是长度主单位，1 cm ≈ 567。`ind-*` / `spacing-*` / `char-spacing` / 页面设置 / 列宽格宽全用 twips。
- A4 竖版 pgW=11906、pgH=16838；横版对调并 `orient:"landscape"`。
- Word 默认页边距：上下 1440、左右 1800。
- 字符单位：百分之一字符，首行缩进 2 字符 = `"ind-first-chars": "200"`。
- 行单位：百分之一行，段前 0.5 行 = `"spacing-before-lines": "50"`。
- 列宽格宽 dxa 等于 twips。A4 竖版默认边距下正文可用宽约 8306，引擎新建表默认总宽 8784；`grid` 各列之和禁止超过可用宽度。

结构示例：

```json
[
  {"action": "format", "key": "S1", "target": "section", "attrs": {"marTop": "1440", "marBottom": "1440", "marLeft": "1800", "marRight": "1800"}},
  {"action": "format", "key": "S1", "target": "section", "attrs": {"orient": "landscape", "pgW": "16838", "pgH": "11906"}},
  {"action": "format", "key": "P43", "target": "paragraph", "attrs": {"ind-first-chars": "200"}},
  {"action": "format", "key": "P12", "target": "cell", "attrs": {"colspan": "3"}},
  {"action": "format", "key": "T2", "target": "table", "attrs": {"grid": "2000,3000,3784"}},
  {"action": "insert", "key": "P25", "target": "row", "position": "after", "cells": ["品名", "数量", "单价"], "comment": "补一行明细"},
  {"action": "insert", "key": "P30", "target": "table", "position": "after", "rows": [["项目", "金额"], ["首付款", "50000"], ["尾款", "150000"]]},
  {"action": "delete", "key": "P18", "target": "column"},
  {"action": "format", "key": "P12", "target": "row", "attrs": {"header": "true", "height": "600", "height-rule": "atLeast"}},
  {"action": "replace", "key": "P27", "target": "row", "cells": ["品名", "数量", "单价"], "comment": "明细行内容整体更正"},
  {"action": "replace", "key": "T3", "target": "table", "rows": [["项目", "金额"], ["首付款", "50000"], ["尾款", "150000"]]}
]
```

### 样式定义编辑

改一处样式定义，全文套用该样式的段落同时变化。先 `read --part styles` 取 styleId：

```json
[
  {"action": "format", "key": "a3", "target": "style", "attrs": {"font-size": "28", "b": "true"}},
  {"action": "format", "key": "Normal", "target": "style", "attrs": {"line-spacing": "360", "ind-first-chars": "200"}}
]
```

- `key` 是 styleId，即 `w:styleId` 稳定键，不是样式显示名；从 `read --part styles` 的 `<style id="…">` 行直接复制。找不到报 DXE_UNKNOWN_KEY。
- attrs 允许的 key = run 级 key 表与段落级 key 表的并集。段落样式两者皆可；字符样式只接受 run 级 key，带段落级 key 报 DXE_BAD_MARKUP；表样式不支持。
- 键级覆盖：attrs 覆盖式合并进该样式的字符 / 段落属性，未提及的键保留。直接生效、无修订记录。
- 样式改动使已读视图全部失效：save 后必须重新 read 再继续编辑或复制样本。
- 删除样式、改既有样式的 basedOn / name 不支持。

新建样式用 insert×style。文档里没有想要的样式时先建后用，key 是新 styleId，属性放在 `style` 字段：

```json
[
  {"action": "insert", "key": "RiskWarning", "target": "style",
   "style": {"name": "风险提示", "type": "paragraph", "based_on": "Normal"},
   "attrs": {"b": "true", "color": "#C00000", "font-size": "28"}},
  {"action": "format", "key": "P43", "target": "paragraph", "attrs": {"class": "RiskWarning"}}
]
```

- 新 styleId 必须匹配 `[A-Za-z_][A-Za-z0-9_]*`、≤40 字符、文档内不存在；`style.name` 显示名必填且全文唯一；`style.type` 只能 `paragraph` 默认 / `character`；`style.based_on` 须为既有 styleId，省略时使用默认段落 / 字符样式；attrs 可选，run ∪ 段落键，字符样式只接受 run 键，`class` / `list` / `list-level` 不接受。
- 套用：同一数组或后续 format×paragraph 的 attrs 用 `class:"<新 styleId>"` 直接填写，引擎解析成 `w:pStyle` 写进段落，段落记 `w:pPrChange` 格式修订，可在 Word 里接受 / 拒绝。
- 新建样式直接生效、无修订记录，新 `w:style` 插在既有最后一个样式之后并标 `w:customStyle="1"`；与 format×style 一样使已读视图全部失效，save 后重新 read。

### 文中分节

- 单节文档只有末尾一个 S1。文中插过分节符后出现多个 S 键，按文档序混排：

```text
P1 … P20  S1  P21 … P42  S2  P43 … 附件内容 …  S3
```

- 分节符的属性适用于它前面的内容。非末尾 S 块的适用范围是上一个分节点至该段含该段；文档末尾 S 块的适用范围是其后的全部内容。在某段后插分节符，是把该段及以上划入新节，后续内容仍属于其后的节。
- `insert target=section` 只接受 `position:"after"`；不带 attrs 时复制其后最近 S 块属性，带了则覆盖。
- `delete target=section` 只删内嵌 S 键，段落本身保留，由后一节的属性生效；文档末节 S 键永不可删。
- 分节符插删与节格式直接生效、无修订记录，交付时向用户说明。
- 同一次 save 内键序稳定：insert target=section 不改变既有 S 键编号，新块本身无键，禁止在同一次 save 内 format 刚插的节。save 后下一次 read 才按键序重排，重排后内嵌块先于末节取得较小编号；分多次 save 时，每次必须先重新 read 取新键。

附件横向示例，正文纵向、文末附件横向，附件标题 P43、正文末段 P42、当前只有末节 S1：

```json
[
  {"action": "insert", "key": "P42", "target": "section", "position": "after"},
  {"action": "format", "key": "S1", "target": "section",
   "attrs": {"orient": "landscape", "pgW": "16838", "pgH": "11906"}}
]
```

- 第一步在 P42 后插分节符：新节属性默认复制其后最近 S 块即原末节，仍纵向。插完后原末节属性只适用于附件，主正文格式改由新插的内嵌分节符决定。
- 第二步把 S1 末节格式化为横向；同一次 save 内既有 S 键不因插入重排，S1 仍指向末节。

### 链接、书签、脚注、域与图片

`insert` / `replace` 的新内容可带以下增强字段，field 仅 insert 可用：

| 字段 | 形态 | 合法组合 | 语义 |
|---|---|---|---|
| `link` | `{"url":"https://…"}` 或 `{"anchor":"书签名"}` | insert / replace × text | 新文字设为超链接。url 必须 http(s)，否则 DXE_URL_REJECTED；anchor 引用的书签必须已存在，含同一数组中先建；url / anchor 二选一，同给报 DXE_BAD_MARKUP |
| `bookmark` | `"名字"` | insert / replace × text | 新内容两侧写入书签锚点，id 自动分配。名字须匹配 `[A-Za-z_][A-Za-z0-9_]*`、非空、≤40 字符、文档内唯一，重名报 DXE_BAD_MARKUP。可与 link 同用 |
| `footnote` | `"脚注文本"` | insert / replace × text | 在锚点处插脚注引用标记，带修订记录，内容写入 footnotes.xml，单段纯文本。与 endnote 互斥；该条 edit 禁止再带 text / new_string |
| `endnote` | `"尾注文本"` | insert / replace × text | 同 footnote，内容写入 endnotes.xml |
| `field` | `"PAGE"` 域指令字符串 | 仅 insert × text | 生成域并自动开启打开时更新。指令首词只允许 PAGE / NUMPAGES / SECTIONPAGES / DATE / TIME / REF / PAGEREF / STYLEREF，超出范围报 DXE_BAD_MARKUP |
| `image` | `{"b64":"…","ext":"png","width_emu":N,"height_emu":N}` | insert × text / paragraph | 插内联图片，不支持浮动。ext 只能 png / jpg / jpeg / gif / bmp；b64 ≤10MB；尺寸默认按图片头像素自动算，×9525 EMU/px，解析不出回退 200px，只给一边按比例补另一边。插入 run 带修订记录 |

- 六字段只对上表组合合法；携带 link / bookmark 时 target 必须是 text，默认即 text；六字段与 cells / rows 互斥；link 与 bookmark 可同用；footnote 与 endnote 互斥。
- 带 field / image 的 insert，text 可选，给了就是排在域 / 图之前的普通文本；带 footnote / endnote 的 edit，text / new_string 必须缺席。
- 页眉页脚 part 里 field / image 与正文使用相同方式。

页脚“第 X 页 共 Y 页”，文本 + PAGE + NUMPAGES 多条 insert 组合，text 级 insert 无 anchor 时按顺序插到段尾；要居中再补一条 format target=paragraph `{"jc":"center"}`：

```json
[
  {"action": "insert", "part": "footer:rId1", "key": "P1", "target": "text", "text": "第 "},
  {"action": "insert", "part": "footer:rId1", "key": "P1", "target": "text", "field": "PAGE"},
  {"action": "insert", "part": "footer:rId1", "key": "P1", "target": "text", "text": " 页 共 "},
  {"action": "insert", "part": "footer:rId1", "key": "P1", "target": "text", "field": "NUMPAGES"},
  {"action": "insert", "part": "footer:rId1", "key": "P1", "target": "text", "text": " 页"}
]
```

外链 + 书签 + REF 交叉引用：

```json
[
  {"action": "insert", "key": "P43", "target": "paragraph",
   "text": "3.4 详细交付清单以附件一为准", "bookmark": "delivery_clause"},
  {"action": "insert", "key": "P12", "anchor": "交付安排", "text": "（详见3.4条）",
   "link": {"anchor": "delivery_clause"}},
  {"action": "insert", "key": "P20", "anchor": "如上所述", "field": "REF delivery_clause \\h"},
  {"action": "insert", "key": "P55", "anchor": "监管规则", "text": "（最新版本见官网公示）",
   "link": {"url": "https://example.com/rules"}}
]
```

插脚注：

```json
[
  {"action": "insert", "key": "P36", "anchor": "尾款",
   "footnote": "尾款指合同总价的20%，于验收合格后七日内支付。"}
]
```

页眉 logo，文档无页眉时先调 /ensure-header 取 part 名再插图：

```json
[
  {"action": "insert", "part": "header:rId1", "key": "P1", "target": "text",
   "image": {"b64": "<图片base64>", "ext": "png", "width_emu": 1714500, "height_emu": 419100}}
]
```

### 脚注 / 尾注正文编辑

- `read --part footnotes` / `endnotes` 把每条 note 的段落输出为普通 `<p>` 块，表格为 `<table>` 块，块属性 `note="N"` 标归属，如 `<p id="P3" note="2">`；separator 与 continuationSeparator 不可见、不可编辑。
- save 带 `part:"footnotes"` 走与正文相同的处理流程：replace / insert / delete / format × text / paragraph 全可用，修改进修订记录，写回时按 note 属性放回原注。
- 注文可以全部标记删除，但注本身不会消失：v2 的删除是修订标记而非物理移除，接受修订后注文为空，`w:footnote` 元素与正文引用仍在。要彻底移除整条脚注，删除正文中含引用标记的那段文字，delete×paragraph，带修订记录，再接受修订。
- 正文的脚注 / 尾注引用在视图里是自闭合标记 `<footnote-ref id="2"/>` / `<endnote-ref id="2"/>`，不是文字，禁止用 text 级 edit 选定。

### insert 的七种插入位置

| target | anchor | position | 插到哪 |
|---|---|---|---|
| text | 有 | after 默认 | 锚文字后面 |
| text | 有 | before | 锚文字前面 |
| text | 无 | after | 段落末尾，全部省略时的默认 |
| text | 无 | before | 段落开头 |
| paragraph | — | after 默认 | key 段后面 |
| paragraph | — | before | key 段前面 |
| section | — | after，唯一合法取值 | key 段之后插分节符 |

- 插到某段前面：用前一段的 key 加 after，或用本段的 key 加 before，两种写法都支持。
- 连续插入多个段落必须拆成多条 edit，一条一段，每段独立继承格式、独立带批注。禁止在一条 edit 的 text 里用换行写入多段。
- 格式继承由引擎自动完成：insert 的新段落复制锚段落格式；insert / replace 的新文字继承锚文字或被替换文字的字体，段落格式不受影响。默认不在 edits 里指定字体；确需给新文字指定格式就在该条 edit 加 attrs，叠加规则相同：只改列出的项。

## validate — 只校验 edits，不写文件

```bash
{python} skills/docx-revisor/scripts/dxe_client.py validate --file 合同.docx --edits edits.json
```

- save 前必调。校验完整 edits 数组，comment 动作的锚定一并校验。
- validate 对整批 edits 做原子性预检：任一项失败则整批不通过，客户端不会获得 save 许可；当前服务只返回首个失败项的 edit[N] + 原因，N 从 0 起。修正后需重新 validate，才能继续 save。
- 引擎不回退、不降级：找不到就是找不到，修复 edits 后重试。

## inspect — 查文档状态

```bash
{python} skills/docx-revisor/scripts/dxe_client.py inspect --file 批注后.docx --what revisions
{python} skills/docx-revisor/scripts/dxe_client.py inspect --file 批注后.docx --what comments
{python} skills/docx-revisor/scripts/dxe_client.py inspect --file 批注后.docx --what headers_footers
```

- `revisions`：修订痕迹数，del / ins / 格式
- `comments`：批注数
- `headers_footers`：页眉页脚 part 列表，rId + 类型 + 首行摘要

## transform — 接受 / 拒绝修订

```bash
{python} skills/docx-revisor/scripts/dxe_client.py transform --file 批注后.docx --op accept --out 最终版.docx
{python} skills/docx-revisor/scripts/dxe_client.py transform --file 批注后.docx --op reject --out 原始版.docx --author 法律助手
```

`--author` 可选，限定只接受 / 拒绝该作者的修订；不传为全部。

## extract-text / sanitize — 外发脱敏

```text
{python} skills/docx-revisor/scripts/dxe_client.py extract-text --file 合同.docx --out all-parts.txt
{python} skills/docx-revisor/scripts/dxe_client.py sanitize --file 合同.docx --mapping mapping.json --out 脱敏.docx
```

- `extract-text` 覆盖正文、页眉、页脚、批注、脚注、尾注。
- `sanitize` 用 `{占位符: 原文}` mapping 做普通替换，不新增修订痕迹。替换后对剥除 XML 标签的可见文本校验无残留；XML 属性不在校验范围，含修订 / 批注的文档 `w:author` 属性里的作者名会保留。需连作者名一起脱敏时先接受全部修订并删除批注。
- `sanitize` 是 contract-desensitize 的唯一 DOCX 写入方式。

## ensure-header / ensure-footer — 补建页眉页脚 part

`dxe_client.py` 无此子命令，直接 HTTP 调用，`DXE_SERVER_URL` 已含 `/dxe-engine` 前缀。文档没有页眉 / 页脚时先补建，取到 part 名后按正常流程 read / save：

```bash
{python} skills/docx-revisor/scripts/dxe_client.py upload --file 合同.docx
curl -XPOST "$DXE_SERVER_URL/ensure-header" -H 'Content-Type: application/json' \
  -d '{"docx_url":"<oss_url>","kind":"default"}'
{python} skills/docx-revisor/scripts/dxe_client.py download --url <out_url> --out 合同.docx
```

- 返回 `{"ok":true,"rid":"rId7","part":"header:rId7","existed":false,"out_url":"https://oss.../with-header.docx"}`；下载补建后的 docx，后续 read --part header:rId7 / save 带 part 正常编辑。
- `kind` 只能 `default` 默认节 / `first` 首页自动置 titlePg / `even` 偶数页，默认 `default`；`/ensure-footer` 用法相同，返回 `part:"footer:rIdN"`。
- 重复调用安全：该 kind 的页眉 / 页脚已存在时直接返回现有 rid，`existed:true`，不重复创建。
- 页眉里放 logo 或页脚放页码：补建后按“链接、书签、脚注、域与图片”的示例编辑对应 part。

## 其余子命令

```text
dxe_client.py validate-docx --file X.docx            # OOXML 结构完整性校验，parts/rels 级
dxe_client.py compare --base 旧.docx --revised 新.docx --out 差异.docx   # 两版差异，产出带修订标记的对比稿
dxe_client.py compare-report --base 旧.docx --revised 新.docx            # 差异结构化报告，逻辑失败 exit≠0
dxe_client.py embed-image --file X.docx --image 图.png --out Y.docx      # 已废弃，插图片改用 save 的 image 字段
dxe_client.py enable-update-fields --file X.docx --out Y.docx            # 打开时自动更新域
dxe_client.py latex-to-omml 'E=mc^2'                                    # LaTeX 转 Office Math
dxe_client.py unit-convert cm-to-emu 100                                # cm/inch ↔ EMU 换算
dxe_client.py upload --file X.docx                                      # 上传 OSS 取 oss_url
dxe_client.py download --url <oss_url> --out X.docx                     # 从 OSS 下载
```

## 作业流程

```
read 全文 → 逐段审读并记录问题 → 构造一个 edits.json → validate → save 产出批注 docx → inspect 核验 → 完成
```

- 每份合同只维护一个 edits.json。多文档按文件名命名，如 `合同A_edits.json`。
- 修订与批注同条：replace / insert / delete 直接带 `comment` 字段；不改文字只提意见用 `comment` 动作；不改文字只改格式用 `format` 动作。
- save 只调用一次。inspect 分别核验 revisions 和 comments；comments 锚点 warning 必须为零，不为零时重新修正锚点。

## 编写规则

- `old_string` / `anchor` / `start_text` / `end_text` 必须是原文连续片段，逐字一致，禁止省略、跨段、凭记忆。不确定时重新 read 对应段落逐字复制。
- 最小范围：一条 edit 只选真正变化的字。`old_string` 只选变化部分，禁止选整句整短语；`new_string` 只写新补的字，禁止重新输入前后未变的字。修订标记和批注范围随选区缩小，选区太大在 Word / WPS 里会覆盖无关文字、批注引线分裂。
- 一段内多个改动拆成多条 edit；需要解释的改动在同一条加 `comment`。
- 删几个字：`delete` + `old_string`。删整段含段落标记和自动编号：`delete` + `target:"paragraph"`，只要 key，禁止带 old_string。
- 大改条款默认拆成多条最小修改：变化的字用小范围 `replace`，新增用 `insert`，删除用 `delete`，与原文相同的文字禁止选进修改范围。与原文几乎无重复时才整段更换：text 级 `replace`，old_string 逐字复制整段原文，new_string 写整段新文字。
- 段尾补文字：`insert`，text 级、无 anchor。段首用 `position:"before"`。插到指定字前后用 `anchor` + `position`。
- 加新段：`insert` + `target:"paragraph"`，key 设锚段落，默认插 key 段后，`position:"before"` 插前面。连续加多段拆多条，一条一段。
- 纯批注用 `comment` 动作，与修订写在同一个 edits.json 按顺序排，禁止另建 comments.json。
- 改格式用 `format`：选中文字加粗 / 标红用 target=text + old_string + attrs；整段行距 / 缩进 / 对齐用 target=paragraph + attrs。只改 attrs 列出的项，文字一个字不动。
- attrs 值从 read 视图样式样本原样复制，禁止编造；value 禁止含 `;`。
- 改页面设置用 `format` + `target:"section"`，key 用 read 视图 `<section id="S1" …/>` 的 S 编号，attrs 没列的属性保留。
- 插行 `cells` 每格一段文字，空串为空格子，长度禁止超参照行格数；插表 `rows` 必须每行列数一致且非空。整表 / 整行内容全部更换用 `replace` + `target:"table"` / `target:"row"`。只调行高 / 表头跨页重复用 `format` + `target:"row"`。
- 改样式定义用 `format` + `target:"style"`，key 用 styleId 不是显示名；新建样式用 `insert` + `target:"style"`，建后在同一数组用 `format` + `target:"paragraph"` 的 class 应用到段落。
- 域生成内容（目录条目、页码、交叉引用）不许用 `replace`/`insert`/`delete` 手工编辑——引擎会直接拒绝；页码在格式调整、分页稳定后，发送 `update_fields` 条目（无需 key）一键刷新全部域并重建目录。
- 文中分节必须记住分节符的属性适用于它前面的内容：附件转横向先在附件前最后一段后 insert target=section，再把末节 S 键 format 成 landscape。
- 加超链接 / 书签 / 脚注 / 页码域 / 图片在 insert / replace 上带对应增强字段；页脚页码用文本 + PAGE + 文本 + NUMPAGES 多条 insert 组合。
- 文档没有页眉 / 页脚时先调 `/ensure-header` / `/ensure-footer` 补建 part，再 read / save 编辑该 part。

## 常见错误

| 错误 | 原因 | 解法 |
|---|---|---|
| 协议已升级为 v2，请按新 SKILL 重新构造 edits | edits.json 是 v1 格式：含已废除的 v1 字段、缺 action 或缺 key | 按五动作重新构造 edits.json；v2 没有空 old_string=追加、换行开头=新段的隐式语义 |
| DXE_NO_MATCH old_string 在该段落找不到 | 片段不精确、有错字或 key 不对 | 重新 read 对应段落逐字复制原文；引擎不回退纯文本替换 |
| DXE_AMBIGUOUS_MATCH 同段多处匹配 | old_string / anchor 太短，同段匹配到多处 | 加长选定文字后重试 |
| DXE_BAD_MARKUP replace 不支持整段替换 | replace 带了 target=paragraph，或没带 old_string | 拆成小范围 replace + insert；确需全段更换用 text 级 replace，old_string 逐字复制整段原文 |
| start_text / end_text 找不到 | 批注锚点文字不逐字 | 重新 read 逐字复制；禁止降级成整段批注 |
| 无格式可继承，拒绝生成无格式文字 | 文档取不到任何可继承格式 | 检查文档本身，人工处理该处 |
| save 报 expect_hash 不匹配 | read 后 docx 被改过 | 重新 read 取新的 hash 和 key |
| read 返回乱码 | 用了 cat / read 打开 .docx | 只用 dxe_client.py read |
| save 成功但 inspect 修订数=0 | replace 的 old / new 一样，没改 | 检查 new_string 确实和 old_string 不同 |
| DXE_BAD_MARKUP attrs 只适用于 replace/insert/format | delete 或 comment 的 edit 带了 attrs | 去掉 attrs；格式诉求改用 format 动作 |
| DXE_BAD_MARKUP format 动作暂不支持行内批注 | format 的 edit 带了 comment | 另起一条 comment 动作说明理由 |
| DXE_BAD_MARKUP attrs key 不在允许范围或 value 含分号 | key 不在 attrs 格式表内，或 value 里有 `;` | 按 attrs 格式表改 key；value 去掉 `;` |
| DXE_BAD_MARKUP format target=paragraph 仅支持段落块 | key 指到了表格块 T 编号 | 改用表格内段落的 P 编号 |
| DXE_BAD_MARKUP cells / rows 只适用于 insert/replace 的 row 及 insert 的 column、insert/replace 的 table | cells / rows 出现在上表之外的组合 | 插行 / 换行用 cells、插表 / 换表用 rows，其余动作去掉这两个字段 |
| DXE_BAD_MARKUP 结构 target 的 key 前缀不对 | format section 用了非 S key、format / replace 的 table 用了非 T key | section 用 S 编号、table 用 T 编号；cell / row / column 一律用格内段落的 P 编号 |
| DXE_BAD_MARKUP rows 必须为矩形非空二维数组 | 插表的 rows 各行列数不一或整体为空 | 每行同列数、至少一行；空格子用空串 `""` |
| DXE_BAD_MARKUP replace×table / replace×row 带了禁用字段或缺 rows / cells | 整表 / 整行替换同时带了 old_string / new_string / text / attrs / 增强字段，或没给 rows / cells | 只接受 key + rows / cells + 可选 comment；文字级修改改用 text 级 replace |
| DXE_BAD_MARKUP format target=row 的 attrs key 不在行级允许范围 | 行级只有 height / height-rule / header 三个 key | 行高用 `height` twips、行高规则用 `height-rule`、表头跨页重复用 `header` |
| DXE_BAD_MARKUP height 非正整数 / height-rule / header 取值非法 | height 不是 twips 正整数，height-rule 不在 atLeast / exact / auto，header 不在 true / false | height 给正整数；height-rule 只能 `atLeast` / `exact` / `auto`；header 只能 `true` / `false` |
| DXE_UNKNOWN_KEY 样式不存在 | format target=style 的 key 不是有效 styleId，常误用显示名当 key | `read --part styles`，从 `<style id="…">` 行复制 id |
| DXE_BAD_MARKUP 字符样式不支持段落属性 | 对字符样式的 format×style / insert×style 带了段落级 key | 字符样式只接受 run 级 key；段落级诉求改段落样式或逐段 format |
| DXE_BAD_MARKUP insert target=style 的 key 非法或已存在 | 新 styleId 不符合 `[A-Za-z_][A-Za-z0-9_]*`、超 40 字符，或与现有 styleId 重复 | 换合法且未占用的 id；先 `read --part styles` 确认未占用 |
| DXE_BAD_MARKUP style.name 重复 / type 非法 / 缺 name | 显示名与既有样式重复忽略大小写、type 不在 paragraph / character，或没给 name | name 全文唯一；type 只能 paragraph / character，默认 paragraph |
| DXE_UNKNOWN_KEY based_on 指向不存在的样式 | style.based_on 不是既有 styleId | 从 `read --part styles` 复制既有 id，或省略 based_on |
| DXE_BAD_MARKUP 不支持的域指令 | field 首词不在允许范围 | 只用 PAGE / NUMPAGES / SECTIONPAGES / DATE / TIME / REF / PAGEREF / STYLEREF |
| DXE_BAD_MARKUP 书签已存在 / 书签名非法 | bookmark 重名，或不符合 `[A-Za-z_][A-Za-z0-9_]*`、≤40 字符 | 换唯一的合法名字；字母数字下划线、字母或下划线开头 |
| DXE_URL_REJECTED / DXE_BAD_MARKUP link 的 url / anchor 不能同给、url 必须 http(s) | 同给两者，或 url 协议不对 | 外链只给 url 且必须 http / https，文内跳转只给 anchor |
| DXE_BAD_MARKUP footnote 与 endnote 互斥 | 同一条 edit 同时带了 footnote 和 endnote | 一条只插一种；两种都要拆两条 |
| DXE_BAD_MARKUP 图片 b64 超限或 ext 不支持 | b64 超过 10MB，或 ext 不在 png / jpg / jpeg / gif / bmp | 压缩图片或转换格式后重试 |
| DXE_BAD_TARGET 该 part 不支持编辑 | 对不可编辑的 part 如 separator、模板类 part 发 save | 只编辑正文 / 页眉 / 页脚 / footnotes / endnotes，别的 part 禁止发写请求 |
| DXE_BAD_TARGET 脚注 N 的段落不可全部删除 | 引擎保护性检查，常规 v2 编辑不会触发 | 要彻底移除整条脚注：删正文中含引用的段落 delete×paragraph，再接受修订 |
| DXE_BAD_MARKUP 带 footnote / endnote 的 edit 不得再带 text / new_string | 插脚注引用的 edit 同时给了 text | 引用标记即全部插入内容，脚注正文写在 footnote 字段里 |
