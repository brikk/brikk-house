#!/usr/bin/env python3
"""Generates the serde round-trip gate corpus for brikk-sql.

For every non-blank, non-comment line of reference/sqlglot/tests/fixtures/identity.sql:
parse with the base sqlglot dialect, dump via sqlglot.serde.dump, and record
{"sql": ..., "dump": [...]}. The Kotlin gate (SerdeIdentityCorpusTest) loads each
dump, re-dumps it, and requires structural equality with the original payloads —
the same round-trip sqlglot's own tests/test_serde.py performs.

Output: brikk-sql/testResources/ast-corpus/identity-serde.json
        (gzipped as identity-serde.json.gz instead if the raw file exceeds 25MB)

Run from anywhere:  python3 tools/gen_serde_corpus.py

Dialect mode (--dialect mysql): reads the identity SQLs from
brikk-sql/testResources/dialect-corpus/<dialect>.json, Python-parses each with
read=<dialect>, and writes brikk-sql/testResources/ast-corpus/<dialect>-serde.json
with {"sql", "generated" (= .sql(dialect=<dialect>)), "dump"}.

Annotate mode (--annotate [--dialect d]): parse_one(sql, read=d) ->
annotate_types(ast, dialect=d) (schema-less) -> serde dump (types included as `t`
payloads), written to ast-corpus/identity-annotated-serde.json (base) or
ast-corpus/<dialect>-annotated-serde.json. Python-side parse/annotate failures are
recorded under "skipped" with their reason.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "ast-corpus"
FIXTURE = SQLGLOT / "tests" / "fixtures" / "identity.sql"

sys.path.insert(0, str(SQLGLOT))

import sqlglot  # noqa: E402
from sqlglot import serde  # noqa: E402


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


def iter_sqls(dialect: str | None):
    if dialect is None:
        for line in FIXTURE.read_text().splitlines():
            sql = line.strip()
            if not sql or sql.startswith("#") or sql.startswith("--"):
                continue
            yield sql
    else:
        corpus_file = ROOT / "brikk-sql" / "testResources" / "dialect-corpus" / f"{dialect}.json"
        corpus = json.loads(corpus_file.read_text())
        for case in corpus["identity"]:
            yield case["sql"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dialect", default=None, help="dialect-corpus name (e.g. mysql)")
    parser.add_argument(
        "--annotate",
        action="store_true",
        help="run annotate_types(ast, dialect=<dialect>) before dumping",
    )
    opts = parser.parse_args()
    dialect = opts.dialect

    if opts.annotate:
        from sqlglot.optimizer.annotate_types import annotate_types

    cases = []
    skipped = []
    for sql in iter_sqls(dialect):
        try:
            expression = sqlglot.parse_one(sql, read=dialect or None)
            if opts.annotate:
                expression = annotate_types(expression, dialect=dialect or None)
            case = {
                "sql": sql,
                "generated": expression.sql(dialect=dialect or None),
                "dump": serde.dump(expression),
            }
            # Some ASTs (e.g. Doris PARTITION ... VALUES [...) nested lists) defeat
            # sqlglot's own serde; skip them rather than crash the whole corpus.
            json.dumps(case)
            cases.append(case)
        except Exception as e:  # noqa: BLE001 — record and move on
            skipped.append({"sql": sql, "error": f"{type(e).__name__}: {e}"})

    corpus = {
        "sqlglot_version": sqlglot_version(),
        "fixture": f"dialect-corpus/{dialect}.json" if dialect else "tests/fixtures/identity.sql",
        "dialect": dialect or "",
        "case_count": len(cases),
        "skipped": skipped,
        "cases": cases,
    }

    if opts.annotate:
        stem = f"{dialect}-annotated-serde" if dialect else "identity-annotated-serde"
    else:
        stem = f"{dialect}-serde" if dialect else "identity-serde"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(corpus, indent=1) + "\n"
    size = len(raw.encode())
    if size > 25 * 1024 * 1024:
        import gzip

        out = OUT_DIR / f"{stem}.json.gz"
        (OUT_DIR / f"{stem}.json").unlink(missing_ok=True)
        out.write_bytes(gzip.compress(raw.encode()))
        print(f"Wrote {out} (raw {size / 1e6:.1f}MB > 25MB, gzipped to {out.stat().st_size / 1e6:.1f}MB)")
        print("NOTE: JVM test must read via java.util.zip.GZIPInputStream")
    else:
        out = OUT_DIR / f"{stem}.json"
        (OUT_DIR / f"{stem}.json.gz").unlink(missing_ok=True)
        out.write_text(raw)
        print(f"Wrote {out} ({size / 1e6:.1f}MB)")
    print(f"cases: {len(cases)}, skipped: {len(skipped)}")
    for s in skipped:
        print("  SKIPPED:", s["sql"][:80], "--", s["error"][:80])


if __name__ == "__main__":
    main()
