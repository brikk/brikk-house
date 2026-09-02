#!/usr/bin/env python3
"""Import polyglot's DataFusion fixture JSONs into brikk-sql's dialect-corpus.

DataFusion has NO sqlglot dialect, so the usual `tools/extract_dialect_tests.py`
Python-oracle recipe is closed for it. Instead we borrow the hand-authored fixture
corpus from the polyglot project (a Rust sqlglot-alike), whose standalone DataFusion
dialect (crates/polyglot-sql/src/dialects/datafusion.rs) is the design reference for
brikk's DatafusionDialect.

Source (vendored under reference/polyglot, MIT License — Copyright (c) 2026 TobiLG):
    reference/polyglot/crates/polyglot-sql/tests/custom_fixtures/datafusion/*.json

Each source file has schema:
    {"dialect": "datafusion", "category": "...",
     "identity": [{"sql": "...", "description": "..."}],
     "transpilation": [{"sql": "<datafusion form>",
                        "write": {dialect: expected, ...},
                        "read":  {dialect: source, ...},   # optional
                        "description": "..."}]}

We emit brikk-sql/testResources/dialect-corpus/datafusion-fixtures.json:
    {
      "provenance": {...},
      "identity":  [{"sql": "...", "category": "...", "description": "..."}],
      "transpile": [{"sql": "<datafusion canonical>", "read": "<dialect>",
                     "read_sql": "...", "category": "...", "description": "..."}]
    }

Identity semantics: parse `sql` under "datafusion", regenerate under "datafusion",
result must equal `sql` (polyglot fixtures are all pure identities — no `expected`).

Transpile semantics (only the `read` sub-entries are portable to brikk, since they
describe another dialect's SQL that should render to the datafusion canonical form):
parse `read_sql` under `read`, generate under "datafusion", expect `sql`. Directions
whose read-dialect is unregistered in brikk are still emitted (the test skips them).
"""

import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC_DIR = os.path.join(
    ROOT,
    "reference/polyglot/crates/polyglot-sql/tests/custom_fixtures/datafusion",
)
OUT = os.path.join(
    ROOT, "brikk-sql/testResources/dialect-corpus/datafusion-fixtures.json"
)

# In dependency order roughly matches how polyglot ships them; category comes from
# the file's own "category" field.
FILES = ["identity", "select", "operators", "types", "functions", "ddl", "dml", "transpilation"]


def polyglot_commit():
    try:
        return subprocess.check_output(
            ["git", "-C", os.path.join(ROOT, "reference/polyglot"), "rev-parse", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def main():
    identity = []
    transpile = []
    seen_identity = set()

    for name in FILES:
        path = os.path.join(SRC_DIR, name + ".json")
        with open(path) as fh:
            data = json.load(fh)
        category = data.get("category", name)

        for case in data.get("identity", []):
            sql = case["sql"]
            if sql in seen_identity:
                continue
            seen_identity.add(sql)
            identity.append(
                {
                    "sql": sql,
                    "category": category,
                    "description": case.get("description", ""),
                }
            )

        for case in data.get("transpilation", []):
            sql = case["sql"]
            for read_dialect, read_sql in (case.get("read") or {}).items():
                transpile.append(
                    {
                        "sql": sql,
                        "read": read_dialect,
                        "read_sql": read_sql,
                        "category": category,
                        "description": case.get("description", ""),
                    }
                )

    out = {
        "provenance": {
            "source": "polyglot (reference/polyglot) custom_fixtures/datafusion/*.json",
            "source_repo": "https://github.com/tobilg/polyglot",
            "commit": polyglot_commit(),
            "license": "MIT (Copyright (c) 2026 TobiLG) — see ATTRIBUTIONS.md",
            "note": (
                "DataFusion has no sqlglot dialect; this corpus is hand-authored by "
                "polyglot, not machine-derived from a Python oracle. Identity cases "
                "assert datafusion parse->generate round-trips; transpile cases assert "
                "another dialect's SQL renders to the datafusion canonical form."
            ),
        },
        "identity": identity,
        "transpile": transpile,
        "stats": {
            "identity_count": len(identity),
            "transpile_count": len(transpile),
        },
    }

    with open(OUT, "w") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")

    print(f"wrote {OUT}")
    print(f"  identity: {len(identity)}  transpile: {len(transpile)}")


if __name__ == "__main__":
    main()
