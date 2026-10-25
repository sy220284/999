# OpenXML SDK 创建参考

使用 C# + OpenXML SDK 创建新的 Word 文档。

工作流：初始化宿主机可见的工作目录 → 编写 `Program.cs` → 运行统一客户端 → 校验通过后交付。

## 1. 构建命令

```bash
{python} skills/docx/scripts/docx_http.py <command> [args]
```

| 命令 | 用途 |
|---------|---------|
| `build <output-path>` | `/legal-dotnet` 编译 → 宿主机 .NET Runtime 生成 → `/legal-validator` 修复并校验 |
| `validate <file.docx>` | `/legal-validator` 修复并做 OpenXML 校验 |
| `init [--fresh]` | 初始化宿主机可见的工作目录；`--fresh` 安装当前经过编译测试的法律文书基线 |
| `env` | 显示三个 HTTP 服务及宿主机 .NET Runtime 状态 |

`build` 会完成全部工作。不得手动编译或运行 `Program.cs`：编译由 `/legal-dotnet` 云服务负责，宿主机 .NET 8 Runtime 执行返回的 `Docx.dll`，`/legal-validator` 负责修复与校验。

## 法律文书基线与编译预检

对于普通中文法律文书，运行 `docx init --fresh`；它会把经过编译测试的 `assets/templates/Program.cs` 基线放入本任务的隔离工作目录。**必须在该文件上就地编辑；不得用从零编写的程序替换它。** 仅当必须保留已有的进行中源码时才使用不带参数的 `docx init`。预期的编辑面被有意收窄：

1. 替换 `DocumentTitle`；
2. 把 `AddDocumentContent` 内的示例调用替换为本案的当事人信息、请求事项、标题、正文、证据行、受文机关、落款块和附件；
3. 复用 `Title`、`Party`、`Heading1`、`Heading2`、`BodyParagraph`、`EvidenceTable`、`SignatureBlock` 及其他扁平化辅助方法；
4. `STABLE OPENXML INFRASTRUCTURE` 以下的所有内容保持不动，除非任务需要其尚不支持的功能。

在完成第一遍完整内容后、进行任何由渲染驱动的细化之前，运行 `docx compile <临时发布目录>`。每次修改辅助方法、样式、表格、页眉/页脚、域或分节后，必须再次运行编译。仅在已编译通过的调用内部做纯文本替换时可以直接进入 `build`；任何涉及括号、方法定义、构造函数结构或 OpenXML 类型的改动都必须先重新编译。

编译前，必须逐项检查以下内容：

- 中文散文直接以 UTF-8 文本书写。中文绝不使用 `\uXXXX` 编码，普通文本前也不得有悬空的反斜杠。
- 所有 C# 语法标点均为 ASCII；C# 字符串内部的引号转义为 `\"`。
- 辅助方法的定义与调用具有相同的参数个数和类型。结构不同的段落应使用专门的辅助方法，而不是省略必需参数。
- 页面尺寸使用 `new PageSize { Width = 11906, Height = 16838 }`。不得添加 `u` 后缀，也不得凭猜测使用 `UInt32Value`/`Int32Value` 包装。
- SDK 类型为字符串支撑的 OpenXML 属性（包括常见的 `Width` 属性）应接收字符串，例如 `Width = "9000"`。
- `ParagraphProperties`、`RunProperties`、`TableCellProperties` 或其他属性容器中不得包含重复的单例子元素。要修改间距，应修改或重建已有的 `SpacingBetweenLines`；绝不追加第二个。
- `RunProperties` 仅包含 run 属性；`Text`、`FieldChar`、`FieldCode`、`Drawing` 等子元素归属于同级的 `Run`。绝不把 `Run` 嵌套进 `RunProperties`。
- SDK 类型名称与子元素顺序必须从基线或已知有效的参考中复制。不得凭猜测使用复数形式，如 `TableCellMargins`。若单元格边距并非必需，应省略而不是即兴编造 schema 顺序。
- 深层构造函数表达式已被拍平。一条内容调用通常应放在一行内；辅助方法应以具名步骤配合 `Append` 构建对象，而不是通过五层或更多层嵌套构造函数。

编译结果处理纪律：

- `CS1026`、`CS1002` 及类似语法错误：检查首个报告位置上下至少 15 行源码；真正未配对的定界符可能在报告行之前。仅修复该区域，然后再次编译。
- `CS1009`：删除有问题的转义，直接书写中文。不得把整个文档改成逐字字符串（verbatim string）作为一刀切的变通。
- `CS7036`：对比辅助方法签名与每一处调用；不得添加会改变文书语义的占位参数。
- `CS0029`：逐字复制基线中的属性赋值，而不是靠试错添加强制转换或数字后缀。
- `CS0246`：应假定 SDK 类型名是猜错的；对照基线/参考核实，而不是盲目添加命名空间。
- `CS0219`、`CS8321` 等普通的未使用变量/本地函数警告不是编译失败。它们可以被清理，但绝不得触发大规模重写。
- 若同一源码区域失败两次，停止逐个修补括号，改用基线的扁平具名步骤模式重写该区域。除非内容本身已损坏，不得重写整个文档。
- 宿主机生成阶段提示输出文件被占用的 `IOException` 不是编译错误。关闭 Word/WPS 或改用其他临时输出文件名生成；不得因此修改有效的 C# 代码。

## 常见陷阱（先读这里）

**元素复用 → InvalidOperationException**：每个 OpenXmlElement 实例只属于一个父元素。绝不把同一个 RunProperties/ParagraphProperties Append 到多个 Run/Paragraph。使用工厂方法或 `CloneNode(true)`。

**C# 语法位置出现 CJK 标点 → CS1056**：中文文本在 ASCII `"` 定界符内是安全的。但中文 `，；（）""` 一旦泄漏到 C# 语法位置（参数列表、语句结束符、字符串定界符）就会导致编译失败。完整示例见 `CJKExample.cs` 文件头。

**枚举作默认参数 → CS1736**：`JustificationValues.Left` 不是编译期常量。使用可空类型 + null 合并：`void Foo(JustificationValues? align = null) { align ??= ...; }`

**是 TitlePage，不是 DifferentFirstPage**：SDK 中不存在 `DifferentFirstPage`。使用 `new TitlePage()`。

**超链接**：使用 `AddHyperlinkRelationship(uri, true)`，而不是 `AddExternalRelationship`。

**中文引号破坏字符串边界 → CS1003**：字符串内部的双引号使用 `\"` 转义。**任何中文文本绝不使用 `\uXXXX`。**

```csharp
// WRONG — quotes break the string:
P("遵循"统筹规划"的原则")          // CS1003!

// CORRECT — escape with \":
P("遵循\"统筹规划\"的原则")
```

**括号嵌套 → CS1002/CS1026**：过早闭合 `ParagraphProperties` 会使子元素成为孤儿。这是深层 OpenXML 构造函数中最常见的 bug。

```csharp
// WRONG — Justification is OUTSIDE ParagraphProperties:
new Paragraph(
    new ParagraphProperties(
        new SpacingBetweenLines { Before = "200" }),     // ← closes ParagraphProperties!
        new Justification { Val = JustificationValues.Left }),  // ← orphaned!
    new Run(...))

// CORRECT — all children INSIDE ParagraphProperties:
new Paragraph(
    new ParagraphProperties(
        new SpacingBetweenLines { Before = "200" },
        new Justification { Val = JustificationValues.Left }),  // ← inside
    new Run(...))
```

使用辅助方法（`MkPara`、`MkH1`）拍平嵌套——见 Example.cs / CJKExample.cs。

法律文书的首选模式更加扁平：先实例化父元素，追加其属性，再追加其内容。避免靠反复数右括号来修补冗长的构造函数表达式。

```csharp
var paragraph = new Paragraph();
var properties = new ParagraphProperties();
properties.Append(new KeepNext());
properties.Append(new Indentation { FirstLine = "0" });
properties.Append(new SpacingBetweenLines
{
    Before = "200",
    After = "80",
    Line = "360",
    LineRule = LineSpacingRuleValues.Auto,
});
paragraph.Append(properties);
paragraph.Append(new Run(new Text(text)));
```

**重复的单例属性子元素 → OpenXML schema 错误**：一个属性容器通常只有一个 `SpacingBetweenLines`、一个 `Indentation`、一个 `Justification` 等。`Append` 不会替换已存在的子元素。

```csharp
// WRONG: helper already created spacing, so this creates a second <w:spacing>.
var paragraph = NoIndent("此致");
paragraph.ParagraphProperties!.Append(
    new SpacingBetweenLines { Before = "400", Line = "360" });

// CORRECT: pass the spacing into a helper that constructs the property once.
var paragraph = KeepNextNoIndent("此致", "400");
```

**Run 的归属 → 非法子元素 `w:r`**：`RunProperties` 仅用于字体/字号/颜色/加粗等。`Run` 是其同级或父级的内容对象，绝不是 `RunProperties` 的子元素。

**属性标量类型 → CS0029**：复制经过测试的声明，而不是猜测有符号/无符号包装或添加 `u` 后缀。

```csharp
new PageSize { Width = 11906, Height = 16838 }
new TableCellWidth { Width = "3000", Type = TableWidthUnitValues.Dxa }
```

**Schema 顺序是有意义的**：OpenXML 不是无序对象图。必须复制已知有效的构造顺序。普通法律文书使用基线的表格辅助方法，并省略自定义 `TableCellMargin`，除非它不可或缺且经过单独校验。

**TableGrid 必须是 Table 的子元素，而不是 TableProperties 的 → "missing tblGrid"**：

```csharp
// WRONG — TableGrid nested inside TableProperties:
new Table(new TableProperties(..., new TableGrid(...)))

// CORRECT — TableGrid is sibling of TableProperties:
new Table(
    new TableProperties(...)),  // ) closes Properties
    new TableGrid(...));        // sibling, child of Table
```

## 颜色与排版

中文法律文书必须使用以下默认层级，除非官方表单、给定模板或用户的明确指示另有覆盖：

- 文档唯一主标题：`SimSun`，22 pt（`FontSize Val="44"`），加粗，`000000`，居中；
- 每一级下级标题：`FangSong`，14 pt（`Val="28"`），加粗，`000000`；
- 正文：`FangSong`，14 pt，常规，`000000`；仅对简短的功能性标签、合计数或真正关键的结论使用加粗；
- 表格、列表、图注和落款块：`FangSong`，14 pt，`000000`；表头可以加粗；
- 页眉、页脚和页码：黑色 `FangSong`，允许小于正文字号。
- 主标题、下级标题、当事人信息、请求事项、正文、列表、图注、受文机关和落款块：使用 `Line="360"` 配合 `LineRule=Auto` 的 1.5 倍比例行距；**表格单元格段落是唯一常设例外，用单倍行距（`Line="240"`、`Auto`）保持表格行紧凑**（权威规则见 SKILL.md 硬规则 13）；封面及紧凑的页眉/页脚/页码可使用单独的行距。

使用编号、间距和缩进——而不是颜色或无衬线字体——来表达下级层次。经过刻意设计的报告封面可以使用特殊排版，但正文必须回归上述层级。

仅限封面/封底：选定一个色相方向并保持其内。反面模式：纯 #FF0000/#0000FF/#00FF00、全蓝文档、彩虹图表、文字与背景同色。

有效的封面色相方向（示例，非强制）：
- 暖调大地色：#5D4037, #795548, #D4A574
- 冷调石板色：#37474F, #546E7A, #78909C
- 自然绿：#4A6741, #7C9885, #9CAF88
- 中性灰 + 单一强调色：#333, #666, #999 + 任意单一色相
- 莓果/李紫：#6B3A5B, #8E6278, #C9A0B5
- 墨与金：#263238, #455A64, #B8860B

行距：面向正文的法律文书段落使用 1.5 倍行距。在 OpenXML 中使用 `new SpacingBetweenLines { Line = "360", LineRule = LineSpacingRuleValues.Auto }`；只有当规则为 `Auto` 时 `360` 才等于 1.5 倍行距。不得使用 `Exact` 或固定磅值行高，那会裁切 14 pt CJK 文本和行内对象。段前/段后间距保持独立：标题的段前间距应大于段后，而普通正文段落只需适度分隔。CJK 正文使用 2 字符首行缩进。大号 CJK 标题应加字间距以避免拥挤观感。表格单元格需要少量内边距，并采用单倍行距（`Line="240"`、`Auto`，唯一常设例外，见 SKILL.md 硬规则 13）。图注与其所属图形的距离应比与后续文字更近。

正式文书默认包含页眉（文档标题）和页脚（页码）。

## 2. Program.cs 初始配置

```csharp
using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using DocumentFormat.OpenXml.Wordprocessing;
using DW = DocumentFormat.OpenXml.Drawing.Wordprocessing;
using A = DocumentFormat.OpenXml.Drawing;
using PIC = DocumentFormat.OpenXml.Drawing.Pictures;
using C = DocumentFormat.OpenXml.Drawing.Charts;  // if charts needed
using M = DocumentFormat.OpenXml.Math;             // if math needed

string outputFile = args.Length > 0 ? args[0] : "./output.docx";
using var doc = WordprocessingDocument.Create(outputFile, WordprocessingDocumentType.Document);
var mainPart = doc.AddMainDocumentPart();
mainPart.Document = new Document(new Body());
var body = mainPart.Document.Body!;
// ... build document ...
doc.Save();
```

**A4**：11906 × 16838 twips = 7560000 × 10692000 EMU。标准页边距：1440 twips（1 英寸）。

## 3. 文档架构

分节通过 `SectionProperties` 链接。每一节以一个 Paragraph 结束，其 ParagraphProperties 中包含 SectionProperties。**最后**一节的 SectionProperties 直接放在 Body 上。

```
Cover   (TitlePage, zero margins)        → NextPage
→ TOC   (normal margins)                  → NextPage
→ Body  (HeaderRef + FooterRef, margins)  → NextPage
→ Back  (zero margins)                    → final SectionProperties on body
```

```csharp
// Mid-document section break
body.Append(new Paragraph(new ParagraphProperties(
    new SectionProperties(
        new SectionType { Val = SectionMarkValues.NextPage },
        new PageSize { Width = 11906, Height = 16838 },
        new PageMargin { Top = 1800, Right = 1440, Bottom = 1440, Left = 1440,
                         Header = 720, Footer = 720 }))));

// Final section (directly on body, not in paragraph)
body.Append(new SectionProperties(
    new PageSize { Width = 11906, Height = 16838 },
    new PageMargin { Top = 0, Right = 0, Bottom = 0, Left = 0, Header = 0, Footer = 0 }));
```

## 4. 模式

### 4.1 样式

对于没有特殊封面的法律文书，把文档级唯一主标题创建为直接格式化段落或专用的非大纲样式。不得复用 `Heading1`，因为主标题的排版不同，且通常不应成为目录条目：

```csharp
static Paragraph CreateLegalMainTitle(string text) => new Paragraph(
    new ParagraphProperties(
        new KeepNext(), new KeepLines(),
        new Justification { Val = JustificationValues.Center },
        new Indentation { FirstLine = "0" },
        new SpacingBetweenLines { After = "360", Line = "360", LineRule = LineSpacingRuleValues.Auto }),
    new Run(
        new RunProperties(
            new Bold(), new FontSize { Val = "44" },
            new RunFonts { Ascii = "SimSun", HighAnsi = "SimSun", EastAsia = "SimSun" },
            new Color { Val = "000000" }),
        new Text(text)));
```

`Heading1`/`Heading2`/`Heading3` 仅用于下级标题。它们全部使用加粗黑色仿宋 14 pt；使用编号、缩进和段间距——而不是不同的字号——来体现层次。

```csharp
var stylesPart = mainPart.AddNewPart<StyleDefinitionsPart>();
stylesPart.Styles = new Styles();

stylesPart.Styles.Append(new Style(
    new StyleName { Val = "heading 1" },       // built-in name (lowercase)
    new BasedOn { Val = "Normal" },
    new StyleParagraphProperties(
        new KeepNext(), new KeepLines(),
        new SpacingBetweenLines { Before = "600", After = "240", Line = "360", LineRule = LineSpacingRuleValues.Auto },
        new OutlineLevel { Val = 0 }           // 0=H1, 1=H2, 2=H3
    ),
    new StyleRunProperties(
        new Bold(), new FontSize { Val = "28" },
        new RunFonts { Ascii = "FangSong", HighAnsi = "FangSong", EastAsia = "FangSong" },
        new Color { Val = "000000" }
    )
) { Type = StyleValues.Paragraph, StyleId = "Heading1" });
```

- `StyleId`（代码引用）≠ `StyleName.Val`（Word 中的显示名）。代码中用 `Heading1`，名称用 `heading 1`。
- 目录识别必须有 `OutlineLevel`。目录只认内置样式名。
- 字号以半磅计：12pt = `Val="24"`。
- CJK 文档必须始终在 RunFonts 上设置 `EastAsia`——否则 CJK 会以 Calibri 渲染。

### 4.2 封面与封底

并非每份文档都需要封面——但当适合使用封面时，必须从零生成背景（见 §4.14）。背景配色与封面文字必须与正文使用同一套配色方案，确保封面是文档协调的一部分，而不是格格不入的附加物。封面文字必须在背景上可读——足够的对比度、大字号、宽裕的间距。

封面 = 浮动背景 + 标题段落 + SectionProperties(TitlePage, 零页边距)。

```csharp
body.Append(new Paragraph(new Run(CreateFloatingBackground(coverImageId, 1, "CoverBg"))));
body.Append(new Paragraph(
    new ParagraphProperties(new SpacingBetweenLines { Before = "6000" }),  // push down
    new Run(new RunProperties(new FontSize { Val = "96" }), new Text("Title"))
));
// Cover section break
body.Append(new Paragraph(new ParagraphProperties(
    new SectionProperties(
        new TitlePage(),   // TRAP: "DifferentFirstPage" does NOT exist — use TitlePage
        new SectionType { Val = SectionMarkValues.NextPage },
        new PageSize { Width = 11906, Height = 16838 },
        new PageMargin { Top = 0, Right = 0, Bottom = 0, Left = 0, Header = 0, Footer = 0 }))));
```

封底：同一模式。封面/封底文字必须与背景形成明显对比——使用文档的色阶（例如深底浅字，或浅底深字）、显著更大的字号和宽裕的垂直间距。

### 4.3 目录（域代码）

目录必须使用域代码，而不是静态文本。标题必须使用内置样式。**占位条目必须与文档实际标题结构 1:1 对应**——正文中的每个 H1/H2/H3 都需要一条文本和层级相匹配的伪目录条目。空目录对读者毫无用处。

```csharp
// Begin
body.Append(new Paragraph(
    new Run(new FieldChar { FieldCharType = FieldCharValues.Begin }),
    new Run(new FieldCode(" TOC \\o \"1-3\" \\h \\z \\u ") { Space = SpaceProcessingModeValues.Preserve }),
    new Run(new FieldChar { FieldCharType = FieldCharValues.Separate })));
// Placeholder entries (refreshed by Word on open)
body.Append(new Paragraph(
    new ParagraphProperties(new ParagraphStyleId { Val = "TOC1" }),
    new Run(new Text("Chapter")), new Run(new TabChar()), new Run(new Text("1"))));
// End
body.Append(new Paragraph(
    new Run(new FieldChar { FieldCharType = FieldCharValues.End })));
```

打开时自动刷新：在 DocumentSettingsPart 中加 `new Settings(new UpdateFieldsOnOpen { Val = true })`。

### 4.4 页眉、页脚与分节属性

```csharp
// Header part
var headerPart = mainPart.AddNewPart<HeaderPart>();
var headerId = mainPart.GetIdOfPart(headerPart);
headerPart.Header = new Header(new Paragraph(
    new Run(new RunProperties(new FontSize { Val = "18" }), new Text("Header"))));

// Footer with page number
var footerPart = mainPart.AddNewPart<FooterPart>();
var footerId = mainPart.GetIdOfPart(footerPart);
var footerPara = new Paragraph(new ParagraphProperties(
    new Justification { Val = JustificationValues.Center }));
// PAGE field: Begin → Code → Separate → Placeholder → End
footerPara.Append(new Run(new FieldChar { FieldCharType = FieldCharValues.Begin }));
footerPara.Append(new Run(new FieldCode(" PAGE ") { Space = SpaceProcessingModeValues.Preserve }));
footerPara.Append(new Run(new FieldChar { FieldCharType = FieldCharValues.Separate }));
footerPara.Append(new Run(new Text("1")));
footerPara.Append(new Run(new FieldChar { FieldCharType = FieldCharValues.End }));
footerPart.Footer = new Footer(footerPara);

// Section — refs MUST come before pgSz/pgMar
new SectionProperties(
    new HeaderReference { Type = HeaderFooterValues.Default, Id = headerId },
    new FooterReference { Type = HeaderFooterValues.Default, Id = footerId },
    new PageSize { Width = 11906, Height = 16838 },
    new PageMargin { Top = 1800, Right = 1440, Bottom = 1440, Left = 1440,
                     Header = 720, Footer = 720 })
```

`NUMPAGES`（总页数）和 `DATE` 使用相同的域模式。要把背景放进页眉，向 HeaderPart 添加图片：`headerPart.AddImagePart()` → 在页眉段落中调用 `CreateFloatingBackground()`。

### 4.5 DrawingML：浮动背景

置于文字之下的整页图片。直接复制此函数——4 个命名空间的嵌套极易出错。

```csharp
// Image setup (before calling this function):
// var imgPart = mainPart.AddImagePart(ImagePartType.Png);
// using (var fs = File.OpenRead(path)) imgPart.FeedData(fs);
// var imageId = mainPart.GetIdOfPart(imgPart);

static Drawing CreateFloatingBackground(string imageId, uint docPrId, string name,
    long cx = 7560000, long cy = 10692000)          // A4 EMU default
{
    return new Drawing(                               // Wordprocessing.Drawing (NO prefix)
        new DW.Anchor(                                // DW = Drawing.Wordprocessing
            new DW.SimplePosition { X = 0, Y = 0 },
            new DW.HorizontalPosition(new DW.PositionOffset("0"))
            { RelativeFrom = DW.HorizontalRelativePositionValues.Page },
            new DW.VerticalPosition(new DW.PositionOffset("0"))
            { RelativeFrom = DW.VerticalRelativePositionValues.Page },
            new DW.Extent { Cx = cx, Cy = cy },
            new DW.EffectExtent { LeftEdge = 0, TopEdge = 0, RightEdge = 0, BottomEdge = 0 },
            new DW.WrapNone(),
            new DW.DocProperties { Id = docPrId, Name = name },
            new DW.NonVisualGraphicFrameDrawingProperties(
                new A.GraphicFrameLocks { NoChangeAspect = true }),
            new A.Graphic(                            // A = Drawing
                new A.GraphicData(                    // Uri goes HERE, not on Graphic
                    new PIC.Picture(                  // PIC = Drawing.Pictures
                        new PIC.NonVisualPictureProperties(
                            new PIC.NonVisualDrawingProperties { Id = 0, Name = $"{name}.png" },
                            new PIC.NonVisualPictureDrawingProperties()),
                        new PIC.BlipFill(
                            new A.Blip { Embed = imageId },
                            new A.Stretch(new A.FillRectangle())),
                        new PIC.ShapeProperties(
                            new A.Transform2D(
                                new A.Offset { X = 0, Y = 0 },
                                new A.Extents { Cx = cx, Cy = cy }), // MUST match Extent above
                            new A.PresetGeometry { Preset = A.ShapeTypeValues.Rectangle })))
                { Uri = "http://schemas.openxmlformats.org/drawingml/2006/picture" }))
        {
            DistanceFromTop = 0, DistanceFromBottom = 0,
            DistanceFromLeft = 0, DistanceFromRight = 0,
            SimplePos = false, RelativeHeight = 251658240,
            BehindDoc = true, Locked = false,
            LayoutInCell = true, AllowOverlap = true
        });
}
```

### 4.6 DrawingML：行内图片

以自然尺寸插入图表/照片并保持纵横比。直接复制此函数——它处理了 PNG 尺寸读取、等比缩放和全部必需的 DrawingML 嵌套。

```csharp
static void AddInlineImage(Body body, MainDocumentPart mainPart,
    string imagePath, string altText, uint docPrId, int maxWidthCm = 15)
{
    if (!File.Exists(imagePath))
    { Console.Error.WriteLine($"WARNING: Image not found: {imagePath}"); return; }

    var imagePart = mainPart.AddImagePart(ImagePartType.Png);
    byte[] imageBytes = File.ReadAllBytes(imagePath);
    using (var ms = new MemoryStream(imageBytes)) imagePart.FeedData(ms);
    var imageId = mainPart.GetIdOfPart(imagePart);

    // PNG header: width at bytes 16-19, height at 20-23 (big-endian)
    int imgWidth, imgHeight;
    using (var ms = new MemoryStream(imageBytes))
    {
        ms.Seek(16, SeekOrigin.Begin);
        byte[] wb = new byte[4], hb = new byte[4];
        ms.Read(wb, 0, 4); ms.Read(hb, 0, 4);
        if (BitConverter.IsLittleEndian) { Array.Reverse(wb); Array.Reverse(hb); }
        imgWidth = BitConverter.ToInt32(wb, 0);
        imgHeight = BitConverter.ToInt32(hb, 0);
    }

    long maxWidthEmu = maxWidthCm * 360000L;           // 1 cm = 360000 EMU
    long cx = maxWidthEmu;
    long cy = (long)(cx * ((double)imgHeight / imgWidth));

    var drawing = new Drawing(
        new DW.Inline(
            new DW.Extent { Cx = cx, Cy = cy },
            new DW.EffectExtent { LeftEdge = 0, TopEdge = 0, RightEdge = 0, BottomEdge = 0 },
            new DW.DocProperties { Id = docPrId, Name = altText },
            new DW.NonVisualGraphicFrameDrawingProperties(
                new A.GraphicFrameLocks { NoChangeAspect = true }),
            new A.Graphic(
                new A.GraphicData(
                    new PIC.Picture(
                        new PIC.NonVisualPictureProperties(
                            new PIC.NonVisualDrawingProperties { Id = 0, Name = $"{altText}.png" },
                            new PIC.NonVisualPictureDrawingProperties()),
                        new PIC.BlipFill(
                            new A.Blip { Embed = imageId },
                            new A.Stretch(new A.FillRectangle())),
                        new PIC.ShapeProperties(
                            new A.Transform2D(
                                new A.Offset { X = 0, Y = 0 },
                                new A.Extents { Cx = cx, Cy = cy }),
                            new A.PresetGeometry { Preset = A.ShapeTypeValues.Rectangle })))
                { Uri = "http://schemas.openxmlformats.org/drawingml/2006/picture" }))
        { DistanceFromTop = 0, DistanceFromBottom = 0,
          DistanceFromLeft = 0, DistanceFromRight = 0 });

    body.Append(new Paragraph(
        new ParagraphProperties(new KeepNext(),
            new Justification { Val = JustificationValues.Center },
            new SpacingBetweenLines
            {
                Before = "200",
                After = "80",
                Line = "360",
                LineRule = LineSpacingRuleValues.Auto
            }),
        new Run(drawing)));
}
```

用法：`AddInlineImage(body, mainPart, "/path/to/chart.png", "Revenue Chart", docPrId++);`

需要不同宽度时传 `maxWidthCm`：`AddInlineImage(body, mainPart, path, "Pie", docPrId++, 11);`

### 图片宽度指南

并非所有图片都应通栏。按内容类型选择 `maxWidthCm`：

| 内容 | maxWidthCm | 理由 |
|---------|------------|-----------|
| 宽幅柱状/折线图 | 15 | 通栏，充分利用横向空间 |
| 饼图/环形图 | 10-11 | 圆形被拉伸会显得不自然 |
| 纵向柱状图 | 12-13 | 中等宽度 |
| 照片/截图 | 视上下文而定 | 仅在高分辨率时通栏 |

反面模式：所有图片不分内容一律同宽。

### DrawingML 陷阱

- `Drawing` 属于 **Wordprocessing** 命名空间（无前缀）。`DW.Drawing` 不存在。
- `DW.RelativeHeight` 和 `DW.SizeRelativeFromValues` 不存在。`Anchor` 上的 `RelativeHeight` 是一个普通的 `UInt32Value`。
- `GraphicData { Uri = "..." }`——Uri 放在 `A.GraphicData` 上，而不是 `A.Graphic` 上。
- 即使所有值均为 0，`EffectExtent` 也是必需的。
- `Extent.Cx/Cy` 与 `Transform2D.Extents.Cx/Cy` 必须一致——数值不同会使图片变形。
- `DocProperties.Id` 必须在整个文档内唯一。使用递增计数器。

### 4.7 表格

**三线表**（学术风格——顶底粗线、无竖线）：

```csharp
var table = new Table();
table.Append(new TableProperties(
    new TableWidth { Width = "5000", Type = TableWidthUnitValues.Pct },
    new TableBorders(
        new TopBorder { Val = BorderValues.Single, Size = 12, Color = "7C9885" },
        new BottomBorder { Val = BorderValues.Single, Size = 12, Color = "7C9885" },
        new LeftBorder { Val = BorderValues.Nil }, new RightBorder { Val = BorderValues.Nil },
        new InsideHorizontalBorder { Val = BorderValues.Nil },
        new InsideVerticalBorder { Val = BorderValues.Nil })));
table.Append(new TableGrid(            // REQUIRED — missing = Word crashes
    new GridColumn { Width = "3000" },
    new GridColumn { Width = "7000" }));
```

在书写任何可见表格之前，必须先做法律文书判定测试：

1. 该信息是否至少有两个有意义的稳定列？
2. 是否有多条记录使用这些列？
3. 并排比较、核对或重新计算是否实质上比常规散文更清晰？

仅当三个答案都为「是」时才使用可见表格，唯一例外是正式证据清单默认使用表格。复杂的款项计算、密集的事件/证据时间线、审批矩阵和项目阶段计划可能符合条件。紧凑的当事人信息、诉讼请求、法律论证、律师函散文、决议条款、规章条文、少量简单日期和单个公式通常不符合。允许使用无边框单单元格表格作为隐藏的布局手段，使落款块保持在同一页。

- `GridColumn.Width` 必须与每个单元格的 `TableCellWidth.Width` 一致。
- 表头行：`Shading { Fill = "f0f4f2" }` + `Bold()` + 底边 `BorderValues.Single Size=6`。
- `TableHeader()` 放在 **`TableRowProperties`** 内，而不是作为 `TableRow` 的直接子元素：
  ```csharp
  new TableRow(new TableRowProperties(new TableHeader()), new TableCell(...), ...)
  ```

**合并单元格**：

```csharp
// Horizontal: Restart → Continue
new TableCellProperties(new HorizontalMerge { Val = MergedCellValues.Restart })
new TableCellProperties(new HorizontalMerge { Val = MergedCellValues.Continue })
// Vertical: Restart → empty VerticalMerge (no Val = continue)
new TableCellProperties(new VerticalMerge { Val = MergedCellValues.Restart })
new TableCellProperties(new VerticalMerge())   // no Val = continue
// Diagonal border
new TopLeftToBottomRightCellBorder { Val = BorderValues.Single, Size = 4 }
```

GridColumn 数量 = 逻辑总列数，即便存在合并。延续单元格需要一个空 `Paragraph`。

### 4.8 脚注

```csharp
// 1. Create part with REQUIRED separators
var fnPart = mainPart.AddNewPart<FootnotesPart>();
fnPart.Footnotes = new Footnotes(
    new Footnote(new Paragraph(new Run(new SeparatorMark())))
    { Type = FootnoteEndnoteValues.Separator, Id = -1 },
    new Footnote(new Paragraph(new Run(new ContinuationSeparatorMark())))
    { Type = FootnoteEndnoteValues.ContinuationSeparator, Id = 0 });

// 2. Add note (id starts from 1)
fnPart.Footnotes.Append(new Footnote(
    new Paragraph(
        new Run(new RunProperties(new VerticalTextAlignment { Val = VerticalPositionValues.Superscript }),
                new FootnoteReferenceMark()),
        new Run(new Text(" Note text") { Space = SpaceProcessingModeValues.Preserve }))
) { Id = 1 });

// 3. Reference in body
paragraph.Append(new Run(
    new RunProperties(new VerticalTextAlignment { Val = VerticalPositionValues.Superscript }),
    new FootnoteReference { Id = 1 }));
```

缺少分隔符（id=-1、id=0）= 脚注不渲染。尾注使用相同模式（EndnotesPart + EndnoteReference）。

**陷阱**：脚注引用 Run 必须追加到已有正文段落的末尾——绝不作为独立段落。孤零零占一行的上标「1」是排版缺陷。

### 4.9 超链接与交叉引用

**超链接**——先建关系，再建引用：

```csharp
var relId = mainPart.AddHyperlinkRelationship(new Uri("https://example.com"), true).Id;
new Hyperlink(new Run(
    new RunProperties(new RunFonts { Ascii = "FangSong", HighAnsi = "FangSong", EastAsia = "FangSong" },
        new Color { Val = "000000" }, new Underline { Val = UnderlineValues.Single }),
    new Text("link text"))) { Id = relId }
```

**交叉引用**——书签 + REF 域：

```csharp
// At target: BookmarkStart/End with matching Id
new BookmarkStart { Id = "100", Name = "Figure1" }
new BookmarkEnd { Id = "100" }

// At reference: REF field (same Begin/Code/Separate/End pattern as PAGE)
new FieldCode($" REF Figure1 \\h ") { Space = SpaceProcessingModeValues.Preserve }
```

### 4.10 编号（列表）

```csharp
var numPart = mainPart.AddNewPart<NumberingDefinitionsPart>();
numPart.Numbering = new Numbering(
    new AbstractNum(new Level(
        new NumberingFormat { Val = NumberFormatValues.Decimal },
        new LevelText { Val = "%1." },
        new LevelJustification { Val = LevelJustificationValues.Left },
        new ParagraphProperties(new Indentation { Left = "720", Hanging = "360" })
    ) { LevelIndex = 0 }) { AbstractNumberId = 1 },
    new NumberingInstance(new AbstractNumId { Val = 1 }) { NumberID = 1 });

// Use: new NumberingProperties(new NumberingLevelReference { Val = 0 }, new NumberingId { Val = 1 })
```

### 4.11 CJK 文本

在 C# 字符串中直接书写中文字符和标点。字符串内部的双引号使用 `\"`。**任何中文文本绝不使用 `\uXXXX`。** 完整陷阱清单见 `CJKExample.cs` 文件头。

在本法律文书工作区中，`SimSun` 仅用于文档唯一主标题；Normal、Heading1/2/3、Caption、表格文本、页眉/页脚和落款样式使用 `FangSong`。`RunFonts` 必须同时设置 `Ascii`、`HighAnsi` 和 `EastAsia`；除刻意设计的封面外，所有文字均为黑色 `000000`。不得把旧版的微软雅黑示例复制进法律文书正文：
```csharp
new RunFonts { Ascii = "FangSong", HighAnsi = "FangSong", EastAsia = "FangSong" },
new Color { Val = "000000" }
```

CJK 正文：在 Normal 样式上使用 `FontSize { Val = "28" }`、`Indentation { FirstLine = "560" }`（14 pt 下的 2 字符）和 `SpacingBetweenLines { Line = "360", LineRule = LineSpacingRuleValues.Auto }`。在标题、图注、居中元素和表格单元格上覆盖为 `FirstLine = "0"`；标题与图注保留 1.5 倍行距，表格单元格改用单倍行距（`Line="240"`、`Auto`）。下级标题使用同样的 14 pt 仿宋字号并加 `Bold()`。

CJK 文档级主标题：添加 `Spacing { Val = 20-60 }`（以 twips 计的字间距），避免全宽字形在大字号下显得拥挤。

可用字体包括 SimSun、SimHei、KaiTi、FangSong 和 Microsoft YaHei。本工作区中文法律文书的主标题使用 SimSun；正文和下级标题使用 FangSong。SimHei 和 Microsoft YaHei 仅限于经过明确设计的封面，绝不得泄漏到正文。

### 4.12 RTL / 双向文本

阿拉伯语、希伯来语或 LTR+RTL 混排文档的最小模式：

```csharp
// RTL paragraph
new ParagraphProperties(new BiDi())
// RTL run
new RunProperties(new RightToLeft(), new RunFonts { ComplexScript = "Arial" })
```

混排内容：RTL run 包裹 `RightToLeft()`，LTR run 不加。阿拉伯语语言标记设置 `Languages { Bidi = "ar-SA" }`。

### 4.13 形状的局限

Word 形状（圆角矩形、标注框、文本框）需要复杂的 VML 或 DrawingML，程序化生成极易出错。简短的视觉提示块优先使用带边框/底纹的普通段落。仅当内容独立通过三段式表格判定测试时才使用可见表格；不得仅为模仿卡片或文本框而引入表格。无边框单单元格表格仍可作为不可见的分页/分组手段。

### 4.12 分页与单位

| 属性 | 类 | 效果 |
|----------|-------|--------|
| 段前分页 | `PageBreakBefore` | 强制换页 |
| 与下段同页 | `KeepNext` | 标题与后续段落保持同页 |
| 段中不分页 | `KeepLines` | 段落不跨页拆分 |
| 表格行不可拆分 | `CantSplit()` | 防止一行被拆到两页 |
| 重复表头 | `TableHeader()` | 表头行跨页重复 |

**不得出现意外的首页空白页。** 把最终分节属性直接放在 `Body` 下、内容之后。文档中部的分节符应把 `SectionProperties` 附加到前一节末尾的段落上。不得仅为设置页眉/页脚而在首个内容段落之前追加 `sectPr` 段落；Word 会把它当作空的第一节，可能渲染出一页只含页眉/页脚/页码的空页。

**保守的表格判定。** 正式证据清单使用原生表格。其他可见表格必须同时具备稳定的列、多条记录以及真实的比较/核对/重算需求。不得仅因条目较多就推定需要表格。当事人信息、请求事项主文、法律论证、律师函、决议和规章条文保持常规段落与编号。当表格确有必要时，对普通数据行应用 `CantSplit()`，用 `TableHeader()` 重复表头，且当短表与其前置标题能同页容纳时保持二者同页。

**不可分割块。** 把整个落款块——出具单位/人、印章、经办人/签名行和日期——放入一个带 `CantSplit()` 的无边框单单元格表格行内，或对除最后一段外的每个段落应用 `KeepNext` 并对每个段落应用 `KeepLines`。对「此致」+ 受文机关、图片 + 图注、附件/证据标题 + 首条条目、标题 + 首个实质段落/表格采用同样的分组原则。绝不用大间距空段落替代分组。

**1.5 倍行距的实现。** 在 Normal 和所有面向正文的段落样式上设置 `Line="360"` 和 `LineRule=Auto`，并在带有直接 `SpacingBetweenLines` 覆盖的段落上保留或显式重复它。这包括标题、主标题、列表、图注、受文机关和落款块；表格单元格例外，使用 `Line="240"` 的单倍行距（SKILL.md 硬规则 13）。在某些渲染器中，直接段落间距元素会替换而非合并样式意图，因此仅设置 `Before`/`After` 的覆盖可能意外丢失 1.5 倍行距；创建此类覆盖时必须同时包含 `Line` 和 `LineRule`。增大行距后不得在表格行上设置精确行高。

| 单位 | 换算 | 示例 |
|------|-----------|---------|
| Twips (dxa) | 1/20 pt，1440/英寸 | A4 宽 = 11906 |
| 半磅 | 字号 × 2 | 12pt = `"24"` |
| EMU | 914400/英寸，360000/厘米 | A4 = 7560000 × 10692000 |

### 4.13 信头

通过 `TitlePage` 实现首页不同的页眉。陷阱：`DifferentFirstPage` 不存在。

```csharp
new SectionProperties(
    new TitlePage(),
    new HeaderReference { Type = HeaderFooterValues.First, Id = firstPageHeaderId },
    new HeaderReference { Type = HeaderFooterValues.Default, Id = defaultHeaderId }, ...)
```

### 4.14 背景图片

当文档包含封面时，通过 Playwright 生成背景（HTML/SVG → 2 倍缩放 PNG → 以浮动锚点插入，见 §4.5）。

**每个背景都必须从零创建。** `scripts/generate_*.py` 中的脚本仅是风格参考——阅读其中一个学习 HTML/SVG + Playwright 截图技术，然后编写你自己的独特设计。**绝不直接调用 `generate_*.py`，绝不用 `subprocess.run` 运行它们，绝不复用它们的 PNG 输出。** 同样绝不使用用户上传的图片、图库照片或任何已有文件作为封面背景。

设计流程：
1. 先定义文档的 Colors 类（各处使用相同的十六进制色值）
2. 阅读一个 `generate_*.py` 脚本学习 SVG 技术（radial-gradient、曲线、滤镜等）
3. 从零编写新的 HTML/SVG——背景使用你 Colors 类中相同的十六进制色值
4. 用 Playwright 以 2 倍缩放截图：`page.screenshot(path=output, clip={...})`，尺寸 794×1123px
5. 把 PNG 作为浮动背景插入（§4.5 CreateFloatingBackground）
6. 封面文字颜色必须来自同一个 Colors 类——确保与刚生成的背景形成对比

在 IPython 中：`subprocess.run(["python3", your_script, output_dir])`——同步 Playwright 无法在已有事件循环中运行。

风格参考（仅供学习技术，不得调用或复用其输出）：

| 脚本 | 风格 | 适合作为以下设计的起点 |
|--------|-------|------------------------|
| `generate_backgrounds.py` | 莫兰迪柔和曲线 | 柔和、有机的设计 |
| `generate_inkwash_backgrounds.py` | 水墨湍流 | CJK 文人风、灰阶 |
| `generate_swiss_backgrounds.py` | 精准细线 | 网格化、极简 |
| `generate_geometric_backgrounds.py` | 交叠圆弧 | 大胆的几何图案 |
| `generate_gradient_backgrounds.py` | 平滑渐变色带 | 优雅的色彩过渡 |
| `generate_formal_backgrounds.py` | 双边框 + 装饰 | 结构化边框设计 |

**封面文字必须与正文文字在视觉上明显区分。** 使用显著更大的字号（48-96 半磅）、不同的字重和宽裕的间距（SpacingBetweenLines Before="4000-6000"）把标题推离顶边。封面应当像一个独立的视觉空间，而不只是首个正文段落的放大版。

## 5. 质量门禁

1. 文档打开无错误
2. OpenXML + 业务规则校验通过
3. 页眉、页脚、目录、页码、图片位置正确
4. 无占位文字残留
5. 主标题为加粗黑色宋体 22 pt；下级标题为加粗黑色仿宋 14 pt；正文为常规黑色仿宋 14 pt，仅按上下文局部加粗
6. 正式证据清单为表格；其他可见表格通过三段式必要性测试，普通法律散文未被机械地表格化
7. 面向正文的主标题、标题、当事人信息、请求事项、散文、列表、图注、受文机关和落款块均使用 `Line=360` 配合 `LineRule=Auto`；表格单元格段落使用单倍行距 `Line=240`（唯一常设例外，SKILL.md 硬规则 13）；无精确行高或空行模拟残留

## 6. 更多参考

- 路由：`../SKILL.md`
- 编辑既有文档：`wir-reference.md`
- 数学公式（OMML）：`omml-reference.md`
- 原生 Word 图表（饼图、柱状、折线）：`chart-reference.md`
- Matplotlib 图表：`matplotlib-guide.md`
- 英文文档演示：`../assets/templates/Example.cs` **（编写英文 Program.cs 前必读）**
- 普通中文法律文书：使用 `docx init --fresh` 返回的 `Program.cs`，编辑前必须通读全文。
- 高级 CJK 报告特性（封面、目录、图表、脚注）：`../assets/templates/CJKExample.cs` **（仅当确实需要这些特性时才阅读）**。
