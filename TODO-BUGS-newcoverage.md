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


**109 items** — by category: defects 66, generation 23, types 20.


## clickhouse  (28)

- [defects] [clickhouse] `ALTER TABLE t ALTER COLUMN IF EXISTS c TYPE Int64` :: ast-mismatch at #8: expected Identifier (k=this) _(gate: parser)_
- [defects] [clickhouse] `ALTER TABLE t MODIFY COLUMN IF EXISTS c Int64` :: ast-mismatch at #0: expected Alter _(gate: parser)_
- [defects] [clickhouse] `ALTER TABLE t MODIFY COLUMN c Int64` :: ast-mismatch at #0: expected Alter _(gate: parser)_
- [defects] [clickhouse] `INSERT INTO t (col1, col2) VALUES (('abcd'), (1234))` :: ast-mismatch at #22: expected Literal (k=expressions) _(gate: parser)_
- [defects] [clickhouse] `SELECT * FROM VALUES ((1), (2), (3))` :: ast-mismatch at #6: expected Literal (k=expressions) _(gate: parser)_
- [defects] [clickhouse] `SELECT arrayMap((a, b) -> a * b, [1, 2, 3], [10, 20, 30]) AS products` :: ast-mismatch at #2: expected Anonymous (k=this) _(gate: parser)_
- [defects] [clickhouse] `SELECT event_id FROM "analytics"."uniqExactIf"(final = 1)` :: ast-mismatch at #7: expected CombinedAggFunc (k=this) _(gate: parser)_
- [defects] [clickhouse] `SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5)` :: Invalid expression / Unexpected token. Line 1, Col: 46. _(gate: parser)_
- [defects] [clickhouse] `SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5)` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 46. SELECT quantileExactInclusive(0.25)(number) AS x FROM numbers(5) _(gate: annotate)_
- [defects] [clickhouse] `arrayFilter((x, y) -> y, [1, 2, 3], [1, 0, 1])` :: ast-mismatch at #0: expected Anonymous _(gate: parser)_
- [defects] [clickhouse] `arrayMap((x, y, z) -> x + y + z, [1, 2], [3, 4], [5, 6])` :: ast-mismatch at #0: expected Anonymous _(gate: parser)_
- [generation] [clickhouse] `ALTER TABLE t ALTER COLUMN IF EXISTS c TYPE Int64` :: output mismatch: expected `ALTER TABLE t ALTER COLUMN IF EXISTS c TYPE Int64` actual `ALTER TABLE t ALTER COLUMN c SET DATA TYPE Int64` _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE t MODIFY COLUMN IF EXISTS c Int64` :: output mismatch: expected `ALTER TABLE t ALTER COLUMN IF EXISTS c TYPE Int64` actual `ALTER TABLE t ALTER COLUMN c SET DATA TYPE Int64` _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE t MODIFY COLUMN c Int64` :: output mismatch: expected `ALTER TABLE t ALTER COLUMN c TYPE Int64` actual `ALTER TABLE t ALTER COLUMN c SET DATA TYPE Int64` _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE t MODIFY COMMENT 'hi'` :: UnsupportedError: Unsupported expression type AlterModifySqlSecurity _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE t MODIFY TTL d + INTERVAL 1 DAY` :: UnsupportedError: Unsupported expression type AlterModifySqlSecurity _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE v MODIFY SQL SECURITY DEFINER DEFINER='alice'` :: UnsupportedError: Unsupported expression type AlterModifySqlSecurity _(gate: generator)_
- [generation] [clickhouse] `ALTER TABLE v MODIFY SQL SECURITY DEFINER DEFINER=CURRENT_USER` :: UnsupportedError: Unsupported expression type AlterModifySqlSecurity _(gate: generator)_
- [generation] [clickhouse] `SELECT $$a\$b$$` :: output mismatch: expected `SELECT 'a\\$b'` actual `SELECT 'a\$b'` _(gate: generator)_
- [types] [clickhouse] `ALTER TABLE t ALTER COLUMN IF EXISTS c TYPE Int64` :: type-mismatch at #8 (Identifier k=this): expected t=… _(gate: annotate)_
- [types] [clickhouse] `ALTER TABLE t MODIFY COLUMN IF EXISTS c Int64` :: type-mismatch at #0 (Alter): expected t=… _(gate: annotate)_
- [types] [clickhouse] `ALTER TABLE t MODIFY COLUMN c Int64` :: type-mismatch at #0 (Alter): expected t=… _(gate: annotate)_
- [types] [clickhouse] `INSERT INTO t (col1, col2) VALUES (('abcd'), (1234))` :: type-mismatch at #22 (Literal k=expressions): expected t=… _(gate: annotate)_
- [types] [clickhouse] `SELECT * FROM VALUES ((1), (2), (3))` :: type-mismatch at #6 (Literal k=expressions): expected t=… _(gate: annotate)_
- [types] [clickhouse] `SELECT arrayMap((a, b) -> a * b, [1, 2, 3], [10, 20, 30]) AS products` :: type-mismatch at #2 (Anonymous k=this): expected t=… _(gate: annotate)_
- [types] [clickhouse] `SELECT event_id FROM "analytics"."uniqExactIf"(final = 1)` :: type-mismatch at #7 (CombinedAggFunc k=this): expected t=… _(gate: annotate)_
- [types] [clickhouse] `arrayFilter((x, y) -> y, [1, 2, 3], [1, 0, 1])` :: type-mismatch at #0 (Anonymous): expected t=… _(gate: annotate)_
- [types] [clickhouse] `arrayMap((x, y, z) -> x + y + z, [1, 2], [3, 4], [5, 6])` :: type-mismatch at #0 (Anonymous): expected t=… _(gate: annotate)_

## bigquery  (25)

- [CRASH] [defects] [bigquery] `SELECT ARRAY_AGG((SELECT c FROM t LIMIT 1) IGNORE NULLS) FROM u` :: NullPointerException: null cannot be cast to non-null type dev.brikk.house.sql.ast.Expression _(gate: generator)_
- [CRASH] [defects] [bigquery] `SELECT ARRAY_AGG((SELECT c FROM t ORDER BY c) IGNORE NULLS LIMIT 1) FROM u` :: NullPointerException: null cannot be cast to non-null type dev.brikk.house.sql.ast.Expression _(gate: generator)_
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

## postgres  (21)

- [defects] [postgres] `SELECT (U&'\FE01' || 'Test literal') AS label FROM data` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [defects] [postgres] `SELECT NOT r IS NOT NULL FROM t` :: ast-mismatch at #2: expected Is (k=this) _(gate: parser)_
- [defects] [postgres] `SELECT U&'a''b''c'` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [defects] [postgres] `SELECT U&'can''t !0061' UESCAPE '!' AS label` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [defects] [postgres] `SELECT U&'can''t'` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [defects] [postgres] `SELECT U&'d!0061t!+000061' UESCAPE '!' AS label` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [defects] [postgres] `SELECT r IS NOT NULL FROM t` :: ast-mismatch at #1: expected Is (k=expressions) _(gate: parser)_
- [defects] [postgres] `SELECT r NOTNULL FROM t` :: ast-mismatch at #1: expected Is (k=expressions) _(gate: parser)_
- [defects] [postgres] `SELECT u&'\0441\043B\043E\043D'` :: round-trip: generated SQL not re-parseable — brikk parse/generate failed: UnsupportedError: Unsupported expression type UnicodeString _(gate: verify)_
- [generation] [postgres] `SELECT (U&'\FE01' || 'Test literal') AS label FROM data` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [generation] [postgres] `SELECT NOT r IS NOT NULL FROM t` :: output mismatch: expected `SELECT NOT r IS NOT NULL FROM t` actual `SELECT NOT r IS NULL FROM t` _(gate: generator)_
- [generation] [postgres] `SELECT U&'a''b''c'` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [generation] [postgres] `SELECT U&'can''t !0061' UESCAPE '!' AS label` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [generation] [postgres] `SELECT U&'can''t'` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [generation] [postgres] `SELECT U&'d!0061t!+000061' UESCAPE '!' AS label` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [generation] [postgres] `SELECT r IS NOT NULL FROM t` :: output mismatch: expected `SELECT r IS NOT NULL FROM t` actual `SELECT r IS NULL FROM t` _(gate: generator)_
- [generation] [postgres] `SELECT r NOTNULL FROM t` :: output mismatch: expected `SELECT r IS NOT NULL FROM t` actual `SELECT r IS NULL FROM t` _(gate: generator)_
- [generation] [postgres] `SELECT u&'\0441\043B\043E\043D'` :: UnsupportedError: Unsupported expression type UnicodeString _(gate: generator)_
- [types] [postgres] `SELECT NOT r IS NOT NULL FROM t` :: type-mismatch at #2 (Is k=this): expected t=… _(gate: annotate)_
- [types] [postgres] `SELECT r IS NOT NULL FROM t` :: type-mismatch at #1 (Is k=expressions): expected t=… _(gate: annotate)_
- [types] [postgres] `SELECT r NOTNULL FROM t` :: type-mismatch at #1 (Is k=expressions): expected t=… _(gate: annotate)_

## mysql  (17)

- [defects] [mysql] `CREATE TABLE t1 (id INT AUTO_INCREMENT KEY)` :: Expecting (. Line 1, Col: 43. _(gate: parser)_
- [defects] [mysql] `CREATE TABLE t1 (id INT AUTO_INCREMENT KEY)` :: ParseError: Expecting (. Line 1, Col: 43. CREATE TABLE t1 (id INT AUTO_INCREMENT KEY) _(gate: annotate)_
- [defects] [mysql] `CREATE TABLE t1 (id INT KEY AUTO_INCREMENT)` :: Expecting (. Line 1, Col: 43. _(gate: parser)_
- [defects] [mysql] `CREATE TABLE t1 (id INT KEY AUTO_INCREMENT)` :: ParseError: Expecting (. Line 1, Col: 43. CREATE TABLE t1 (id INT KEY AUTO_INCREMENT) _(gate: annotate)_
- [defects] [mysql] `CREATE TABLE t1 (id INT KEY)` :: Expecting (. Line 1, Col: 28. _(gate: parser)_
- [defects] [mysql] `CREATE TABLE t1 (id INT KEY)` :: ParseError: Expecting (. Line 1, Col: 28. CREATE TABLE t1 (id INT KEY) _(gate: annotate)_
- [defects] [mysql] `INSERT INTO `test_table` SET `test_col_1` = 123, `test_col_2` = '456'` :: Invalid expression / Unexpected token. Line 1, Col: 28. _(gate: parser)_
- [defects] [mysql] `INSERT INTO `test_table` SET `test_col_1` = 123, `test_col_2` = '456'` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 28. INSERT INTO `test_table` SET `test_col_1` = 123, `test_col_2` = '456' _(gate: annotate)_
- [defects] [mysql] `INSERT INTO t SET a = DEFAULT, b = 2 AS new ON DUPLICATE KEY UPDATE a = new.a + 1` :: Invalid expression / Unexpected token. Line 1, Col: 17. _(gate: parser)_
- [defects] [mysql] `INSERT INTO t SET a = DEFAULT, b = 2 AS new ON DUPLICATE KEY UPDATE a = new.a + 1` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 17. INSERT INTO t SET a = DEFAULT, b = 2 AS new ON DUPLICATE KEY UPDATE a = ne _(gate: annotate)_
- [defects] [mysql] `SELECT DATE_FORMAT(x, '%x-%v')` :: ast-mismatch at #8: expected {"i":7,"k":"this","v":"%G-%v"} _(gate: parser)_
- [defects] [mysql] `SELECT a SOUNDS LIKE b IS NULL` :: Invalid expression / Unexpected token. Line 1, Col: 30. _(gate: parser)_
- [defects] [mysql] `SELECT a SOUNDS LIKE b IS NULL` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 30. SELECT a SOUNDS LIKE b IS NULL _(gate: annotate)_
- [defects] [mysql] `SELECT a SOUNDS LIKE b | c` :: Invalid expression / Unexpected token. Line 1, Col: 24. _(gate: parser)_
- [defects] [mysql] `SELECT a SOUNDS LIKE b | c` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 24. SELECT a SOUNDS LIKE b | c _(gate: annotate)_
- [generation] [mysql] `SELECT DATE_FORMAT(x, '%x-%v')` :: output mismatch: expected `SELECT DATE_FORMAT(x, '%x-%v')` actual `SELECT DATE_FORMAT(x, '%G-%v')` _(gate: generator)_
- [types] [mysql] `SELECT DATE_FORMAT(x, '%x-%v')` :: type-mismatch at #8 (? k=this): expected t=… _(gate: annotate)_

## duckdb  (12)

- [defects] [duckdb] `FROM_HEX('AA')` :: ast-mismatch at #0: expected Unhex _(gate: parser)_
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
- [types] [duckdb] `FROM_HEX('AA')` :: type-mismatch at #0 (Unhex): expected t=… _(gate: annotate)_

## presto  (6)

- [defects] [presto] `SELECT JSON_EXTRACT(x, '$["a",""]')` :: ast-mismatch at #8: expected JSONPathUnion (k=expressions) _(gate: parser)_
- [defects] [presto] `SELECT JSON_EXTRACT(x, '$[1,0]')` :: ast-mismatch at #8: expected JSONPathUnion (k=expressions) _(gate: parser)_
- [defects] [presto] `SELECT JSON_EXTRACT_SCALAR(x, '$[1,0]')` :: ast-mismatch at #8: expected JSONPathUnion (k=expressions) _(gate: parser)_
- [types] [presto] `SELECT JSON_EXTRACT(x, '$["a",""]')` :: type-mismatch at #8 (JSONPathUnion k=expressions): expected t=… _(gate: annotate)_
- [types] [presto] `SELECT JSON_EXTRACT(x, '$[1,0]')` :: type-mismatch at #8 (JSONPathUnion k=expressions): expected t=… _(gate: annotate)_
- [types] [presto] `SELECT JSON_EXTRACT_SCALAR(x, '$[1,0]')` :: type-mismatch at #8 (JSONPathUnion k=expressions): expected t=… _(gate: annotate)_
