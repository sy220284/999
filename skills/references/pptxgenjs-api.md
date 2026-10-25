# pptxgenjs API Reference

pptxgenjs generates `.pptx` files in **JavaScript / Node.js**. Coordinates are in **inches**.

## Setup & basic structure

```bash
npm install -g pptxgenjs
```

```javascript
const pptxgen = require("pptxgenjs");

let pres = new pptxgen();
pres.layout = 'LAYOUT_WIDE';   // 13.33 × 7.5" — the default for this skill (see below)
pres.author = 'Your Name';
pres.title  = 'Presentation Title';

// Canvas constants — derive every position from these, never hardcode 10 or 13.33
const W = 13.33, H = 7.5, M = 0.5;   // width, height, margin (true width is 13.333"; 13.33 keeps you inside it)

let slide = pres.addSlide();
slide.addText("Hello World!", { x: M, y: M, w: W - 2 * M, fontSize: 36, color: "363636" });

pres.writeFile({ fileName: "Presentation.pptx" }).then(() => console.log("done"));
```


## Layout dimensions


| Layout         | Size (inches)                   |
| ---------------- | --------------------------------- |
| `LAYOUT_WIDE`  | **13.33 × 7.5 — use this**    |
| `LAYOUT_16x9`  | 10 × 5.625 (pptxgenjs default) |
| `LAYOUT_16x10` | 10 × 6.25                      |
| `LAYOUT_4x3`   | 10 × 7.5                       |

> ⚠️ **Set `LAYOUT_WIDE` explicitly on every deck.** pptxgenjs defaults to `LAYOUT_16x9`, which is only **10 × 5.625"** — but every type size in this skill (§6: 44–72pt titles, ~24pt body; §3: 60–72pt stat callouts) is tuned for a **13.33 × 7.5"** canvas. Leaving the default gives you a canvas 25% narrower and 25% shorter with type sized for the big one, which overflows systematically — the exact failure §3, §9 and §10 all tell you to avoid. If you deliberately target a 10"-wide layout, scale every font size and the 0.5" margins down by the same factor (×0.75).

## Text & formatting

```javascript
// Basic text
slide.addText("Simple Text", {
  x: 1, y: 1, w: 8, h: 2, fontSize: 24, fontFace: "Arial",
  color: "363636", bold: true, align: "center", valign: "middle"
});

// Character spacing: use charSpacing (letterSpacing is silently ignored)
slide.addText("SPACED TEXT", { x: 1, y: 1, w: 8, h: 1, charSpacing: 6 });

// Rich text array (mixed styles in one paragraph)
slide.addText([
  { text: "Bold ",   options: { bold: true } },
  { text: "Italic ", options: { italic: true } }
], { x: 1, y: 3, w: 8, h: 1 });

// Multi-line (each line needs breakLine: true; the last may omit it)
slide.addText([
  { text: "Line 1", options: { breakLine: true } },
  { text: "Line 2", options: { breakLine: true } },
  { text: "Line 3" }
], { x: 0.5, y: 0.5, w: 8, h: 2 });

// Text-box padding: set margin: 0 to align with shapes/lines
slide.addText("Title", { x: 0.5, y: 0.3, w: 9, h: 0.6, margin: 0 });
```

## Lists & bullets

```javascript
// ✅ Correct: multiple bullets
slide.addText([
  { text: "First item",  options: { bullet: true, breakLine: true } },
  { text: "Second item", options: { bullet: true, breakLine: true } },
  { text: "Third item",  options: { bullet: true } }
], { x: 0.5, y: 0.5, w: 8, h: 3 });

// ❌ Wrong: never use unicode bullets (creates double bullets)
slide.addText("• First item", { ... });

// Sub-items & numbered lists
{ text: "Sub-item", options: { bullet: true, indentLevel: 1 } }
{ text: "First",    options: { bullet: { type: "number" }, breakLine: true } }
```

### Make bullets look good (default `bullet: true` looks amateurish)

The bare `bullet: true` renders a big dot with a **huge gap** to the text (pptxgenjs defaults to a ~27pt hanging indent) — a classic AI-list tell. Always style bullets:

```javascript
// bullet is a PARAGRAPH property — put it in EACH item's options, not top-level.
// A top-level `bullet` only styles the first paragraph; the rest get <a:buNone/> (no dot).
const bu = () => ({ code: "2022", indent: 14 });  // factory: fresh object per item (pptxgenjs mutates in place)
slide.addText([
  { text: "First item",  options: { bullet: bu(), breakLine: true } },
  { text: "Second item", options: { bullet: bu(), breakLine: true } },
  { text: "Third item",  options: { bullet: bu() } }
], {
  x: 0.5, y: 0.5, w: 8, h: 3, fontSize: 15, color: "334155",
  paraSpaceAfter: 8,   // item spacing (never lineSpacing)
  margin: 0,           // align glyph to x
});
```

- **`indent` matters most** — cut the default ~27pt to 10–16pt to kill the "floating dot" (`indent` = glyph→text gap in pt; try 10–16).
- **Refined glyphs** — `2022`(•), `25AA`(▪), `2013`(–), `25B8`(▸) read more designed than a fat dot; mute the color (e.g. `94A3B8`) to keep it subtle.
- **For short card lists** (3–4 items), skip native bullets: draw a small colored dot/square shape + a text box per row for full control.

## Shapes

```javascript
slide.addShape(pres.shapes.RECTANGLE, {
  x: 0.5, y: 0.8, w: 1.5, h: 3.0,
  fill: { color: "FF0000" }, line: { color: "000000", width: 2 }
});

slide.addShape(pres.shapes.OVAL, { x: 4, y: 1, w: 2, h: 2, fill: { color: "0000FF" } });

slide.addShape(pres.shapes.LINE, {
  x: 1, y: 3, w: 5, h: 0, line: { color: "FF0000", width: 3, dashType: "dash" }
});

// Transparency
slide.addShape(pres.shapes.RECTANGLE, {
  x: 1, y: 1, w: 3, h: 2, fill: { color: "0088CC", transparency: 50 }
});

// Rounded rectangle (rectRadius works only on ROUNDED_RECTANGLE, not RECTANGLE)
slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
  x: 1, y: 1, w: 3, h: 2, fill: { color: "FFFFFF" }, rectRadius: 0.1
});

// Shadow (to make a card stand out — use this, not an edge stripe)
slide.addShape(pres.shapes.RECTANGLE, {
  x: 1, y: 1, w: 3, h: 2, fill: { color: "FFFFFF" },
  shadow: { type: "outer", color: "000000", blur: 6, offset: 2, angle: 45, opacity: 0.15 }
});
```

**Shadow options:**


| Property  | Range / notes                                                                           |
| ----------- | ----------------------------------------------------------------------------------------- |
| `type`    | `"outer"` / `"inner"`                                                                   |
| `color`   | 6-char hex (no`#`, no 8-char hex)                                                       |
| `blur`    | 0–100 pt                                                                               |
| `offset`  | 0–200 pt,**must be non-negative** (negatives corrupt the file)                         |
| `angle`   | 0–359°, clockwise from 3 o'clock (45 = bottom-right, 135 = bottom-left, 270 = upward) |
| `opacity` | 0.0–1.0 (use this for transparency, never encode it in`color`)                         |

> To cast a shadow upward (e.g., a card near the bottom), use `angle: 270` + a positive offset — **not** a negative offset.
> Gradient fills are not natively supported — use a gradient image as the background instead.

## Images

```javascript
// Three sources
slide.addImage({ path: "images/photo.jpg", x: 1, y: 1, w: 5, h: 3 });            // local
slide.addImage({ path: "https://example.com/img.jpg", x: 1, y: 1, w: 5, h: 3 }); // URL
slide.addImage({ data: "image/png;base64,iVBORw0KGgo...", x: 1, y: 1, w: 5, h: 3 }); // base64 (faster)

// Options
slide.addImage({
  path: "image.png", x: 1, y: 1, w: 5, h: 3,
  rotate: 45, rounding: true /*circular crop*/, transparency: 50,
  flipH: true, flipV: false, altText: "Description",
  hyperlink: { url: "https://example.com" }
});

// Preserve aspect ratio explicitly (W = the canvas-width constant, never a literal)
const origW = 1978, origH = 923, maxH = 3.0;
const calcW = maxH * (origW / origH);
const centerX = (W - calcW) / 2;
slide.addImage({ path: "image.png", x: centerX, y: 1.2, w: calcW, h: maxH });

// IMPORTANT: do not assume a generic `{ sizing: { type: 'contain'|'cover'|'crop' } }`
// object is honored by the installed pptxgenjs version. Some versions ignore it and
// write a stretched image. For a contained image, compute w/h from the source aspect
// ratio as above. For a true cover/crop, use a version-verified crop helper or pre-crop
// the pixels only when the composition actually requires it; then insert the result
// without changing its aspect ratio. Verify the result only when crop behavior is
// materially important to the slide.
```

Supports PNG / JPG / GIF / SVG (SVG works in modern PowerPoint / Microsoft 365).

## No `outEnd` labels on stacked bar charts
OOXML restricts `c:dLblPos` by grouping: **stacked / percentStacked only allow
`ctr` / `inBase` / `inEnd`** — `outEnd` is valid only for `clustered` (line: ctr/l/r/t/b;
pie/doughnut: bestFit/ctr/inEnd/outEnd).

pptxgenjs won't stop you from writing `barGrouping: "stacked"` +
`dataLabelPosition: "outEnd"`, and LibreOffice renders it fine — only PowerPoint rejects it,
triggers "repair", and drops the chart.

**To highlight a single bar, use per-point colors instead:**

```js
slide.addChart(p.charts.BAR, [{
  name: "series",
  labels: ["A", "B", "C"],
  values: [10, 20, 30],
}], {
  barDir: "col",
  varyColors: true,                            // per-point coloring
  chartColors: ["17457E", "17457E", "E8590C"], // highlight the 3rd bar
  showValue: true,
  dataLabelPosition: "outEnd",                 // valid under clustered grouping
});
```

## Backgrounds

```javascript
slide.background = { color: "F1F1F1" };                        // solid
slide.background = { color: "FF3399", transparency: 50 };      // with transparency
slide.background = { path: "https://example.com/bg.jpg" };     // image URL
slide.background = { data: "image/png;base64,iVBORw0KGgo..." };// image base64
```

## Tables

```javascript
slide.addTable([
  ["Header 1", "Header 2"],
  ["Cell 1", "Cell 2"]
], { x: 1, y: 1, w: 8, h: 2, border: { pt: 1, color: "999999" }, fill: { color: "F1F1F1" } });

// Merged cells
let tableData = [
  [{ text: "Header", options: { fill: { color: "6699CC" }, color: "FFFFFF", bold: true } }, "Cell"],
  [{ text: "Merged", options: { colspan: 2 } }]
];
slide.addTable(tableData, { x: 1, y: 3.5, w: 8, colW: [4, 4] });
```

## Charts

**Principle: keep charts native and editable.** Choose your approach by what PowerPoint can represent, not by what's quickest to code:

1. **Library-native** (bar/column/line/pie/area/scatter/bubble/radar/doughnut/combo) → use `addChart()`; **never** render to an image.
2. **PowerPoint-native but not exposed by the library** (trendlines, error bars) → stay native: compute the extra series yourself (e.g., a regression line as a second LINE/SCATTER series) or inject the OOXML. **Don't** fall back to a matplotlib PNG — you lose editability.
3. **Genuinely no native representation** (Sankey, network/graph, chord, complex statistical plots) → only here render to an image and insert via `addImage()`.

```javascript
// Bar
slide.addChart(pres.charts.BAR, [{
  name: "Sales", labels: ["Q1","Q2","Q3","Q4"], values: [4500,5500,6200,7100]
}], { x: 0.5, y: 0.6, w: 6, h: 3, barDir: 'col', showTitle: true, title: 'Quarterly Sales' });

// Line
slide.addChart(pres.charts.LINE, [{
  name: "Temp", labels: ["Jan","Feb","Mar"], values: [32,35,42]
}], { x: 0.5, y: 2.5, w: 6, h: 2.5, lineSize: 3, lineSmooth: true });

// Pie
slide.addChart(pres.charts.PIE, [{
  name: "Share", labels: ["A","B","Other"], values: [35,45,20]
}], { x: 6.5, y: 1, w: 3, h: 3, showPercent: true });
```

**Make charts look modern (defaults look dated):**

```javascript
slide.addChart(pres.charts.BAR, chartData, {
  x: 0.5, y: 1, w: 9, h: 4, barDir: "col",
  chartColors: ["0D9488", "14B8A6", "5EEAD4"],            // match your palette
  chartArea: { fill: { color: "FFFFFF" }, roundedCorners: true },
  catAxisLabelColor: "64748B", valAxisLabelColor: "64748B", // muted axis labels
  valGridLine: { color: "E2E8F0", size: 0.5 },             // subtle grid, value axis only
  catGridLine: { style: "none" },
  showValue: true, dataLabelPosition: "outEnd", dataLabelColor: "1E293B", // data labels
  showLegend: false,                                       // hide legend for single series
});
```

## Slide masters & speaker notes

```javascript
// Master
pres.defineSlideMaster({
  title: 'TITLE_SLIDE', background: { color: '283A5E' },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 1, y: 2, w: 8, h: 2 } } }]
});
let titleSlide = pres.addSlide({ masterName: "TITLE_SLIDE" });
titleSlide.addText("My Title", { placeholder: "title" });

// Speaker notes (visible only in Presenter View, not on the slide)
slide.addNotes("Open with the FY25 revenue headline; pause after the number. If asked about the Q3 dip: supply chain, resolved in Q4.");
```

## Common pitfalls (file corruption / visual bugs / AI look)

1. **Never use `#` with hex** — corrupts the file: `color: "FF0000"` ✅ / `"#FF0000"` ❌
2. **Never encode opacity in hex** — 8-char hex (e.g., `"00000020"`) corrupts the file; use the `opacity` property
3. **Use `bullet: true`** — never unicode `•` (double bullets)
4. **Use `breakLine: true`** between array items
5. **Avoid `lineSpacing` with bullets** (excessive gaps) — use `paraSpaceAfter` instead
6. **Fresh instance per presentation** — don't reuse the `pptxgen()` object
7. **Don't reuse option objects across calls** — pptxgenjs **mutates objects in place** (e.g., converts shadow values to EMU), so sharing corrupts the second shape. Use a factory that returns a fresh object:
   ```javascript
   const makeShadow = () => ({ type:"outer", blur:6, offset:2, color:"000000", opacity:0.15 });
   slide.addShape(pres.shapes.RECTANGLE, { shadow: makeShadow(), ... }); // ✅
   ```
8. **Don't add edge accent bars to cards** — use a `fill` tint or `shadow` to set them apart

## Quick reference

- **Shapes**: RECTANGLE / OVAL / LINE / ROUNDED_RECTANGLE
- **Charts**: BAR / COLUMN / LINE / AREA / PIE / DOUGHNUT / SCATTER / BUBBLE / RADAR / combo (array of `{type, data, options}`)
- **Alignment**: `"left"` / `"center"` / `"right"`
- **Data-label position**: `"outEnd"` / `"inEnd"` / `"center"`
