#!/usr/bin/env python3
"""Generates a Python-oracle parser corpus for brikk-sql differential testing.

Each case is a SQL string parsed with the base ("sqlglot") dialect via
sqlglot.parse_one and dumped with sqlglot.serde.dump. The Kotlin twin in
brikk-sql/test@jvm/dev.brikk.house.sql/ParserCorpusDifferentialTest.kt parses the
same SQL with our Parser and asserts a structural match on the dumps (with meta
and comments stripped on both sides).

Run from anywhere:  python3 tools/gen_parser_corpus.py
Re-run whenever reference/sqlglot is updated or cases are added.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "parser-corpus"

sys.path.insert(0, str(SQLGLOT))

import sqlglot  # noqa: E402
from sqlglot.serde import dump  # noqa: E402

QUERIES = [
    "SELECT 1",
    "SELECT 1 + 2 * 3",
    "SELECT (1 + 2) * 3",
    "SELECT -x, NOT y",
    "SELECT * FROM t",
    "SELECT a, b FROM t",
    "SELECT t.a FROM t",
    "SELECT db.t.a FROM db.t",
    "SELECT a AS x, b y FROM t AS tt",
    'SELECT "quoted col" FROM "quoted table"',
    "SELECT 'str', 1, 1.5, TRUE, FALSE, NULL FROM t",
    "SELECT a FROM t WHERE x = 1 AND y < 2 OR NOT z",
    "SELECT a FROM t WHERE x <> 1 AND y >= 2 AND z <= 3",
    "SELECT a FROM t WHERE x BETWEEN 1 AND 10",
    "SELECT a FROM t WHERE x IN (1, 2, 3)",
    "SELECT a FROM t WHERE x IN (SELECT b FROM u)",
    "SELECT a FROM t WHERE x LIKE 'a%' AND y IS NULL AND z IS NOT NULL",
    "SELECT COUNT(*) FROM t",
    "SELECT COUNT(DISTINCT a), SUM(b), MIN(c), MAX(d), AVG(e) FROM t",
    "SELECT my_func(a, b, 1) FROM t",
    "SELECT CAST(a AS INT), CAST(b AS VARCHAR(255)), CAST(c AS DECIMAL(10, 2)) FROM t",
    "SELECT DISTINCT a FROM t",
    "SELECT a FROM t GROUP BY a",
    "SELECT a, COUNT(*) FROM t GROUP BY a HAVING COUNT(*) > 1",
    "SELECT a FROM t ORDER BY a",
    "SELECT a FROM t ORDER BY a DESC, b ASC",
    "SELECT a FROM t ORDER BY a NULLS FIRST",
    "SELECT a FROM t LIMIT 10",
    "SELECT a FROM t LIMIT 10 OFFSET 5",
    "SELECT a FROM t JOIN u ON t.id = u.id",
    "SELECT a FROM t LEFT JOIN u ON t.id = u.id",
    "SELECT a FROM t LEFT OUTER JOIN u ON t.id = u.id",
    "SELECT a FROM t CROSS JOIN u",
    "SELECT a FROM t JOIN u USING (id)",
    "SELECT a FROM (SELECT b FROM u) AS sub",
    "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.id = t.id)",
    "WITH cte AS (SELECT a FROM t) SELECT * FROM cte",
    "WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a JOIN b ON TRUE",
    "SELECT a FROM t UNION SELECT b FROM u",
    "SELECT a FROM t UNION ALL SELECT b FROM u",
    "SELECT a FROM t INTERSECT SELECT b FROM u",
    "SELECT a FROM t EXCEPT SELECT b FROM u",
    "SELECT * FROM t WHERE x = ? AND y = :named AND z = @param",
    "SELECT CASE WHEN x = 1 THEN 'a' WHEN x = 2 THEN 'b' ELSE 'c' END FROM t",
    "SELECT a, ROW_NUMBER() OVER (PARTITION BY b ORDER BY c) FROM t",
]


def sqlglot_version() -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(SQLGLOT), "describe", "--tags"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cases = []
    for sql in QUERIES:
        expression = sqlglot.parse_one(sql)  # base dialect; raises on failure
        cases.append({"sql": sql, "dump": dump(expression)})

    corpus = {"sqlglot_version": sqlglot_version(), "cases": cases}
    out = OUT_DIR / "base.json"
    out.write_text(json.dumps(corpus, indent=1) + "\n")
    print(f"wrote {out} ({len(cases)} cases, sqlglot {corpus['sqlglot_version']})")


if __name__ == "__main__":
    main()
