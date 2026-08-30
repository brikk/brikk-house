#!/usr/bin/env python3
"""Generates the StarRocks built-in function catalog for brikk-sql-metadata.

Version-pinned to StarRocks **4.1.4** (git tag 4.1.4 -> commit 4a9848edf..., Docker
starrocks/allin1-ubuntu:4.1.4 @ sha256:faf7ce9c...; current 4.1.x patch at harvest).

Sources of truth (all vendored under vendor/data/, reproducible from this repo alone):

  1. vendor/data/starrocks-builtin-functions-4.1.4.tsv — the AUTHORITATIVE registry dump
     from the pinned live engine (`SHOW FULL BUILTIN FUNCTIONS`). Columns:
       Signature (e.g. `abs(DOUBLE)`) | Return Type | Function Type
       (Scalar/Aggregate/Table) | Intermediate Type | Properties (JSON incl. "fid").
     This is exactly what the engine registers (scalar + aggregate + window + table),
     so it is more complete and version-exact than the source alone.

  2. vendor/data/starrocks-registry/functions.py — StarRocks' declarative vectorized
     scalar registry (gensrc/script/functions.py). Used to mark VARIADIC scalar
     overloads (arg list ending in '...') and to sanity-cross-check scalar fids.

  3. vendor/data/starrocks-registry/FunctionSet.java — used ONLY for the
     `onlyAnalyticUsedFunctions` set: the live dump reports window functions under the
     "Aggregate" Function Type, so these names are re-classified WINDOW.

  4. vendor/data/starrocks-registry/TableFunction.java — table-function default output
     column names (a semantic fact the live dump does not expose).

Kind classification:
  - Function Type "Table"                          -> TABLE_GENERATING (row-set producers)
  - Function Type "Aggregate" & name in WINDOW set -> WINDOW
  - Function Type "Aggregate"                       -> AGGREGATE
  - Function Type "Scalar"                          -> SCALAR

Overloads: every distinct (arg types, return type) from the live dump is a
FunctionOverload. Scalar overloads whose functions.py entry ends in '...' get
variadic=true. Aliases are NOT inferred from the dump (the dump lists each registered
name separately; StarRocks aliases are name-distinct registry entries, so cross-name
aliasing is only asserted where FunctionSet.java's alias registration proves it — none
are folded here without that evidence, matching the "aliases only when evidence supports
aliasing" rule).

Profiles/sinceVersion: the live dump exposes no null-propagation or introduced-in
metadata, so profile and sinceVersion stay null (honest UNKNOWN), matching DuckDB/Trino.

Apache StarRocks is Apache-2.0 licensed. See ATTRIBUTIONS.md and vendor/README.md.

Usage: python3 tools/generate_starrocks_functions.py
"""

from __future__ import annotations

import ast
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
VENDOR = ROOT / "vendor" / "data"
LIVE_TSV = VENDOR / "starrocks-builtin-functions-4.1.4.tsv"
FUNCTIONS_PY = VENDOR / "starrocks-registry" / "functions.py"
FUNCTIONSET_JAVA = VENDOR / "starrocks-registry" / "FunctionSet.java"
TABLEFUNCTION_JAVA = VENDOR / "starrocks-registry" / "TableFunction.java"
OUT = ROOT / "brikk-sql-metadata" / "src" / "dev.brikk.house.sql.metadata" / "GeneratedStarrocksFunctionCatalog.kt"

STARROCKS_VERSION = "4.1.4"  # git tag 4.1.4 -> commit 4a9848edf03f5c936dac664b2d52527f48e72eb0
DOCKER_DIGEST = "sha256:faf7ce9c24d9c29c9431b4e8cbd4bb7a74cd169907c63f0c5ebaacc7f9df276b"

SIG_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$")


def parse_window_names() -> set[str]:
    """The `onlyAnalyticUsedFunctions` name set from FunctionSet.java (uppercased)."""
    text = FUNCTIONSET_JAVA.read_text(encoding="utf-8")
    # Map the FunctionSet.X constant -> its string value, then resolve the set members.
    const = dict(re.findall(r'public static final String ([A-Z_0-9]+) = "([^"]+)";', text))
    m = re.search(r"onlyAnalyticUsedFunctions\s*=\s*ImmutableSet.*?\.build\(\);", text, re.S)
    if not m:
        sys.exit("error: could not locate onlyAnalyticUsedFunctions in FunctionSet.java")
    members = re.findall(r"FunctionSet\.([A-Z_0-9]+)", m.group(0))
    names = set()
    for mem in members:
        if mem in const:
            names.add(const[mem].upper())
    # first_value_rewrite is an internal rewrite target; keep it out of user-facing WINDOW.
    return names


def parse_variadic_scalar_names() -> set[str]:
    """Scalar function names (uppercased) whose functions.py registry marks any overload
    variadic (arg list ends in '...')."""
    text = FUNCTIONS_PY.read_text(encoding="utf-8")
    tree = ast.parse(text)
    variadic: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id in (
                "vectorized_functions",
                "vectorized_aggregate_functions",
            ):
                if not isinstance(node.value, ast.List):
                    continue
                for entry in node.value.elts:
                    if not isinstance(entry, ast.List) or len(entry.elts) < 6:
                        continue
                    try:
                        name = ast.literal_eval(entry.elts[1])
                        args = ast.literal_eval(entry.elts[5])
                    except Exception:
                        continue
                    if isinstance(args, list) and args and args[-1] == "...":
                        variadic.add(str(name).upper())
    return variadic


def parse_table_fn_columns() -> dict[str, list[str]]:
    """Table-function primary name (uppercased) -> default output column names, from
    TableFunction.java initBuiltins()."""
    text = TABLEFUNCTION_JAVA.read_text(encoding="utf-8")
    cols: dict[str, list[str]] = {}
    # new TableFunction(new FunctionName("<name>"), Lists.newArrayList("c1","c2",...), ...)
    for m in re.finditer(
        r'new TableFunction\(new FunctionName\((?:"([^"]+)"|FunctionSet\.([A-Z_0-9]+))\),\s*'
        r"Lists\.newArrayList\(([^)]*)\)",
        text,
    ):
        name = m.group(1)
        if name is None:
            # resolve FunctionSet.CONST
            const = dict(re.findall(r'public static final String ([A-Z_0-9]+) = "([^"]+)";',
                                    FUNCTIONSET_JAVA.read_text(encoding="utf-8")))
            name = const.get(m.group(2), m.group(2))
        colnames = re.findall(r'"([^"]+)"', m.group(3))
        if name and colnames:
            cols[name.upper()] = colnames
    return cols


def load_live() -> list[tuple[str, list[str], str, str]]:
    """Returns [(name_upper, arg_types, return_type, function_type)] from the live dump."""
    rows = []
    lines = LIVE_TSV.read_text(encoding="utf-8").splitlines()
    for line in lines[1:]:  # skip header
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        sig, ret, ftype = parts[0], parts[1], parts[2]
        m = SIG_RE.match(sig)
        if not m:
            continue
        name = m.group(1).upper()
        arg_str = m.group(2).strip()
        args = [a.strip() for a in arg_str.split(",")] if arg_str else []
        rows.append((name, args, ret, ftype))
    return rows


def kesc(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def main() -> None:
    for p in (LIVE_TSV, FUNCTIONS_PY, FUNCTIONSET_JAVA, TABLEFUNCTION_JAVA):
        if not p.exists():
            sys.exit(f"error: missing vendored source {p}")

    window_names = parse_window_names()
    variadic_names = parse_variadic_scalar_names()
    table_cols = parse_table_fn_columns()
    rows = load_live()

    # name -> {"kind": ..., "overloads": [(args, ret)]}
    by_name: dict[str, dict] = {}
    for name, args, ret, ftype in rows:
        if ftype == "Table":
            kind = "TABLE_GENERATING"
        elif ftype == "Aggregate":
            kind = "WINDOW" if name in window_names else "AGGREGATE"
        else:
            kind = "SCALAR"
        entry = by_name.setdefault(name, {"kind": kind, "overloads": []})
        # A name reported under multiple Function Types keeps its most specific kind:
        # WINDOW > AGGREGATE > SCALAR > TABLE precedence for the (rare) dual-registered.
        prec = {"TABLE_GENERATING": 0, "SCALAR": 1, "AGGREGATE": 2, "WINDOW": 3}
        if prec[kind] > prec[entry["kind"]]:
            entry["kind"] = kind
        entry["overloads"].append((args, ret))

    # Dedup identical overloads while preserving order.
    for entry in by_name.values():
        seen = set()
        deduped = []
        for args, ret in entry["overloads"]:
            key = (tuple(args), ret)
            if key not in seen:
                seen.add(key)
                deduped.append((args, ret))
        entry["overloads"] = deduped

    KIND_ORDER = ("SCALAR", "AGGREGATE", "WINDOW", "TABLE_VALUED", "TABLE_GENERATING")
    counts = {k: 0 for k in KIND_ORDER}
    for e in by_name.values():
        counts[e["kind"]] += 1

    header = [
        "// GENERATED FILE — DO NOT EDIT.",
        f"// Generated by tools/generate_starrocks_functions.py from StarRocks {STARROCKS_VERSION}.",
        "//",
        "// AUTHORITATIVE registry: vendor/data/starrocks-builtin-functions-4.1.4.tsv — the",
        "// live pinned engine's `SHOW FULL BUILTIN FUNCTIONS` dump (Docker",
        f"// starrocks/allin1-ubuntu:{STARROCKS_VERSION} @ {DOCKER_DIGEST};",
        "// current_version() = 4.1.4-4a9848e). Names + arg/return signatures + kinds come",
        "// straight from the engine. VARIADIC scalar overloads are marked from the",
        "// declarative registry vendor/data/starrocks-registry/functions.py ('...' args).",
        "// WINDOW kind: the live dump reports window functions under Function Type",
        "// 'Aggregate'; names in FunctionSet.java's onlyAnalyticUsedFunctions are",
        "// reclassified WINDOW. Table-generating functions carry no profile.",
        "//",
        "// profile / sinceVersion stay null: the engine exposes no null-propagation or",
        "// introduced-in metadata (honest UNKNOWN, as for DuckDB/Trino). Aliases are NOT",
        "// inferred (StarRocks registers each name separately; no cross-name aliasing is",
        "// asserted without FunctionSet.java alias evidence).",
        "//",
        "// Apache StarRocks is Apache-2.0 licensed. See ATTRIBUTIONS.md and vendor/README.md.",
        "package dev.brikk.house.sql.metadata",
    ]

    blocks: list[tuple[list[str], int]] = []
    total_overloads = 0
    for kind in KIND_ORDER:
        names = sorted(n for n, e in by_name.items() if e["kind"] == kind)
        blocks.append(([f"    // {kind.lower()} ({len(names)})"], 0))
        for name in names:
            entry = by_name[name]
            overloads = entry["overloads"]
            total_overloads += len(overloads)
            is_var = name in variadic_names
            ctor = f'    FunctionDef("{kesc(name)}", FunctionKind.{kind}'
            if not overloads:
                blocks.append(([ctor + "),"], 1))
                continue
            block = [ctor + ", overloads = listOf("]
            for args, ret in overloads:
                # The live dump encodes a variadic tail as a literal '...' final arg (and
                # functions.py marks the same with a trailing '...'). Normalize: drop the
                # sentinel and set variadic=true (matching the Doris/Trino catalog shape,
                # where the last concrete type repeats). A name flagged variadic in
                # functions.py but whose live overload lacks the sentinel is also marked.
                is_variadic_overload = bool(args) and args[-1] == "..."
                concrete = args[:-1] if is_variadic_overload else args
                variadic = is_variadic_overload or (is_var and bool(concrete))
                arglist = ", ".join(f'"{kesc(a)}"' for a in concrete)
                variadic_kw = ", variadic = true" if variadic else ""
                block.append(f'        FunctionOverload(listOf({arglist}), "{kesc(ret)}"{variadic_kw}),')
            block.append("    )),")
            blocks.append((block, 1 + len(overloads)))

    CHUNK_BUDGET = 400
    chunks: list[list[str]] = [[]]
    budget = 0
    for block, cost in blocks:
        if budget + cost > CHUNK_BUDGET and chunks[-1]:
            chunks.append([])
            budget = 0
        chunks[-1].extend(block)
        budget += cost

    total_defs = len(by_name)
    lines = list(header)
    lines.append("")
    lines.append(
        f"/** StarRocks {STARROCKS_VERSION} built-in functions: {total_defs} definitions, "
        f"{total_overloads} overloads "
        f"(scalar {counts['SCALAR']}, aggregate {counts['AGGREGATE']}, window "
        f"{counts['WINDOW']}, table-generating {counts['TABLE_GENERATING']}). */"
    )
    lines.append("val STARROCKS_FUNCTION_CATALOG: FunctionCatalog = FunctionCatalog(")
    lines.append("    " + " + ".join(f"chunk{i}()" for i in range(len(chunks))) + ",")
    lines.append("    // Grammar/operator forms absent from the registry (see StarrocksGrammarBuiltins.kt).")
    lines.append("    grammarBuiltins = STARROCKS_GRAMMAR_BUILTINS,")
    lines.append(")")
    for i, chunk in enumerate(chunks):
        lines.append("")
        lines.append(f"private fun chunk{i}(): List<FunctionDef> = listOf(")
        lines.extend(chunk)
        lines.append(")")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {total_defs} defs / {total_overloads} overloads from StarRocks {STARROCKS_VERSION} -> {OUT}")
    print("kinds: " + ", ".join(f"{k}={counts[k]}" for k in KIND_ORDER))
    print(f"window names reclassified: {sorted(n for n in by_name if by_name[n]['kind']=='WINDOW')}")
    print(f"table-generating: {sorted(n for n in by_name if by_name[n]['kind']=='TABLE_GENERATING')}")


if __name__ == "__main__":
    main()
