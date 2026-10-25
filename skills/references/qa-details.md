# QA Details — Scripts, Scope Routing & Backup

## QA tiers (full specification)

- **Tier 0 — integrity (scripted, required check, always full-file):** run `python {skill_dir}/scripts/qa/preflight_check.py <file> --integrity --expected-slides N` after ANY modification. This checks: ZIP duplicate entries, dangling slide relationships, python-pptx parsability, and slide count against the expected number. It costs <0.1s and catches structural corruption that scoped geometric checks would miss.
- **Tier 1 — geometry (scripted, required check):** run `python {skill_dir}/scripts/qa/preflight_check.py <file> [--slides ...]`. This checks: text-bearing shapes within canvas bounds; pairwise text-box overlap (>30% area ratio); empty text boxes; missing fontFace. If preflight reports ANY issue, fix and regenerate before proceeding. Scoping rules — see below.
- **Tier 2 — fast extraction (scripted):** convert to PDF (`libreoffice --headless --convert-to pdf`) and extract text (`pdftotext "<file>.pdf" -`). Verify: all expected slide titles appear, no garbled CJK (font-fallback failure), key terminology present on expected slides. This catches content loss and font issues in seconds, with zero API calls.
- **Tier 3 — visual spot-check:** visually inspect only HIGH-RISK slides. Classify by layout complexity:

| Risk | Layout types | Action |
|------|-------------|--------|
| HIGH | dense comparison tables, complex flow/diagram pages, annotated artifacts with many callouts, cluster/bubble fields, asymmetric mosaics with many independently positioned elements, complex timelines, or other custom layouts whose visual correctness is hard to infer from geometry alone | MUST visual check |
| LOW | cover, chapter dividers, closing, single-title + body text, simple typographic statements, and simple 3–4 element information-design layouts when deterministic checks pass | Skip visual model |

Inspect the **rendered slide image**. If the current model can view the render directly, use it for the visual review; otherwise use the available external VLM. Do not run both by default. Apply the same rubric in either case, and do not infer visual quality from code, XML, coordinates, or extracted text alone.

Use the external VLM as a defect inspector rather than a second presentation designer: report concrete visible problems without changing narrative, content, brand system, or visual direction unless such a change is necessary to fix a visible defect. Default to one visual pass; after fixes, re-check only materially changed or uncertain slides.

Typical HIGH-RISK ratio: ~50–60% of content slides. This halves VLM API calls without losing coverage. When iterating fixes, only re-render and re-check the specific changed slides (`pdftoppm -f N -l N`).

## QA scope routing

Scoped QA (`--slides`) is safe only when the modification cannot affect other pages. Route by operation type:

| Operation | Integrity (Tier 0) | Geometry scope (Tier 1) |
|-----------|-------|---------|
| pptxgenjs full generation | full | changed slides only ✅ |
| python-pptx in-place shape edits (text/image swap) | full | changed slides only ✅ |
| python-pptx delete/add/reorder slides | full | **full-file** ⚠️ |

**Why:** deleting + adding slides can corrupt other pages via ZIP entry collisions and relationship re-numbering. Scoped geometry checks would miss the damage. Always run `--integrity` on the full file; only scope Tier 1 geometry when slide count and order are unchanged.

## Backup before in-place edits

Before any python-pptx modification to an existing .pptx, copy the file:

```python
import shutil
shutil.copy2(pptx_path, pptx_path + ".bak")
```

If QA fails after the modification, restore from `.bak` rather than attempting incremental repair on a potentially corrupted file.

## Image QA note

For images, the hard requirement is **no non-uniform stretching/distortion**. Crop composition is a polish concern; do not run an exhaustive crop audit by default. Stop QA when there are no material defects or unresolved high-risk warnings.

## Packaged script responsibilities

- `{skill_dir}/scripts/qa/preflight_check.py`: fast day-to-day QA for the final `.pptx`. `--integrity` and geometry are independent modes; add `--integrity --geometry` only when you intentionally want both in one invocation. The tiered workflow normally runs them separately to avoid duplicate work.
- `{skill_dir}/scripts/ooxml/unpack.py` / `pack.py`: use only for raw OOXML editing workflows.
- `{skill_dir}/scripts/ooxml/validate_pptx_structure.py`: OOXML-level checks for an unpacked PPTX package after raw edits. It validates XML well-formedness, relationship targets, content types, presentation slide references, slideLayout references, notesSlide ownership, and key non-visual IDs. It **does not depend on XSD schemas** and does not claim full Office XSD conformance.
