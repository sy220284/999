#!/usr/bin/env python3
"""Unpack a .pptx package and pretty-print its XML for controlled OOXML editing."""

import argparse
import zipfile
import defusedxml.minidom
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Unpack a PPTX file into a directory")
    parser.add_argument("office_file", help="PowerPoint file (.pptx)")
    parser.add_argument("output_dir", help="Output directory")
    args = parser.parse_args()
    unpack_document(args.office_file, args.output_dir)


def unpack_document(input_file, output_dir):
    """Unpack a PPTX file into a directory and pretty-print its XML parts."""
    input_path = Path(input_file)
    if input_path.suffix.lower() != ".pptx":
        raise ValueError(f"{input_file} must be a .pptx file")
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(input_path) as zf:
        zf.extractall(output_path)

    for pattern in ["*.xml", "*.rels"]:
        for xml_file in output_path.rglob(pattern):
            pretty_print_xml(xml_file)



def pretty_print_xml(xml_file):
    """Pretty-print a single XML file in place."""
    content = xml_file.read_text(encoding="utf-8")
    dom = defusedxml.minidom.parseString(content)
    xml_file.write_bytes(dom.toprettyxml(indent="  ", encoding="ascii"))


if __name__ == "__main__":
    main()
