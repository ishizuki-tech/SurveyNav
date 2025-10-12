#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
convert_survey_json_to_yaml.py
--------------------------------
Convert the given survey JSON into YAML with the exact styles requested:
- prompts[].prompt         -> YAML literal block scalar (|-)  [strip trailing newline]
- graph.nodes[].question   -> YAML folded block scalar  (>-)  [unless short or empty]

Usage:
  python convert_survey_json_to_yaml.py input.json -o output.yaml
  cat input.json | python convert_survey_json_to_yaml.py - > output.yaml

Requires:
  pip install ruamel.yaml
"""

import sys
import json
import argparse
from typing import Any, Dict

try:
    from ruamel.yaml import YAML
    from ruamel.yaml.scalarstring import LiteralScalarString, FoldedScalarString
except Exception:
    print("Error: ruamel.yaml is required. Install with: pip install ruamel.yaml", file=sys.stderr)
    sys.exit(2)


def is_short_plain_question(s: str) -> bool:
    """Heuristic to keep very short questions as plain scalars (e.g., 'Start')."""
    if not s:
        return True
    # Keep plain if short and simple (no newline, length <= 40)
    return ("\n" not in s) and (len(s) <= 40)


def as_literal_strip(text: str) -> LiteralScalarString:
    """Return a LiteralScalarString with chomp='strip' (emits '|-')."""
    # Normalize newlines to LF and strip trailing newlines
    s = text.replace("\r\n", "\n").replace("\r", "\n").rstrip("\n")
    lit = LiteralScalarString(s)
    fa = getattr(lit, "fa", None)
    if fa is not None:
        fa.set_chomp("-")  # |- (strip)
    return lit


def as_folded_strip(text: str) -> FoldedScalarString:
    """Return a FoldedScalarString with chomp='strip' (emits '>-' )."""
    s = text.replace("\r\n", "\n").replace("\r", "\n").rstrip("\n")
    fs = FoldedScalarString(s)
    fa = getattr(fs, "fa", None)
    if fa is not None:
        fa.set_chomp("-")  # >- (strip)
    return fs


def transform(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Transform input JSON to the desired YAML-ready structure:
    - prompts: keep nodeId, prompt; prompt becomes LiteralScalarString (|-)
    - graph: copy as-is except question style rules
    """
    out: Dict[str, Any] = {}

    # --- prompts ---
    prompts_out = []
    for p in data.get("prompts", []):
        node_id = p.get("nodeId")
        prompt_text = p.get("prompt", "")
        prompts_out.append({
            "nodeId": node_id,
            "prompt": as_literal_strip(prompt_text),
        })
    out["prompts"] = prompts_out

    # --- graph ---
    g_in = data.get("graph", {})
    g_out: Dict[str, Any] = {}

    # Keep startId as-is
    if "startId" in g_in:
        g_out["startId"] = g_in["startId"]

    # Nodes with question styling
    nodes_out = []
    for n in g_in.get("nodes", []):
        n2 = dict(n)  # shallow copy
        q = n2.get("question", None)
        if isinstance(q, str):
            if len(q) == 0:
                # Leave empty string as is: ""
                n2["question"] = ""
            elif is_short_plain_question(q):
                # Keep short/plain as normal string (plain scalar)
                n2["question"] = q
            else:
                # Use folded (>-) for longer questions
                n2["question"] = as_folded_strip(q)
        nodes_out.append(n2)

    g_out["nodes"] = nodes_out
    out["graph"] = g_out

    return out


def dump_yaml(doc: Dict[str, Any], stream):
    yaml = YAML()
    yaml.default_flow_style = False
    yaml.allow_unicode = True
    yaml.width = 1000  # prevent unnecessary wrapping
    yaml.dump(doc, stream)


def load_json(path: str) -> Dict[str, Any]:
    if path == "-" or path == "stdin":
        return json.load(sys.stdin)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description="Convert survey JSON to styled YAML.")
    ap.add_argument("input", help="Input JSON file path or '-' for stdin")
    ap.add_argument("-o", "--output", help="Output YAML file path (default: stdout)")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        data = load_json(args.input)
        out_doc = transform(data)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as f:
                dump_yaml(out_doc, f)
        else:
            dump_yaml(out_doc, sys.stdout)
    except Exception as e:
        print(f"Conversion failed: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
