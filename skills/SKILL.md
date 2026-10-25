---
name: pptx
metadata:
  author: Z.AI
  version: "1.2"
description: "create and edit pptx files via pptxgenjs/python-pptx"
license: Proprietary. LICENSE.txt has complete terms
---
# Part 0 · Runtime Workflow & Orchestration

This skill runs in a harness that provides file operations, a **widget tool** for interactive clarification, search/research capabilities, and visual inspection when needed. Tool names and invocation syntax belong to the harness; this skill defines **when** those capabilities are useful.

## 0.1 Preflight before clarification

Inspect the prompt and available uploads before any widget interaction. Do not ask for information that a supplied template, reference, brand asset, source document, or explicit instruction already establishes.

Use these internal planning signals:

- `content_source`: `new_generation` | `source_conversion` | `mixed`
- `reference_constraint`: `none` | `style_reference` | `template_fill` | `screenshot_replication`
- `usage_scenario`: infer from explicit intent first, then audience/content signals — e.g. `formal_report`, `courseware`, `proposal`, `data_report`, `tech_analysis`, `academic`, `travel/editorial`, `general`.

Reference detection has priority over style questions:

- If a PPTX/template/brand reference already defines the visual language, inspect it before clarification and do not ask the user to choose an unrelated palette or typography direction.
- If a screenshot or visual reference is explicitly meant to be replicated, treat its visual direction as constrained unless the user asks for alternatives.
- If uploaded material is content to convert rather than a visual reference, preserve its information structure but do not assume it defines the deck style.

Route the task:

1. **Create from scratch** — no PPTX template must be preserved.
2. **Create from a user-provided template** — build a new deck that inherits the uploaded PPTX's structure/style. 📖 Read `references/template-workflow.md` before proceeding.
3. **Edit an existing deck** — modify the uploaded PPTX itself. 📖 Read `references/editing-workflow.md` before proceeding.

For an uploaded PPTX, distinguish **"use this as a template/reference"** from **"modify this file"**. Do not silently switch between template inheritance and in-place editing.

## 0.2 Clarification: normally one pre-generation widget round

Default to **at most one pre-generation clarification round**. Use it to resolve high-impact presentation intent. If the user says "just do it", "don't ask", "quick generate", or equivalent, use sensible defaults and proceed.

For **open-ended create-from-scratch requests**, page scope and visual direction are default clarification dimensions. When **both approximate length and visual direction are unspecified**, a compact widget clarification is normally expected unless the prompt/materials already constrain them.

Ensure every widget option is selectable and interactive.

Batch only the highest-value missing variables, normally **2–4 questions total**:

- **Approximate length / scope** — concise, standard, comprehensive, or a custom page count.
- **Visual direction** — when no template/brand/reference already constrains it.
- **Up to two topic-specific variables** that materially change the content. Audience is high impact for professional, educational, medical, technical, academic, or decision-oriented decks because it changes depth, terminology, evidence, and structure; do not ask it mechanically when the intended audience is obvious or another variable matters more.
- **Content scope** only when supplied materials exist and it is genuinely unclear whether the deck must stay materials-only or may be supplemented externally.

Do not ask the user to make implementation-level design decisions that the presentation system can reasonably resolve itself. Clarify only choices that materially affect the deck's scope, audience, content direction, or overall visual language.

### Visual style swatch contract

When visual direction needs clarification and the widget can present visual choices, show **2–3 topic-specific 16:9 miniature slide previews** rather than a text-only list. Each direction must differ meaningfully in:

1. palette;
2. typographic temperament;
3. spatial rhythm / composition.

Use the user's actual topic in the preview title. Show colors inside a miniature layout context rather than as isolated color dots/bars. Avoid three options that are merely different colors of the same generic corporate layout. If a template, brand system, or explicit style reference already constrains the visual direction, **skip style swatches**.

### After clarification

Once the clarification round has established the key presentation variables, continue through research, narrative planning, slide planning, implementation, and QA. Keep the detailed production plan internal. For creation tasks, **always show the concise Framework Preview defined in §0.4** so the user can see the planned direction and intervene if needed.

Only surface an outline for confirmation when:

- the user explicitly asks to review/approve it before generation;
- a newly discovered material conflict cannot be resolved reasonably without the user's decision;
- the deck has unusually high revision cost and materially different narrative directions remain unresolved.

Normal uncertainty in slide sequencing, wording, visual form, or individual page composition is not grounds for another clarification round.

## 0.3 Sources and research

When the user provides source material, extract and preserve its exact numbers, dates, terminology, required sections, usable assets, and chartable data **before** adding outside information.

Use search when external evidence is genuinely needed: a new-generation deck, current market/company facts, competitor context, authoritative statistics, or a user request to supplement. If the task is "convert these materials" or "materials only", do not browse for extra claims. Stop researching once the slide plan has enough evidence.

For every externally sourced quantitative claim or chart, retain a slide-level source. User-provided/internal data does not need an external citation unless requested.

### Image search

Use the available **image-search** capability when real photographic, documentary, product, place, person, artwork, or other externally sourced visuals materially improve the slide.

**Timing:** image search runs **after** the Framework Preview (§0.4) is displayed, not before. Plan which slides need images during slide planning; execute the searches only after the outline is shown.

Decide the image's role and search intent during slide planning before requesting assets. Prefer a specific visual description over a broad topic name.

Do not search for images merely to fill empty space or because a slide lacks a chart/photo. For conceptual or text-led slides, first consider structural information design, diagrams, typography, or semantic iconography.

Delegate search mechanics, query syntax, ranking, retries, localization, and result handling to the **image-search Skill**. Never fabricate image URLs; use only assets returned by the search capability or user-provided/local assets.

Do not visually verify each returned image — trust search-result relevance. Visual spot-checks for image issues (distortion, mismatch) happen at Tier 3 QA on the finished slide, not per-asset.

## 0.4 Plan before coding

Separate content reasoning from coordinate/code generation. Before writing PPTX code, establish a narrative arc and an explicit slide-level plan. The plan may stay internal or be persisted when useful to the runtime; a JSON file is optional, not mandatory.

### Communication strategy

Before planning slides, decide the deck's argumentative posture: who is the audience, what should they believe/do/understand after, and what logical sequence gets them there. Infer one dominant posture or a deliberate blend. Ask the user only when this cannot be resolved reasonably from the request and materials.

- **Instructional / science explainer** — teach step by step; prerequisite → mechanism → implication; define specialist terms before relying on them; use diagrams or information structures that build understanding incrementally.
- **Pyramid / decision** — conclusion first; page titles state supported findings or recommendations; evidence sits directly beneath the claim.
- **Narrative** — situation → tension → resolution; use reveal/payoff and deliberate dense/breathing beats.
- **Briefing / reference** — neutral, complete, scannable coverage; topic titles are acceptable when persuasion is not the goal.
- **Showcase / photo editorial** — imagery or artifacts lead; copy is concise; use editorial whitespace and image-led composition.
- **Data-driven / data journalism** — charts, numbers, comparisons, and direct annotations become the page spine; usually layered onto pyramid, briefing, or instructional logic.

Do not force-fit one posture. When the user's material already has a strong logical structure, preserve it unless re-architecting clearly improves the stated goal. Do not default to neutral topic-label pages with bullet lists — every slide must advance a specific communicative purpose (teach, persuade, prove, compare, reveal, summarize, breathe).

Each planned slide should resolve at least:

```yaml
slide:
  title: "..."
  page_role: "cover | chapter | tldr | content | data | breathing | closing"
  purpose: "argumentative, teaching, or informational role in the story"
  claim: "one concrete takeaway, or coverage statement in briefing mode"
  supporting_content: "facts, data, examples, or copy"
  proof: "the evidence/visual that makes the claim credible or understandable"
  semantic_relation: "peer | hierarchy | sequence | causal | contrast | containment | cluster | iteration | core+annotation | none"
  visual_form: "chart | diagram | comparison | timeline | icon strip | big-number editorial | modular mosaic | process rail | annotated artifact | typographic statement | hero image | ..."
  continuity_group: "optional identifier when adjacent slides share one mental map"
  source: "source for important external claims, if any"
  template_mapping: "layout/slide to reuse, when template-based"
  image_needed: false
```

Planning rules:

- `purpose` is the slide's role in the argument/lesson, not merely a topic label.
- `claim` is the one thing the audience should remember. In neutral briefing/reference decks it may state coverage without manufacturing a persuasive conclusion.
- `proof` identifies what on the page actually supports, demonstrates, or explains the claim.
- `semantic_relation` captures the relationship the audience must understand. For text-led slides, choose this **before** choosing containers or coordinates; the spatial relationship should carry part of the meaning.
- `page_role` controls rhythm: `cover`, `chapter`, `tldr`, `content`, `data`, `breathing`, `closing`. A `breathing` page deliberately reduces density to one focal statement, number, image, or transition.
- Use page roles to create rhythm; do not mechanically alternate roles or insert breathing pages to satisfy a quota.
- For text-led / conceptual content, map the relation to a visual structure before defaulting to cards: `sequence → process rail`, `iteration → lifecycle loop`, `hierarchy → stacked/nested structure`, `contrast → editorial split`, `core+annotation → annotated artifact`, `cluster → bubble/field`, `peer → icon strip or modular mosaic when appropriate`.
- For **3–6 short peer concepts, attributes, symptoms, benefits, risks, steps, categories, channels, places, or object types**, actively consider `icon/pictogram + concise label`, `icon + text rows`, a pictogram strip, or an asymmetric modular mosaic before repeated bullets/cards.
- When adjacent slides explain one evolving system, scene, process, geography, or comparison, assign a shared `continuity_group` and preserve a recognizable mental map across them.
- In template mode, map content to the template's existing **layout vocabulary** before drafting copy.

### Framework preview

For **create-from-scratch** and **template-based creation**, you **MUST display** a concise user-facing framework after slide planning and before implementation so the user can see the planned story and intervene early if needed. Ask for approval only when the user explicitly requested outline-first review or when a material unresolved conflict requires their decision.

For existing-deck edits that do not materially change the deck's story, page sequence, or structure, a framework preview is not required.

```markdown
**大纲 · {Deck Title}** ({N} pages · {communication_posture})

| # | Title | Key Content / Claim | Broad Form |
|---|-------|---------------------|------------|
| 1 | ...   | —                   | cover      |
| 2 | ...   | ① ... ② ...        | 结构解剖 / 对照 / 流程 / 图文等 |
| … | ...   | ...                 | ...        |

Narrative arc: {one-line summary of the argumentative/teaching/informational flow}
```

Rules:

- Keep the preview concise: page sequence + title + main message + broad presentation form. Do **not** expose internal fields such as `semantic_relation`, `continuity_group`, internal design-system details, QA risk labels, or implementation notes.
- Use the preview's page count, order, titles, and key content as implementation constraints. The broad form is directional: implementation may refine the exact layout while preserving the same semantic relationship and story logic.
- Keep "Key Content / Claim" to ≤ 2 short phrases per row — enough to confirm direction, not a full script.
- Language follows the user's language.
- For very small/quick creation tasks, shorten the preview rather than omitting it. Skip the preview only when the user explicitly asks to generate directly or to avoid intermediate previews.

## 0.5 Lock the visual system

Once the visual direction is selected or inherited, establish one consistent deck-wide system for **palette, typography scale, spacing rhythm, iconography, image treatment, and chart treatment**. Derive it from the selected style swatch, brand/template, communication mode, or explicit user direction, and do not redefine it slide by slide.

In template mode, inherited theme and layout rules take precedence over newly invented styling. The visual system may remain internal; consistency matters more than a prescribed schema.

## 0.6 Build and QA

> 📖 For full QA script usage, scope routing tables, and backup procedures, read `references/qa-details.md`.

Use Parts 1–2 for design and implementation. For templates/edits, prefer **reuse over reconstruction**: reuse a real layout when possible; otherwise clone an existing slide; replace existing text/media when preserving formatting matters; scope replacements per slide when short tokens repeat.

QA is **risk-based, not exhaustive**:

- **Tier 0 — integrity:** run `python {skill_dir}/scripts/qa/preflight_check.py <file> --integrity --expected-slides N` after ANY modification. Required.
- **Tier 1 — geometry:** run `python {skill_dir}/scripts/qa/preflight_check.py <file> [--slides ...]`. Checks bounds, overlap, empty boxes, missing fontFace. Required.
- **Tier 2 — fast extraction:** PDF convert + `pdftotext`. Verify titles, CJK integrity, key terms.
- **Tier 3 — visual spot-check:** visually inspect HIGH-RISK slides (dense tables, complex diagrams, annotated artifacts with many callouts, cluster/bubble fields, asymmetric mosaics, or other custom multi-element layouts). Skip covers/dividers and simple typographic or simple 3–4 element text-led pages when deterministic checks pass.

When visual inspection is needed, inspect the **rendered slide image**. If the current model can view it directly, use that capability; otherwise use the available external VLM. Do not use both by default, and do not infer visual quality from code, XML, coordinates, or extracted text alone. Use the same visual QA criteria either way. Default to one visual pass; after fixes, re-check only materially changed or uncertain slides.

For images, the hard requirement is **no non-uniform stretching/distortion**. Stop QA when there are no material defects.
QA is internal by default. Do not invent or expose internal review roles or approval stages unless the user asks about the validation process.

**Packaged scripts:**

- `scripts/qa/preflight_check.py` — fast deterministic QA for finished PPTX files.
- `scripts/ooxml/unpack.py` / `pack.py` — unpack/repack helpers used only for raw OOXML editing.
- `scripts/ooxml/validate_pptx_structure.py` — PPTX-specific structural validation after raw OOXML edits; no XSD schema dependency.


# Part 1 · Slide Design Best Practices

In one sentence: **don't make boring slides.** Bullet points on a white background are forgettable.

But "not boring" is not "flashy." The goal is a deck that looks **ready to use in real life** — the kind you could drop into a real meeting, class, or client pitch without editing.

**Substance comes before styling:** every slide must carry one specific, concrete claim — real numbers, names and mechanisms, and the "so what" behind them — not a topic label or generic filler. Depth is not density: it comes from picking the sharpest fact and cutting the rest, never from more text.

## 1. Decide three things before you start

**① Pick a content-informed color palette**
The palette should feel designed *for this topic*. A good test: if you could drop your colors into a completely unrelated deck and it would still "work," your choices aren't specific enough. Derive the hues from the subject itself — its place, industry, brand, or mood — rather than reaching for a default.

**② Dominance, not equality**
One color should dominate 60–70% of the visual weight, supported by 1–2 secondary tones and one sharp accent. **Never give all colors equal weight.**

**③ Dark/light contrast + a visual motif**

- "Sandwich" structure: **dark backgrounds** for the title and closing slides, **light backgrounds** for content slides. Or commit to dark throughout for a premium feel.
- Pick **one** signature motif and repeat it on every slide: rounded image frames, oversized numbers, a consistent card treatment, etc.
- ⚠️ **Do NOT** use a "color bar / accent stripe / sidebar strip" as your motif — that's a hallmark of AI-generated slides (see the avoid list).

## 2. Color

Build the palette on the **BACKGROUND → PRIMARY → ACCENT** model, and reuse those exact same three roles on every single slide:

- **BACKGROUND** — a neutral or near-neutral surface (off-white, warm grey, deep navy, near-black) that carries 60–70% of the slide
- **PRIMARY** — the brand/topic color used for headers, key shapes, chart bars and structural elements
- **ACCENT** — ONE saturated color, used sparingly (roughly 5–10% of the slide) on the single most important element: a headline number, a highlighted bar, an underline
- **Define the palette once as constants at the top of the script** (e.g. `BG`, `PRIMARY`, `ACCENT`, plus `TEXT` and `MUTED`) and reference only those constants — never hand-pick ad-hoc colors slide by slide.
- Be **restrained and consistent**: no loud, garish, neon or clashing colors, no rainbow palettes, no slide that introduces a color the rest of the deck never uses. Slide-to-slide color shifts should be limited to the background/text inversion of the sandwich structure.
- Prefer **tints and shades of the primary** (lighter/darker variants) over adding new hues when you need more differentiation — including in charts.
- High contrast between text and background: dark text on light backgrounds **or** light text on dark backgrounds.
- **Cross-deck independence:** when generating multiple decks in one session, derive each palette fresh from its own topic. Do not reuse or drift toward palettes from earlier decks in the same conversation.

## 3. Layout for each slide

**Every slide needs a meaningful visual device** — image, chart, diagram, semantic icon/pictogram, shape, or deliberate typographic composition. Dense prose-only slides are forgettable; an intentional breathing page built around one strong statement, number, or image is valid.

**Every slide needs ONE clear visual focal point** — an oversized number, a chart, an image, a single bold statement — that dominates the composition. Everything else is subordinate to it.

**Prefer container-free grouping — use it on the majority of slides.** Grouping comes from alignment, spacing and weight, not from drawing a box around things:

- **Hairline separators** (one 1px rule between items, never thickened, never shadowed) / **row list** (full-width rows, no container, separated by generous line spacing and a bold lead-in phrase) / **outline box** (1px stroke, no fill) / **tinted band** (a horizontal band of very light tint bleeding to both slide edges — no rounded corners, no border, no shadow)
- Icon + text rows (icon at the row's left, bold header, description below — set the icon on the bare background or in a square/rounded-square holder, not a circle)

**Semantic iconography:** treat icons and pictograms as information design, not filler. Use them actively for 3–6 peer concepts, features, symptoms, benefits, risks, short steps, categories, comparison rows, or diagram nodes when they improve recognition/scanning. Prefer one coherent vector/native-shape icon family per deck; avoid emoji, mixed icon styles, and the stereotyped `three rounded cards + three circle icons` pattern. Icons should remain subordinate to the page's primary focal point and should not be forced onto cover, breathing, hero-photo, or chart-dominant pages without a semantic job.

**Choose layout based on what's actually on the page** — element count, their relationships (sequence, comparison, hierarchy, spatial), and whether imagery or data leads. Do not mechanically map page role to a fixed layout.

**Layout vocabulary (available palette, not exhaustive):**
bold title, numbered steps, side-by-side / table comparison, chart-centric + oversized figure, single-statement emphasis, card row, full-bleed hero, split visual + content, image band, framed figure + caption, annotated evidence, overview + detail, diptych/triptych, small multiples, asymmetric editorial collage, map + routed callouts.

**Data display:** (point sizes here and throughout assume the 13.33 × 7.5" canvas — see §6)

- Large stat callouts (typically 52–80pt with a small label below; a breathing/data hero number may reach ~88–96pt when it is the sole focal point)
- Comparison columns (before/after, pros/cons, side-by-side options)
- Timeline / process flow (numbered steps, arrows)
- Keep metrics short and punchy — "$87B TAM" beats "Total Addressable Market is $87 billion"

**Density & rhythm:**

- **One key message per slide** — visual hierarchy: title → subtitle → body → supporting detail.
- **Control text volume.** Keep bullets to short phrases; if a slide is getting text-heavy, trim or split. Goal is comfortable density, not emptiness.
- **Leave breathing room** — text boxes need margin to spare, not edge-to-edge fill.
- **Vary the rhythm** — mix text-driven, image-driven, data-driven, and breathing pages according to the story; do not mechanically alternate.
- **No accidental blank areas, no content-free decoration.** Resolve residual space by enlarging content or tightening the grid — never with filler bars/strips/blocks. Deliberate negative space is valid on breathing/hero/editorial pages. A photo region is NOT empty.
- **Sanity check per slide:** is there an obvious focal point, and is nothing overflowing?

**Title policy:** match the title to the communication job. Pyramid/decision pages should prefer supported assertion titles; instructional pages should use teaching titles; briefing/reference pages may use neutral topic titles; showcase pages keep titles concise and subordinate to the hero visual. When a page makes a claim, the chart, diagram, evidence, example, or visual comparison that proves/explains it should be obvious and spatially close.

**Cross-page visual continuity:** when adjacent slides explain one evolving system, process, geography, scene, or comparison, preserve the same visual anchors where useful — progressive annotation, overview → detail, stable comparison frames, consistent peer-photo treatment, or controlled cross-page crops — rather than redesigning the mental map on every slide.

### Information design for text-led & conceptual slides

When a slide has little or no external imagery or quantitative data, **the information itself must become the visual material**. Do not fall back to `title + equal cards + paragraphs`. First identify the semantic relationship among ideas, then encode that relationship with position, scale, whitespace, typography, lines, shapes, and concise labels.

**Relationship → preferred composition**

| Information structure | Prefer | Avoid |
|---|---|---|
| One dominant takeaway + 1–3 supporting facts | **Typographic statement / big-number editorial** | A card for the headline plus separate cards for every support point |
| 3–6 peer facts, features, principles, symptoms, or categories | **Asymmetric modular mosaic**, icon strip, or editorial rows | Repeated equal rounded cards by default |
| Sequence / workflow / journey | **Process rail / pathway / numbered flow** | Independent step cards with weak directional connection |
| Iteration / continuous improvement | **Lifecycle loop / return path** | A linear list that hides the feedback loop |
| Hierarchy / layers / containment | **Stacked layers / nested structure / depth diagram** | Equal peer boxes that erase hierarchy |
| A vs B / good vs bad / before vs after | **Editorial split / opposition axis / comparison field** | Two oversized card containers unless the entities truly need encapsulation |
| Core artifact + explanation | **Annotated artifact / callout anatomy** | Rewriting the artifact as prose in a second column |
| Many related factors around one theme | **Cluster / bubble field / hub-and-spoke** | A long bullet list |
| Input → transformation → output | **Pipeline / staged flow** | Three unrelated columns |
| Ranked or weighted items | **Ranked blocks / unequal bands / scale contrast** | Equal-size tiles that imply equal importance |

**Editorial information-design rules**

- **Typography is a graphic element.** A short phrase, number, code token, category label, or keyword may be the page's primary visual; do not reserve large type only for slide titles.
- **Importance should be visible.** Unequal ideas should receive unequal area, type size, contrast, or position. Avoid giving every module identical visual weight.
- **Cards are for encapsulation, not for hiding relationships.** Use a container when an item is truly an independent entity; if the content has sequence, hierarchy, causality, dependency, flow, containment, or contrast, visualize that relationship directly.
- **A modular mosaic is not a card grid.** Use asymmetric block sizes, shared edges/alignment, varied emphasis, and one dominant module. Avoid six equal rounded rectangles with identical styling.
- **Use micro-visuals deliberately.** Thin rules, arrows, brackets, numbering, axis lines, labels, semantic icons, and subtle tints may encode grouping or direction; they must do semantic work, not merely decorate empty space.
- **Preserve reading order.** Experimental/asymmetric composition is acceptable only when the eye can still enter at the focal point and follow a clear path through supporting information.
- **Vary composition families across text-led decks.** Do not repeat the same container-based pattern on adjacent or near-adjacent slides when the information relationships differ. Maintain one visual system while varying structure.
- **Prefer transformation over ornament.** If a concept can be made clearer by turning prose into a process, hierarchy, comparison, cluster, annotated artifact, or typographic statement, do that before adding decorative imagery.

## 4. Diagrams, flowcharts & data charts (strongly preferred)

- **Favor visual explanation over prose.** Whenever content can be shown as a diagram, show it as a diagram — a bulleted list is the fallback, not the default.
- Reach for these first: schematic/concept diagrams (boxes + connectors for architecture or relationships), flowcharts and numbered process chains (chevrons, arrows, timelines), comparison matrices, and data charts (bar, column, line, stacked, donut) for anything quantitative.
- Aim for a diagram, flowchart, or chart on a substantial share of the content slides — **a deck that is all text blocks is a failed deck**.
- Build them **natively** (`addChart` / `add_chart` with `CategoryChartData`, or composed shapes + connectors + text boxes) so they inherit the palette and cost zero image calls.
- **Label everything**: axis titles, units, categories, and direct value labels on bars/points. Drop chart junk — no 3D, no gradients, no unnecessary gridlines or legends when direct labels suffice.
- **Cite the source for every key figure and every chart**: a small source line (usually 10–12pt, muted) at the bottom of the slide, e.g. "Source: IDC Worldwide Tracker, 2025" or "Source: company 10-K, FY2024". Source/legal text may be smaller than explanatory labels, but never use it to carry a key message.
- Never present an invented number as sourced. If a figure is an estimate or illustrative, label it as such ("illustrative", "est.").

## 5. Image + text layering (critical)

- **Do not place text over uncontrolled image detail.** A full-bleed photo is acceptable only when the text sits in a deliberately protected zone with reliable contrast.
- Protect overlay text with the **least destructive treatment that works**: first prefer a directional/local gradient scrim, localized translucent field, or naturally low-detail region; use a full-slide dark overlay only when the composition genuinely needs it.
- **Often preferred:** place the image in a bounded region (e.g. the right 45–60%) and keep text in the other region on a solid background.
- If an image is decorative, keep it small and positioned where it won't collide with text.
- Test mentally: *"if I printed this slide in grayscale, could I still read every word?"*

For image-heavy slides, choose from the layout vocabulary in §3 rather than generic card grids. Do not force an image-heavy pattern when the available imagery is weak.

## 6. Typography

- **Deliberately CHOOSE a font pairing that suits the scenario, and name the choice in your plan** — a geometric/neo-grotesque sans for corporate, tech and data decks; a serif for editorial, academic, legal or heritage topics; a high-contrast display face for the cover only. **Never leave the library's default font in place.**
- **Set the font explicitly on every run/paragraph** (`run.font.name` in python-pptx, `fontFace` in pptxgenjs) — do not rely on inherited theme fonts.
- **Choose one deck-wide typography usage mode before composing slides, and keep that scale consistent across the deck.** Do not size each text box ad hoc. The ranges below assume a **13.33 × 7.5"** canvas and are starting ranges, not quotas:

| Usage mode | Hero / cover | Slide title | Main body | Support / caption | Chart / diagram labels | Source / legal |
|---|---:|---:|---:|---:|---:|---:|
| **Speaker-led** — projected, presenter explains detail | 52–72pt | 30–38pt | 22–26pt | 16–20pt | 14–18pt | 10–12pt |
| **Balanced** — presented and later read | 48–64pt | 28–36pt | 19–22pt | 15–18pt | 13–17pt | 10–12pt |
| **Reading-first** — report-like, more self-contained | 42–56pt | 26–32pt | 16–19pt | 13–16pt | 12–15pt | 10–11pt |

- **Page role adjusts the scale, not the underlying hierarchy:** `cover` uses hero type; `chapter` typically 36–52pt; `tldr` uses a 32–44pt takeaway headline; `content` uses the selected title/body scale; `data` may use a 44–80pt key metric; `breathing` may use a 36–64pt statement or ~60–96pt hero number; `closing` usually 32–48pt.
- **Use a small, coherent type scale.** Prefer ~5–6 reusable size tokens (`hero`, `title`, `body`, `support`, `label`, `source`) rather than many one-off sizes scattered across the deck.
- **Title length affects size:** one-line titles may use the upper end of the range; two-line titles use the lower end. Keep most slide titles to ≤2 lines. Shorten/rewrite before shrinking below the role range.
- **Do not solve overflow by continuously shrinking text.** Remedy order for newly created slides: (1) shorten copy, (2) widen/reflow the composition, (3) split the slide, then (4) reduce type within the recommended range.
- **Legibility floors:** meaningful explanatory body text should normally stay **≥14pt**; explanatory labels/captions normally **≥12pt**; **10–11pt is reserved for sources, legal notes, or similarly non-primary metadata**.
- **CJK should usually use the upper half of the chosen body range** and fewer characters per line rather than compensating for dense Chinese copy with smaller type.
- **Legibility beats decoration:** no thin/light weights on colored or image backgrounds, no all-caps for long strings, no letter-spacing so tight that glyphs collide.
- **Limit to 2 font families maximum**, and create hierarchy with **size and weight**, not by swapping faces.
- **No emoji in slide content.**

> **Canvas scaling:** the ranges above assume `LAYOUT_WIDE` / 13.33 × 7.5". If you deliberately use a 10"-wide canvas, scale the typography tokens and spatial measurements consistently (roughly ×0.75).

> **Preview caveat:** the font names you write into the `.pptx` are rendered by the **user's PowerPoint**, not by your build environment. If you preview via LibreOffice, it substitutes any font it doesn't have — and substitutes with different character widths make the preview's "overflow / fits" verdict disagree with the real deck. Prefer faces that both ship with Office and render true-to-width locally; where you can't, leave ~10% slack instead of trusting the preview.

## 7. Spacing

- Minimum margins **0.5"**
- **0.3–0.5"** between content blocks
- Consistent margins and spacing across all slides
- Leave breathing room — don't fill every inch

## 8. CJK fonts
- **Name a face the viewer's PowerPoint actually ships**: 微软雅黑 / 等线 (Windows), 苹方 PingFang SC (macOS). A face that only exists on the build machine (Noto Sans SC, 思源黑体, LXGW 文楷 …) silently substitutes on the user's machine — use it only as the fallback, not the only name.
- **Sans for the deck, serif for editorial weight**: 微软雅黑 / 苹方 for corporate, tech and data decks; 思源宋体 / 宋体 only for cultural, academic or heritage topics, and mainly on titles. **Never** use 楷体 / 行楷 / 隶书 / 艺术字体 for body text.
- **Avoid Light/Thin CJK weights** — Chinese glyphs have far more strokes than Latin, so hairline weights turn to mud on a projector. Regular for body, Bold/Semibold for titles; build hierarchy with size and weight, not with a third face.
- Chinese text has no spaces to break on, so a long run wraps mid-phrase — keep lines short and give CJK boxes ~15% more width than the Latin equivalent. Upside: CJK glyphs are full-width, so overflow checks on Chinese text are fairly trustworthy.
- Default to 宋体 or the library default and the deck instantly reads as a Word document — pick the face deliberately, same as §6 requires.

## 9. Avoid list (sources of an "AI-generated" look)

- ❌ **Don't reuse the same layout on every slide** — vary between columns, cards, and callouts
- ❌ **Don't overuse grid layouts** — card/tile grids (2×2, 3×2, 4-up …) are a strong AI-slide tell when repeated. Cap them at roughly **1 in 5 content slides**, never on consecutive slides, and only when the content is genuinely a set of peer items (team, feature matrix). For everything else use a focal-point, column, timeline, or diagram layout instead
- ❌ **Don't center body text** — left-align paragraphs and lists; center only titles
- ❌ **Make size contrast big enough** — title/body hierarchy must be obvious; use the §6 usage-mode/page-role scale rather than a fixed title size on every page
- ❌ **Don't default to blue** — choose colors that reflect the topic
- ❌ **Don't mix spacing randomly** — pick 0.3" or 0.5" and use it consistently
- ❌ **Don't style one slide and leave the rest plain** — commit fully or keep it simple throughout
- ❌ **Don't create dense prose-only slides** — use images, charts, diagrams, semantic icons, meaningful shapes, or deliberate typographic emphasis; sparse breathing pages are an intentional exception
- ❌ **Mind text-box padding** — to align text with shapes/lines, set the text box `margin` to 0 (or offset the shape to compensate)
- ❌ **Don't use low contrast** — text and graphics both need strong contrast against the background; avoid light-on-light or dark-on-dark
- 🚫 **Never add a decorative underline under titles** — a classic AI-slide tell; use whitespace or background color instead
- 🚫 **Never add decorative color bars / accent stripes** — including full-width header/footer bands, vertical sidebar strips, thin colored strips along a card edge, and "single-side borders" on rectangles. To set a card apart, use a **subtle background tint or shadow**, not an edge stripe. In particular, never run the same edge-bar treatment on several consecutive slides
- ❌ **Don't default to cream/beige backgrounds** — when unspecified, use white `FFFFFF` or your brand color; avoid warm-neutral defaults like `F5F5DC`, `FAF0E6`, `FAEBD7`, `FFF8E1`
- ❌ **Don't let text overflow its shape** — shorten copy, reflow/enlarge the container, or split the slide before shrinking core text; never leave content cut off or spilling out

## 10. QA

Follow the **risk-based QA ladder in §0.6**. Additionally: when using a template, grep for leftover placeholders (`xxx`, `lorem`, `TODO`, `[insert`, etc.).

---

# Part 2 · pptxgenjs Implementation

> 📖 **Before writing code, read `references/pptxgenjs-api.md`** for the full API reference (text, shapes, images, charts, tables, masters, backgrounds).

## Critical constraints (always in effect)

- **`LAYOUT_WIDE` (13.33 × 7.5") must be set explicitly** — pptxgenjs defaults to 10 × 5.625" which causes systematic overflow with this skill's type scale.
- **Canvas constants:** `const W = 13.33, H = 7.5, M = 0.5;` — derive all positions from these.
- **Hex colors without `#`** — `"FF0000"` ✅ / `"#FF0000"` ❌ (corrupts file)
- **Never 8-char hex for opacity** — use the `opacity` property instead (8-char corrupts file)
- **Never reuse option objects across calls** — pptxgenjs mutates in place; use factories: `const makeShadow = () => ({...})`
- **Bullet per-item, not top-level** — put `bullet` in each item's `options`, use a factory `const bu = () => ({code:"2022", indent:14})`
- **`breakLine: true`** between array items for multi-line text
- **`paraSpaceAfter`** for bullet spacing (never `lineSpacing`)
- **Fresh `pptxgen()` instance** per presentation
- **No `outEnd` on stacked bar charts** — only valid for clustered grouping
- **No `sizing:{type:'cover'}`** — compute aspect ratio manually instead
- **`rectRadius` only on `ROUNDED_RECTANGLE`**, not on `RECTANGLE`
- **Shadow `offset` must be non-negative** — use `angle: 270` for upward shadow

## Code Style

- Write concise code
- Avoid verbose variable names and redundant operations
- Avoid unnecessary print statements

---

# Template & Edit Workflows

> 📖 **Template-based creation:** read `references/template-workflow.md`
> 📖 **Editing existing decks:** read `references/editing-workflow.md`

These references cover: template study procedures, clone & fill vs fill-in modes, python-pptx helpers, text capacity budgeting, overflow handling, and verification checklists.

---

# Post-generation Enhancements

> 📖 Read `references/post-generation.md` when the user requests speaker notes, scripts, or animations.

After the core deck passes QA, you may briefly surface relevant follow-ups (speaker notes, full script, animations). Do not ask about these before generating the core deck unless the user explicitly requests them. The deck is complete without them unless the user asks.

---

## Dependencies

Required dependencies (should already be installed):

- **markitdown**: `pip install "markitdown[pptx]"` (text extraction)
- **python-pptx**: `pip install python-pptx` (template inspection and editing existing decks)
- **pptxgenjs**: `npm install -g pptxgenjs` (creating presentations)
- **playwright**: `npm install -g playwright@1.50.0` (HTML rendering)
- **sharp**: `npm install -g sharp` (SVG rasterization and image processing)
- **LibreOffice**: `sudo apt-get install libreoffice` (PDF conversion)
- **Poppler**: `sudo apt-get install poppler-utils` (pdftoppm)
- **defusedxml**: `pip install defusedxml` (secure XML parsing)
