#!/usr/bin/env python3
"""Generates the Python-oracle qualify corpora for brikk-sql differential testing.

Replicates tests/helpers.py load_sql_fixture_pairs (naive `;` split, `#meta` headers)
over sqlglot's own optimizer fixtures and the exact kwargs the corresponding
tests/test_optimizer.py functions pass:

  - qualify_columns.sql / qualify_columns__with_invisible.sql
        qualify(expr, infer_schema=True, identify=False, schema=..., dialect=<meta>,
                validate_qualify_columns=<meta, default True>)   [test_qualify_columns]
  - qualify_tables.sql
        qualify_tables(expr, db="db", catalog="c",
                       canonicalize_table_aliases=<meta>)        [test_qualify_tables]
  - normalize_identifiers.sql
        normalize_identifiers(expr, dialect=<meta>)              [test_normalize_identifiers]
  - qualify_columns__invalid.sql (line fixtures)
        qualify_columns(expr, schema=...) + validate_qualify_columns
        must raise (OptimizeError, SchemaError)                  [test_qualify_columns__invalid]

For every pair case the Python function is RUN and its output compared with the
fixture's expected text; mismatches are recorded as skipped-with-reason (there should
be ~none). Cases whose meta dialect is not one of brikk-sql's 8 supported dialects are
emitted with "dialect_supported": false so the Kotlin gate can skip them explicitly.

The Kotlin twin is brikk-sql/test@jvm/dev.brikk.house.sql/QualifyCorpusTest.kt.

Run from anywhere:  python3 tools/gen_qualify_corpus.py
Re-run whenever reference/sqlglot is updated or fixtures change.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
FIXTURES = SQLGLOT / "tests" / "fixtures" / "optimizer"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "qualify-corpus"

sys.path.insert(0, str(SQLGLOT))

from sqlglot import parse_one  # noqa: E402
from sqlglot.errors import OptimizeError, SchemaError  # noqa: E402
from sqlglot.optimizer import qualify as qualify_mod  # noqa: E402
from sqlglot.optimizer import qualify_columns as qualify_columns_mod  # noqa: E402
from sqlglot.optimizer import qualify_tables as qualify_tables_mod  # noqa: E402
from sqlglot.optimizer import normalize_identifiers as normalize_identifiers_mod  # noqa: E402
from sqlglot.schema import MappingSchema  # noqa: E402

SUPPORTED_DIALECTS = {"", "mysql", "doris", "presto", "trino", "duckdb", "postgres", "clickhouse"}

# tests/test_optimizer.py TestOptimizer.setUp
SCHEMA = {
    "x": {"a": "INT", "b": "INT"},
    "y": {"b": "INT", "c": "INT"},
    "z": {"b": "INT", "c": "INT"},
    "w": {"d": "TEXT", "e": "TEXT"},
    "temporal": {"d": "DATE", "t": "DATETIME"},
    "structs": {
        "one": "STRUCT<a_1 INT, b_1 VARCHAR>",
        "nested_0": "STRUCT<a_1 INT, nested_1 STRUCT<a_2 INT, nested_2 STRUCT<a_3 INT>>>",
        "quoted": 'STRUCT<"foo bar" INT>',
    },
    "t_bool": {"a": "BOOLEAN"},
}

# tests/test_optimizer.py test_qualify_columns__with_invisible
VISIBLE = {"x": ["a"], "y": ["b"], "z": ["b"]}


def sqlglot_version() -> str:
    return subprocess.check_output(
        ["git", "describe", "--tags", "--always"], cwd=SQLGLOT, text=True
    ).strip()


# --- tests/helpers.py -------------------------------------------------------------
def _filter_comments(s: str) -> str:
    return "\n".join(line for line in s.splitlines() if line and not line.startswith("--"))


def _extract_meta(sql: str):
    meta = {}
    sql_lines = sql.split("\n")
    i = 0
    while sql_lines[i].startswith("#"):
        key, val = sql_lines[i].split(":", maxsplit=1)
        meta[key.lstrip("#").strip()] = val.strip()
        i += 1
    return "\n".join(sql_lines[i:]), meta


def load_sql_fixture_pairs(path: pathlib.Path):
    statements = _filter_comments(path.read_text(encoding="utf-8")).split(";")
    size = len(statements)
    for i in range(0, size, 2):
        if i + 1 < size:
            sql = statements[i].strip()
            sql, meta = _extract_meta(sql)
            expected = statements[i + 1].strip()
            yield meta, sql, expected


def load_sql_fixtures(path: pathlib.Path):
    yield from _filter_comments(path.read_text(encoding="utf-8")).splitlines()


def string_to_bool(string):
    if string is None:
        return False
    if string in (True, False):
        return string
    return string and string.lower() in ("true", "1")


# --- oracle runners ---------------------------------------------------------------
def run_qualify_columns(sql, dialect, flags, schema):
    expression = parse_one(sql, read=dialect)
    expression = qualify_mod.qualify(
        expression,
        infer_schema=True,
        validate_qualify_columns=flags.get("validate_qualify_columns", True),
        identify=False,
        schema=schema,
        **({"dialect": dialect} if dialect else {}),
    )
    return expression.sql(dialect=dialect)


def run_qualify_tables(sql, dialect, flags, schema):
    expression = parse_one(sql, read=dialect)
    kwargs = {"db": "db", "catalog": "c"}
    if "canonicalize_table_aliases" in flags:
        kwargs["canonicalize_table_aliases"] = flags["canonicalize_table_aliases"]
    if dialect:
        kwargs["dialect"] = dialect
    expression = qualify_tables_mod.qualify_tables(expression, **kwargs)
    return expression.sql(dialect=dialect)


def run_normalize_identifiers(sql, dialect, flags, schema):
    expression = parse_one(sql, read=dialect)
    kwargs = {"dialect": dialect} if dialect else {}
    expression = normalize_identifiers_mod.normalize_identifiers(expression, **kwargs)
    return expression.sql(dialect=dialect)


def build_pair_corpus(fixture_name, runner, schema=None, visible=None):
    cases = []
    for meta, sql, expected in load_sql_fixture_pairs(FIXTURES / f"{fixture_name}.sql"):
        dialect = meta.get("dialect")
        flags = {}
        if meta.get("validate_qualify_columns") is not None:
            flags["validate_qualify_columns"] = string_to_bool(meta["validate_qualify_columns"])
        if meta.get("canonicalize_table_aliases") is not None:
            flags["canonicalize_table_aliases"] = string_to_bool(
                meta["canonicalize_table_aliases"]
            )

        case = {"sql": sql, "expected": expected}
        if meta.get("title"):
            case["title"] = meta["title"]
        if dialect:
            case["dialect"] = dialect
        if flags:
            case["flags"] = flags

        base_dialect = (dialect or "").split(",")[0].strip().lower()
        if base_dialect not in SUPPORTED_DIALECTS:
            case["dialect_supported"] = False

        oracle_schema = (
            MappingSchema(schema, {k: set(v) for k, v in visible.items()})
            if visible is not None
            else schema
        )
        try:
            actual = runner(sql, dialect, flags, oracle_schema)
        except Exception as e:  # noqa: BLE001
            case["skipped"] = f"oracle raised {type(e).__name__}: {e}"
            cases.append(case)
            continue
        if actual != expected:
            case["skipped"] = f"oracle output differs from fixture: {actual}"
        cases.append(case)

    return cases


def build_invalid_corpus():
    cases = []
    for sql in load_sql_fixtures(FIXTURES / "qualify_columns__invalid.sql"):
        case = {"sql": sql, "raises": True}
        try:
            expression = qualify_columns_mod.qualify_columns(parse_one(sql), schema=SCHEMA)
            qualify_columns_mod.validate_qualify_columns(expression)
        except (OptimizeError, SchemaError):
            pass
        except Exception as e:  # noqa: BLE001
            case["skipped"] = f"oracle raised unexpected {type(e).__name__}: {e}"
        else:
            case["skipped"] = "oracle did not raise"
        cases.append(case)
    return cases


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    version = sqlglot_version()

    corpora = {
        "qualify_columns": {
            "fixture": "qualify_columns",
            "schema": SCHEMA,
            "cases": build_pair_corpus("qualify_columns", run_qualify_columns, schema=SCHEMA),
        },
        "qualify_columns__with_invisible": {
            "fixture": "qualify_columns__with_invisible",
            "schema": SCHEMA,
            "visible": VISIBLE,
            "cases": build_pair_corpus(
                "qualify_columns__with_invisible",
                run_qualify_columns,
                schema=SCHEMA,
                visible=VISIBLE,
            ),
        },
        "qualify_tables": {
            "fixture": "qualify_tables",
            "cases": build_pair_corpus("qualify_tables", run_qualify_tables),
        },
        "normalize_identifiers": {
            "fixture": "normalize_identifiers",
            "cases": build_pair_corpus("normalize_identifiers", run_normalize_identifiers),
        },
        "qualify_columns__invalid": {
            "fixture": "qualify_columns__invalid",
            "schema": SCHEMA,
            "cases": build_invalid_corpus(),
        },
    }

    for name, corpus in corpora.items():
        corpus = {"sqlglot_version": version, **corpus}
        out = OUT_DIR / f"{name}.json"
        out.write_text(json.dumps(corpus, indent=1) + "\n", encoding="utf-8")
        counts = {
            "total": len(corpus["cases"]),
            "skipped": sum(1 for c in corpus["cases"] if "skipped" in c),
            "unsupported_dialect": sum(
                1 for c in corpus["cases"] if c.get("dialect_supported") is False
            ),
        }
        print(f"{out.relative_to(ROOT)}: {counts}")


if __name__ == "__main__":
    main()
