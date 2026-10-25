# Editing an existing PowerPoint presentation (.pptx)

For in-place edits to an existing deck (Fill-in mode, and small fixes like typos or updating numbers), work on a COPY and pick the approach by what the edit touches:

- **Approach A — `python-pptx` script** — preferred for text replacement, deleting/reordering slides, and any edit that should preserve fonts/colors/layout. Simpler and safer than raw XML for content swaps.
- **Approach B — raw OOXML** — required for animations, transitions, comments, speaker notes XML, theme tweaks, custom layout edits — anything `python-pptx` can't reach.

## Approach A — `python-pptx` text replacement (preferred for text edits)

**Workflow**

1. **Inventory the deck** — walk every slide, recurse into GROUP shapes (`shape_type == 6`), `print(repr(para.text))`. Use the inventory as the source of truth for replacement keys; rendered text often contains hidden chars that won't survive copy-paste.
2. **Helpers** — keep the build script short:

   ```python
   from pptx import Presentation
   from pptx.enum.text import MSO_AUTO_SIZE
   from pptx.oxml.ns import qn
   from pptx.util import Emu, Pt

   def iter_text_frames(shapes):
       for s in shapes:
           if s.shape_type == 6:                 # GROUP → recurse
               yield from iter_text_frames(s.shapes)
           elif s.has_text_frame:
               yield s, s.text_frame

   def _norm(s):                                  # strip soft breaks before matching
       return s.replace("\x0b", "").replace("\r", "").strip()

   def replace_in_paragraph(p, new_text):         # first-run replace preserves formatting
       runs = p.runs
       if not runs:
           p.add_run().text = new_text; return
       runs[0].text = new_text
       for r in runs[1:]:
           r._r.getparent().remove(r._r)

   def apply_replacements(tf, mapping):           # full-frame match, then per-paragraph
       m = {_norm(k): v for k, v in mapping.items()}
       full = "\n".join(p.text for p in tf.paragraphs)
       if _norm(full) in m:
           parts = m[_norm(full)].split("\n")
           for i, p in enumerate(tf.paragraphs):
               replace_in_paragraph(p, parts[i] if i < len(parts) else "")
           return
       for p in tf.paragraphs:
           if _norm(p.text) in m:
               replace_in_paragraph(p, m[_norm(p.text)])

   def delete_slide(prs, idx):                    # call high-index first
       sld = list(prs.slides._sldIdLst)[idx]
       prs.part.drop_rel(sld.get(qn("r:id")))
       prs.slides._sldIdLst.remove(sld)
   ```

**Estimate each shape's text capacity BEFORE generating replacement text**

Most overflow bugs come from generating copy without knowing the target box's capacity. Before drafting replacements for a template-heavy edit, inventory the target boxes and use that manifest as an early risk constraint. Do not mistake character count for exact rendered fit.

For each text-bearing shape collect: `slide_idx, shape_id, w_cm, h_cm, font_pt, orig_text, orig_len`. Then:

- `budget ≈ orig_len × 1.1` is a useful **risk heuristic**, especially when font/box geometry stays unchanged. The original copy is evidence of what fit, but not a universal ceiling: CJK/Latin glyph widths, line breaks, font changes, and empty template copy can make raw character counts misleading.
- `role = "label"` if `h_cm < 1.5` OR `orig_len ≤ 8` OR `font_pt ≥ 20`; else `"body"`.

Rules the generation step MUST obey:

- **Label boxes**: short phrase only. No full sentences, no trailing punctuation, no "term + explanation" expansion. Treat `max(orig_len, 8)` as a strong warning threshold rather than a universal mathematical cap. SWOT tiles, timeline tags, KPI labels all fall here.
- **Body boxes**: aim to stay near the original content budget when geometry is unchanged. Font size is inherited from the template; shrinking is a last resort, not plan A.
- If the content is genuinely longer and the layout permits, **grow the box itself** (`widen_to_fit(shape, Emu(...))` — see below) rather than shrinking the font. Check first that `left + width` won't collide with the next shape.

**Handling long replacement / unwanted wrapping after replacement**

When a longer replacement wraps to a new line, apply remedies in this order (cheapest first):

```python
def widen_to_fit(shape, max_grow_emu=Emu(0)):
    """Let PowerPoint size the shape to its text. Pass max_grow_emu>0 to also
    grow the explicit width (centered on the original position) before sizing."""
    if max_grow_emu:
        shape.left -= max_grow_emu // 2
        shape.width += max_grow_emu
    shape.text_frame.word_wrap = True
    shape.text_frame.auto_size = MSO_AUTO_SIZE.SHAPE_TO_FIT_TEXT

def shrink_text_to_fit(shape):
    """Keep the box fixed; let PowerPoint shrink the font to fit."""
    shape.text_frame.word_wrap = True
    shape.text_frame.auto_size = MSO_AUTO_SIZE.TEXT_TO_FIT_SHAPE
```

> ⚠️ Both helpers only **write the autofit flag** into the XML — python-pptx does not compute the resized shape or the shrunk font-scale itself. The actual fit is applied by the viewer (PowerPoint / LibreOffice) when the file is opened, so your programmatic overflow check can't see the result. Prefer trimming to `budget` (below), which *is* verifiable without rendering.

1. **Budget first (preferred).** Use the inventory to keep replacement copy close to the original visual budget; trim obvious outliers before relying on autofit. Numeric badges / small label boxes (`width ≤ 0.7"`, `font_size ≥ 16pt`) should remain very short; exact capacity depends on the font and characters.
2. **Widen the shape** with `widen_to_fit(shape, Emu(...))` when the content is genuinely longer and there's free space next to it. Always check the shape isn't going to collide with a neighbor first (compare `left+width` against the next shape's `left`).
3. **Shrink the font** with `shrink_text_to_fit(shape)` only for tight-layout boxes (table cells, numeric badges) where widening would break the grid. Last resort — it visibly breaks the typographic rhythm.

Skip `word_wrap = False`: it makes text overflow the box invisibly in PowerPoint and looks broken when exported.

**Critical gotchas**

- **Soft line breaks (`\x0b`)** silently break exact-match. Always `_norm()` both keys and lookups.
- **GROUP shapes** (`shape_type == 6`) hide text frames — recurse.
- **First-run replace** preserves formatting; `paragraph.text = ...` destroys it.
- **Short tokens collide.** `"01"`, `"%"`, `"18"` recur across slides — keep identity mappings or scope per slide index, never global cross-mappings like `"18": "12"`.
- **Delete slides high-index first** — deleting index 5 first shifts every later index down by one.

**Verify changed slides**

- Reopen the saved copy with `Presentation(path)` and walk the changed slides/shapes.
- Check changed/cloned slide relationships for missing media; use `{skill_dir}/scripts/ooxml/validate_pptx_structure.py` when raw OOXML was touched.
- Run bounds/overlap/overflow checks on changed or dense slides rather than the whole deck by default.
- If visual inspection is needed, use native vision first; otherwise external VLM. Spot-check changed/high-risk pages and expand only when an issue is found.
