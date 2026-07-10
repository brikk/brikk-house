#!/usr/bin/env python3
"""Generates a Python-oracle token corpus for brikk-sql differential testing.

For each dialect in DIALECTS, tokenizes every statement in
reference/sqlglot/tests/fixtures/identity.sql with the pinned Python sqlglot and
writes the resulting token streams as JSON to brikk-sql/testResources/token-corpus/.

The corpus is consumed by the JVM-only differential test
brikk-sql/test@jvm/dev.brikk.house.sql/TokenCorpusDifferentialTest.kt.

Run from anywhere:  python3 tools/gen_token_corpus.py
Re-run whenever reference/sqlglot is updated.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
FIXTURE = SQLGLOT / "tests" / "fixtures" / "identity.sql"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "token-corpus"

sys.path.insert(0, str(SQLGLOT))

from sqlglot.dialects.dialect import Dialect  # noqa: E402

DIALECTS = ["", "mysql", "doris", "presto", "trino", "duckdb", "postgres"]


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


README = """\
# token-corpus

Generated Python-oracle token corpus for the brikk-sql tokenizer differential test.

- Source SQL: `reference/sqlglot/tests/fixtures/identity.sql` from sqlglot's
  MIT-licensed test fixtures (copyright Toby Mao, see `reference/sqlglot/LICENSE`).
- sqlglot version pin: `{version}`.
- One JSON file per dialect; each case holds the exact token stream produced by
  `Dialect.get_or_raise(<dialect> or None).tokenize(sql)`.

Regenerate with:

```sh
python3 tools/gen_token_corpus.py
```
"""


def main() -> None:
    version = sqlglot_version()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    statements = [
        line
        for line in FIXTURE.read_text().splitlines()
        if line.strip() and not line.startswith("--")
    ]

    for name in DIALECTS:
        dialect = Dialect.get_or_raise(name or None)
        cases = []
        skipped = []
        for sql in statements:
            try:
                tokens = dialect.tokenize(sql)
            except Exception as e:  # noqa: BLE001 — record and continue
                skipped.append({"sql": sql, "error": str(e)})
                continue
            cases.append(
                {
                    "sql": sql,
                    "tokens": [
                        {
                            "type": tok.token_type.name,
                            "text": tok.text,
                            "line": tok.line,
                            "col": tok.col,
                            "start": tok.start,
                            "end": tok.end,
                            "comments": tok.comments,
                        }
                        for tok in tokens
                    ],
                }
            )

        doc = {
            "sqlglot_version": version,
            "dialect": name,
            "case_count": len(cases),
            "skipped": skipped,
            "cases": cases,
        }
        out = OUT_DIR / f"{name or 'base'}.json"
        out.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n")
        print(f"{out.name}: {len(cases)} cases, {len(skipped)} skipped (dialect={name or 'base'!r})")

    (OUT_DIR / "README.md").write_text(README.format(version=version))
    print(f"Wrote README.md (sqlglot {version})")


if __name__ == "__main__":
    main()
