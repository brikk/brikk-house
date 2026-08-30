# TODO - newly-surfaced in-scope gaps (extractor loop-unroll coverage)

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
> **Every item below is IN SCOPE**: it involves only ported dialects and is a real divergence
> from the reference. (~94% of ~16.4k corpus cases match the reference exactly; these are part
> of the remaining tail.)
>
> **How verification works.** Behaviour is pinned by "corpus gates": generated fixtures under
> `brikk-sql/testResources/**` capture the reference's output, and each gate enforces an *exact*
> known-failures ledger (`*-known-failures.json`). Build/test from the repo root with
> `./kotlin build` and `./kotlin test`. Each run also writes `brikk-sql/*-ledger-actual.json`
> (gitignored) with the current failing set. **When you fix an item, delete its line from the
> matching committed `*-known-failures.json`** (or copy the refreshed actual over it); the gate
> then proves it passes. Reproduce a single item by parsing/generating with the noted dialect(s)
> and diffing against `reference/sqlglot` (e.g. `python3 -c "import sqlglot; print(sqlglot.transpile(SQL, read=SRC, write=TGT)[0])"`).
>
> **Finding the code.** Port files carry `// sqlglot: <symbol>` provenance comments pointing at
> the Python source — grep them. Parser: `brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt`
> + `dialects/<D>Parser.kt`. Generator: `generator/Generator.kt` + `generator/GeneratorTables.kt`
> + `dialects/<D>Generator.kt`. Type inference: `optimizer/AnnotateTypes.kt` + `ast/TypingSpec.kt`
> + `tools/gen_typing_metadata.py`.
>
> **This file = in-scope gaps NEWLY surfaced by the extractor's loop-unrolling** (commit that
> enhanced `tools/extract_dialect_tests.py` to unroll `for x in [<literals>]: validate_identity(x)`).
> These were always real divergences from the reference; they were simply never captured as corpus
> cases before, so they're brand-new to the ledgers. Each line is tagged with its category —
> **[rewrites]** (cross-dialect transpile), **[types]** (annotate_types), **[generation]** (native
> round-trip), **[defects]** (parse/mis-parse/crash/verify round-trip). Same guidance as the four
> `TODO-BUGS-<category>.md` files applies per tag. `verify` gate = our generated SQL doesn't re-parse.
---


**0 items.** All loop-unrolled coverage gaps have been resolved.
