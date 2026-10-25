// Program.cs — 中文法律文书的编译验证基线。
//
// 模型编辑契约
// 1. 先改 DocumentTitle 和 AddDocumentContent。
// 2. 证据行只通过 EvidenceTable(...) 添加；其他任何可见表格一律通过
//    DataTable(...) 构建，保证始终带 tblGrid 和边框。
// 3. "STABLE OPENXML INFRASTRUCTURE" 以下的基建保持不动，
//    除非文书确实需要不支持的特性。
// 4. 中文直接写汉字，绝不使用 \uXXXX 编码。
// 5. 不得把扁平辅助方法替换成深层嵌套构造器。
// 6. 绝不让两个表格紧挨——内容表与 SignatureBlock 签名块表之间必须放
//    Spacer()（或真实段落），否则 LibreOffice/飞书预览下边框丢失
//    （OOXML 中相邻表格会被合并）。
// 7. 留白占位一律用显式下划线："第____号"、"____年____月____日"。
//    绝不用裸全角空格（"第　号"）。题目时间线给出的日期必须填写。
// 8. 列宽为非 Word 渲染器留余量：日期列 >= 2600 twips，
//    4 字标签列 >= 1500，6 字表头 >= 2000。
// 9. ChapterHeading(...) 仅用于章条体制度文书（"第X章"）；
//    ReportHeading1(...) 仅用于面向客户交付的报告类文书
//    （服务方案/法律意见书/专项报告）。诉状、函件、决议、
//    说明类文书保持 Heading1/Heading2。
// 10. 面向客户交付的报告类文书（约 6 页以上）默认封面+目录：
//    先调 CoverPage(...)，再调 TocPage(...)（每个一级章节一条），
//    并用 ReportHeading1Bookmarked(text, "secN") 创建各章节标题，
//    书签名与目录条目保持一致。页码由 PAGEREF 域在
//    Word/WPS/LibreOffice 三端解析。绝不向文档输出"更新域"类提示文字。

using System.Collections.Generic;
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;

namespace Docx;

public static class Program
{
    private const string DocumentTitle = "法律文书标题";

    public static void Main(string[] args)
    {
        string outputFile = args.Length > 0 ? args[0] : "./法律文书.docx";

        using WordprocessingDocument document = WordprocessingDocument.Create(
            outputFile,
            WordprocessingDocumentType.Document);

        MainDocumentPart mainPart = document.AddMainDocumentPart();
        mainPart.Document = new Document(new Body());
        Body body = mainPart.Document.Body!;

        AddStyles(mainPart);
        (string headerId, string footerId) = AddHeaderFooter(mainPart, DocumentTitle);
        (string firstHeaderId, string firstFooterId) = AddEmptyFirstPageHeaderFooter(mainPart);

        AddDocumentContent(body);
        body.Append(CreateFinalSection(headerId, footerId, firstHeaderId, firstFooterId));
        SetUpdateFieldsOnOpen(mainPart);

        document.Save();
        Console.WriteLine($"Saved: {outputFile}");
    }

    // =====================================================================
    // CONTENT EDITING AREA — 内容编辑区：以下每个示例/占位内容都要替换。
    // =====================================================================

    private static void AddDocumentContent(Body body)
    {
        body.Append(Title(DocumentTitle));

        // 传统诉讼文书排法：每个当事人或代表人一个自然段。
        body.Append(Party("原告：____，住所地____。"));
        body.Append(Party("法定代表人：____，职务：____。"));
        body.Append(Party("被告：____，住所地____。"));
        body.Append(Party("法定代表人：____，职务：____。"));

        body.Append(Heading1("请求事项"));
        body.Append(BodyParagraph("一、____；"));
        body.Append(BodyParagraph("二、____。"));

        body.Append(Heading1("事实与理由"));
        body.Append(Heading2("一、结论式小标题"));
        body.Append(BodyParagraph("请在此填写已经完成法律审校的正文。"));
        body.Append(Heading2("二、结论式小标题"));
        body.Append(BodyParagraph("请在此填写已经完成法律审校的正文。"));

        body.Append(Heading1("证据和证据来源"));
        IReadOnlyList<string[]> evidenceRows = new List<string[]>
        {
            new[] { "1", "证据名称", "具体证明目的" },
            new[] { "2", "证据名称", "具体证明目的" },
        };
        body.Append(EvidenceTable(evidenceRows));

        body.Append(KeepNextNoIndent("此致", "400"));
        body.Append(NoIndent("____人民法院"));

        body.Append(SignatureBlock(new[]
        {
            "具状人：____（盖章）",
            "法定代表人：____（签名）",
            "____年____月____日",
        }));

        body.Append(NoIndent("附："));
        body.Append(BodyParagraph("1. 本文书副本____份"));
        body.Append(BodyParagraph("2. 证据材料____套"));
    }

    // =====================================================================
    // STABLE OPENXML INFRASTRUCTURE — 稳定基建区：优先调用辅助方法，勿改动。
    // =====================================================================

    private const string BodyFont = "FangSong";
    private const string TitleFont = "SimSun";
    private const string Black = "000000";
    private const string BodySize = "28";   // 14 pt / 四号
    private const string TitleSize = "44";  // 22 pt / 二号

    private static void AddStyles(MainDocumentPart mainPart)
    {
        StyleDefinitionsPart stylesPart = mainPart.AddNewPart<StyleDefinitionsPart>();
        stylesPart.Styles = new Styles();

        Style normal = new Style
        {
            Type = StyleValues.Paragraph,
            StyleId = "Normal",
            Default = true,
        };
        normal.Append(new StyleName { Val = "Normal" });

        StyleParagraphProperties normalParagraph = new StyleParagraphProperties();
        normalParagraph.Append(LineSpacing("0", "120"));
        normalParagraph.Append(new Indentation { FirstLine = "560" });
        normal.Append(normalParagraph);

        StyleRunProperties normalRun = new StyleRunProperties();
        normalRun.Append(FontProperties(BodyFont));
        normalRun.Append(new FontSize { Val = BodySize });
        normalRun.Append(new Color { Val = Black });
        normal.Append(normalRun);
        stylesPart.Styles.Append(normal);

        stylesPart.Styles.Append(HeadingStyle("Heading1", "heading 1", 0, "600", "240"));
        stylesPart.Styles.Append(HeadingStyle("Heading2", "heading 2", 1, "400", "160"));
    }

    private static Style HeadingStyle(
        string styleId,
        string styleName,
        int outlineLevel,
        string before,
        string after)
    {
        Style style = new Style { Type = StyleValues.Paragraph, StyleId = styleId };
        style.Append(new StyleName { Val = styleName });
        style.Append(new BasedOn { Val = "Normal" });

        StyleParagraphProperties paragraphProperties = new StyleParagraphProperties();
        paragraphProperties.Append(new KeepNext());
        paragraphProperties.Append(new KeepLines());
        paragraphProperties.Append(LineSpacing(before, after));
        paragraphProperties.Append(new Indentation { FirstLine = "0" });
        paragraphProperties.Append(new OutlineLevel { Val = outlineLevel });
        style.Append(paragraphProperties);

        StyleRunProperties runProperties = new StyleRunProperties();
        runProperties.Append(new Bold());
        runProperties.Append(FontProperties(BodyFont));
        runProperties.Append(new FontSize { Val = BodySize });
        runProperties.Append(new Color { Val = Black });
        style.Append(runProperties);
        return style;
    }

    private static (string, string) AddHeaderFooter(
        MainDocumentPart mainPart,
        string headerText)
    {
        HeaderPart headerPart = mainPart.AddNewPart<HeaderPart>();
        string headerId = mainPart.GetIdOfPart(headerPart);
        Paragraph headerParagraph = new Paragraph();
        ParagraphProperties headerProperties = NoIndentProperties("0", "0", false);
        headerProperties.Append(new Justification { Val = JustificationValues.Right });
        headerProperties.GetFirstChild<SpacingBetweenLines>()!.Line = "240";
        headerParagraph.Append(headerProperties);
        headerParagraph.Append(TextRun(headerText, BodyFont, "18", false));
        headerPart.Header = new Header(headerParagraph);

        FooterPart footerPart = mainPart.AddNewPart<FooterPart>();
        string footerId = mainPart.GetIdOfPart(footerPart);
        Paragraph footerParagraph = new Paragraph();
        ParagraphProperties footerProperties = NoIndentProperties("0", "0", false);
        footerProperties.Append(new Justification { Val = JustificationValues.Center });
        footerProperties.GetFirstChild<SpacingBetweenLines>()!.Line = "240";
        footerParagraph.Append(footerProperties);
        footerParagraph.Append(FieldTextRun("第 "));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.Begin));
        footerParagraph.Append(FieldCodeRun(" PAGE "));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.Separate));
        footerParagraph.Append(FieldTextRun("1"));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.End));
        footerParagraph.Append(FieldTextRun(" 页 / 共 "));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.Begin));
        footerParagraph.Append(FieldCodeRun(" NUMPAGES "));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.Separate));
        footerParagraph.Append(FieldTextRun("1"));
        footerParagraph.Append(FieldCharacterRun(FieldCharValues.End));
        footerParagraph.Append(FieldTextRun(" 页"));
        footerPart.Footer = new Footer(footerParagraph);

        return (headerId, footerId);
    }

    private static Paragraph Title(string text)
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = NoIndentProperties("0", "360", true);
        properties.Append(new Justification { Val = JustificationValues.Center });
        paragraph.Append(properties);

        RunProperties runProperties = BaseRunProperties(TitleFont, TitleSize, true);
        runProperties.Append(new Spacing { Val = 30 });
        paragraph.Append(TextRun(text, runProperties));
        return paragraph;
    }

    private static Paragraph Party(string text)
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(NoIndentProperties("0", "0", false));
        paragraph.Append(TextRun(GluePlaceholders(text), BodyFont, BodySize, false));
        return paragraph;
    }

    // 用 WORD JOINER(U+2060)把下划线占位与其后标点粘连,防止行尾折行剩下孤立"_。"。
    private static string GluePlaceholders(string text)
    {
        return text
            .Replace("____。", "____\u2060。")
            .Replace("____，", "____\u2060，")
            .Replace("____；", "____\u2060；");
    }

    private static Paragraph Heading1(string text)
    {
        return StyledParagraph(text, "Heading1");
    }

    private static Paragraph Heading2(string text)
    {
        return StyledParagraph(text, "Heading2");
    }

    // 章条体制度专用："第X章" 章级标题,居中、16pt 加粗,作为章级检索锚点。
    private static Paragraph ChapterHeading(string text)
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = NoIndentProperties("480", "240", true);
        properties.Append(new Justification { Val = JustificationValues.Center });
        properties.Append(new OutlineLevel { Val = 0 });
        paragraph.Append(properties);
        paragraph.Append(TextRun(text, BodyFont, "32", true));
        return paragraph;
    }

    // 报告体客户交付件专用:一级标题 16pt 加粗形成层级梯度;二级及以下用 Heading2。
    private static Paragraph ReportHeading1(string text)
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = NoIndentProperties("600", "240", true);
        properties.Append(new OutlineLevel { Val = 0 });
        paragraph.Append(properties);
        paragraph.Append(TextRun(text, BodyFont, "32", true));
        return paragraph;
    }

    private static uint bookmarkCounter = 100;
    private static bool hasCoverPage;

    // 报告体一级标题 + 书签(供目录 PAGEREF 引用)。bookmarkName 如 "sec1"。
    private static Paragraph ReportHeading1Bookmarked(string text, string bookmarkName)
    {
        Paragraph paragraph = ReportHeading1(text);
        uint id = bookmarkCounter++;
        paragraph.InsertAfter(
            new BookmarkStart { Name = bookmarkName, Id = id.ToString() },
            paragraph.GetFirstChild<ParagraphProperties>());
        paragraph.Append(new BookmarkEnd { Id = id.ToString() });
        return paragraph;
    }

    // 报告体封面:纯排印(不依赖图片背景),居中大标题+副题+委托/受托信息+日期,
    // 末尾自动分页。仅用于面向客户的报告体交付件。
    private static void CoverPage(
        Body body,
        string title,
        string subtitle,
        IReadOnlyList<string> metaLines,
        string dateLine)
    {
        hasCoverPage = true;
        body.Append(CoverSpacer("2400"));
        body.Append(CoverRule());

        Paragraph titleParagraph = new Paragraph();
        ParagraphProperties titleProperties = NoIndentProperties("360", "240", false);
        titleProperties.Append(new Justification { Val = JustificationValues.Center });
        titleParagraph.Append(titleProperties);
        RunProperties titleRun = BaseRunProperties(TitleFont, "52", true);
        titleRun.Append(new Spacing { Val = 40 });
        titleParagraph.Append(TextRun(title, titleRun));
        body.Append(titleParagraph);

        if (!string.IsNullOrEmpty(subtitle))
        {
            Paragraph subtitleParagraph = new Paragraph();
            ParagraphProperties subtitleProperties = NoIndentProperties("120", "0", false);
            subtitleProperties.Append(new Justification { Val = JustificationValues.Center });
            subtitleParagraph.Append(subtitleProperties);
            subtitleParagraph.Append(TextRun(subtitle, BodyFont, "32", false));
            body.Append(subtitleParagraph);
        }

        body.Append(CoverRule());
        body.Append(CoverSpacer("3600"));

        foreach (string line in metaLines)
        {
            Paragraph metaParagraph = new Paragraph();
            ParagraphProperties metaProperties = NoIndentProperties("0", "120", false);
            metaProperties.Append(new Justification { Val = JustificationValues.Center });
            metaParagraph.Append(metaProperties);
            metaParagraph.Append(TextRun(line, BodyFont, BodySize, false));
            body.Append(metaParagraph);
        }

        Paragraph dateParagraph = new Paragraph();
        ParagraphProperties dateProperties = NoIndentProperties("240", "0", false);
        dateProperties.Append(new Justification { Val = JustificationValues.Center });
        dateParagraph.Append(dateProperties);
        dateParagraph.Append(TextRun(dateLine, BodyFont, BodySize, false));
        body.Append(dateParagraph);

        body.Append(PageBreakParagraph());
    }

    // 目录页:标题"目 录"+静态条目(文字+点线制表位+PAGEREF 书签页码域),
    // 末尾自动分页。entries 的书签名必须与 ReportHeading1Bookmarked 一致。
    private static void TocPage(Body body, IReadOnlyList<(string Text, string Bookmark)> entries)
    {
        Paragraph tocTitle = new Paragraph();
        ParagraphProperties tocTitleProperties = NoIndentProperties("0", "360", true);
        tocTitleProperties.Append(new Justification { Val = JustificationValues.Center });
        tocTitle.Append(tocTitleProperties);
        RunProperties tocTitleRun = BaseRunProperties(TitleFont, "36", true);
        tocTitleRun.Append(new Spacing { Val = 60 });
        tocTitle.Append(TextRun("目 录", tocTitleRun));
        body.Append(tocTitle);

        foreach ((string text, string bookmark) in entries)
        {
            Paragraph line = new Paragraph();
            ParagraphProperties lineProperties = NoIndentProperties("0", "160", false);
            lineProperties.Append(new Tabs(
                new TabStop
                {
                    Val = TabStopValues.Right,
                    Leader = TabStopLeaderCharValues.Dot,
                    Position = 8900,
                }));
            line.Append(lineProperties);
            line.Append(TextRun(text, BodyFont, BodySize, false));
            Run tabRun = new Run();
            tabRun.Append(BaseRunProperties(BodyFont, BodySize, false));
            tabRun.Append(new TabChar());
            line.Append(tabRun);
            line.Append(FieldCharacterRun(FieldCharValues.Begin));
            line.Append(FieldCodeRun($" PAGEREF {bookmark} \\h "));
            line.Append(FieldCharacterRun(FieldCharValues.Separate));
            line.Append(TextRun("1", BodyFont, BodySize, false));
            line.Append(FieldCharacterRun(FieldCharValues.End));
            body.Append(line);
        }

        body.Append(PageBreakParagraph());
    }

    private static Paragraph CoverSpacer(string before)
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(NoIndentProperties(before, "0", false));
        return paragraph;
    }

    private static Paragraph CoverRule()
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = NoIndentProperties("0", "0", false);
        properties.Append(new ParagraphBorders(
            new BottomBorder { Val = BorderValues.Single, Size = 8, Color = Black }));
        paragraph.Append(properties);
        return paragraph;
    }

    private static Paragraph PageBreakParagraph()
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(NoIndentProperties("0", "0", false));
        Run run = new Run();
        run.Append(new Break { Type = BreakValues.Page });
        paragraph.Append(run);
        return paragraph;
    }

    // 表格之间的分隔段(相邻表格会被合并渲染丢边框)。不用于制造视觉空距。
    private static Paragraph Spacer()
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = new ParagraphProperties();
        properties.Append(new Indentation { FirstLine = "0" });
        properties.Append(new SpacingBetweenLines
        {
            Before = "0",
            After = "0",
            Line = "240",
            LineRule = LineSpacingRuleValues.Auto,
        });
        paragraph.Append(properties);
        return paragraph;
    }

    private static Paragraph StyledParagraph(string text, string styleId)
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = new ParagraphProperties();
        properties.Append(new ParagraphStyleId { Val = styleId });
        paragraph.Append(properties);
        paragraph.Append(new Run(new Text(text)));
        return paragraph;
    }

    private static Paragraph BodyParagraph(string text)
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(new Run(new Text(text)));
        return paragraph;
    }

    private static Paragraph NoIndent(string text)
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(NoIndentProperties("0", "0", false));
        paragraph.Append(TextRun(text, BodyFont, BodySize, false));
        return paragraph;
    }

    private static Paragraph KeepNextNoIndent(string text, string before)
    {
        Paragraph paragraph = new Paragraph();
        paragraph.Append(NoIndentProperties(before, "0", true));
        paragraph.Append(TextRun(text, BodyFont, BodySize, false));
        return paragraph;
    }

    private static Table EvidenceTable(IReadOnlyList<string[]> rows)
    {
        return DataTable(
            new[] { "序号", "证据名称", "证明目的" },
            rows,
            new[] { "900", "3100", "5000" },
            0);
    }

    // 通用可见表格:强制 tblGrid + 全框线 + 跨页重复表头 + 行不拆分。
    // widths 为各列 twips(列宽下限:日期列 2600、4字标签列 1500、6字表头 2000);
    // centeredColumn 为需要居中的列下标(-1 表示无)。
    private static Table DataTable(
        string[] headers,
        IReadOnlyList<string[]> rows,
        string[] widths,
        int centeredColumn)
    {
        if (headers.Length != widths.Length)
        {
            throw new ArgumentException("headers and widths must have the same length.");
        }

        Table table = new Table();
        table.Append(EvidenceTableProperties());
        TableGrid grid = new TableGrid();
        foreach (string width in widths)
        {
            grid.Append(new GridColumn { Width = width });
        }
        table.Append(grid);

        table.Append(DataRow(headers, widths, true, centeredColumn));
        foreach (string[] row in rows)
        {
            if (row.Length != headers.Length)
            {
                throw new ArgumentException("Each row must match the header column count.");
            }
            table.Append(DataRow(row, widths, false, centeredColumn));
        }
        return table;
    }

    private static TableRow DataRow(
        string[] cells,
        string[] widths,
        bool header,
        int centeredColumn)
    {
        TableRowProperties rowProperties = new TableRowProperties();
        rowProperties.Append(new CantSplit());
        if (header)
        {
            rowProperties.Append(new TableHeader());
        }

        TableRow row = new TableRow();
        row.Append(rowProperties);
        for (int index = 0; index < cells.Length; index++)
        {
            row.Append(EvidenceCell(
                cells[index],
                widths[index],
                header,
                index == centeredColumn));
        }
        return row;
    }

    // 表格题注:"表1：××××",置于表格上方,与表格保持同页。
    private static Paragraph TableCaption(string text)
    {
        Paragraph paragraph = new Paragraph();
        ParagraphProperties properties = NoIndentProperties("200", "80", true);
        paragraph.Append(properties);
        paragraph.Append(TextRun(text, BodyFont, BodySize, true));
        return paragraph;
    }

    private static TableProperties EvidenceTableProperties()
    {
        TableProperties properties = new TableProperties();
        properties.Append(new TableWidth { Width = "5000", Type = TableWidthUnitValues.Pct });
        properties.Append(new TableBorders(
            new TopBorder { Val = BorderValues.Single, Size = 12, Color = Black },
            new LeftBorder { Val = BorderValues.Single, Size = 4, Color = Black },
            new BottomBorder { Val = BorderValues.Single, Size = 12, Color = Black },
            new RightBorder { Val = BorderValues.Single, Size = 4, Color = Black },
            new InsideHorizontalBorder { Val = BorderValues.Single, Size = 4, Color = Black },
            new InsideVerticalBorder { Val = BorderValues.Single, Size = 4, Color = Black }));
        return properties;
    }

    private static TableCell EvidenceCell(
        string text,
        string width,
        bool bold,
        bool centered)
    {
        TableCellProperties cellProperties = new TableCellProperties();
        cellProperties.Append(new TableCellWidth
        {
            Width = width,
            Type = TableWidthUnitValues.Dxa,
        });
        if (bold)
        {
            // 表头行浅灰底纹,增强表头辨识度(仅表头,数据行保持白底)。
            cellProperties.Append(new Shading
            {
                Val = ShadingPatternValues.Clear,
                Color = "auto",
                Fill = "F2F2F2",
            });
        }

        Paragraph paragraph = new Paragraph();
        // 表格单元格用单倍行距(240):正文 1.5 倍行距进单元格会让表格行虚胖。
        ParagraphProperties paragraphProperties = new ParagraphProperties();
        paragraphProperties.Append(new Indentation { FirstLine = "0" });
        paragraphProperties.Append(new SpacingBetweenLines
        {
            Before = "40",
            After = "40",
            Line = "240",
            LineRule = LineSpacingRuleValues.Auto,
        });
        if (centered)
        {
            paragraphProperties.Append(new Justification { Val = JustificationValues.Center });
        }
        paragraph.Append(paragraphProperties);
        paragraph.Append(TextRun(text, BodyFont, BodySize, bold));

        TableCell cell = new TableCell();
        cell.Append(cellProperties);
        cell.Append(paragraph);
        return cell;
    }

    private static Table SignatureBlock(IReadOnlyList<string> lines)
    {
        Table table = new Table();
        TableProperties tableProperties = new TableProperties();
        tableProperties.Append(new TableWidth { Width = "9000", Type = TableWidthUnitValues.Dxa });
        tableProperties.Append(new TableBorders(
            new TopBorder { Val = BorderValues.Nil },
            new LeftBorder { Val = BorderValues.Nil },
            new BottomBorder { Val = BorderValues.Nil },
            new RightBorder { Val = BorderValues.Nil },
            new InsideHorizontalBorder { Val = BorderValues.Nil },
            new InsideVerticalBorder { Val = BorderValues.Nil }));
        table.Append(tableProperties);
        table.Append(new TableGrid(new GridColumn { Width = "9000" }));

        TableCellProperties cellProperties = new TableCellProperties();
        cellProperties.Append(new TableCellWidth
        {
            Width = "9000",
            Type = TableWidthUnitValues.Dxa,
        });
        TableCell cell = new TableCell();
        cell.Append(cellProperties);

        for (int index = 0; index < lines.Count; index++)
        {
            Paragraph paragraph = new Paragraph();
            ParagraphProperties properties = NoIndentProperties(
                index == 0 ? "600" : "0",
                "80",
                index < lines.Count - 1);
            properties.Append(new Justification { Val = JustificationValues.Right });
            paragraph.Append(properties);
            paragraph.Append(TextRun(lines[index], BodyFont, BodySize, false));
            cell.Append(paragraph);
        }

        TableRowProperties rowProperties = new TableRowProperties();
        rowProperties.Append(new CantSplit());
        TableRow row = new TableRow();
        row.Append(rowProperties);
        row.Append(cell);
        table.Append(row);
        return table;
    }

    // 封面页专用:空白首页页眉/页脚(封面不挂页眉页脚,由 TitlePage 引用)。
    private static (string, string) AddEmptyFirstPageHeaderFooter(MainDocumentPart mainPart)
    {
        HeaderPart headerPart = mainPart.AddNewPart<HeaderPart>();
        headerPart.Header = new Header(new Paragraph());
        FooterPart footerPart = mainPart.AddNewPart<FooterPart>();
        footerPart.Footer = new Footer(new Paragraph());
        return (mainPart.GetIdOfPart(headerPart), mainPart.GetIdOfPart(footerPart));
    }

    private static SectionProperties CreateFinalSection(
        string headerId,
        string footerId,
        string firstHeaderId,
        string firstFooterId)
    {
        SectionProperties section = new SectionProperties();
        section.Append(new HeaderReference
        {
            Type = HeaderFooterValues.Default,
            Id = headerId,
        });
        section.Append(new FooterReference
        {
            Type = HeaderFooterValues.Default,
            Id = footerId,
        });
        if (hasCoverPage)
        {
            section.Append(new HeaderReference
            {
                Type = HeaderFooterValues.First,
                Id = firstHeaderId,
            });
            section.Append(new FooterReference
            {
                Type = HeaderFooterValues.First,
                Id = firstFooterId,
            });
            section.Append(new TitlePage());
        }
        section.Append(new PageSize { Width = 11906, Height = 16838 });
        section.Append(new PageMargin
        {
            Top = 1800,
            Right = 1440,
            Bottom = 1440,
            Left = 1440,
            Header = 720,
            Footer = 720,
        });
        return section;
    }

    private static ParagraphProperties NoIndentProperties(
        string before,
        string after,
        bool keepNext)
    {
        ParagraphProperties properties = new ParagraphProperties();
        if (keepNext)
        {
            properties.Append(new KeepNext());
            properties.Append(new KeepLines());
        }
        properties.Append(new Indentation { FirstLine = "0" });
        properties.Append(LineSpacing(before, after));
        return properties;
    }

    private static SpacingBetweenLines LineSpacing(string before, string after)
    {
        return new SpacingBetweenLines
        {
            Before = before,
            After = after,
            Line = "360",
            LineRule = LineSpacingRuleValues.Auto,
        };
    }

    private static Run TextRun(string text, string font, string size, bool bold)
    {
        return TextRun(text, BaseRunProperties(font, size, bold));
    }

    private static Run TextRun(string text, RunProperties properties)
    {
        Run run = new Run();
        run.Append(properties);
        run.Append(new Text(text) { Space = SpaceProcessingModeValues.Preserve });
        return run;
    }

    private static RunProperties BaseRunProperties(string font, string size, bool bold)
    {
        RunProperties properties = new RunProperties();
        properties.Append(FontProperties(font));
        properties.Append(new FontSize { Val = size });
        properties.Append(new Color { Val = Black });
        if (bold)
        {
            properties.Append(new Bold());
        }
        return properties;
    }

    private static RunFonts FontProperties(string font)
    {
        return new RunFonts
        {
            Ascii = font,
            HighAnsi = font,
            EastAsia = font,
        };
    }

    private static Run FieldTextRun(string text)
    {
        return TextRun(text, BodyFont, "18", false);
    }

    private static Run FieldCharacterRun(FieldCharValues type)
    {
        Run run = new Run();
        run.Append(BaseRunProperties(BodyFont, "18", false));
        run.Append(new FieldChar { FieldCharType = type });
        return run;
    }

    private static Run FieldCodeRun(string code)
    {
        Run run = new Run();
        run.Append(BaseRunProperties(BodyFont, "18", false));
        run.Append(new FieldCode(code) { Space = SpaceProcessingModeValues.Preserve });
        return run;
    }

    private static void SetUpdateFieldsOnOpen(MainDocumentPart mainPart)
    {
        DocumentSettingsPart settingsPart =
            mainPart.DocumentSettingsPart ?? mainPart.AddNewPart<DocumentSettingsPart>();
        settingsPart.Settings = new Settings(new UpdateFieldsOnOpen { Val = true });
    }
}
