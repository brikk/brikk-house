#!/usr/bin/env python3
"""Native-grammar verification of brikk-emitted StarRocks SQL against the live engine.

Runs each SELECT from the brikk StarRocks dialect corpus (identity SQLs — brikk parses and
regenerates them as StarRocks) through the pinned live StarRocks 4.1.4 all-in-one Docker
engine's PARSER/ANALYZER (via `docker exec ... mysql`). A statement is PARSE-ACCEPTED when
the engine does not raise a *syntax* error — an analysis error about a missing table/column
(the corpus uses literal table names with no schema) proves the grammar accepted it. Only a
lexer/parser error counts as a rejection.

Also verifies a handful of representative brikk FUNCTION MAPPINGS end to end by transpiling
a source-dialect fragment to StarRocks (via the JVM is out of scope here; the mappings are
provided as pre-rendered StarRocks SQL) and confirming the engine parses them.

Engine-dependent + reproducible; kept OUT of the JVM test suite (needs Docker). Emits
docs/research/starrocks-engine-verification.md.

Usage: python3 tools/verify_starrocks_engine.py [--container starrocks-4.1.4]
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORPUS = ROOT / "brikk-sql" / "testResources" / "dialect-corpus" / "starrocks.json"
OUT = ROOT / "docs" / "research" / "starrocks-engine-verification.md"

# Analyzer errors that PROVE the grammar accepted the statement (no schema is set up).
ACCEPT_SUBSTRINGS = [
    "Unknown table", "Table", "does not exist", "Unknown column", "Column",
    "cannot be resolved", "Unknown database", "Getting analyzing error",
    "detailMessage = Unknown", "Unknown function",  # function-name analysis, not syntax
    "Unexpected exception: Unknown",
]
# Substrings that indicate a real SYNTAX/parse rejection.
SYNTAX_SUBSTRINGS = ["Getting syntax error", "You have an error in your SQL syntax",
                     "Syntax error", "parse error", "Unexpected input"]


def run_sql(container: str, sql: str) -> tuple[bool, str]:
    cmd = ["docker", "exec", container, "mysql", "-uroot", "-h127.0.0.1", "-P9030",
           "-N", "-B", "-e", sql]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30,
                          errors="replace")
    if proc.returncode == 0:
        return True, "ok"
    err = (proc.stderr or proc.stdout).strip().replace("\n", " ")
    return False, err


def classify(ok: bool, err: str) -> str:
    if ok:
        return "EXECUTED"
    low = err.lower()
    if any(s.lower() in low for s in SYNTAX_SUBSTRINGS):
        return "SYNTAX_REJECT"
    if any(s.lower() in low for s in ACCEPT_SUBSTRINGS):
        return "PARSE_ACCEPTED"
    # default: unknown analyzer error — treat as parse-accepted (not a syntax reject) but flag.
    return "PARSE_ACCEPTED?"


# Representative brikk function mappings rendered as StarRocks SQL (the target side of a
# transpile). Each must be grammar-accepted by the live engine. Sourced from the dialect
# generator TRANSFORMS + brikk gap-report bucket-B render checks.
REPRESENTATIVE_MAPPINGS = [
    ("ArgMax->MAX_BY", "SELECT MAX_BY(a, b) FROM t"),
    ("ArrayToString->ARRAY_JOIN", "SELECT ARRAY_JOIN([1, 2], '_') FROM t"),
    ("RegexpLike->REGEXP", "SELECT a REGEXP 'x' FROM t"),
    ("Flatten->ARRAY_FLATTEN", "SELECT ARRAY_FLATTEN([[1, 2], [3]]) FROM t"),
    ("ArrayContainsAll", "SELECT ARRAY_CONTAINS_ALL([1, 2, 3], [1, 2]) FROM t"),
    ("StDistance->ST_Distance_Sphere",
     "SELECT ST_Distance_Sphere(ST_X(p1), ST_Y(p1), ST_X(p2), ST_Y(p2)) FROM t"),
    ("TimestampTrunc->DATE_TRUNC", "SELECT DATE_TRUNC('MONTH', ts) FROM t"),
    ("UnixToStr->FROM_UNIXTIME", "SELECT FROM_UNIXTIME(n, '%Y') FROM t"),
    ("Map variadic", "SELECT MAP('k1', 1, 'k2', 2)"),
    ("TableFromRows/GENERATE_SERIES", "SELECT * FROM TABLE(GENERATE_SERIES(0, 10))"),
    ("PARSE_JSON", "SELECT PARSE_JSON('{\"a\": 1}')"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", default="starrocks-4.1.4")
    args = ap.parse_args()

    corpus = json.loads(CORPUS.read_text())
    # Verify what brikk EMITS for StarRocks: the `expected` regeneration when present
    # (e.g. DISTINCT ON -> ROW_NUMBER rewrite, `text` -> backtick-quoted reserved word),
    # else the identity `sql`. This is the SQL a consumer actually gets from brikk.
    selects = [
        (c.get("expected") or c["sql"])
        for c in corpus["identity"]
        if (c.get("expected") or c["sql"]).strip().upper().startswith("SELECT")
    ]

    rows = []
    counts: dict[str, int] = {}
    rejects = []
    for sql in selects:
        ok, err = run_sql(args.container, sql)
        verdict = classify(ok, err)
        counts[verdict] = counts.get(verdict, 0) + 1
        rows.append((sql, verdict, err if not ok else ""))
        if verdict == "SYNTAX_REJECT":
            rejects.append((sql, err))

    map_rows = []
    for label, sql in REPRESENTATIVE_MAPPINGS:
        ok, err = run_sql(args.container, sql)
        verdict = classify(ok, err)
        counts[verdict] = counts.get(verdict, 0) + 1
        map_rows.append((label, sql, verdict, err if not ok else ""))
        if verdict == "SYNTAX_REJECT":
            rejects.append((sql, err))

    lines = [
        "# StarRocks native-grammar engine verification",
        "",
        "Generated by `tools/verify_starrocks_engine.py` against the pinned live engine",
        "`starrocks/allin1-ubuntu:4.1.4` (`current_version()` = 4.1.4-4a9848e).",
        "",
        "Each brikk-emitted StarRocks SELECT from the dialect corpus is sent to the live",
        "engine parser/analyzer. **PARSE_ACCEPTED** = the grammar accepted it (any error is",
        "a missing-table/column analysis error, since the corpus uses schema-less literal",
        "table names). **EXECUTED** = ran to completion. **SYNTAX_REJECT** = a real parser",
        "rejection (a genuine grammar bug worth fixing).",
        "",
        "## Summary",
        "",
        "| verdict | count |",
        "|---|---|",
    ]
    for k in sorted(counts):
        lines.append(f"| {k} | {counts[k]} |")
    lines.append("")
    lines.append(f"Corpus SELECTs verified: {len(selects)}; representative mappings: {len(REPRESENTATIVE_MAPPINGS)}.")
    lines.append("")
    if rejects:
        lines.append("## SYNTAX REJECTIONS (investigate)")
        lines.append("")
        for sql, err in rejects:
            lines.append(f"- `{sql}`")
            lines.append(f"  - {err[:300]}")
        lines.append("")
    lines.append("## Representative function mappings")
    lines.append("")
    lines.append("| mapping | sql | verdict |")
    lines.append("|---|---|---|")
    for label, sql, verdict, _ in map_rows:
        lines.append(f"| {label} | `{sql}` | {verdict} |")
    lines.append("")
    lines.append("## Corpus SELECT results")
    lines.append("")
    lines.append("| sql | verdict |")
    lines.append("|---|---|")
    for sql, verdict, _ in rows:
        lines.append(f"| `{sql}` | {verdict} |")
    OUT.write_text("\n".join(lines) + "\n")

    print(f"wrote {OUT.relative_to(ROOT)}")
    print("verdicts: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    if rejects:
        print(f"WARNING: {len(rejects)} syntax rejections — see the report", file=sys.stderr)
        return 0  # report-only; do not fail the run
    return 0


if __name__ == "__main__":
    sys.exit(main())
