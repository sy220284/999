# Creating a presentation FROM a user-provided template (.pptx)

Route here when the user supplies a .pptx and wants a NEW deck built on it. Treat the template as a **structural system, not merely a visual reference**: its slide/layout vocabulary, typography, spacing, theme, placeholders, footers, and brand objects are the source of truth. Two non-negotiables: study the template BEFORE writing any content, and verify AFTER building.

> **Template inheritance (mandatory)** — If the user provides an existing PPT, a corporate template, or a reference file, you **must** follow the "template inheritance" flow rather than recreating a look-alike from scratch:
>
> - Analyze fonts, color scheme, spacing, footers, page numbers, placeholders, and brand elements.
> - Build a mapping between source pages and new pages.
> - Inherit existing layouts as much as possible instead of recreating a new set.
> - Only modify elements that are allowed to be modified.
> - Preserve the original template's visual language unless the user asks for a redesign.
> - If no page in the original template can carry the target content, state the limitation explicitly and propose the closest alternative.

**1. Decide the mode — "use as template" hides two different jobs:**


| Mode             | User signal                                                            | Build                                                                                                                        |
| ------------------ | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| **Clone & fill** | new outline or free structure — "做成这个风格" / "用这个模板做一份 X" | clone template pages and fill them; pick which pages to reuse by role and shape your outline around what the template offers |
| **Fill-in**      | new content maps ≈1:1 onto the template's pages (换数据 / 换客户)     | in-place replacement — Approach A of the Editing section                                                                    |

Both modes edit the .pptx natively and output an editable PowerPoint — **never** route a template job through the from-scratch HTML pipeline; a foreign HTML-rendered page next to the template's real pages is instantly visible and loses the theme. Clone & fill builds a new deck on the template; Fill-in is the lighter in-place path. Everything below applies to both.

**2. Study the template (before generating any content) — programmatic first, vision when it resolves ambiguity:**
  • Inventory every text shape with python-pptx (recurse into GROUPs): position, size, font, current text length. Use original text length as a capacity signal — the Editing section's text-budget heuristic applies, but it is not proof of rendered fit. Collect this first; it also helps drive the role classification below.
  • Classify each page's role (cover / section / content / stats / quote / closing…) — this role map is your layout catalog for planning. Start from structure and inventory: cover = few shapes + an outsized title (≥36pt) and/or a full-page background image; section = only 1–2 short text shapes; stats = a visibly oversized numeric shape (often 48pt+ on a wide canvas); quote = a single large centered long-text box; content = several body boxes. If the role depends on visual composition that structure alone cannot resolve, use native vision or external VLM per Part 0 rather than guessing.
  • Answer the one decisive structural question: does the design live in `slideLayouts` with real placeholders (→ clone & fill can `add_slide` + fill), or is it drawn on slides with free text boxes while layouts sit empty (common in downloaded templates; → clone & fill must clone slides at XML level)?
  • Read the design's source of truth: `presentation.xml` for slide size (never assume 16:9); `theme1.xml` for colors and fonts — slide XML uses `schemeClr` indirection, the real hex lives in the theme (remapped by the master's `clrMap`), and CJK text renders in the `<a:ea>` font.

**3. Plan, then build — always on a COPY of the user's file:**
  • Write a short plan first: final page order; each page's template source (slide index to clone, or layout name) chosen by ROLE; replacement text kept close to the target box's visual capacity. If the user's outline and the template's structure conflict, resolve toward the closest faithful template mapping when the choice is reasonable; only surface the trade-off when it is material and cannot be resolved safely without the user. Do not ask for a second outline approval unless the user explicitly requests outline-first review or a material unresolved conflict requires their decision. (Clone & fill: since structure is free, let the template's available page roles drive the outline rather than forcing a shape the template can't carry.)
  • Fill-in: follow the Editing section's Approach A as written.
  • Clone & fill: materialize pages first — `add_slide` + fill placeholders when layouts are real; otherwise clone the slide at XML level (copy the slide part + its `.rels`, re-register media rIds, add to `sldIdLst` and `[Content_Types].xml`). Prefer the packaged OOXML helpers (`{skill_dir}/scripts/ooxml/unpack.py` → edit → `{skill_dir}/scripts/ooxml/pack.py`; spot-check with `{skill_dir}/scripts/ooxml/validate_pptx_structure.py`) over ad-hoc ZIP/XML handling. If an in-process clone is simpler, rebuild relationships deliberately rather than copying only shape XML. Then replace content page by page (scope mappings per slide — clones share identical source text), swap images by replacing the image part bytes when preserving crop/formatting matters (keep the shape and rId), and delete unused template pages LAST, high-index first. Never hand-build a from-scratch page next to template pages — a foreign page is instantly visible.

**4. Verify before reporting done — deterministic baseline, visual inspection only when useful:**
Run the deterministic checks below first, then apply Part 0's targeted/visual QA only to risky or ambiguous pages:
  • **Page order & count** — count `sldIdLst` against the plan.
  • **Leftover placeholders** — grep the slide XML for `Click to add`, `xxx`, `lorem`, `TODO`, `[insert`.
  • **Broken images** — confirm every image rId on a cloned page resolves to a real media part (empty frames come from dangling rels): for each slide walk `slide.part.rels` and assert every non-external rel has a `target_part` with a non-empty `.blob`.
  • **File still opens** — reopen the saved copy with `Presentation(path)` and walk every slide's shapes. When raw OOXML was touched, unpack and spot-check it with `{skill_dir}/scripts/ooxml/validate_pptx_structure.py <unpacked_dir>`; `pack.py` also performs a LibreOffice open/convert check when available.
  • **Fonts & colors unchanged** — diff the run/theme font+color against the original.
  • **Overflow / collision** — use the "Budget every shape" heuristic and neighbor geometry to identify high-risk replacements. Text materially longer than the original deserves inspection, especially in labels and tightly packed layouts, but `len ≤ orig_len × 1.1` is not proof of fit and exceeding it is not automatically a defect. Trim, widen, or shrink when the geometry/visual result actually requires it.
