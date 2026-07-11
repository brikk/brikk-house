#!/usr/bin/env python3
"""Generates a Python-oracle scope corpus for brikk-sql differential testing.

For every identity.sql line (plus handpicked nested/correlated/CTE/set-op/pivot queries
lifted from sqlglot's tests/test_optimizer.py test_scope), parse with the base dialect
and run sqlglot.optimizer.scope.traverse_scope. Queries that yield scopes emit a
per-scope summary in traversal order; queries that raise or yield no scopes are recorded
as skipped-with-reason.

The Kotlin twin in brikk-sql/test@jvm/dev.brikk.house.sql/ScopeCorpusTest.kt builds the
same summaries with our traverseScope and asserts a structural match, ledgering known
failures in brikk-sql/testResources/scope-corpus/known-failures.json.

Run from anywhere:  python3 tools/gen_scope_corpus.py
Re-run whenever reference/sqlglot is updated or cases are added.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "scope-corpus"

sys.path.insert(0, str(SQLGLOT))

import sqlglot  # noqa: E402
from sqlglot import exp  # noqa: E402
from sqlglot.optimizer.scope import Scope, traverse_scope  # noqa: E402

# Handpicked queries from tests/test_optimizer.py::test_scope (and close variants),
# exercising nested subqueries, correlation, CTEs, set operations, pivots and UDTFs.
HANDPICKED = [
    "SELECT a FROM (SELECT a FROM x) AS y",
    "SELECT a FROM (SELECT a FROM (SELECT a FROM x) AS y) AS z",
    "SELECT x FROM t UNION ALL SELECT x FROM t UNION ALL SELECT x FROM t",
    "SELECT x FROM t UNION SELECT y FROM u INTERSECT SELECT z FROM v",
    "(SELECT 1) UNION (SELECT 2)",
    """
    WITH q AS (
      SELECT x.b FROM x
    ), r AS (
      SELECT y.b FROM y
    ), z as (
      SELECT cola, colb FROM (VALUES(1, 'test')) AS tab(cola, colb)
    )
    SELECT
      r.b,
      s.b
    FROM r
    JOIN (
      SELECT y.c AS b FROM y
    ) s
    ON s.b = r.b
    WHERE s.b > (SELECT MAX(x.a) FROM x WHERE x.b = s.b)
    """,
    "SELECT * FROM (((SELECT * FROM (t1 JOIN t2) AS t3) JOIN (SELECT * FROM t4)))",
    "SELECT a FROM foo CROSS JOIN UNNEST((SELECT bar FROM baz))",
    "SELECT a FROM foo CROSS JOIN LATERAL (SELECT bar FROM baz)",
    "UPDATE customers SET total_spent = (SELECT 1 FROM t1) WHERE EXISTS (SELECT 1 FROM t2)",
    "UPDATE tbl1 SET col = 1 WHERE EXISTS (SELECT 1 FROM tbl2 WHERE tbl1.id = tbl2.id)",
    "UPDATE tbl1 SET col = 0",
    "SELECT * FROM t LEFT JOIN UNNEST(a) AS a1 LEFT JOIN UNNEST(a1.a) AS a2",
    "WITH x AS (SELECT 1 AS id) SELECT x.id, (SELECT MAX(x2.id) FROM x AS x2 WHERE x2.id = x.id) AS mx FROM x",
    "WITH x AS (SELECT 1 AS id), y AS (SELECT 2 AS id) SELECT (SELECT y.id FROM y WHERE y.id = x.id) FROM x",
    "WITH x AS (SELECT 1 AS id) SELECT (SELECT x.id FROM (SELECT * FROM x) AS sub) FROM x",
    "WITH q AS (@y) SELECT * FROM q",
    "WITH RECURSIVE t AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM t WHERE n < 10) SELECT n FROM t",
    "SELECT a FROM x WHERE a IN (SELECT b FROM y WHERE y.c = x.c)",
    "SELECT a FROM x WHERE EXISTS (SELECT 1 FROM y WHERE y.a = x.a AND y.b IN (SELECT b FROM z WHERE z.c = y.c))",
    "SELECT (SELECT (SELECT x.a FROM x) FROM y) FROM z",
    "SELECT * FROM x AS x1 JOIN x AS x2 ON x1.a = x2.a",
    "SELECT * FROM x LEFT SEMI JOIN y ON x.a = y.a",
    "SELECT * FROM x LEFT ANTI JOIN y ON x.a = y.a",
    "SELECT * FROM t PIVOT (SUM(val) FOR name IN ('a', 'b')) AS piv",
    "SELECT * FROM t UNPIVOT (val FOR name IN (a, b)) AS unpiv",
    "WITH c AS (SELECT 1 AS x) SELECT * FROM c AS a PIVOT (SUM(x) FOR y IN ('z'))",
    "SELECT * FROM t, LATERAL (SELECT a FROM u WHERE u.id = t.id) AS l",
    "SELECT t.a, l.b FROM t LATERAL VIEW EXPLODE(t.arr) l AS b",
    "SELECT a FROM tbl1, tbl2 WHERE tbl1.x = tbl2.y",
    "CREATE TABLE t1 AS SELECT a, b FROM t2",
    "CREATE VIEW v AS WITH c AS (SELECT 1 AS x) SELECT x FROM c",
    "INSERT INTO t1 SELECT a FROM t2 WHERE a > 1",
    "INSERT INTO t1 WITH c AS (SELECT a FROM t2) SELECT a FROM c",
    "DELETE FROM t1 WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t1.id)",
    "MERGE INTO a USING (SELECT id FROM b) AS s ON a.id = s.id WHEN MATCHED THEN UPDATE SET a.x = s.id",
    "SELECT a, SUM(b) FROM t GROUP BY a HAVING SUM(b) > (SELECT AVG(b) FROM t)",
    "SELECT a FROM t ORDER BY (SELECT MAX(b) FROM u WHERE u.id = t.id)",
    "SELECT a AS b FROM t ORDER BY b",
    "SELECT a AS b FROM t ORDER BY c",
    "SELECT DISTINCT a FROM t ORDER BY a",
    "SELECT COUNT(*) FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) = 1",
    "SELECT * FROM (SELECT * FROM (t1 JOIN t2) AS t3) WHERE t3.a = 1",
    "SELECT (SELECT y.id FROM y) FROM x UNION SELECT a FROM z",
]


def summarize_scope(scope: Scope) -> dict:
    return {
        "expression_class": type(scope.expression).__name__,
        "scope_type": scope.scope_type.name,
        "sources": [
            [name, "table" if isinstance(source, exp.Table) else "scope"]
            for name, source in scope.sources.items()
        ],
        "selected_source_names": list(scope.selected_sources),
        "column_names": [
            f"{c.text('table')}.{c.name}" if c.text("table") else c.name
            for c in scope.columns
        ],
        "external_column_names": [
            f"{c.text('table')}.{c.name}" if c.text("table") else c.name
            for c in scope.external_columns
        ],
        "unqualified_column_count": len(scope.unqualified_columns),
        "subquery_count": len(scope.subquery_scopes),
        "cte_count": len(scope.cte_scopes),
        "derived_count": len(scope.derived_table_scopes),
        "udtf_count": len(scope.udtf_scopes),
        "is_correlated_subquery": scope.is_correlated_subquery,
    }


def build_case(sql: str) -> dict:
    try:
        ast = sqlglot.parse_one(sql)
    except Exception as e:
        return {"sql": sql, "skipped": f"parse error: {type(e).__name__}"}

    try:
        scopes = traverse_scope(ast)
        if not scopes:
            return {"sql": sql, "skipped": "no scopes"}
        return {"sql": sql, "scopes": [summarize_scope(s) for s in scopes]}
    except Exception as e:
        return {"sql": sql, "error": type(e).__name__}


def main() -> None:
    queries: list[str] = []
    seen: set[str] = set()

    identity = (SQLGLOT / "tests" / "fixtures" / "identity.sql").read_text().splitlines()
    for line in identity:
        sql = line.strip()
        if sql and sql not in seen:
            seen.add(sql)
            queries.append(sql)

    for sql in HANDPICKED:
        sql = "\n".join(l.rstrip() for l in sql.strip("\n").rstrip().splitlines())
        if sql not in seen:
            seen.add(sql)
            queries.append(sql)

    version = subprocess.run(
        ["git", "describe", "--tags", "--always"],
        cwd=SQLGLOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()

    cases = [build_case(sql) for sql in queries]
    n_scoped = sum(1 for c in cases if "scopes" in c)
    n_skipped = sum(1 for c in cases if "skipped" in c)
    n_error = sum(1 for c in cases if "error" in c)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "base.json"
    out.write_text(
        json.dumps({"sqlglot_version": version, "cases": cases}, indent=1) + "\n"
    )
    print(f"wrote {out}: {len(cases)} cases ({n_scoped} scoped, {n_skipped} skipped, {n_error} errors)")


if __name__ == "__main__":
    main()
