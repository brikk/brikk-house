# TODO - native generation differences (in-scope)

> **Context for an agent picking this up fresh.**
> `brikk-sql` is a hand-written **Kotlin port of the Python library `sqlglot`** (SQL
> parser / transpiler / optimizer). It is pinned to upstream **`v30.17.0-72-gbac1a897b`**;
> a read-only reference clone of that exact version lives at **`reference/sqlglot/`** — treat
> its behaviour as the oracle for every item here.
>
> **In-scope dialects** (the only ones that matter): base (`""`), mysql, doris, presto, trino,
> duckdb, postgres, clickhouse, hive, spark2, spark, bigquery, plus brikk-native datafusion.
> Non-ported dialects (snowflake, tsql, oracle, sqlite, redshift, databricks, starrocks, …)
> are **out of scope** and already skipped by the gates — never touch them.
>
> **Every actionable item below is IN SCOPE**: it involves only ported dialects and is a real
> divergence from the reference that should still be closed toward sqlglot. (~94% of ~16.4k
> corpus cases match the reference exactly; these are part of the remaining tail.)
>
> **Intentional divergences are not TODOs (durable policy).** Verifier-backed intentional
> divergences registered in [`docs/brikk-extensions.md`](docs/brikk-extensions.md) must
> **never** be copied into this file or any other actionable TODO/problem inventory, and
> must **never** be "fixed" toward SQLGlot, unless that intentional policy is explicitly
> reversed in the extensions registry. Their parity ledger entries are **expected and
> protected** — do not delete them from `*-known-failures.json` as if they were bugs.
>
> **How verification works.** Behaviour is pinned by "corpus gates": generated fixtures under
> `brikk-sql/testResources/**` capture the reference's output, and each gate enforces an *exact*
> known-failures ledger (`*-known-failures.json`). Build/test from the repo root with
> `./kotlin build` and `./kotlin test`. Each run also writes `brikk-sql/*-ledger-actual.json`
> (gitignored) with the current failing set. **When you fix an actionable item, delete its line
> from the matching committed `*-known-failures.json`** (or copy the refreshed actual over it);
> the gate then proves it passes. Do **not** delete protected intentional-divergence entries.
> Reproduce a single item by parsing/generating with the noted dialect(s)
> and diffing against `reference/sqlglot` (e.g. `python3 -c "import sqlglot; print(sqlglot.transpile(SQL, read=SRC, write=TGT)[0])"`).
>
> **Finding the code.** Port files carry `// sqlglot: <symbol>` provenance comments pointing at
> the Python source — grep them. Parser: `brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt`
> + `dialects/<D>Parser.kt`. Generator: `generator/Generator.kt` + `generator/GeneratorTables.kt`
> + `dialects/<D>Generator.kt`. Type inference: `optimizer/AnnotateTypes.kt` + `ast/TypingSpec.kt`
> + `tools/gen_typing_metadata.py`.
>
> **This file = native round-trip generation diffs.** Parse in dialect D, generate back in D,
> and the string differs — formatting, escape sequences, time-format specifiers, quoting, etc.
> Fix = the reference `<node>_sql` in `generator.py` / `generators/<D>.py`.
---


**0 actionable items.**

The previous 64-item inventory is resolved: 59 SQLGlot parity gaps now pass their
generator gates, and the remaining 4 Doris + 1 Trino ledger entries are protected
intentional differences described below.

## Excluded: intentional differences (do not "fix" toward SQLGlot)

These verifier-backed divergences stay in the generator parity ledgers on purpose.
They are registered in [`docs/brikk-extensions.md`](docs/brikk-extensions.md). Do not
copy them back into the actionable inventory above.

- **4 Doris** in [`brikk-sql/testResources/generator-corpus/doris-generator-known-failures.json`](brikk-sql/testResources/generator-corpus/doris-generator-known-failures.json):
  - 3× `CREATE TABLE ... PARTITION BY (...)` clause completion — [`docs/brikk-extensions.md`](docs/brikk-extensions.md) §9
  - 1× `CREATE MATERIALIZED VIEW ...` typed column list stripped to bare names — [`docs/brikk-extensions.md`](docs/brikk-extensions.md) §10
- **1 Trino** in [`brikk-sql/testResources/generator-corpus/trino-generator-known-failures.json`](brikk-sql/testResources/generator-corpus/trino-generator-known-failures.json):
  - `JSON_QUERY(... WITHOUT CONDITIONAL WRAPPER)` → `WITHOUT WRAPPER` — [`docs/brikk-extensions.md`](docs/brikk-extensions.md) §8
