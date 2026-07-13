#!/usr/bin/env python3
"""Extract a curated DataFusion sqllogictest (SLT) parse-acceptance corpus.

DataFusion has NO sqlglot dialect, so brikk's `datafusion` dialect cannot be gated by a
Python oracle. As a real-engine parse-acceptance backstop we mine DataFusion's own SLT
suite (reference/datafusion/datafusion/sqllogictest/test_files/*.slt — Apache-2.0), which
records SQL that the real DataFusion engine accepts.

We take a mainstream subset of files and emit every SELECT / WITH / VALUES statement from
`statement ok` and `query <types>` blocks. The gate (DatafusionSltParseTest) only asserts
that each statement PARSES under "datafusion" without a ParseError — not round-trip or
result equality (that is phase 2's engine verifier). Failures are expected and ledgered
honestly as parser gaps.

SLT block grammar (see sqllogictest docs):
    statement ok            -> SQL lines until a blank line (engine must accept)
    statement error <...>   -> SKIP (negative test)
    statement count <n>     -> SKIP (DML, not a parse-shape we gate)
    query <types> [opts]    -> SQL lines until a line that is exactly `----`
Comment lines start with `#`. We keep only statements whose first keyword is SELECT,
WITH or VALUES, and drop CREATE EXTERNAL TABLE / SET / EXPLAIN / PRAGMA / COPY / INSERT /
CREATE / DROP / ALTER (out of the SELECT-shape scope for thin phase 1).
"""

import json
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SLT_DIR = os.path.join(ROOT, "reference/datafusion/datafusion/sqllogictest/test_files")
OUT = os.path.join(
    ROOT, "brikk-sql/testResources/dialect-corpus/datafusion-slt-parse.json"
)

# ~8 mainstream files covering the core SELECT surface.
FILES = [
    "select.slt",
    "joins.slt",
    "cte.slt",
    "subquery.slt",
    "group_by.slt",
    "order.slt",
    "limit.slt",
    "pipe_operator.slt",
]

KEEP_LEADING = ("SELECT", "WITH", "VALUES", "TABLE", "FROM")  # FROM => pipe queries
# FROM is allowed because DataFusion pipe queries begin `FROM t |> ...`.


def datafusion_commit():
    try:
        return subprocess.check_output(
            ["git", "-C", os.path.join(ROOT, "reference/datafusion"), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def first_keyword(sql):
    m = re.match(r"\s*([A-Za-z_]+)", sql)
    return m.group(1).upper() if m else ""


def extract_file(path, source_name):
    cases = []
    with open(path) as fh:
        lines = fh.readlines()

    i = 0
    n = len(lines)
    while i < n:
        raw = lines[i]
        line = raw.rstrip("\n")
        stripped = line.strip()

        # header lines
        m_stmt = re.match(r"^statement\s+(\w+)", stripped)
        m_query = re.match(r"^query\b", stripped)

        if m_stmt and m_stmt.group(1) == "ok":
            start_line = i + 1  # 1-based header line
            i += 1
            sql_lines = []
            while i < n and lines[i].strip() != "":
                sql_lines.append(lines[i].rstrip("\n"))
                i += 1
            cases.append((start_line + 1, "\n".join(sql_lines).strip()))
            continue
        elif m_query:
            start_line = i + 1
            i += 1
            sql_lines = []
            while i < n and lines[i].rstrip("\n") != "----":
                if lines[i].strip() == "":
                    break
                sql_lines.append(lines[i].rstrip("\n"))
                i += 1
            # consume the ---- + result rows until blank
            while i < n and lines[i].strip() != "":
                i += 1
            cases.append((start_line + 1, "\n".join(sql_lines).strip()))
            continue
        else:
            i += 1

    out = []
    for line_no, sql in cases:
        if not sql:
            continue
        kw = first_keyword(sql)
        if kw not in KEEP_LEADING:
            continue
        # drop DDL/EXTERNAL/EXPLAIN-ish even if they start with a kept keyword
        upper = sql.upper()
        if kw == "TABLE":
            continue  # bare TABLE t (not a select shape)
        if "CREATE EXTERNAL" in upper or upper.startswith("EXPLAIN"):
            continue
        out.append(
            {
                "sql": sql,
                "source": f"{source_name}:{line_no}",
            }
        )
    return out


def main():
    all_cases = []
    per_file = {}
    for name in FILES:
        path = os.path.join(SLT_DIR, name)
        if not os.path.exists(path):
            print(f"  WARN: {name} not found, skipping")
            continue
        cases = extract_file(path, name)
        per_file[name] = len(cases)
        all_cases.extend(cases)

    # de-dup on sql text (keep first source)
    seen = set()
    deduped = []
    for c in all_cases:
        if c["sql"] in seen:
            continue
        seen.add(c["sql"])
        deduped.append(c)

    out = {
        "provenance": {
            "source": "Apache DataFusion sqllogictest test_files (reference/datafusion)",
            "source_repo": "https://github.com/apache/datafusion",
            "commit": datafusion_commit(),
            "license": "Apache-2.0 — see ATTRIBUTIONS.md",
            "files": FILES,
            "note": (
                "Real-engine parse-acceptance corpus: SQL that DataFusion itself accepts. "
                "The brikk gate only checks that each statement parses under 'datafusion' "
                "without a ParseError (no round-trip / result check — that is phase 2's "
                "engine verifier). Failures are honest parser gaps and are ledgered."
            ),
        },
        "cases": deduped,
        "stats": {"case_count": len(deduped), "per_file": per_file},
    }
    with open(OUT, "w") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")
    print(f"wrote {OUT}")
    print(f"  cases: {len(deduped)} (per-file pre-dedup: {per_file})")


if __name__ == "__main__":
    main()
