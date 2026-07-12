#!/usr/bin/env python3
"""Generates the Doris built-in function catalog for brikk-sql-metadata from Doris's own registry.

Adapted from doris-intellij-plugin/tools/generate_doris_functions.py (same extraction
approach, upgraded output: per-kind FunctionDefs as generated Kotlin instead of a flat
name list).

Source of truth: the committed Nereids registry lists
  fe/fe-core/src/main/java/org/apache/doris/catalog/Builtin{Scalar,Aggregate,Window,
  TableValued,TableGenerating}Functions.java
Each entry is `scalar(SomeFn.class, "name", "alias1", ...)` (or agg/window/tableValued/
tableGenerating variants) — the quoted strings after `.class,` are the function's primary
name followed by aliases. This is what Doris registers at runtime
(FunctionRegistry.registerBuiltinFunctions), so it's more complete and authoritative than
the docs. Re-run against a newer Doris checkout to refresh.

Signatures (arg/return types) come from vendor/data/doris-signatures.json — statically
extracted from each function class's SIGNATURES field by
tools/extract_doris_signatures.py (no fe-core build; see that script for the grammar
and type-rendering rules). When the JSON is present, FunctionDef.overloads is filled by
joining registry class name -> extracted signatures; names whose class exposes no
static SIGNATURES keep empty overloads (counted in the run report).

Usage: python3 tools/generate_doris_functions.py [<doris-repo-root>]
       Default source: vendor/data/doris-registry/ (committed, pinned copy of the
       registry files — reproducible from this repo alone). Pass a Doris checkout
       root (e.g. reference/doris) to refresh against a newer Doris; then re-copy
       the Builtin*Functions.java files into vendor/data/doris-registry/, re-run
       tools/extract_doris_signatures.py against the same checkout, and update
       the provenance in vendor/README.md so the vendored copies stay in sync.
"""

from __future__ import annotations

import glob
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
VENDORED_REGISTRY = ROOT / "vendor" / "data" / "doris-registry"
SIGNATURES_JSON = ROOT / "vendor" / "data" / "doris-signatures.json"
# Pin of the vendored registry copy (update when refreshing vendor/data/doris-registry/):
VENDORED_VERSION = "v0.8.2-31011-gd8fd23f7f38"
OUT = ROOT / "brikk-sql-metadata" / "src" / "dev.brikk.house.sql.metadata" / "GeneratedDorisFunctionCatalog.kt"

IDENT = re.compile(r"[A-Za-z][A-Za-z0-9_]*$")
QUOTED = re.compile(r'"([^"]+)"')
CLASS_REF = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\.class,")

# Registry file -> FunctionKind enum member
KINDS = {
    "BuiltinScalarFunctions.java": "SCALAR",
    "BuiltinAggregateFunctions.java": "AGGREGATE",
    "BuiltinWindowFunctions.java": "WINDOW",
    "BuiltinTableValuedFunctions.java": "TABLE_VALUED",
    "BuiltinTableGeneratingFunctions.java": "TABLE_GENERATING",
}


def doris_version(doris_root: pathlib.Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(doris_root), "describe", "--tags", "--always"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def collect(registry_dir: pathlib.Path) -> dict[str, list[tuple[str, str, list[str]]]]:
    """kind -> [(java class simple name, PRIMARY_NAME, [ALIASES...])]."""
    catalog = registry_dir
    by_kind: dict[str, list[tuple[str, str, list[str]]]] = {k: [] for k in KINDS.values()}
    found = 0
    for path in sorted(glob.glob(str(catalog / "Builtin*Functions.java"))):
        base = os.path.basename(path)
        kind = KINDS.get(base)
        if kind is None:
            print(f"warning: unrecognized registry file {base} — skipped (update KINDS?)", file=sys.stderr)
            continue
        found += 1
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                m = CLASS_REF.search(line)
                if m is None:
                    continue
                names = [n.strip().upper() for n in QUOTED.findall(line[m.end():]) if IDENT.match(n.strip())]
                if names:
                    by_kind[kind].append((m.group(1), names[0], names[1:]))
    if found == 0:
        sys.exit(f"error: no Builtin*Functions.java under {catalog}")
    return by_kind


def load_signatures() -> tuple[dict[str, list[dict]], str | None]:
    """class name -> parsed signature dicts from vendor/data/doris-signatures.json."""
    if not SIGNATURES_JSON.exists():
        return {}, None
    data = json.loads(SIGNATURES_JSON.read_text(encoding="utf-8"))
    return {cls: e["signatures"] for cls, e in data["classes"].items()}, data.get("doris_version")


def main() -> None:
    if len(sys.argv) > 1:
        # Refresh mode: read from a Doris checkout.
        doris_root = pathlib.Path(sys.argv[1])
        registry = doris_root / "fe" / "fe-core" / "src" / "main" / "java" / "org" / "apache" / "doris" / "catalog"
        version = doris_version(doris_root)
        print("NOTE: refreshing from a checkout — re-copy the Builtin*Functions.java files "
              "into vendor/data/doris-registry/ and update VENDORED_VERSION + vendor/README.md.",
              file=sys.stderr)
    else:
        registry = VENDORED_REGISTRY
        version = VENDORED_VERSION
    by_kind = collect(registry)
    signatures_by_class, sig_version = load_signatures()
    if signatures_by_class and sig_version and sig_version != version:
        print(f"warning: doris-signatures.json is from {sig_version} but registry is {version} "
              "— re-run tools/extract_doris_signatures.py against the same checkout",
              file=sys.stderr)
    total_defs = sum(len(v) for v in by_kind.values())
    total_names = sum(1 + len(a) for v in by_kind.values() for (_, _, a) in v)

    lines = [
        "// GENERATED FILE — DO NOT EDIT.",
        f"// Generated by tools/generate_doris_functions.py from Apache Doris {version}",
        "// (fe/fe-core/.../catalog/Builtin*Functions.java — Doris's runtime function registry;",
        "// overloads from vendor/data/doris-signatures.json, statically extracted from each",
        "// function class's SIGNATURES field by tools/extract_doris_signatures.py — see that",
        "// script's header for the type-rendering rules incl. ANY_<n>/ARG_<n> placeholders).",
        "// Apache Doris is Apache-2.0 licensed. See ATTRIBUTIONS.md and vendor/README.md.",
        "package dev.brikk.house.sql.metadata",
    ]

    # One block of lines per FunctionDef, costed by constructor count, then emitted as
    # chunked private functions (same pattern as the DuckDB catalog: a single listOf
    # would blow the JVM's 64KB method/clinit bytecode limit).
    blocks: list[tuple[list[str], int]] = []
    total_overloads = defs_with_overloads = defs_without_overloads = 0
    for kind in ("SCALAR", "AGGREGATE", "WINDOW", "TABLE_VALUED", "TABLE_GENERATING"):
        blocks.append(([f"    // {kind.lower()} ({len(by_kind[kind])})"], 0))
        for cls, name, aliases in sorted(by_kind[kind], key=lambda t: t[1]):
            overloads = signatures_by_class.get(cls, [])
            ctor = f'    FunctionDef("{name}", FunctionKind.{kind}'
            if aliases:
                alias_list = ", ".join(f'"{a}"' for a in aliases)
                ctor += f", listOf({alias_list})"
            if not overloads:
                defs_without_overloads += 1
                blocks.append(([ctor + "),"], 1))
                continue
            defs_with_overloads += 1
            total_overloads += len(overloads)
            block = [ctor + (", overloads = listOf(" if aliases else ", overloads = listOf(")]
            for sig in overloads:
                args = ", ".join(f'"{a}"' for a in sig["args"])
                ret = sig["return"]
                variadic = ", variadic = true" if sig["variadic"] else ""
                block.append(f'        FunctionOverload(listOf({args}), "{ret}"{variadic}),')
            block.append("    )),")
            blocks.append((block, 1 + len(overloads)))

    CHUNK_BUDGET = 400  # constructors per chunk function (JVM method-size headroom)
    chunks: list[list[str]] = [[]]
    budget = 0
    for block, cost in blocks:
        if budget + cost > CHUNK_BUDGET and chunks[-1]:
            chunks.append([])
            budget = 0
        chunks[-1].extend(block)
        budget += cost

    lines.append("")
    lines.append(f"/** Doris built-in functions: {total_defs} definitions, {total_names} names incl. aliases, "
                 f"{total_overloads} overloads. */")
    lines.append("val DORIS_FUNCTION_CATALOG: FunctionCatalog = FunctionCatalog(")
    lines.append("    " + " + ".join(f"chunk{i}()" for i in range(len(chunks))) + ",")
    lines.append(")")
    for i, chunk in enumerate(chunks):
        lines.append("")
        lines.append(f"private fun chunk{i}(): List<FunctionDef> = listOf(")
        lines.extend(chunk)
        lines.append(")")
    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {total_defs} defs / {total_names} names from Doris {version} -> {OUT}")
    if signatures_by_class:
        print(f"overloads: {defs_with_overloads} defs with {total_overloads} overloads; "
              f"{defs_without_overloads} defs without signatures (no static SIGNATURES on class)")
    else:
        print("overloads: vendor/data/doris-signatures.json not found — all overloads empty "
              "(run tools/extract_doris_signatures.py)")


if __name__ == "__main__":
    main()
