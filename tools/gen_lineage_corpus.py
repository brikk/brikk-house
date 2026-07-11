#!/usr/bin/env python3
"""Generate testResources/lineage-corpus/base.json.

Extracts lineage() calls from reference/sqlglot/tests/test_lineage.py (the calls are
stereotyped: lineage("col", "sql", schema={...}, sources={...}, dialect=...)) via ast
parsing, resolving simple in-function variable assignments. Cases whose dialect is not
one of brikk-sql's 8 supported dialects are recorded as skipped-with-reason. A curated
EXTRA_CASES list adds coverage for categories the Python suite only exercises through
unsupported dialects (pivots via duckdb, etc.).

For each runnable case the Python oracle's node graph is serialized deterministically:
{name, expression_sql, source_sql, source_name, reference_node_name, downstream: [...]}
with downstream children sorted by their canonical JSON dump (Python iterates a set of
Column expressions whose order is hash-dependent, so child order is not comparable).
"""

import ast
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "reference", "sqlglot"))

from sqlglot.lineage import lineage  # noqa: E402

VERSION = "v30.12.0-44-g93d16591"
SUPPORTED_DIALECTS = {"", "mysql", "doris", "presto", "trino", "duckdb", "postgres", "clickhouse"}
TEST_FILE = os.path.join(
    os.path.dirname(__file__), "..", "reference", "sqlglot", "tests", "test_lineage.py"
)
OUT_FILE = os.path.join(
    os.path.dirname(__file__), "..", "brikk-sql", "testResources", "lineage-corpus", "base.json"
)

# Curated additions: categories the extracted suite covers only via unsupported
# dialects, exercised here through base/duckdb SQL. (column, sql, schema, sources,
# dialect, trim_selects); column=None means all-columns mode.
EXTRA_CASES = [
    ("a", "SELECT t.a FROM (SELECT x.a FROM x AS x) AS t", None, None, "", True),
    ("b", "SELECT a + 1 AS b FROM x", {"x": {"a": "int"}}, None, "", True),
    (
        "c",
        "WITH cte1 AS (SELECT a AS b FROM x), cte2 AS (SELECT b AS c FROM cte1) SELECT c FROM cte2",
        {"x": {"a": "int"}},
        None,
        "",
        True,
    ),
    (
        "a",
        "SELECT a FROM (SELECT a FROM (SELECT a FROM x) AS inner1) AS inner2",
        {"x": {"a": "int"}},
        None,
        "",
        True,
    ),
    ("a", "SELECT a FROM x UNION ALL SELECT b AS a FROM y", {"x": {"a": "int"}, "y": {"b": "int"}}, None, "", True),
    ("a", "SELECT x.a, y.b FROM x CROSS JOIN y", {"x": {"a": "int"}, "y": {"b": "int"}}, None, "", False),
    ("a", "SELECT * FROM x", {"x": {"a": "int", "b": "int"}}, None, "", True),
    ("a", "SELECT a FROM unknown_table", None, None, "", True),
    ("a", "SELECT a FROM z", {"x": {"a": "int"}}, {"z": "SELECT a FROM x"}, "", True),
    (
        "a",
        "SELECT a FROM z",
        {"x": {"a": "int"}},
        {"z": "SELECT a FROM y", "y": "SELECT a FROM x"},
        "",
        True,
    ),
    ("a", "SELECT a FROM x WHERE b > (SELECT MAX(c) FROM y)", {"x": {"a": "int", "b": "int"}, "y": {"c": "int"}}, None, "", True),
    ("a", "SELECT a FROM x GROUP BY a HAVING SUM(b) > 0", {"x": {"a": "int", "b": "int"}}, None, "", True),
    (None, "SELECT a, b + 1 AS c FROM x", {"x": {"a": "int", "b": "int"}}, None, "", True),
    (None, "SELECT a FROM x UNION SELECT a FROM y", {"x": {"a": "int"}, "y": {"a": "int"}}, None, "", True),
    ("a", "SELECT a FROM x ORDER BY b LIMIT 5", {"x": {"a": "int", "b": "int"}}, None, "duckdb", True),
    (
        "cat_a_value_sum",
        "SELECT * FROM (SELECT category, value FROM sales) PIVOT (SUM(value) AS value_sum FOR category IN ('a' AS cat_a, 'b' AS cat_b))",
        {"sales": {"category": "text", "value": "int"}},
        None,
        "duckdb",
        True,
    ),
]


def resolve(node, env):
    """Resolve an ast node to a python literal, following simple Name assignments."""
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        if node.id in env:
            return env[node.id]
        raise ValueError(f"unresolvable name: {node.id}")
    if isinstance(node, (ast.Dict, ast.List, ast.Tuple)):
        return ast.literal_eval(node)
    raise ValueError(f"non-literal arg: {ast.dump(node)[:80]}")


def extract_calls():
    """Yield (test_name, column, sql, kwargs, reason_if_skipped)."""
    with open(TEST_FILE) as f:
        tree = ast.parse(f.read())

    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef))
    for fn in cls.body:
        if not isinstance(fn, ast.FunctionDef) or not fn.name.startswith("test_"):
            continue
        env = {}
        idx = 0
        for stmt in ast.walk(fn):
            if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1 and isinstance(
                stmt.targets[0], ast.Name
            ):
                try:
                    env[stmt.targets[0].id] = ast.literal_eval(stmt.value)
                except (ValueError, TypeError, SyntaxError):
                    pass
        for call in ast.walk(fn):
            if not (
                isinstance(call, ast.Call)
                and isinstance(call.func, ast.Name)
                and call.func.id == "lineage"
            ):
                continue
            idx += 1
            case_id = f"{fn.name}#{idx}"
            try:
                if len(call.args) < 2:
                    raise ValueError("fewer than 2 positional args")
                column = resolve(call.args[0], env)
                sql = resolve(call.args[1], env)
                if not isinstance(sql, str):
                    raise ValueError("sql arg is not a string literal")
                kwargs = {}
                for kw in call.keywords:
                    if kw.arg is None:
                        raise ValueError("**kwargs call")
                    kwargs[kw.arg] = resolve(kw.value, env)
                yield case_id, column, sql, kwargs, None
            except ValueError as e:
                yield case_id, None, None, None, str(e)


def serialize(node, dialect):
    children = [serialize(d, dialect) for d in node.downstream]
    children.sort(key=lambda d: json.dumps(d, sort_keys=True))
    return {
        "name": node.name,
        "expression": node.expression.sql(dialect=dialect),
        "source": node.source.sql(dialect=dialect),
        "source_name": node.source_name,
        "reference_node_name": node.reference_node_name,
        "downstream": children,
    }


def run_case(case_id, column, sql, schema, sources, dialect, trim_selects):
    case = {
        "id": case_id,
        "column": column,
        "sql": sql,
        "dialect": dialect,
        "trim_selects": trim_selects,
    }
    if schema is not None:
        case["schema"] = schema
    if sources is not None:
        case["sources"] = sources
    try:
        result = lineage(
            column,
            sql,
            schema=schema,
            sources=sources,
            dialect=dialect or None,
            trim_selects=trim_selects,
        )
    except Exception as e:  # noqa: BLE001
        case["error"] = type(e).__name__
        case["error_message"] = str(e)
        return case
    if column is None:
        case["expected_columns"] = {k: serialize(v, dialect or None) for k, v in result.items()}
    else:
        case["expected"] = serialize(result, dialect or None)
    return case


def main():
    cases = []
    skipped = []
    seen = set()

    extracted = list(extract_calls())
    for case_id, column, sql, kwargs, reason in extracted:
        if reason is not None or kwargs is None:
            skipped.append({"id": case_id, "reason": reason or "unresolvable"})
            continue
        unsupported_kw = set(kwargs) - {"schema", "sources", "dialect", "trim_selects"}
        if unsupported_kw:
            skipped.append({"id": case_id, "reason": f"unsupported kwargs: {sorted(unsupported_kw)}"})
            continue
        dialect = kwargs.get("dialect") or ""
        if dialect not in SUPPORTED_DIALECTS:
            skipped.append({"id": case_id, "reason": f"unsupported dialect: {dialect}"})
            continue
        schema = kwargs.get("schema")
        sources = kwargs.get("sources")
        trim_selects = kwargs.get("trim_selects", True)
        key = json.dumps(
            [column, sql, schema, sources, dialect, trim_selects], sort_keys=True
        )
        if key in seen:
            skipped.append({"id": case_id, "reason": "duplicate of earlier case"})
            continue
        seen.add(key)
        cases.append(run_case(case_id, column, sql, schema, sources, dialect, trim_selects))

    for i, (column, sql, schema, sources, dialect, trim_selects) in enumerate(EXTRA_CASES, 1):
        key = json.dumps([column, sql, schema, sources, dialect, trim_selects], sort_keys=True)
        if key in seen:
            continue
        seen.add(key)
        cases.append(run_case(f"extra#{i}", column, sql, schema, sources, dialect, trim_selects))

    out = {
        "version": VERSION,
        "generator": "tools/gen_lineage_corpus.py",
        "cases": cases,
        "skipped": skipped,
    }
    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "w") as f:
        json.dump(out, f, indent=2, sort_keys=False)
        f.write("\n")
    errors = sum(1 for c in cases if "error" in c)
    print(f"cases: {len(cases)} ({errors} error-expecting), skipped: {len(skipped)}")
    for s in skipped:
        print(f"  skipped {s['id']}: {s['reason']}")


if __name__ == "__main__":
    main()
