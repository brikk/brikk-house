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


**38 items** — by category: defects 28, generation 6, types 4.


## clickhouse  (5)

- [defects] [clickhouse] `SELECT event_id FROM "analytics"."uniqExactIf"(final = 1)` :: ast-mismatch at #7: expected CombinedAggFunc (k=this) _(gate: parser)_
- [defects] [clickhouse] `SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5)` :: Invalid expression / Unexpected token. Line 1, Col: 46. _(gate: parser)_
- [defects] [clickhouse] `SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5)` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 46. SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5) _(gate: annotate)_
- [generation] [clickhouse] `SELECT $$a\$b$$` :: output mismatch: expected `SELECT 'a\\$b'` actual `SELECT 'a\$b'` _(gate: generator)_
- [types] [clickhouse] `SELECT event_id FROM "analytics"."uniqExactIf"(final = 1)` :: type-mismatch at #7 (CombinedAggFunc k=this): expected t=… _(gate: annotate)_

## bigquery  (23)

- [defects] [bigquery] `(SELECT 1 AS foo) FULL OUTER UNION ALL BY NAME (SELECT 2 AS foo, 3 AS bar)` :: ast-mismatch at #1: expected Subquery (k=this) _(gate: parser)_
- [defects] [bigquery] `(SELECT 1 AS foo) FULL UNION ALL BY NAME (SELECT 2 AS foo)` :: ast-mismatch at #1: expected Subquery (k=this) _(gate: parser)_
- [defects] [bigquery] `(SELECT 1) AS x UNION ALL (SELECT 2)` :: ast-mismatch at #1: expected Subquery (k=this) _(gate: parser)_
- [defects] [bigquery] `FOR system_time IN (SELECT 1 AS x) DO SELECT system_time.x` :: ParseError: Required keyword: 'this' missing for Comprehension. Line 1, Col: 37. FOR system_time IN (SELECT 1 AS x) DO SELECT system_time.x _(gate: annotate)_
- [defects] [bigquery] `FOR system_time IN (SELECT 1 AS x) DO SELECT system_time.x` :: Required keyword: 'this' missing for Comprehension. Line 1, Col: 37. _(gate: parser)_
- [defects] [bigquery] `FOR timestamp IN (SELECT 1 AS x) DO SELECT timestamp.x` :: ParseError: Required keyword: 'this' missing for Comprehension. Line 1, Col: 35. FOR timestamp IN (SELECT 1 AS x) DO SELECT timestamp.x _(gate: annotate)_
- [defects] [bigquery] `FOR timestamp IN (SELECT 1 AS x) DO SELECT timestamp.x` :: Required keyword: 'this' missing for Comprehension. Line 1, Col: 35. _(gate: parser)_
- [defects] [bigquery] `SELECT """a\"b"""` :: ast-mismatch at #2: expected {"i":1,"k":"this","v":"a\"b"} _(gate: parser)_
- [defects] [bigquery] `SELECT """ends with \"word\""""` :: TokenError: Missing " from 1:30 _(gate: parser)_
- [defects] [bigquery] `SELECT '''ends with \'word\''''` :: TokenError: Missing ' from 1:30 _(gate: parser)_
- [defects] [bigquery] `SELECT STRING(data.144) FROM t` :: ast-mismatch at #3: expected Identifier (k=this) _(gate: parser)_
- [defects] [bigquery] `SELECT STRING(data.1e10) FROM t` :: ast-mismatch at #3: expected Identifier (k=this) _(gate: parser)_
- [defects] [bigquery] `SELECT data.144A_FLAG FROM t` :: ast-mismatch at #1: expected Column (k=expressions) _(gate: parser)_
- [defects] [bigquery] `SELECT r"""ends with \""""` :: TokenError: Missing " from 1:25 _(gate: parser)_
- [defects] [bigquery] `SELECT t.144 A_FLAG FROM t` :: ast-mismatch at #3: expected Identifier (k=this) _(gate: parser)_
- [defects] [bigquery] `SELECT t.1a FROM t` :: ast-mismatch at #1: expected Column (k=expressions) _(gate: parser)_
- [generation] [bigquery] `FOR system_time IN (SELECT 1 AS x) DO SELECT system_time.x` :: UnsupportedError: Unsupported expression type ForIn _(gate: generator)_
- [generation] [bigquery] `FOR timestamp IN (SELECT 1 AS x) DO SELECT timestamp.x` :: UnsupportedError: Unsupported expression type ForIn _(gate: generator)_
- [generation] [bigquery] `SELECT r"""a\"b"""` :: output mismatch: expected `SELECT 'a\\"b'` actual `SELECT 'a\"b'` _(gate: generator)_
- [generation] [bigquery] `SELECT r"""ends with \""""` :: output mismatch: expected `SELECT 'ends with \\"'` actual `SELECT 'ends with \"'` _(gate: generator)_
- [types] [bigquery] `SELECT data.144A_FLAG FROM t` :: type-mismatch at #1 (Column k=expressions): expected t=… _(gate: annotate)_
- [types] [bigquery] `SELECT t.144 A_FLAG FROM t` :: type-mismatch at #3 (Identifier k=this): expected t=… _(gate: annotate)_
- [types] [bigquery] `SELECT t.1a FROM t` :: type-mismatch at #1 (Column k=expressions): expected t=… _(gate: annotate)_

## duckdb  (10)

- [defects] [duckdb] `SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => NOW() - INTERVAL '1' WEEK) ON ` :: AT index expressions are not supported yet. Line 1, Col: 26. _(gate: parser)_
- [defects] [duckdb] `SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => NOW() - INTERVAL '1' WEEK) ON ` :: ParseError: AT index expressions are not supported yet. Line 1, Col: 26. SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => _(gate: annotate)_
- [defects] [duckdb] `SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => NOW() - INTERVAL '1' WEEK) ON ` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: ParseError: AT index expressions are not supported yet. Line 1, Col: 26. SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2  _(gate: verify)_
- [defects] [duckdb] `SELECT a -> 'it''s' FROM t` :: TokenError: Missing ' from 1:2 _(gate: annotate)_
- [defects] [duckdb] `SELECT a -> 'it''s' FROM t` :: TokenError: Missing ' from 1:2 _(gate: parser)_
- [defects] [duckdb] `SELECT a -> 'it''s' FROM t` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: TokenError: Missing ' from 1:2 _(gate: verify)_
- [defects] [duckdb] `SELECT a ->> 'it''s' FROM t` :: TokenError: Missing ' from 1:2 _(gate: annotate)_
- [defects] [duckdb] `SELECT a ->> 'it''s' FROM t` :: TokenError: Missing ' from 1:2 _(gate: parser)_
- [defects] [duckdb] `SELECT a ->> 'it''s' FROM t` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: TokenError: Missing ' from 1:2 _(gate: verify)_
- [generation] [duckdb] `SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => NOW() - INTERVAL '1' WEEK) ON ` :: output mismatch: expected `SELECT * FROM t1 AS a AT (VERSION => 3) JOIN t2 AS b AT (TIMESTAMP => NOW() - INTERVAL '1' WEEK) ON a.id = b.id` actual `SE _(gate: generator)_
