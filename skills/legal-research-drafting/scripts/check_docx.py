#!/usr/bin/env python3
"""check_docx.py — 可交付法律文书 docx 的本体纯净与格式残留检查。

用法: python3 check_docx.py <文件.docx> [更多.docx ...]

检查项(均为通用规则,与具体案件无关):
- ERROR: 文书本体混入免责声明、AI/助手身份说明
- ERROR: 文书本体混入核验状态标注(未经核验/要旨归纳/转引自…未经 等)
- ERROR: 文书本体混入工具名、检索过程说明、来源链接(http/https、markdown 链接)
- ERROR: Markdown 残留(标题井号、加粗星号、表格竖线、代码围栏)
- ERROR: 文档为空或无法打开
- WARN : 正文出现当天日期(须确认来自任务时间线而非生成日期)
- ERROR: 编号裸空留白(如"第　号"),留白必须用"第____号"显式样式
- ERROR: 提交型文书落款日期裸空("年 月 日"无数字无下划线占位)
- ERROR: 相邻表格无分隔段落(OOXML 合并渲染导致边框丢失)
- ERROR: 可见表格缺 <w:tblGrid> 列宽定义(LibreOffice/飞书预览会布局失真)
- WARN : "待专业人士核实"字样(本体内应改为占位符或删除)
- WARN : 正文过短(可能交付不完整)
- WARN : 日期行疑似缺失(文书通常应有落款日期或留白占位)
- WARN : 半角双引号(中文文书应使用全角引号)

退出码: 有 ERROR 时为 1,否则 0。
"""
import html
import re
import sys

# Windows 下 stdout/stderr 默认按本地代码页(如 cp936)编码,中文与"✓"等字符会
# 触发 UnicodeEncodeError;统一切换为 UTF-8(Python 3.7+),失败时静默保持原状。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
import zipfile
from datetime import date


def _today_patterns():
    """生成当日日期的各种写法——正文出现即视为以生成日期为时间基准(违反题目时间线规则)。"""
    t = date.today()
    cn_digits = "〇一二三四五六七八九"
    year_cn = "".join(cn_digits[int(c)] for c in str(t.year))

    def cn_num(n):
        if n <= 10:
            return cn_digits[n] if n < 10 else "十"
        if n < 20:
            return "十" + (cn_digits[n % 10] if n % 10 else "")
        return cn_digits[n // 10] + "十" + (cn_digits[n % 10] if n % 10 else "")

    pats = [
        rf"{t.year}\s*年\s*0?{t.month}\s*月\s*0?{t.day}\s*日",
        rf"{t.year}[-/.]0?{t.month}[-/.]0?{t.day}\b",
        rf"{year_cn}年{cn_num(t.month)}月{cn_num(t.day)}日",
    ]
    return "|".join(pats)


ERROR_PATTERNS = [
    (r"免责声明", "混入免责声明标题"),
    (r"不构成(正式)?法律意见", "混入免责声明语句"),
    (r"初稿参考", "混入免责声明语句"),
    (r"仅供参考.{0,12}(复核|采纳)", "混入免责声明语句"),
    (r"auto[- ]?legal|AutoGLM", "混入 AI 产品名"),
    (r"(AI|人工智能|大模型|语言模型)(助手|生成|起草)", "混入 AI 身份说明"),
    (r"初级助手", "混入 AI 身份说明"),
    (r"执业(法律专业人员|律师).{0,6}(复核|定稿)", "混入免责声明语句"),
    (r"未经(原文|一手|全文|判决书)核[验实]", "混入核验状态标注"),
    (r"「?要旨归纳」?", "混入核验状态标注"),
    (r"转引自", "混入二手来源标注"),
    (r"legal_(statute|case)|web_(search|fetch)|元典", "混入内部工具名"),
    (r"https?://", "混入链接"),
    (r"\[[^\]]+\]\([^)]+\)", "混入 Markdown 链接"),
    (r"题干|本题|评测题目|题目(要求|给定|已)", "混入题面/任务语言"),
    (r"第[　\s]+号|字第号", "编号裸空留白——未知编号必须用'第____号'显式留白样式,不得用空格占位"),
    (r"^#{1,6}\s", "Markdown 标题残留"),
    (r"\*\*[^*]+\*\*", "Markdown 加粗残留"),
    (r"^```", "Markdown 代码围栏残留"),
]

WARN_PATTERNS = [
    (_today_patterns(), "正文出现当天日期——确认该日期来自用户材料/任务时间线,而非用生成日期充当时间基准"),
    (r"待专业人士核实", "本体内出现待核实标注,应改为占位符或删除"),
    (r'"', "半角双引号——中文文书应统一使用全角引号“”(英文引文除外)"),
    (r"[一-鿿][!?]", "中文后接半角叹号/问号——应使用全角！？"),
    (r"经检索|据检索|通过检索(未|已)", "疑似检索过程说明"),
    (r"数据库(未|已)(收录|查到)", "疑似检索过程说明"),
    (r"已检索|检索截至|法答网", "疑似检索过程/来源说明"),
    (r"指导性案例|公报案例|参考案例|人民法院案例库|典型案例", "案例地位标签——确认与检索返回类型字段一致,勿自行升格"),
    (r"至今|截至目前|现已(逾期|届满)", "相对时间锚点——确认基准日来自题目时间线而非生成日期"),
    (r"未经核[验实]", "出现'未经核验/核实'——确认是程序性论述(如质证、庭审核对)而非内部核验标注"),
]

DATE_HINT_RE = re.compile(r"[〇零一二三四五六七八九十\d]{2,4}\s*年|年\s+月\s+日")

# 按文书类型的模板必备章节检查(依据 references/templates/ 要素清单;标题命中类型即检查)
# (类型识别正则[匹配标题], [(必备内容正则, 缺失提示)...])
TYPE_REQUIRED = [
    (r"法律意见书", [
        (r"声明|本所声明|律师声明", "法律意见书缺'声明事项'节(证监会编报规则第12号必备要素)"),
        (r"(律师事务所|本所).{0,40}$|经办律师|盖章|签字", "法律意见书落款疑缺出具主体/经办律师要素"),
    ]),
    (r"情况说明", [
        (r"特此说明|(以上|上述|本)(情况|说明|函)?[^。\n]{0,10}(真实|属实|准确|客观|完整)", "情况说明缺结论性表态(模板骨架必备)"),
    ]),
    (r"(管理办法|管理制度|细则|规程)", [
        (r"发布|发文|印发|文号|字\s*[〔\[]|施行", "制度文书疑缺落款块(发布单位/文号)"),
    ]),
    (r"(法律服务|诉讼|执行).{0,4}方案|服务方案", [
        (r"服务团队|项目团队|团队(组成|成员|配置)", "服务方案缺'服务团队'章(模板必备要素)"),
        (r"(服务|律师)?(费用|收费|报价)", "服务方案缺'费用安排'章(模板必备要素)"),
        (r"落款|盖章|(律师事务所|本所)\s*$|年\s+月\s+日", "服务方案疑缺落款块"),
    ]),
]


def check_type_required(title, full):
    """标题命中文书类型时,检查模板必备章节;缺失报 ERROR。"""
    errs = []
    for type_pat, requirements in TYPE_REQUIRED:
        if re.search(type_pat, title):
            for req_pat, msg in requirements:
                if not re.search(req_pat, full, re.M):
                    errs.append(msg)
            break
    return errs


# 提交型文书(落款日期依题目时间线可确定)标题正则——命中且落款区含裸空日期即 ERROR。
# 管理办法/制度类(印发日期常留待发文)与函件不在此列,由通用 WARN 兜底。
DATED_TYPES_RE = re.compile(
    r"起诉状|上诉状|答辩状|仲裁申请书|申请书|代理词|辩护词|决议|情况说明|意见书")
# 裸空日期:年/月/日之间存在纯空白间隔(全角或半角),无数字、汉字数字或下划线占位。
# 至少一侧必须有空白,避免误伤正文"出生年月日""载明年月日"等连写词。
BLANK_DATE_RE = re.compile(r"年[　 \t]+月[　 \t]*日|年[　 \t]*月[　 \t]+日")


def check_blank_date(title, texts):
    if not DATED_TYPES_RE.search(title):
        return []
    # 只扫描落款区(文末若干行),不扫正文,避免误伤正文中的日期性叙述
    for line in texts[-8:]:
        m = BLANK_DATE_RE.search(line)
        if m:
            return [f"落款日期裸空——题目时间线可推知日期时必须填写;确不可知用'____年____月____日'显式留白: …{line[:40]}…"]
    return []


def check_table_grid(path):
    """XML 级表格结构检查:
    1. 相邻表格(</w:tbl> 紧跟 <w:tbl>)之间必须有段落——OOXML 相邻表格会被合并
       渲染,LibreOffice/飞书预览下边框丢失、布局失真(实证:内容表紧跟无边框签名块表)。
    2. 每个可见表格必须有 <w:tblGrid> 列宽定义,否则非 Word 渲染器自行猜测列宽。"""
    errs = []
    try:
        with zipfile.ZipFile(path) as z:
            xml = z.read("word/document.xml").decode("utf-8", "ignore")
    except Exception:
        return []
    adjacent = len(re.findall(r"</w:tbl>\s*<w:tbl>", xml))
    if adjacent:
        errs.append(
            f"{adjacent} 处相邻表格无分隔段落——相邻 <w:tbl> 会被合并渲染导致边框丢失,两表之间必须插入段落")
    # 「本页无正文」标记必须紧跟显式分页符:自然分页因渲染器字体度量不同而漂移,
    # 无显式分页符的标记在其他渲染器(飞书预览/WPS)里会变成谎言。
    for m in re.finditer(r"本页无正文", xml):
        before = xml[max(0, m.start() - 3000):m.start()]
        if 'w:type="page"' not in before and "pageBreakBefore" not in before:
            errs.append(
                "「本页无正文」标记前缺显式分页符——签署页独立成页必须先插分页符再写标记,否则其他渲染器下标记与页面内容矛盾")
            break
    tables = re.findall(r"<w:tbl>.*?</w:tbl>", xml, re.S)
    missing = 0
    for t in tables:
        if "<w:tblGrid" not in t or "<w:gridCol" not in t:
            missing += 1
    if missing:
        errs.append(
            f"{missing}/{len(tables)} 个表格缺 <w:tblGrid> 列宽定义——非 Word 渲染器会布局失真,建表必须写出与各列 tcW 一致的 gridCol")
    return errs



def extract_paragraph_texts(path):
    """纯标准库抽取 document.xml 中每个段落(含表格单元格内段落)的文本,按文档顺序。
    不依赖 python-docx/lxml,保证 Windows 内置 Python 与 macOS 系统 Python 均可运行。"""
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf-8", "ignore")
    texts = []
    for pm in re.finditer(r"<w:p[ >].*?</w:p>", xml, re.S):
        runs = re.findall(r"<w:t[^>]*>([^<]*)</w:t>", pm.group(0))
        text = html.unescape("".join(runs)).strip()
        if text:
            texts.append(text)
    return texts


def check(path):
    errors, warns = [], []
    try:
        texts = extract_paragraph_texts(path)
    except Exception as e:
        return [f"无法打开: {e}"], []
    if not texts:
        return ["文档为空"], []
    full = "\n".join(texts)
    title = texts[0] if texts else ""
    errors.extend(check_type_required(title, full))
    errors.extend(check_blank_date(title, texts))
    errors.extend(check_table_grid(path))
    for pat, msg in ERROR_PATTERNS:
        for line in texts:
            m = re.search(pat, line)
            if m:
                errors.append(f"{msg}: …{line[:60]}…")
                break
    for pat, msg in WARN_PATTERNS:
        for line in texts:
            if re.search(pat, line):
                warns.append(f"{msg}: …{line[:60]}…")
                break
    if len(full) < 200:
        warns.append(f"正文过短({len(full)} 字符),确认是否交付完整")
    if not DATE_HINT_RE.search(full):
        warns.append("未检出日期或日期占位行,确认落款是否符合规则")
    return errors, warns


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    any_error = False
    for path in sys.argv[1:]:
        errors, warns = check(path)
        print(f"== {path}")
        for e in errors:
            print(f"  ERROR {e}")
        for w in warns:
            print(f"  WARN  {w}")
        if not errors and not warns:
            print("  PASS")
        any_error = any_error or bool(errors)
    sys.exit(1 if any_error else 0)


if __name__ == "__main__":
    main()
