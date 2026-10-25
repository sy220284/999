#!/usr/bin/env python3
"""Validate the structure of an unpacked PPTX package without XSD schemas.

This validator is intentionally PPTX-specific. It checks the failure modes that
matter most after raw OOXML edits: XML well-formedness, relationship targets,
content-type coverage, presentation slide references, slide-layout references,
notes-slide ownership, and duplicate non-visual drawing IDs.

Usage:
    python validate_pptx_structure.py <unpacked_pptx_dir> [-v]

Exit 0 = pass; Exit 1 = structural issues found.
"""
from __future__ import annotations

import argparse
import posixpath
import sys
from collections import defaultdict
from pathlib import Path

from lxml import etree

PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"


def rels_source_part(rel_path: Path, root: Path) -> Path | None:
    """Return the package source part corresponding to a .rels file."""
    rel = rel_path.relative_to(root).as_posix()
    if rel == "_rels/.rels":
        return None
    parent, name = posixpath.split(rel)
    if not parent.endswith("/_rels") or not name.endswith(".rels"):
        return None
    source_dir = parent[: -len("/_rels")]
    source_name = name[: -len(".rels")]
    return root / source_dir / source_name


def resolve_target(rel_path: Path, target: str, root: Path) -> Path:
    """Resolve an internal relationship target to a package path."""
    target = target.replace("\\", "/")
    if target.startswith("/"):
        return root / target.lstrip("/")
    source_part = rels_source_part(rel_path, root)
    if source_part is None:
        base = ""
    else:
        base = source_part.relative_to(root).parent.as_posix()
    joined = posixpath.normpath(posixpath.join(base, target))
    return root / joined


def parse_xml(path: Path, issues: list[str]):
    try:
        return etree.parse(str(path))
    except Exception as exc:
        issues.append(f"  XML: {path.name}: {exc}")
        return None


def check_xml(root: Path, issues: list[str], verbose: bool) -> None:
    files = sorted(list(root.rglob("*.xml")) + list(root.rglob("*.rels")))
    for path in files:
        try:
            etree.parse(str(path))
        except Exception as exc:
            issues.append(f"  XML: {path.relative_to(root)}: {exc}")
    if verbose and not issues:
        print(f"PASS XML: {len(files)} XML/.rels files are well-formed")


def check_relationship_targets(root: Path, issues: list[str], verbose: bool) -> None:
    count = 0
    for rel_path in sorted(root.rglob("*.rels")):
        tree = parse_xml(rel_path, issues)
        if tree is None:
            continue
        for rel in tree.getroot().findall(f"{{{PKG_REL_NS}}}Relationship"):
            if rel.get("TargetMode") == "External":
                continue
            target = rel.get("Target")
            if not target:
                issues.append(f"  REL: {rel_path.relative_to(root)} has relationship without Target")
                continue
            count += 1
            resolved = resolve_target(rel_path, target, root)
            if not resolved.exists():
                issues.append(
                    f"  REL: {rel_path.relative_to(root)} {rel.get('Id')} -> {target} (missing)"
                )
    if verbose:
        print(f"CHECK relationships: {count} internal targets scanned")


def load_content_types(root: Path, issues: list[str]):
    path = root / "[Content_Types].xml"
    if not path.exists():
        issues.append("  CT: missing [Content_Types].xml")
        return {}, {}
    tree = parse_xml(path, issues)
    if tree is None:
        return {}, {}
    defaults = {}
    overrides = {}
    for node in tree.getroot():
        local = etree.QName(node).localname
        if local == "Default":
            defaults[node.get("Extension", "").lower()] = node.get("ContentType", "")
        elif local == "Override":
            overrides[node.get("PartName", "").lstrip("/")] = node.get("ContentType", "")
    return defaults, overrides


def check_content_types(root: Path, issues: list[str], verbose: bool) -> None:
    defaults, overrides = load_content_types(root, issues)
    if not defaults and not overrides:
        return
    checked = 0
    ignored = {"[Content_Types].xml"}
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if rel in ignored or rel.endswith(".rels"):
            continue
        checked += 1
        if rel in overrides:
            continue
        ext = path.suffix.lstrip(".").lower()
        if ext and ext in defaults:
            continue
        issues.append(f"  CT: no content type declaration covers '{rel}'")
    if verbose:
        print(f"CHECK content types: {checked} package parts scanned")


def relationship_map(rels_path: Path, root: Path, issues: list[str]) -> dict[str, tuple[str, str]]:
    result = {}
    if not rels_path.exists():
        return result
    tree = parse_xml(rels_path, issues)
    if tree is None:
        return result
    for rel in tree.getroot().findall(f"{{{PKG_REL_NS}}}Relationship"):
        result[rel.get("Id")] = (rel.get("Type", ""), rel.get("Target", ""))
    return result


def check_presentation_slide_refs(root: Path, issues: list[str], verbose: bool) -> None:
    presentation = root / "ppt/presentation.xml"
    rels = root / "ppt/_rels/presentation.xml.rels"
    if not presentation.exists():
        issues.append("  PPT: missing ppt/presentation.xml")
        return
    if not rels.exists():
        issues.append("  PPT: missing ppt/_rels/presentation.xml.rels")
        return
    tree = parse_xml(presentation, issues)
    if tree is None:
        return
    rmap = relationship_map(rels, root, issues)
    seen_ids = set()
    count = 0
    for node in tree.findall(f".//{{{P_NS}}}sldId"):
        count += 1
        sid = node.get("id")
        rid = node.get(f"{{{OFFICE_REL_NS}}}id")
        if sid in seen_ids:
            issues.append(f"  PPT: duplicate presentation slide id '{sid}'")
        seen_ids.add(sid)
        if not rid or rid not in rmap:
            issues.append(f"  PPT: slide id '{sid}' references missing relationship '{rid}'")
            continue
        rel_type, target = rmap[rid]
        if not rel_type.endswith("/slide"):
            issues.append(f"  PPT: relationship '{rid}' for slide id '{sid}' is not a slide relationship")
        resolved = resolve_target(rels, target, root)
        if not resolved.exists():
            issues.append(f"  PPT: slide id '{sid}' target missing: {target}")
    if verbose:
        print(f"CHECK presentation: {count} slide references scanned")


def check_slide_layout_refs(root: Path, issues: list[str], verbose: bool) -> None:
    rel_files = sorted((root / "ppt/slides/_rels").glob("*.rels")) if (root / "ppt/slides/_rels").exists() else []
    for rel_path in rel_files:
        tree = parse_xml(rel_path, issues)
        if tree is None:
            continue
        layout_rels = [
            rel for rel in tree.getroot().findall(f"{{{PKG_REL_NS}}}Relationship")
            if rel.get("Type", "").endswith("/slideLayout")
        ]
        if len(layout_rels) != 1:
            issues.append(
                f"  LAYOUT: {rel_path.relative_to(root)} has {len(layout_rels)} slideLayout relationships (expected 1)"
            )
    if verbose:
        print(f"CHECK layouts: {len(rel_files)} slide relationship files scanned")


def check_notes_ownership(root: Path, issues: list[str], verbose: bool) -> None:
    owners: dict[str, list[str]] = defaultdict(list)
    rel_files = sorted((root / "ppt/slides/_rels").glob("*.rels")) if (root / "ppt/slides/_rels").exists() else []
    for rel_path in rel_files:
        tree = parse_xml(rel_path, issues)
        if tree is None:
            continue
        for rel in tree.getroot().findall(f"{{{PKG_REL_NS}}}Relationship"):
            if rel.get("Type", "").endswith("/notesSlide"):
                resolved = resolve_target(rel_path, rel.get("Target", ""), root)
                owners[resolved.relative_to(root).as_posix()].append(rel_path.name)
    for notes_part, refs in owners.items():
        if len(refs) > 1:
            issues.append(f"  NOTES: '{notes_part}' referenced by multiple slides: {', '.join(refs)}")
    if verbose:
        print(f"CHECK notes: {len(owners)} notesSlide parts referenced")


def check_nonvisual_ids(root: Path, issues: list[str], verbose: bool) -> None:
    """Check cNvPr/@id uniqueness within each drawing-bearing XML part."""
    files = sorted((root / "ppt").rglob("*.xml")) if (root / "ppt").exists() else []
    scanned = 0
    for path in files:
        tree = parse_xml(path, issues)
        if tree is None:
            continue
        ids: dict[str, int] = {}
        duplicates = set()
        for node in tree.findall(f".//{{{P_NS}}}cNvPr") + tree.findall(f".//{{{A_NS}}}cNvPr"):
            value = node.get("id")
            if value is None:
                continue
            if value in ids:
                duplicates.add(value)
            ids[value] = ids.get(value, 0) + 1
        if ids:
            scanned += 1
        for value in sorted(duplicates):
            issues.append(f"  ID: {path.relative_to(root)} duplicate cNvPr id='{value}'")
    if verbose:
        print(f"CHECK non-visual IDs: {scanned} XML parts with cNvPr scanned")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate an unpacked PPTX package after raw OOXML edits")
    parser.add_argument("unpacked_dir", type=Path)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    root = args.unpacked_dir
    if not root.is_dir():
        parser.error(f"not a directory: {root}")
    if not (root / "ppt").is_dir():
        parser.error("directory does not look like an unpacked PPTX package (missing ppt/)")

    issues: list[str] = []
    check_xml(root, issues, args.verbose)
    check_relationship_targets(root, issues, args.verbose)
    check_content_types(root, issues, args.verbose)
    check_presentation_slide_refs(root, issues, args.verbose)
    check_slide_layout_refs(root, issues, args.verbose)
    check_notes_ownership(root, issues, args.verbose)
    check_nonvisual_ids(root, issues, args.verbose)

    if issues:
        print("PPTX STRUCTURE ISSUES:")
        for issue in issues:
            print(issue)
        print(f"\n{len(issues)} issue(s).")
        sys.exit(1)

    print("PPTX structure: ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
