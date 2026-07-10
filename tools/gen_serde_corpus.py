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
"""

from __future__ import annotations

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


def main() -> None:
    cases = []
    skipped = []
    for line in FIXTURE.read_text().splitlines():
        sql = line.strip()
        if not sql or sql.startswith("#") or sql.startswith("--"):
            continue
        try:
            expression = sqlglot.parse_one(sql)
            cases.append({"sql": sql, "dump": serde.dump(expression)})
        except Exception as e:  # noqa: BLE001 — record and move on
            skipped.append({"sql": sql, "error": f"{type(e).__name__}: {e}"})

    corpus = {
        "sqlglot_version": sqlglot_version(),
        "fixture": "tests/fixtures/identity.sql",
        "case_count": len(cases),
        "skipped": skipped,
        "cases": cases,
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(corpus, indent=1) + "\n"
    size = len(raw.encode())
    if size > 25 * 1024 * 1024:
        import gzip

        out = OUT_DIR / "identity-serde.json.gz"
        (OUT_DIR / "identity-serde.json").unlink(missing_ok=True)
        out.write_bytes(gzip.compress(raw.encode()))
        print(f"Wrote {out} (raw {size / 1e6:.1f}MB > 25MB, gzipped to {out.stat().st_size / 1e6:.1f}MB)")
        print("NOTE: JVM test must read via java.util.zip.GZIPInputStream")
    else:
        out = OUT_DIR / "identity-serde.json"
        (OUT_DIR / "identity-serde.json.gz").unlink(missing_ok=True)
        out.write_text(raw)
        print(f"Wrote {out} ({size / 1e6:.1f}MB)")
    print(f"cases: {len(cases)}, skipped: {len(skipped)}")
    for s in skipped:
        print("  SKIPPED:", s["sql"][:80], "--", s["error"][:80])


if __name__ == "__main__":
    main()
