# TODO - cross-dialect transpile rewrites (in-scope)

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
> **This file = cross-dialect transpile rewrites.** Parsing SQL in the *source* dialect and
> generating it in the *target* dialect produces **valid SQL that isn't the reference's
> canonical form** (e.g. duckdb `RANGE(1,5)` -> spark should be `SEQUENCE(1,4)`). Both ends are
> ported. Fix = add/adjust the target dialect's generator `TRANSFORMS`/`<node>_sql` or a
> source-aware rewrite; reference is `reference/sqlglot/sqlglot/generators/<target>.py` /
> `dialects/<target>.py`. Lower risk (output is already valid), but should still match.
---


**448 items** across 54 groups.


## bigquery->duckdb  (116)

- [bigquery -> duckdb] `APPROX_QUANTILES(DISTINCT x, 2)` :: expected `APPROX_QUANTILE(DISTINCT x, [0, 0.5, 1])` actual `APPROX_QUANTILES(DISTINCT x, 2)`
- [bigquery -> duckdb] `APPROX_QUANTILES(x, 1)` :: expected `APPROX_QUANTILE(x, [0, 1])` actual `APPROX_QUANTILES(x, 1)`
- [bigquery -> duckdb] `APPROX_QUANTILES(x, 2 IGNORE NULLS)` :: expected `APPROX_QUANTILE(x, [0, 0.5, 1])` actual `APPROX_QUANTILES(x, 2)`
- [bigquery -> duckdb] `APPROX_QUANTILES(x, 2)` :: expected `APPROX_QUANTILE(x, [0, 0.5, 1])` actual `APPROX_QUANTILES(x, 2)`
- [bigquery -> duckdb] `APPROX_QUANTILES(x, 4)` :: expected `APPROX_QUANTILE(x, [0, 0.25, 0.5, 0.75, 1])` actual `APPROX_QUANTILES(x, 4)`
- [bigquery -> duckdb] `CURRENT_DATE('UTC')` :: expected `CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)` actual `CURRENT_DATE('UTC')`
- [bigquery -> duckdb] `DATE_DIFF('2021-01-01', '2020-01-01', DAY)` :: expected `DATE_DIFF('DAY', CAST('2020-01-01' AS DATE), CAST('2021-01-01' AS DATE))` actual `DATE_DIFF(DAY, CAST('2020-01-01' AS DATE), CAST('2021-01-0
- [bigquery -> duckdb] `EDIT_DISTANCE(col1, col2, max_distance => 3)` :: expected `CASE WHEN LEVENSHTEIN(col1, col2) IS NULL OR 3 IS NULL THEN NULL ELSE LEAST(LEVENSHTEIN(col1, col2), 3) END` actual `LEVENSHTEIN(col1, col2,
- [bigquery -> duckdb] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> duckdb] `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')` :: expected `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]', 1)` actual `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')`
- [bigquery -> duckdb] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `CASE WHEN (2 * y) <> 0 THEN (x + 1) / (2 * y) ELSE NULL END` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> duckdb] `SAFE_DIVIDE(x, y)` :: expected `CASE WHEN y <> 0 THEN x / y ELSE NULL END` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> duckdb] `SELECT * FROM UNNEST(ARRAY<STRUCT<device_id INT64, time DATETIME, signal INT64, state STRING>>[STRUC` :: expected `SELECT * FROM (SELECT UNNEST(CAST([ROW(1, CAST('2023-11-01 09:34:01' AS TIMESTAMP), 74, 'INACTIVE'), ROW(4, CAST('2023-11-01 09:38:01' AS TI
- [bigquery -> duckdb] `SELECT * FROM UNNEST(ARRAY<STRUCT<x INT64>>[])` :: expected `SELECT * FROM (SELECT UNNEST(CAST([] AS STRUCT(x BIGINT)[]), max_depth => 2))` actual `SELECT * FROM UNNEST("ARRAY" < STRUCT(x BIGINT) > [])
- [bigquery -> duckdb] `SELECT * FROM UNNEST([STRUCT('Alice' AS name, 85 AS score), STRUCT('Bob', 92), STRUCT('Diana', 95)])` :: expected `SELECT * FROM (SELECT UNNEST([{'name': 'Alice', 'score': 85}, {'name': 'Bob', 'score': 92}, {'name': 'Diana', 'score': 95}], max_depth => 2)
- [bigquery -> duckdb] `SELECT * FROM UNNEST([STRUCT('Alice' AS name, STRUCT(85 AS math, 90 AS english) AS scores), STRUCT('` :: expected `SELECT * FROM (SELECT UNNEST([{'name': 'Alice', 'scores': {'math': 85, 'english': 90}}, {'name': 'Bob', 'scores': {'math': 92, 'english': 88
- [bigquery -> duckdb] `SELECT * FROM a LEFT JOIN b ON a.key = b.key AND a.val IN UNNEST(b.arr)` :: expected `SELECT * FROM a LEFT JOIN b ON a.key = b.key AND CASE WHEN b.arr IS NULL OR ARRAY_LENGTH(b.arr) = 0 THEN FALSE WHEN ARRAY_CONTAINS(b.arr, a.
- [bigquery -> duckdb] `SELECT * FROM a WHERE b IN UNNEST([1, 2, 3])` :: expected `SELECT * FROM a WHERE CASE WHEN [1, 2, 3] IS NULL OR ARRAY_LENGTH([1, 2, 3]) = 0 THEN FALSE WHEN ARRAY_CONTAINS([1, 2, 3], b) THEN TRUE WHEN
- [bigquery -> duckdb] `SELECT * FROM a WHERE b NOT IN UNNEST([1, 2, 3])` :: expected `SELECT * FROM a WHERE NOT CASE WHEN [1, 2, 3] IS NULL OR ARRAY_LENGTH([1, 2, 3]) = 0 THEN FALSE WHEN ARRAY_CONTAINS([1, 2, 3], b) THEN TRUE 
- [bigquery -> duckdb] `SELECT * FROM t WHERE EXISTS(SELECT * FROM unnest(nums) AS x WHERE x > 1)` :: expected `SELECT * FROM t WHERE EXISTS(SELECT * FROM UNNEST(nums) AS _t0(x) WHERE x > 1)` actual `SELECT * FROM t WHERE EXISTS(SELECT * FROM UNNEST(nu
- [bigquery -> duckdb] `SELECT ARRAY<FLOAT64>[1, 2, 3]` :: expected `SELECT CAST([1, 2, 3] AS DOUBLE[])` actual `SELECT "ARRAY" < FLOAT64 > [1, 2, 3]`
- [bigquery -> duckdb] `SELECT ARRAY_AGG(DISTINCT x IGNORE NULLS ORDER BY x) AS x` :: expected `SELECT ARRAY_AGG(DISTINCT x ORDER BY x NULLS FIRST) FILTER(WHERE x IS NOT NULL) AS x` actual `SELECT ARRAY_AGG(DISTINCT x ORDER BY x NULLS F
- [bigquery -> duckdb] `SELECT ARRAY_AGG(x IGNORE NULLS) AS x` :: expected `SELECT ARRAY_AGG(x) FILTER(WHERE x IS NOT NULL) AS x` actual `SELECT ARRAY_AGG(x) AS x`
- [bigquery -> duckdb] `SELECT ARRAY_CONCAT_AGG(arr LIMIT 2) FROM (SELECT [1, 2] AS arr) AS t` :: expected UnsupportedError, got `SELECT ARRAY_CONCAT_AGG(arr LIMIT 2) FROM (SELECT [1, 2] AS arr) AS t`
- [bigquery -> duckdb] `SELECT ARRAY_CONCAT_AGG(arr ORDER BY y DESC LIMIT 2) FROM (SELECT [1, 2] AS arr, 1 AS y) AS t` :: expected UnsupportedError, got `SELECT ARRAY_CONCAT_AGG(arr ORDER BY y DESC LIMIT 2) FROM (SELECT [1, 2] AS arr, 1 AS y) AS t`
- [bigquery -> duckdb] `SELECT ARRAY_CONCAT_AGG(arr ORDER BY y) FROM (SELECT [1, 2] AS arr, 1 AS y) AS t` :: expected `SELECT FLATTEN(ARRAY_AGG(arr ORDER BY y NULLS FIRST) FILTER(WHERE NOT arr IS NULL)) FROM (SELECT [1, 2] AS arr, 1 AS y) AS t` actual `SELECT
- [bigquery -> duckdb] `SELECT ARRAY_CONCAT_AGG(arr) FROM (SELECT [1, 2] AS arr) AS t` :: expected `SELECT FLATTEN(ARRAY_AGG(arr) FILTER(WHERE NOT arr IS NULL)) FROM (SELECT [1, 2] AS arr) AS t` actual `SELECT ARRAY_CONCAT_AGG(arr) FROM (SE
- [bigquery -> duckdb] `SELECT ARRAY_TO_STRING(['cake', 'pie', NULL], '--', 'MISSING') AS text` :: expected `SELECT ARRAY_TO_STRING(LIST_TRANSFORM(['cake', 'pie', NULL], x -> COALESCE(x, 'MISSING')), '--') AS text` actual `SELECT ARRAY_TO_STRING(['c
- [bigquery -> duckdb] `SELECT AS STRUCT ARRAY(SELECT AS STRUCT 1 AS b FROM x) AS y FROM z` :: expected `SELECT {'y': ARRAY(SELECT {'b': 1} FROM x)} FROM z` actual `SELECT AS STRUCT ARRAY(SELECT AS STRUCT 1 AS b FROM x) AS y FROM z`
- [bigquery -> duckdb] `SELECT CAST(CAST('2016-12-25 23:59:59' AS TIMESTAMP) AS DATE)` :: expected `SELECT CAST(CAST('2016-12-25 23:59:59' AS TIMESTAMP) AS DATE)` actual `SELECT DATE(CAST('2016-12-25 23:59:59' AS TIMESTAMP))`
- [bigquery -> duckdb] `SELECT CAST(CAST('2024-01-15 23:30:00' AS TIMESTAMP) AT TIME ZONE 'UTC' AT TIME ZONE 'Europe/Berlin'` :: expected `SELECT CAST(CAST('2024-01-15 23:30:00' AS TIMESTAMP) AT TIME ZONE 'UTC' AT TIME ZONE 'Europe/Berlin' AS DATE)` actual `SELECT DATE('2024-01-
- [bigquery -> duckdb] `SELECT CAST(CAST(CAST('2016-12-25' AS TIMESTAMPTZ) AS TIMESTAMP) AT TIME ZONE 'UTC' AT TIME ZONE 'Am` :: expected `SELECT CAST(CAST(CAST('2016-12-25' AS TIMESTAMPTZ) AS TIMESTAMP) AT TIME ZONE 'UTC' AT TIME ZONE 'America/Los_Angeles' AS DATE)` actual `SEL
- [bigquery -> duckdb] `SELECT CAST(CAST(STRPTIME('1970 ' || '05/06/2020', '%Y ' || '%m/%d/%Y') AS DATE) AS DATE)` :: expected `SELECT CAST(CAST(STRPTIME('1970 ' || '05/06/2020', '%Y ' || '%m/%d/%Y') AS DATE) AS DATE)` actual `SELECT DATE(CAST(STRPTIME('1970 ' || '05/
- [bigquery -> duckdb] `SELECT CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)` :: expected `SELECT CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)` actual `SELECT CURRENT_DATE('UTC')`
- [bigquery -> duckdb] `SELECT CAST(STRPTIME('14:30', '%H:%M') AS TIME)` :: expected `SELECT CAST(STRPTIME('14:30', '%H:%M') AS TIME)` actual `SELECT PARSE_TIME('14:30', '%H:%M')`
- [bigquery -> duckdb] `SELECT CAST(STRPTIME('15:30:00.123456', '%H:%M:%S.%f') AS TIME)` :: expected `SELECT CAST(STRPTIME('15:30:00.123456', '%H:%M:%S.%f') AS TIME)` actual `SELECT PARSE_TIME('15:30:00.123456', '%H:%M:%S.%f')`
- [bigquery -> duckdb] `SELECT DATETIME('2020-01-01')` :: expected `SELECT CAST('2020-01-01' AS TIMESTAMP)` actual `SELECT TS_OR_DS_TO_DATETIME('2020-01-01')`
- [bigquery -> duckdb] `SELECT DATETIME('2020-01-01', 'America/Los_Angeles')` :: expected `SELECT CAST(CAST('2020-01-01' AS TIMESTAMPTZ) AT TIME ZONE 'America/Los_Angeles' AS TIMESTAMP)` actual `SELECT DATETIME('2020-01-01')`
- [bigquery -> duckdb] `SELECT DATETIME('2020-01-01', TIME '23:59:59')` :: expected `SELECT CAST(CAST('2020-01-01' AS DATE) + CAST('23:59:59' AS TIME) AS TIMESTAMP)` actual `SELECT DATETIME('2020-01-01')`
- [bigquery -> duckdb] `SELECT DATETIME_DIFF(DATETIME '2017-10-15 00:00:00', DATETIME '2017-10-14 00:00:00', WEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2017-10-14 00:00:00' AS TIMESTAMP) + INTERVAL '1' DAY), DATE_TRUNC('WEEK', CAST('2017-10-1
- [bigquery -> duckdb] `SELECT DATETIME_TRUNC('2023-01-01T01:01:01', HOUR)` :: expected `SELECT DATE_TRUNC('HOUR', CAST('2023-01-01T01:01:01' AS TIMESTAMP))` actual `SELECT DATETIME_TRUNC('2023-01-01T01:01:01', HOUR)`
- [bigquery -> duckdb] `SELECT DATETIME_TRUNC(DATETIME '2008-11-10 14:30:00', WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC('WEEK', CAST(CAST('2008-11-10 14:30:00' AS TIMESTAMP) AS TIMESTAMP) + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELEC
- [bigquery -> duckdb] `SELECT DATETIME_TRUNC(DATETIME '2008-11-10 14:30:00', WEEK)` :: expected `SELECT DATE_TRUNC('WEEK', CAST(CAST('2008-11-10 14:30:00' AS TIMESTAMP) AS TIMESTAMP) + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELEC
- [bigquery -> duckdb] `SELECT DATETIME_TRUNC(dt, WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC('WEEK', CAST(dt AS TIMESTAMP) + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELECT DATETIME_TRUNC(dt, WEEK_START(SUNDAY
- [bigquery -> duckdb] `SELECT DATE_DIFF('2024-01-07', '2024-01-06', WEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2024-01-06' AS DATE) + INTERVAL '1' DAY), DATE_TRUNC('WEEK', CAST('2024-01-07' AS DATE) + 
- [bigquery -> duckdb] `SELECT DATE_DIFF('2024-01-15', '2024-01-08', ISOWEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2024-01-08' AS DATE)), DATE_TRUNC('WEEK', CAST('2024-01-15' AS DATE)))` actual `SELECT DAT
- [bigquery -> duckdb] `SELECT DATE_DIFF('2024-01-15', '2024-01-08', WEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2024-01-08' AS DATE) + INTERVAL '1' DAY), DATE_TRUNC('WEEK', CAST('2024-01-15' AS DATE) + 
- [bigquery -> duckdb] `SELECT DATE_DIFF(DATE '2023-05-01', DATE '2024-01-15', ISOWEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2024-01-15' AS DATE)), DATE_TRUNC('WEEK', CAST('2023-05-01' AS DATE)))` actual `SELECT DAT
- [bigquery -> duckdb] `SELECT DATE_DIFF(DATE '2024-01-01', DATE '2024-01-15', DAY)` :: expected `SELECT DATE_DIFF('DAY', CAST('2024-01-15' AS DATE), CAST('2024-01-01' AS DATE))` actual `SELECT DATE_DIFF(DAY, CAST('2024-01-15' AS DATE), C
- [bigquery -> duckdb] `SELECT DATE_TRUNC(DATE '2008-11-10', ISOWEEK)` :: expected `SELECT DATE_TRUNC('WEEK', CAST('2008-11-10' AS DATE))` actual `SELECT DATE_TRUNC(ISOWEEK, CAST('2008-11-10' AS DATE))`
- [bigquery -> duckdb] `SELECT DATE_TRUNC(DATE '2008-11-10', WEEK)` :: expected `SELECT CAST(DATE_TRUNC('WEEK', CAST('2008-11-10' AS DATE) + INTERVAL '1' DAY) + INTERVAL '-1' DAY AS DATE)` actual `SELECT DATE_TRUNC(WEEK, 
- [bigquery -> duckdb] `SELECT DATE_TRUNC(DATE '2015-06-15', ISOYEAR)` :: expected `SELECT DATE_TRUNC('ISOYEAR', CAST('2015-06-15' AS DATE))` actual `SELECT DATE_TRUNC(ISOYEAR, CAST('2015-06-15' AS DATE))`
- [bigquery -> duckdb] `SELECT FORMAT_DATETIME('%F %T', DATETIME '2023-10-15 14:30:45')` :: expected `SELECT STRFTIME(CAST('2023-10-15 14:30:45' AS TIMESTAMP), '%Y-%m-%d %H:%M:%S')` actual `SELECT STRFTIME(TS_OR_DS_TO_DATETIME(CAST('2023-10-1
- [bigquery -> duckdb] `SELECT FORMAT_DATETIME('%Y%m%d %H:%M:%S', DATETIME '2023-12-25 15:30:00')` :: expected `SELECT STRFTIME(CAST('2023-12-25 15:30:00' AS TIMESTAMP), '%Y%m%d %H:%M:%S')` actual `SELECT STRFTIME(TS_OR_DS_TO_DATETIME(CAST('2023-12-25 
- [bigquery -> duckdb] `SELECT FORMAT_DATETIME('%Y-%m-%e', DATETIME '2020-09-09 10:15:30')` :: expected `SELECT STRFTIME(CAST('2020-09-09 10:15:30' AS TIMESTAMP), '%Y-%m-%-d')` actual `SELECT STRFTIME(TS_OR_DS_TO_DATETIME(CAST('2020-09-09 10:15:
- [bigquery -> duckdb] `SELECT FORMAT_DATETIME('%c', DATETIME '2008-12-25 15:30:00')` :: expected `SELECT STRFTIME(CAST('2008-12-25 15:30:00' AS TIMESTAMP), '%a %b %-d %H:%M:%S %Y')` actual `SELECT STRFTIME(TS_OR_DS_TO_DATETIME(CAST('2008-
- [bigquery -> duckdb] `SELECT FORMAT_DATETIME('%x', '2023-12-25 15:30:00')` :: expected `SELECT STRFTIME(CAST('2023-12-25 15:30:00' AS TIMESTAMP), '%m/%d/%y')` actual `SELECT STRFTIME(TS_OR_DS_TO_DATETIME('2023-12-25 15:30:00'), 
- [bigquery -> duckdb] `SELECT FORMAT_TIMESTAMP("%b-%d-%Y", TIMESTAMP "2050-12-25 15:30:55+00")` :: expected `SELECT STRFTIME(CAST(CAST('2050-12-25 15:30:55+00' AS TIMESTAMPTZ) AS TIMESTAMP), '%b-%d-%Y')` actual `SELECT STRFTIME(TS_OR_DS_TO_TIMESTAMP
- [bigquery -> duckdb] `SELECT GENERATE_DATE_ARRAY('2016-10-05', '2016-10-08')` :: expected `SELECT CAST(GENERATE_SERIES(CAST('2016-10-05' AS DATE), CAST('2016-10-08' AS DATE), INTERVAL '1' DAY) AS DATE[])` actual `SELECT GENERATE_DA
- [bigquery -> duckdb] `SELECT GENERATE_DATE_ARRAY('2016-10-05', '2016-10-08', INTERVAL '1' MONTH)` :: expected `SELECT CAST(GENERATE_SERIES(CAST('2016-10-05' AS DATE), CAST('2016-10-08' AS DATE), INTERVAL '1' MONTH) AS DATE[])` actual `SELECT GENERATE_
- [bigquery -> duckdb] `SELECT GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-07 00:00:00', INTERVAL '1' DAY)` :: expected `SELECT GENERATE_SERIES(CAST('2016-10-05 00:00:00' AS TIMESTAMP), CAST('2016-10-07 00:00:00' AS TIMESTAMP), INTERVAL '1' DAY)` actual `SELECT
- [bigquery -> duckdb] `SELECT GENERATE_UUID()` :: expected `SELECT CAST(UUID() AS TEXT)` actual `SELECT UUID()`
- [bigquery -> duckdb] `SELECT GREATEST(1, NULL, 3)` :: expected `SELECT CASE WHEN 1 IS NULL OR NULL IS NULL OR 3 IS NULL THEN NULL ELSE GREATEST(1, NULL, 3) END` actual `SELECT GREATEST(1, NULL, 3)`
- [bigquery -> duckdb] `SELECT INSTR('foo@example.com', '@')` :: expected `SELECT STRPOS('foo@example.com', '@')` actual `SELECT STR_POSITION('foo@example.com', '@')`
- [bigquery -> duckdb] `SELECT INT64(JSON_QUERY(JSON '{"key": 2000}', '$.key'))` :: expected `SELECT CAST(JSON('{"key": 2000}') -> '$.key' AS BIGINT)` actual `SELECT INT64(JSON('{"key": 2000}') -> '$.key')`
- [bigquery -> duckdb] `SELECT JSON_VALUE_ARRAY('{"arr": [1, "a"]}', '$.arr')` :: expected `SELECT CAST('{"arr": [1, "a"]}' -> '$.arr' AS TEXT[])` actual `SELECT J_S_O_N_VALUE_ARRAY('{"arr": [1, "a"]}', '$.arr')`
- [bigquery -> duckdb] `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)` :: expected `SELECT LAST_DAY(CAST('2008-11-25' AS DATE))` actual `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)`
- [bigquery -> duckdb] `SELECT LAST_DAY(DATE '2008-11-10', ISOWEEK)` :: expected `SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((7 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)` actual `SELEC
- [bigquery -> duckdb] `SELECT LAST_DAY(DATE '2008-11-10', WEEK(MONDAY))` :: expected `SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((7 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)` actual `SELEC
- [bigquery -> duckdb] `SELECT LAST_DAY(DATE '2008-11-10', WEEK(SUNDAY))` :: expected `SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((13 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)` actual `SELE
- [bigquery -> duckdb] `SELECT LAST_DAY(DATE '2008-11-10', WEEK)` :: expected `SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((13 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)` actual `SELE
- [bigquery -> duckdb] `SELECT LEAST(1, NULL, 3)` :: expected `SELECT CASE WHEN 1 IS NULL OR NULL IS NULL OR 3 IS NULL THEN NULL ELSE LEAST(1, NULL, 3) END` actual `SELECT LEAST(1, NULL, 3)`
- [bigquery -> duckdb] `SELECT LENGTH(foo)` :: expected `SELECT CASE TYPEOF(foo) WHEN 'BLOB' THEN OCTET_LENGTH(CAST(foo AS BLOB)) ELSE LENGTH(CAST(foo AS TEXT)) END` actual `SELECT LENGTH(foo)`
- [bigquery -> duckdb] `SELECT PARSE_DATETIME('%F %T', '2023-01-15 14:30:00')` :: expected `SELECT STRPTIME('1970 ' || '2023-01-15 14:30:00', '%Y ' || '%Y-%m-%d %H:%M:%S')` actual `SELECT PARSE_DATETIME('2023-01-15 14:30:00', '%Y-%m
- [bigquery -> duckdb] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1) FROM "table"` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM "table"`
- [bigquery -> duckdb] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1, 1) FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1) FROM "table"` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1, 1) FROM "table"`
- [bigquery -> duckdb] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2) FROM table` :: expected `SELECT REGEXP_EXTRACT(NULLIF(SUBSTRING(abc, 2), ''), 'pattern(group)', 1) FROM "table"` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)',
- [bigquery -> duckdb] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2, 3) FROM table` :: expected `SELECT ARRAY_EXTRACT(REGEXP_EXTRACT_ALL(NULLIF(SUBSTRING(abc, 2), ''), 'pattern(group)', 1), 3) FROM "table"` actual `SELECT REGEXP_EXTRACT(
- [bigquery -> duckdb] `SELECT ROUND(NUMERIC '2.25', 1, 'ROUND_HALF_AWAY_FROM_ZERO') AS value` :: expected `SELECT ROUND(CAST('2.25' AS DECIMAL), 1) AS value` actual `SELECT ROUND(CAST('2.25' AS DECIMAL), 1, 'ROUND_HALF_AWAY_FROM_ZERO') AS value`
- [bigquery -> duckdb] `SELECT ROUND(NUMERIC '2.25', 1, 'ROUND_HALF_EVEN') AS value` :: expected `SELECT ROUND_EVEN(CAST('2.25' AS DECIMAL), 1) AS value` actual `SELECT ROUND(CAST('2.25' AS DECIMAL), 1, 'ROUND_HALF_EVEN') AS value`
- [bigquery -> duckdb] `SELECT STRPTIME('1970 ' || '15:30:00.123456', '%Y ' || '%H:%M:%S.%f')` :: expected `SELECT STRPTIME('1970 ' || '15:30:00.123456', '%Y ' || '%H:%M:%S.%f')` actual `SELECT PARSE_DATETIME('15:30:00.123456', '%H:%M:%S.%f', 1970)
- [bigquery -> duckdb] `SELECT STRPTIME('1970 ' || '2023-01-15 14:30:00', '%Y ' || '%Y-%m-%d %H:%M:%S')` :: expected `SELECT STRPTIME('1970 ' || '2023-01-15 14:30:00', '%Y ' || '%Y-%m-%d %H:%M:%S')` actual `SELECT PARSE_DATETIME('2023-01-15 14:30:00', '%Y-%m
- [bigquery -> duckdb] `SELECT STRPTIME('1970 ' || 'Thu Dec 25 07:30:00 2008', '%Y ' || '%a %b %-d %I:%M:%S %Y')` :: expected `SELECT STRPTIME('1970 ' || 'Thu Dec 25 07:30:00 2008', '%Y ' || '%a %b %-d %I:%M:%S %Y')` actual `SELECT PARSE_DATETIME('Thu Dec 25 07:30:00
- [bigquery -> duckdb] `SELECT TIME('2008-12-25 15:30:00')` :: expected `SELECT CAST('2008-12-25 15:30:00' AS TIME)` actual `SELECT TS_OR_DS_TO_TIME('2008-12-25 15:30:00')`
- [bigquery -> duckdb] `SELECT TIME(15, 30, 00)` :: expected `SELECT MAKE_TIME(15, 30, 00)` actual `SELECT TIME_FROM_PARTS(15, 30, 00)`
- [bigquery -> duckdb] `SELECT TIMESTAMP('2008-12-25 15:30:00', 'America/Los_Angeles')` :: expected `SELECT CAST('2008-12-25 15:30:00' AS TIMESTAMP) AT TIME ZONE 'America/Los_Angeles'` actual `SELECT TIMESTAMP('2008-12-25 15:30:00', 'America
- [bigquery -> duckdb] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00', WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC('WEEK', CAST('2008-11-10 14:30:00' AS TIMESTAMPTZ) + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELECT DATE_TRUNC(WEEK
- [bigquery -> duckdb] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00', WEEK)` :: expected `SELECT DATE_TRUNC('WEEK', CAST('2008-11-10 14:30:00' AS TIMESTAMPTZ) + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELECT DATE_TRUNC('WEE
- [bigquery -> duckdb] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00+00', WEEK(SUNDAY), 'America/New_York')` :: expected `SELECT (DATE_TRUNC('WEEK', CAST('2008-11-10 14:30:00+00' AS TIMESTAMPTZ) AT TIME ZONE 'America/New_York' + INTERVAL '1' DAY) + INTERVAL '-1'
- [bigquery -> duckdb] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00+00', WEEK, 'America/New_York')` :: expected `SELECT (DATE_TRUNC('WEEK', CAST('2008-11-10 14:30:00+00' AS TIMESTAMPTZ) AT TIME ZONE 'America/New_York' + INTERVAL '1' DAY) + INTERVAL '-1'
- [bigquery -> duckdb] `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC('WEEK', ts + INTERVAL '1' DAY) + INTERVAL '-1' DAY` actual `SELECT DATE_TRUNC(WEEK_START(SUNDAY), ts)`
- [bigquery -> duckdb] `SELECT TIME_DIFF('12:00:00', '12:30:00', MINUTE)` :: expected `SELECT DATE_DIFF('MINUTE', CAST('12:30:00' AS TIME), CAST('12:00:00' AS TIME))` actual `SELECT TIME_DIFF('12:00:00', '12:30:00', MINUTE)`
- [bigquery -> duckdb] `SELECT UNIX_DATE(DATE '2008-12-25')` :: expected `SELECT DATE_DIFF('DAY', CAST('1970-01-01' AS DATE), CAST('2008-12-25' AS DATE))` actual `SELECT UNIX_DATE(CAST('2008-12-25' AS DATE))`
- [bigquery -> duckdb] `SELECT UNIX_MICROS('2008-12-25 15:30:00+00')` :: expected `SELECT EPOCH_US(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))` actual `SELECT UNIX_MICROS('2008-12-25 15:30:00+00')`
- [bigquery -> duckdb] `SELECT UNIX_MICROS(TIMESTAMP '2008-12-25 15:30:00+00')` :: expected `SELECT EPOCH_US(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))` actual `SELECT UNIX_MICROS(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))`
- [bigquery -> duckdb] `SELECT UNIX_MILLIS('2008-12-25 15:30:00+00')` :: expected `SELECT EPOCH_MS(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))` actual `SELECT UNIX_MILLIS('2008-12-25 15:30:00+00')`
- [bigquery -> duckdb] `SELECT UNIX_MILLIS(TIMESTAMP '2008-12-25 15:30:00+00')` :: expected `SELECT EPOCH_MS(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))` actual `SELECT UNIX_MILLIS(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ))`
- [bigquery -> duckdb] `SELECT UNIX_SECONDS('2008-12-25 15:30:00+00')` :: expected `SELECT CAST(EPOCH(CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ)) AS BIGINT)` actual `SELECT UNIX_SECONDS('2008-12-25 15:30:00+00')`
- [bigquery -> duckdb] `SELECT b'a'` :: expected `SELECT CAST(e'a' AS BLOB)` actual `SELECT CAST('e''a''' AS BLOB)`
- [bigquery -> duckdb] `SELECT id, mnth + 1 AS a_mnth FROM t CROSS JOIN UNNEST(GENERATE_DATE_ARRAY(start_month, DATE_TRUNC(C` :: expected `SELECT id, mnth + 1 AS a_mnth FROM t CROSS JOIN UNNEST(CAST(GENERATE_SERIES(start_month, DATE_TRUNC('MONTH', CURRENT_DATE), INTERVAL '1' MON
- [bigquery -> duckdb] `SELECT id, mnth AS a_mnth FROM t CROSS JOIN UNNEST(GENERATE_DATE_ARRAY(start_month, DATE_TRUNC(CURRE` :: expected `SELECT id, mnth AS a_mnth FROM t CROSS JOIN UNNEST(CAST(GENERATE_SERIES(start_month, DATE_TRUNC('MONTH', CURRENT_DATE), INTERVAL '1' MONTH) 
- [bigquery -> duckdb] `SELECT id, mnth FROM t CROSS JOIN UNNEST(GENERATE_DATE_ARRAY(start_month, DATE_TRUNC(CURRENT_DATE, M` :: expected `SELECT id, mnth FROM t CROSS JOIN UNNEST(CAST(GENERATE_SERIES(start_month, DATE_TRUNC('MONTH', CURRENT_DATE), INTERVAL '1' MONTH) AS DATE[])
- [bigquery -> duckdb] `SELECT name, laps FROM UNNEST([STRUCT('Rudisha' AS name, [23.4, 26.3, 26.4, 26.1] AS laps), STRUCT('` :: expected `SELECT name, laps FROM (SELECT UNNEST([{'name': 'Rudisha', 'laps': [23.4, 26.3, 26.4, 26.1]}, {'name': 'Makhloufi', 'laps': [24.5, 25.4, 26.
- [bigquery -> duckdb] `SELECT participant FROM UNNEST([STRUCT('Rudisha' AS name, [23.4, 26.3, 26.4, 26.1] AS laps)]) AS par` :: expected `SELECT participant FROM (SELECT UNNEST([{'name': 'Rudisha', 'laps': [23.4, 26.3, 26.4, 26.1]}], max_depth => 2)) AS participant` actual `SEL
- [bigquery -> duckdb] `SELECT t.c1, h.c2, s.c3 FROM t1 AS t, UNNEST(t.t2) AS h, UNNEST(h.t3) AS s` :: expected `SELECT t.c1, h.c2, s.c3 FROM t1 AS t CROSS JOIN UNNEST(t.t2) AS _t0(h) CROSS JOIN UNNEST(h.t3) AS _t1(s)` actual `SELECT t.c1, h.c2, s.c3 FR
- [bigquery -> duckdb] `SELECT ts + MAKE_INTERVAL(1, 2, minute => 5, day => 3)` :: expected `SELECT ts + INTERVAL '1 year 2 month 5 minute 3 day'` actual `SELECT ts + MAKE_INTERVAL(1, 2, day => 3, minute => 5)`
- [bigquery -> duckdb] `STRING('2008-12-25 15:30:00', 'America/New_York')` :: expected `CAST(CAST('2008-12-25 15:30:00' AS TIMESTAMP) AT TIME ZONE 'UTC' AT TIME ZONE 'America/New_York' AS TEXT)` actual `STRING('2008-12-25 15:30:
- [bigquery -> duckdb] `STRING(a)` :: expected `CAST(a AS TEXT)` actual `STRING(a)`
- [bigquery -> duckdb] `TIMESTAMP(x)` :: expected `CAST(x AS TIMESTAMPTZ)` actual `TIMESTAMP(x)`
- [bigquery -> duckdb] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> duckdb] `WITH Races AS (SELECT '800M' AS race) SELECT race, name, laps FROM Races AS r CROSS JOIN UNNEST([STR` :: expected `WITH Races AS (SELECT '800M' AS race) SELECT race, name, laps FROM Races AS r CROSS JOIN (SELECT UNNEST([{'name': 'Rudisha', 'laps': [23.4, 
- [bigquery -> duckdb] `WITH Races AS (SELECT '800M' AS race) SELECT race, participant FROM Races AS r CROSS JOIN UNNEST([ST` :: expected `WITH Races AS (SELECT '800M' AS race) SELECT race, participant FROM Races AS r CROSS JOIN (SELECT UNNEST([{'name': 'Rudisha', 'laps': [23.4,
- [bigquery -> duckdb] `WITH sample AS (SELECT * FROM UNNEST([TIMESTAMP '2024-03-15 14:35:46', TIMESTAMP '2024-03-16 01:12:0` :: expected `WITH sample AS (SELECT * FROM UNNEST([CAST('2024-03-15 14:35:46' AS TIMESTAMPTZ), CAST('2024-03-16 01:12:03' AS TIMESTAMPTZ)]) AS _t0(ts)) S
- [bigquery -> duckdb] `WITH sample AS (SELECT * FROM UNNEST([TIMESTAMP '2024-03-15 14:35:46', TIMESTAMP '2024-03-16 01:12:0` :: expected `WITH sample AS (SELECT * FROM UNNEST([CAST('2024-03-15 14:35:46' AS TIMESTAMPTZ), CAST('2024-03-16 01:12:03' AS TIMESTAMPTZ)]) AS _t0(ts)) S
- [bigquery -> duckdb] `WITH sample AS (SELECT * FROM UNNEST([TIMESTAMP '2024-03-15 14:35:46', TIMESTAMP '2024-03-16 01:12:0` :: expected `WITH sample AS (SELECT * FROM UNNEST([CAST('2024-03-15 14:35:46' AS TIMESTAMPTZ), CAST('2024-03-16 01:12:03' AS TIMESTAMPTZ)]) AS _t0(ts)) S
- [bigquery -> duckdb] `WITH sample AS (SELECT ts FROM UNNEST([TIMESTAMP '2024-03-15 14:35:46', TIMESTAMP '2024-03-16 01:12:` :: expected `WITH sample AS (SELECT ts FROM UNNEST([CAST('2024-03-15 14:35:46' AS TIMESTAMPTZ), CAST('2024-03-16 01:12:03' AS TIMESTAMPTZ)]) AS _t0(ts)) 

## bigquery->bigquery  (50)

- [bigquery -> bigquery] `"""a
"""` :: expected `'a\n'` actual `'a '`
- [bigquery -> bigquery] `'\\'` :: expected `'\\'` actual `'\'`
- [bigquery -> bigquery] `EDIT_DISTANCE(a, b)` :: expected `EDIT_DISTANCE(a, b)` actual `LEVENSHTEIN(a, b)`
- [bigquery -> bigquery] `EDIT_DISTANCE(col1, col2, max_distance => 3)` :: expected `EDIT_DISTANCE(col1, col2, max_distance => 3)` actual `LEVENSHTEIN(col1, col2, 3)`
- [bigquery -> bigquery] `GENERATE_ARRAY(1, 4)` :: expected `GENERATE_ARRAY(1, 4)` actual `GENERATE_SERIES(1, 4)`
- [bigquery -> bigquery] `PARSE_TIMESTAMP('%Y-%m-%dT%H:%M:%E6S%z', x)` :: expected `PARSE_TIMESTAMP('%FT%H:%M:%E6S%z', x)` actual `PARSE_TIMESTAMP('%Y-%m-%dT%H:%M:%S.%f%z', x)`
- [bigquery -> bigquery] `SELECT '\n'` :: expected `SELECT '\n'` actual `SELECT ' '`
- [bigquery -> bigquery] `SELECT '\n'` :: expected `SELECT '\n'` actual `SELECT ' '`
- [bigquery -> bigquery] `SELECT * FROM UNNEST(ARRAY<STRUCT<device_id INT64, time DATETIME, signal INT64, state STRING>>[STRUC` :: expected `SELECT * FROM UNNEST(ARRAY<STRUCT<device_id INT64, time DATETIME, signal INT64, state STRING>>[STRUCT(1, CAST('2023-11-01 09:34:01' AS DATET
- [bigquery -> bigquery] `SELECT * FROM UNNEST(ARRAY<STRUCT<x INT64>>[])` :: expected `SELECT * FROM UNNEST(ARRAY<STRUCT<x INT64>>[])` actual `SELECT * FROM UNNEST(`ARRAY` < STRUCT<x INT64> > [])`
- [bigquery -> bigquery] `SELECT 0xA` :: expected `SELECT 0xA` actual `SELECT 10`
- [bigquery -> bigquery] `SELECT ARRAY<FLOAT64>[1, 2, 3]` :: expected `SELECT ARRAY<FLOAT64>[1, 2, 3]` actual `SELECT `ARRAY` < FLOAT64 > [1, 2, 3]`
- [bigquery -> bigquery] `SELECT DATETIME('2020-01-01', 'America/Los_Angeles')` :: expected `SELECT DATETIME('2020-01-01', 'America/Los_Angeles')` actual `SELECT DATETIME('2020-01-01')`
- [bigquery -> bigquery] `SELECT DATETIME('2020-01-01', TIME '23:59:59')` :: expected `SELECT DATETIME('2020-01-01', CAST('23:59:59' AS TIME))` actual `SELECT DATETIME('2020-01-01')`
- [bigquery -> bigquery] `SELECT DATETIME_TRUNC(DATETIME '2008-11-10 14:30:00', WEEK(SUNDAY))` :: expected `SELECT DATETIME_TRUNC(CAST('2008-11-10 14:30:00' AS DATETIME), WEEK)` actual `SELECT DATETIME_TRUNC(CAST('2008-11-10 14:30:00' AS DATETIME),
- [bigquery -> bigquery] `SELECT DATETIME_TRUNC(dt, WEEK(SUNDAY))` :: expected `SELECT DATETIME_TRUNC(dt, WEEK)` actual `SELECT DATETIME_TRUNC(dt, WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT DATE_DIFF('2026-01-15', '2024-01-08', WEEK(SUNDAY))` :: expected `SELECT DATE_DIFF('2026-01-15', '2024-01-08', WEEK)` actual `SELECT DATE_DIFF('2026-01-15', '2024-01-08', WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT DATE_DIFF(DATE '2024-01-01', DATE '2024-01-15', WEEK(SUNDAY))` :: expected `SELECT DATE_DIFF(CAST('2024-01-01' AS DATE), CAST('2024-01-15' AS DATE), WEEK)` actual `SELECT DATE_DIFF(CAST('2024-01-01' AS DATE), CAST('2
- [bigquery -> bigquery] `SELECT DATE_DIFF(d1, d2, WEEK(SUNDAY))` :: expected `SELECT DATE_DIFF(d1, d2, WEEK)` actual `SELECT DATE_DIFF(d1, d2, WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT DATE_TRUNC(DATE '2008-11-10', WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC(CAST('2008-11-10' AS DATE), WEEK)` actual `SELECT DATE_TRUNC(CAST('2008-11-10' AS DATE), WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT DATE_TRUNC(d, WEEK(SUNDAY))` :: expected `SELECT DATE_TRUNC(d, WEEK)` actual `SELECT DATE_TRUNC(d, WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT FORMAT_DATETIME('%F %T', DATETIME '2023-10-15 14:30:45')` :: expected `SELECT FORMAT_DATETIME('%F %T', CAST('2023-10-15 14:30:45' AS DATETIME))` actual `SELECT FORMAT_DATETIME('%Y-%m-%d %H:%M:%S', CAST('2023-10-
- [bigquery -> bigquery] `SELECT FORMAT_DATETIME('%Y%m%d %H:%M:%S', DATETIME '2023-12-25 15:30:00')` :: expected `SELECT FORMAT_DATETIME('%Y%m%d %T', CAST('2023-12-25 15:30:00' AS DATETIME))` actual `SELECT FORMAT_DATETIME('%Y%m%d %H:%M:%S', CAST('2023-1
- [bigquery -> bigquery] `SELECT FORMAT_DATETIME('%Y-%m-%e', DATETIME '2020-09-09 10:15:30')` :: expected `SELECT FORMAT_DATETIME('%Y-%m-%e', CAST('2020-09-09 10:15:30' AS DATETIME))` actual `SELECT FORMAT_DATETIME('%Y-%m-%-d', CAST('2020-09-09 10
- [bigquery -> bigquery] `SELECT FORMAT_DATETIME('%c', DATETIME '2008-12-25 15:30:00')` :: expected `SELECT FORMAT_DATETIME('%c', CAST('2008-12-25 15:30:00' AS DATETIME))` actual `SELECT FORMAT_DATETIME('%a %b %e %H:%M:%S %Y', CAST('2008-12-
- [bigquery -> bigquery] `SELECT FORMAT_DATETIME('%x', '2023-12-25 15:30:00')` :: expected `SELECT FORMAT_DATETIME('%D', '2023-12-25 15:30:00')` actual `SELECT FORMAT_DATETIME('%m/%d/%y', '2023-12-25 15:30:00')`
- [bigquery -> bigquery] `SELECT INSTR('foo@example.com', '@')` :: expected `SELECT INSTR('foo@example.com', '@')` actual `SELECT STR_POSITION('foo@example.com', '@')`
- [bigquery -> bigquery] `SELECT INT64(JSON_QUERY(JSON '{"key": 2000}', '$.key'))` :: expected `SELECT INT64(JSON_QUERY(PARSE_JSON('{"key": 2000}'), '$.key'))` actual `SELECT INT64(JSON_EXTRACT(PARSE_JSON('{"key": 2000}'), '$.key'))`
- [bigquery -> bigquery] `SELECT JSON_QUERY('{"class": {"students": []}}', '$.class')` :: expected `SELECT JSON_QUERY('{"class": {"students": []}}', '$.class')` actual `SELECT JSON_EXTRACT('{"class": {"students": []}}', '$.class')`
- [bigquery -> bigquery] `SELECT JSON_QUERY(foo, '$.class')` :: expected `SELECT JSON_QUERY(foo, '$.class')` actual `SELECT JSON_EXTRACT(foo, '$.class')`
- [bigquery -> bigquery] `SELECT LAST_DAY(DATE '2008-11-10', WEEK(SUNDAY))` :: expected `SELECT LAST_DAY(CAST('2008-11-10' AS DATE), WEEK)` actual `SELECT LAST_DAY(CAST('2008-11-10' AS DATE), WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT LAST_DAY(d, WEEK(SUNDAY))` :: expected `SELECT LAST_DAY(d, WEEK)` actual `SELECT LAST_DAY(d, WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT MAX_BY(name, score) FROM table1` :: expected `SELECT MAX_BY(name, score) FROM table1` actual `SELECT ARG_MAX(name, score) FROM table1`
- [bigquery -> bigquery] `SELECT MIN_BY(product, price) FROM table1` :: expected `SELECT MIN_BY(product, price) FROM table1` actual `SELECT ARG_MIN(product, price) FROM table1`
- [bigquery -> bigquery] `SELECT PARSE_DATE('%A %b %e %Y', 'Thursday Dec 25 2008')` :: expected `SELECT PARSE_DATE('%A %b %e %Y', 'Thursday Dec 25 2008')` actual `SELECT PARSE_DATE('%A %b %-d %Y', 'Thursday Dec 25 2008')`
- [bigquery -> bigquery] `SELECT PARSE_TIMESTAMP('%m-%d %H:%M:%S', '12-25 07:30:00')` :: expected `SELECT PARSE_TIMESTAMP('%m-%d %T', '12-25 07:30:00')` actual `SELECT PARSE_TIMESTAMP('%m-%d %H:%M:%S', '12-25 07:30:00')`
- [bigquery -> bigquery] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1) FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1) FROM table` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM table`
- [bigquery -> bigquery] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1, 1) FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 1, 1) FROM table` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM table`
- [bigquery -> bigquery] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2) FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2) FROM table` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM table`
- [bigquery -> bigquery] `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2, 3) FROM table` :: expected `SELECT REGEXP_EXTRACT(abc, 'pattern(group)', 2, 3) FROM table` actual `SELECT REGEXP_EXTRACT(abc, 'pattern(group)') FROM table`
- [bigquery -> bigquery] `SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), minute)` :: expected `SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), MINUTE)` actual `SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_S
- [bigquery -> bigquery] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00', WEEK(SUNDAY))` :: expected `SELECT TIMESTAMP_TRUNC(CAST('2008-11-10 14:30:00' AS TIMESTAMP), WEEK)` actual `SELECT TIMESTAMP_TRUNC(CAST('2008-11-10 14:30:00' AS TIMESTA
- [bigquery -> bigquery] `SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00+00', WEEK(SUNDAY), 'America/New_York')` :: expected `SELECT TIMESTAMP_TRUNC(CAST('2008-11-10 14:30:00+00' AS TIMESTAMP), WEEK, 'America/New_York')` actual `SELECT TIMESTAMP_TRUNC(CAST('2008-11-
- [bigquery -> bigquery] `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))` :: expected `SELECT TIMESTAMP_TRUNC(ts, WEEK)` actual `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))`
- [bigquery -> bigquery] `SELECT results FROM Coordinates, Coordinates.position AS results` :: expected `SELECT results FROM Coordinates CROSS JOIN UNNEST(Coordinates.position) AS results` actual `SELECT results FROM Coordinates CROSS JOIN Coord
- [bigquery -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `S_H_A2_DIGEST(x, 256)`
- [bigquery -> bigquery] `SHA512(x)` :: expected `SHA512(x)` actual `S_H_A2_DIGEST(x, 512)`
- [bigquery -> bigquery] `STRING('2008-12-25 15:30:00', 'America/New_York')` :: expected `STRING('2008-12-25 15:30:00', 'America/New_York')` actual `STRING('2008-12-25 15:30:00')`
- [bigquery -> bigquery] `TIMESTAMP_DIFF(a, b, MONTH)` :: expected `TIMESTAMP_DIFF(a, b, MONTH)` actual `TIMESTAMP_DIFF(a, b)`
- [bigquery -> bigquery] `r"""a
"""` :: expected `'a\n'` actual `'a '`

## datafusion  (35)

- [datafusion] `identity|SELECT * FROM t ORDER BY x NULLS FIRST` :: expected `SELECT * FROM t ORDER BY x NULLS FIRST` actual `SELECT * FROM t ORDER BY x`
- [datafusion] `identity|SELECT * FROM t QUALIFY RANK() OVER (ORDER BY x) <= 3` :: expected `SELECT * FROM t QUALIFY RANK() OVER (ORDER BY x) <= 3` actual `SELECT * FROM t QUALIFY rank() OVER (ORDER BY x) <= 3`
- [datafusion] `identity|SELECT * FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY x ORDER BY y) = 1` :: expected `SELECT * FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY x ORDER BY y) = 1` actual `SELECT * FROM t QUALIFY row_number() OVER (PARTITION BY x
- [datafusion] `identity|SELECT * FROM t WHERE x IS NOT NULL` :: expected `SELECT * FROM t WHERE x IS NOT NULL` actual `SELECT * FROM t WHERE NOT x IS NULL`
- [datafusion] `identity|SELECT * FROM t WHERE x NOT BETWEEN 1 AND 10` :: expected `SELECT * FROM t WHERE x NOT BETWEEN 1 AND 10` actual `SELECT * FROM t WHERE NOT x BETWEEN 1 AND 10`
- [datafusion] `identity|SELECT * FROM t WHERE x NOT IN (1, 2, 3)` :: expected `SELECT * FROM t WHERE x NOT IN (1, 2, 3)` actual `SELECT * FROM t WHERE NOT x IN (1, 2, 3)`
- [datafusion] `identity|SELECT * FROM unnest(ARRAY[1, 2, 3]) AS t(x)` :: expected `SELECT * FROM unnest(ARRAY[1, 2, 3]) AS t(x)` actual `SELECT * FROM UNNEST(array(1, 2, 3)) AS t(x)`
- [datafusion] `identity|SELECT ALL x FROM t` :: expected `SELECT ALL x FROM t` actual `SELECT x FROM t`
- [datafusion] `identity|SELECT CAST(x AS BYTEA) FROM t` :: expected `SELECT CAST(x AS BYTEA) FROM t` actual `SELECT CAST(x AS VARBINARY) FROM t`
- [datafusion] `identity|SELECT CAST(x AS INTEGER) FROM t` :: expected `SELECT CAST(x AS INTEGER) FROM t` actual `SELECT CAST(x AS INT) FROM t`
- [datafusion] `identity|SELECT CAST(x AS NUMERIC(10, 2)) FROM t` :: expected `SELECT CAST(x AS NUMERIC(10, 2)) FROM t` actual `SELECT CAST(x AS DECIMAL(10, 2)) FROM t`
- [datafusion] `identity|SELECT DENSE_RANK() OVER (ORDER BY x) FROM t` :: expected `SELECT DENSE_RANK() OVER (ORDER BY x) FROM t` actual `SELECT dense_rank() OVER (ORDER BY x) FROM t`
- [datafusion] `identity|SELECT FIRST_VALUE(x) OVER (ORDER BY y) FROM t` :: expected `SELECT FIRST_VALUE(x) OVER (ORDER BY y) FROM t` actual `SELECT first_value(x) OVER (ORDER BY y) FROM t`
- [datafusion] `identity|SELECT LAG(x, 1) OVER (ORDER BY y) FROM t` :: expected `SELECT LAG(x, 1) OVER (ORDER BY y) FROM t` actual `SELECT lag(x, 1) OVER (ORDER BY y) FROM t`
- [datafusion] `identity|SELECT LAST_VALUE(x) OVER (ORDER BY y) FROM t` :: expected `SELECT LAST_VALUE(x) OVER (ORDER BY y) FROM t` actual `SELECT last_value(x) OVER (ORDER BY y) FROM t`
- [datafusion] `identity|SELECT LEAD(x, 1) OVER (ORDER BY y) FROM t` :: expected `SELECT LEAD(x, 1) OVER (ORDER BY y) FROM t` actual `SELECT lead(x, 1) OVER (ORDER BY y) FROM t`
- [datafusion] `identity|SELECT NTH_VALUE(x, 3) OVER (ORDER BY y) FROM t` :: expected `SELECT NTH_VALUE(x, 3) OVER (ORDER BY y) FROM t` actual `SELECT nth_value(x, 3) OVER (ORDER BY y) FROM t`
- [datafusion] `identity|SELECT NTILE(4) OVER (ORDER BY x) FROM t` :: expected `SELECT NTILE(4) OVER (ORDER BY x) FROM t` actual `SELECT ntile(4) OVER (ORDER BY x) FROM t`
- [datafusion] `identity|SELECT RANK() OVER (ORDER BY x) FROM t` :: expected `SELECT RANK() OVER (ORDER BY x) FROM t` actual `SELECT rank() OVER (ORDER BY x) FROM t`
- [datafusion] `identity|SELECT ROW_NUMBER() OVER (ORDER BY x) FROM t` :: expected `SELECT ROW_NUMBER() OVER (ORDER BY x) FROM t` actual `SELECT row_number() OVER (ORDER BY x) FROM t`
- [datafusion] `identity|SELECT array_has(arr, 1) FROM t` :: expected `SELECT array_has(arr, 1) FROM t` actual `SELECT array_contains(arr, 1) FROM t`
- [datafusion] `identity|SELECT bool_and(x) FROM t` :: expected `SELECT bool_and(x) FROM t` actual `SELECT logical_and(x) FROM t`
- [datafusion] `identity|SELECT bool_or(x) FROM t` :: expected `SELECT bool_or(x) FROM t` actual `SELECT logical_or(x) FROM t`
- [datafusion] `identity|SELECT char_length(s) FROM t` :: expected `SELECT char_length(s) FROM t` actual `SELECT length(s) FROM t`
- [datafusion] `identity|SELECT current_date()` :: expected `SELECT current_date()` actual `SELECT CURRENT_DATE`
- [datafusion] `identity|SELECT date_trunc('month', ts) FROM t` :: expected `SELECT date_trunc('month', ts) FROM t` actual `SELECT date_trunc('MONTH', ts) FROM t`
- [datafusion] `identity|SELECT log10(x) FROM t` :: expected `SELECT log10(x) FROM t` actual `SELECT log(10, x) FROM t`
- [datafusion] `identity|SELECT log2(x) FROM t` :: expected `SELECT log2(x) FROM t` actual `SELECT log(2, x) FROM t`
- [datafusion] `identity|SELECT random()` :: expected `SELECT random()` actual `SELECT rand()`
- [datafusion] `identity|SELECT strpos(s, 'sub') FROM t` :: expected `SELECT strpos(s, 'sub') FROM t` actual `SELECT str_position(s, 'sub') FROM t`
- [datafusion] `identity|SELECT substr(s, 1, 5) FROM t` :: expected `SELECT substr(s, 1, 5) FROM t` actual `SELECT substring(s, 1, 5) FROM t`
- [datafusion] `identity|SELECT x != 1 FROM t` :: expected `SELECT x != 1 FROM t` actual `SELECT x <> 1 FROM t`
- [datafusion] `identity|SELECT x ~* 'pattern' FROM t` :: expected `SELECT x ~* 'pattern' FROM t` actual `SELECT regexp_i_like(x, 'pattern') FROM t`
- [datafusion] `identity|SELECT x::INT FROM t` :: expected `SELECT x::INT FROM t` actual `SELECT CAST(x AS INT) FROM t`
- [datafusion] `identity|SELECT x::VARCHAR FROM t` :: expected `SELECT x::VARCHAR FROM t` actual `SELECT CAST(x AS VARCHAR) FROM t`

## bigquery->spark  (16)

- [bigquery -> spark] `EDIT_DISTANCE(col1, col2, max_distance => 3)` :: expected UnsupportedError, got `LEVENSHTEIN(col1, col2, 3)`
- [bigquery -> spark] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> spark] `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')` :: expected `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')` actual `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]', 0)`
- [bigquery -> spark] `SELECT * FROM UNNEST(['7', '14']) AS x` :: expected `SELECT * FROM EXPLODE(ARRAY('7', '14')) AS _t0(x)` actual `SELECT * FROM EXPLODE(ARRAY('7', '14')) AS x`
- [bigquery -> spark] `SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS sales FOR quarter IN ('Q1' AS Q1, 'Q2' AS Q1))` :: expected `SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS sales FOR quarter IN ('Q1' AS Q1, 'Q2' AS Q1))` actual `SELECT * FROM produce AS p PIVOT(SU
- [bigquery -> spark] `SELECT DATETIME_ADD('2023-01-01T00:00:00', INTERVAL 1 MILLISECOND)` :: expected `SELECT '2023-01-01T00:00:00' + INTERVAL '1' MILLISECOND` actual `SELECT DATETIME_ADD('2023-01-01T00:00:00', '1', 'MILLISECOND')`
- [bigquery -> spark] `SELECT DATETIME_SUB('2023-01-01T00:00:00', INTERVAL 1 MILLISECOND)` :: expected `SELECT '2023-01-01T00:00:00' - INTERVAL '1' MILLISECOND` actual `SELECT DATETIME_SUB('2023-01-01T00:00:00', '1', 'MILLISECOND')`
- [bigquery -> spark] `SELECT DATE_TRUNC(d, WEEK(SUNDAY))` :: expected UnsupportedError, got `SELECT TRUNC(d, WEEK_START(SUNDAY))`
- [bigquery -> spark] `SELECT EXTRACT(WEEK(THURSDAY) FROM d)` :: expected UnsupportedError, got `SELECT EXTRACT(THURSDAY FROM d)`
- [bigquery -> spark] `SELECT GENERATE_UUID()` :: expected `SELECT CAST(UUID() AS STRING)` actual `SELECT UUID()`
- [bigquery -> spark] `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)` :: expected `SELECT LAST_DAY(CAST('2008-11-25' AS DATE))` actual `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)`
- [bigquery -> spark] `SELECT TIME('2008-12-25 15:30:00')` :: expected `SELECT CAST('2008-12-25 15:30:00' AS TIMESTAMP)` actual `SELECT TS_OR_DS_TO_TIME('2008-12-25 15:30:00')`
- [bigquery -> spark] `SELECT TIMESTAMP_ADD(TIMESTAMP "2008-12-25 15:30:00+00", INTERVAL 10 MINUTE)` :: expected `SELECT DATE_ADD(MINUTE, '10', CAST('2008-12-25 15:30:00+00' AS TIMESTAMP))` actual `SELECT TIMESTAMP_ADD(CAST('2008-12-25 15:30:00+00' AS TI
- [bigquery -> spark] `SELECT TIMESTAMP_SUB(TIMESTAMP "2008-12-25 15:30:00+00", INTERVAL 10 MINUTE)` :: expected `SELECT CAST('2008-12-25 15:30:00+00' AS TIMESTAMP) - INTERVAL '10' MINUTE` actual `SELECT TIMESTAMP_SUB(CAST('2008-12-25 15:30:00+00' AS TIM
- [bigquery -> spark] `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))` :: expected UnsupportedError, got `SELECT DATE_TRUNC(WEEK_START(SUNDAY), ts)`
- [bigquery -> spark] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`

## spark->duckdb  (18)

- [spark -> duckdb] `
            WITH hourlycostagg AS (
                SELECT
                    101 AS id,
         ` :: expected `WITH hourlycostagg AS (SELECT 101 AS id, [{'amount': 10.0, 'currency': 'USD'}, {'amount': 20.0, 'currency': 'EUR'}] AS costs, [{'type': 'tax
- [spark -> duckdb] `CONCAT_WS(' ', NULL, 'Smith')` :: expected `CONCAT_WS(' ', NULL, 'Smith')` actual `CASE WHEN ' ' IS NULL OR NULL IS NULL OR 'Smith' IS NULL THEN NULL ELSE CONCAT_WS(' ', NULL, 'Smith')
- [spark -> duckdb] `SELECT * FROM POSEXPLODE(ARRAY('a')) AS (a, b)` :: expected `SELECT * FROM (SELECT GENERATE_SUBSCRIPTS(['a'], 1) - 1 AS a, UNNEST(['a']) AS b)` actual `SELECT * FROM POSEXPLODE(['a']) AS _t0(a, b)`
- [spark -> duckdb] `SELECT * FROM POSEXPLODE(ARRAY('a'))` :: expected `SELECT * FROM (SELECT GENERATE_SUBSCRIPTS(['a'], 1) - 1 AS pos, UNNEST(['a']) AS col)` actual `SELECT * FROM POSEXPLODE(['a'])`
- [spark -> duckdb] `SELECT ARRAY_AGG(DISTINCT STRUCT('a'))` :: expected `SELECT ARRAY_AGG(DISTINCT {'col1': 'a'})` actual `SELECT ARRAY_AGG(DISTINCT {'_0': 'a'})`
- [spark -> duckdb] `SELECT ARRAY_AGG(x) FILTER (WHERE x = 5) FROM (SELECT 1 UNION ALL SELECT NULL) AS t(x)` :: expected `SELECT ARRAY_AGG(x) FILTER(WHERE x = 5 AND NOT x IS NULL) FROM (SELECT 1 UNION ALL SELECT NULL) AS t(x)` actual `SELECT ARRAY_AGG(x) FILTER(
- [spark -> duckdb] `SELECT LIST(DISTINCT sample_col) FILTER(WHERE NOT sample_col IS NULL) FROM sample_table` :: expected `SELECT LIST(DISTINCT sample_col) FILTER(WHERE NOT sample_col IS NULL) FROM sample_table` actual `SELECT ARRAY_UNIQUE_AGG(sample_col) FROM sa
- [spark -> duckdb] `SELECT MONTHS_BETWEEN('1997-02-28 10:30:00', '1996-10-30')` :: expected `SELECT DATE_DIFF('MONTH', CAST('1996-10-30' AS DATE), CAST('1997-02-28 10:30:00' AS DATE)) + CASE WHEN DAY(CAST('1997-02-28 10:30:00' AS DAT
- [spark -> duckdb] `SELECT MONTHS_BETWEEN('1997-02-28 10:30:00', '1996-10-30', FALSE)` :: expected `SELECT DATE_DIFF('MONTH', CAST('1996-10-30' AS DATE), CAST('1997-02-28 10:30:00' AS DATE)) + CASE WHEN DAY(CAST('1997-02-28 10:30:00' AS DAT
- [spark -> duckdb] `SELECT POSEXPLODE(ARRAY('a'))` :: expected `SELECT GENERATE_SUBSCRIPTS(['a'], 1) - 1 AS pos, UNNEST(['a']) AS col` actual `SELECT POSEXPLODE(['a'])`
- [spark -> duckdb] `SELECT POSEXPLODE(x) AS (a, b)` :: expected `SELECT GENERATE_SUBSCRIPTS(x, 1) - 1 AS a, UNNEST(x) AS b` actual `SELECT POSEXPLODE(x) AS (a, b)`
- [spark -> duckdb] `SELECT STRUCT(1, 2)` :: expected `SELECT {'col1': 1, 'col2': 2}` actual `SELECT {'_0': 1, '_1': 2}`
- [spark -> duckdb] `SELECT STRUCT(x, 1, y AS col3, STRUCT(5)) FROM t` :: expected `SELECT {'x': x, 'col2': 1, 'col3': y, 'col4': {'col1': 5}} FROM t` actual `SELECT {'x': x, '_1': 1, 'col3': y, '_3': {'_0': 5}} FROM t`
- [spark -> duckdb] `SELECT TRY_DIVIDE(a, b)` :: expected `SELECT CASE WHEN b <> 0 THEN a / b ELSE NULL END` actual `SELECT SAFE_DIVIDE(a, b)`
- [spark -> duckdb] `SELECT h.id, amount FROM hourlycostagg h LATERAL VIEW inline(h.adjustments) as type, val, curr` :: expected `SELECT h.id, amount FROM hourlycostagg AS h CROSS JOIN LATERAL (SELECT UNNEST(h.adjustments, max_depth => 2)) AS _u_0(type, val, curr)` actu
- [spark -> duckdb] `SELECT h.id, amount FROM hourlycostagg h LATERAL VIEW inline(h.costs) c` :: expected `SELECT h.id, amount FROM hourlycostagg AS h CROSS JOIN LATERAL (SELECT UNNEST(h.costs, max_depth => 2)) AS c` actual `SELECT h.id, amount FR
- [spark -> duckdb] `SELECT id_column, name, age FROM test_table LATERAL VIEW INLINE(struc_column) explode_view AS name, ` :: expected `SELECT id_column, name, age FROM test_table CROSS JOIN LATERAL (SELECT UNNEST(struc_column, max_depth => 2)) AS explode_view(name, age)` act
- [spark -> duckdb] `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUN` :: expected `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUNT(DISTINCT CASE WHEN id IS NULL THEN NUL

## bigquery->presto  (15)

- [bigquery -> presto] `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')` :: expected `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]', 1)` actual `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')`
- [bigquery -> presto] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `IF((2 * y) <> 0, CAST((x + 1) AS DOUBLE) / (2 * y), NULL)` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> presto] `SAFE_DIVIDE(x, y)` :: expected `IF(y <> 0, CAST(x AS DOUBLE) / y, NULL)` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> presto] `SELECT * FROM UNNEST(['7', '14']) AS x` :: expected `SELECT * FROM UNNEST(ARRAY['7', '14']) AS _t0(x)` actual `SELECT * FROM UNNEST(ARRAY['7', '14']) AS x`
- [bigquery -> presto] `SELECT * FROM UNNEST([STRUCT('Alice' AS name, 85 AS score), STRUCT('Bob', 92), STRUCT('Diana', 95)])` :: expected `SELECT * FROM UNNEST(ARRAY[CAST(ROW('Alice', 85) AS ROW(name VARCHAR, score INTEGER)), CAST(ROW('Bob', 92) AS ROW(name VARCHAR, score INTEGE
- [bigquery -> presto] `SELECT DATETIME_DIFF(DATETIME '2017-10-15 00:00:00', DATETIME '2017-10-14 00:00:00', WEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2017-10-14 00:00:00' AS TIMESTAMP) + INTERVAL '1' DAY), DATE_TRUNC('WEEK', CAST('2017-10-1
- [bigquery -> presto] `SELECT DATETIME_DIFF(DATETIME '2021-02-01 00:00:00', DATETIME '2021-01-31 00:00:00', MONTH)` :: expected `SELECT DATE_DIFF('MONTH', DATE_TRUNC('MONTH', CAST('2021-01-31 00:00:00' AS TIMESTAMP)), DATE_TRUNC('MONTH', CAST('2021-02-01 00:00:00' AS T
- [bigquery -> presto] `SELECT GENERATE_UUID()` :: expected `SELECT CAST(UUID() AS VARCHAR)` actual `SELECT UUID()`
- [bigquery -> presto] `SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), minute)` :: expected `SELECT DATE_DIFF('MINUTE', FROM_UNIXTIME(0), FROM_UNIXTIME(60))` actual `SELECT TIMESTAMPDIFF(FROM_UNIXTIME(60), FROM_UNIXTIME(0), MINUTE)`
- [bigquery -> presto] `SELECT purchases, LAST_VALUE(item) OVER item_window AS most_popular FROM Produce WINDOW item_window ` :: expected `SELECT purchases, LAST_VALUE(item) OVER (PARTITION BY purchases ORDER BY purchases NULLS FIRST ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING) AS 
- [bigquery -> presto] `SELECT results FROM Coordinates AS c, UNNEST(c.position) AS results` :: expected `SELECT results FROM Coordinates AS c CROSS JOIN UNNEST(c.position) AS _t0(results)` actual `SELECT results FROM Coordinates AS c CROSS JOIN 
- [bigquery -> presto] `SELECT results FROM Coordinates, Coordinates.position AS results` :: expected `SELECT results FROM Coordinates CROSS JOIN UNNEST(Coordinates.position) AS _t0(results)` actual `SELECT results FROM Coordinates CROSS JOIN 
- [bigquery -> presto] `SELECT results FROM Coordinates, `Coordinates.position` AS results` :: expected `SELECT results FROM Coordinates CROSS JOIN "Coordinates"."position" AS results` actual `SELECT results FROM Coordinates CROSS JOIN "Coordina
- [bigquery -> presto] `TIMESTAMP(x)` :: expected `CAST(x AS TIMESTAMP WITH TIME ZONE)` actual `CAST(x AS TIMESTAMP)`
- [bigquery -> presto] `TIMESTAMP_DIFF(a, b, MONTH)` :: expected `DATE_DIFF('MONTH', b, a)` actual `TIMESTAMPDIFF(a, b, MONTH)`

## spark->bigquery  (15)

- [spark -> bigquery] `SELECT * FROM UNNEST(['7', '14']) AS x` :: expected `SELECT * FROM UNNEST(['7', '14']) AS x` actual `SELECT * FROM UNNEST(['7', '14']) AS _t0`
- [spark -> bigquery] `SELECT DATE_ADD(my_date_column, 1)` :: expected `SELECT DATE_ADD(CAST(CAST(my_date_column AS DATETIME) AS DATE), INTERVAL 1 DAY)` actual `SELECT TS_OR_DS_ADD(my_date_column, 1, DAY)`
- [spark -> bigquery] `SELECT EXPLODE(ARRAY(1, 2))` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH([1, 2])) - 1)) AS pos CROSS JOIN UNNEST([1, 2])
- [spark -> bigquery] `SELECT EXPLODE(col) FROM _u` :: expected `SELECT IF(pos = pos_2, col_2, NULL) AS col_2 FROM _u CROSS JOIN UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(col)) - 1)) AS pos CROSS JOIN
- [spark -> bigquery] `SELECT EXPLODE(x) FROM tbl` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col FROM tbl CROSS JOIN UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(x)) - 1)) AS pos CROSS JOIN UNNE
- [spark -> bigquery] `SELECT IF(pos = pos_2, col, NULL) AS col FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(IF(ARRA` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(IF(ARRAY_LENGTH(COALESCE([], [])) = 0, [[][SAFE
- [spark -> bigquery] `SELECT IF(pos = pos_2, col, NULL) AS col, IF(pos = pos_2, pos_2, NULL) AS pos_2 FROM UNNEST(GENERATE` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col, IF(pos = pos_2, pos_2, NULL) AS pos_2 FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(IF(ARRAY
- [spark -> bigquery] `SELECT POSEXPLODE(ARRAY(2, 3)) AS x` :: expected `SELECT IF(pos = pos_2, x, NULL) AS x, IF(pos = pos_2, pos_2, NULL) AS pos_2 FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH([2, 3])) - 1
- [spark -> bigquery] `SELECT POSEXPLODE(ARRAY(2, 3)), EXPLODE(ARRAY(4, 5, 6)) FROM tbl` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col, IF(pos = pos_2, pos_2, NULL) AS pos_2, IF(pos = pos_3, col_2, NULL) AS col_2 FROM tbl CROSS JOIN U
- [spark -> bigquery] `SELECT REPEAT(' ', 2)` :: expected `SELECT REPEAT(' ', 2)` actual `SELECT SPACE(2)`
- [spark -> bigquery] `SELECT TO_DATE(x, 'MM/dd/yyyy')` :: expected `SELECT CAST(SAFE_CAST(x AS TIMESTAMP FORMAT 'MM/DD/YYYY') AS DATE)` actual `SELECT CAST(PARSE_TIMESTAMP('%m/%d/%Y', x) AS DATE)`
- [spark -> bigquery] `SELECT TO_UTC_TIMESTAMP('2016-08-31', 'Asia/Seoul')` :: expected `SELECT DATETIME(TIMESTAMP(CAST('2016-08-31' AS DATETIME), 'Asia/Seoul'), 'UTC')` actual `SELECT CAST('2016-08-31' AS DATETIME) AT TIME ZONE 
- [spark -> bigquery] `SELECT cola, colb FROM UNNEST([STRUCT(1 AS cola, 'test' AS colb)]) AS tab` :: expected `SELECT cola, colb FROM UNNEST([STRUCT(1 AS cola, 'test' AS colb)]) AS tab` actual `SELECT cola, colb FROM (VALUES (1, 'test')) AS tab`
- [spark -> bigquery] `WITH cte AS (SELECT 1 AS foo) SELECT foo FROM cte` :: expected `WITH cte AS (SELECT 1 AS foo) SELECT foo FROM cte` actual `WITH cte AS (SELECT 1 AS bar) SELECT foo FROM cte`
- [spark -> bigquery] `WITH cte AS (SELECT [1, 2, 3] AS arr) SELECT IF(pos = pos_2, col, NULL) AS col FROM cte CROSS JOIN U` :: expected `WITH cte AS (SELECT [1, 2, 3] AS arr) SELECT IF(pos = pos_2, col, NULL) AS col FROM cte CROSS JOIN UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_L

## postgres->bigquery  (12)

- [postgres -> bigquery] `SELECT * FROM UNNEST([STRUCT(1 AS _c0)]) AS t1` :: expected `SELECT * FROM UNNEST([STRUCT(1 AS _c0)]) AS t1` actual `SELECT * FROM (VALUES (1)) AS t1`
- [postgres -> bigquery] `SELECT * FROM UNNEST([STRUCT(1 AS id)]) AS t1 CROSS JOIN UNNEST([STRUCT(1 AS id)]) AS t2` :: expected `SELECT * FROM UNNEST([STRUCT(1 AS id)]) AS t1 CROSS JOIN UNNEST([STRUCT(1 AS id)]) AS t2` actual `SELECT * FROM (VALUES (1)) AS t1 CROSS JOI
- [postgres -> bigquery] `SELECT GENERATE_SERIES(1, 2) AS a, GENERATE_SERIES(11, 13) AS b` :: expected `SELECT a, b FROM UNNEST(GENERATE_ARRAY(1, 2)) AS a CROSS JOIN UNNEST(GENERATE_ARRAY(11, 13)) AS b` actual `SELECT GENERATE_SERIES(1, 2) AS a
- [postgres -> bigquery] `SELECT GENERATE_SERIES(1, 5) AS x WHERE x > 2 ORDER BY x DESC LIMIT 3` :: expected `SELECT x FROM UNNEST(GENERATE_ARRAY(1, 5)) AS x WHERE x > 2 ORDER BY x DESC NULLS FIRST LIMIT 3` actual `SELECT GENERATE_SERIES(1, 5) AS x W
- [postgres -> bigquery] `SELECT GENERATE_SERIES(1, 5) AS x` :: expected `SELECT x FROM UNNEST(GENERATE_ARRAY(1, 5)) AS x` actual `SELECT GENERATE_SERIES(1, 5) AS x`
- [postgres -> bigquery] `SELECT GENERATE_SERIES(1, 5)` :: expected `SELECT _gen_series_value FROM UNNEST(GENERATE_ARRAY(1, 5)) AS _gen_series_value` actual `SELECT GENERATE_SERIES(1, 5)`
- [postgres -> bigquery] `SELECT U&'a
b'` :: UnsupportedError: Unsupported expression type UnicodeString
- [postgres -> bigquery] `SELECT y, GENERATE_SERIES(1, 2) AS a, GENERATE_SERIES(11, 13) AS b FROM t` :: expected `SELECT y, a, b FROM t CROSS JOIN UNNEST(GENERATE_ARRAY(1, 2)) AS a CROSS JOIN UNNEST(GENERATE_ARRAY(11, 13)) AS b` actual `SELECT y, GENERAT
- [postgres -> bigquery] `SELECT y, GENERATE_SERIES(1, 3) AS g FROM t` :: expected `SELECT y, g FROM t CROSS JOIN UNNEST(GENERATE_ARRAY(1, 3)) AS g` actual `SELECT y, GENERATE_SERIES(1, 3) AS g FROM t`
- [postgres -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `SHA2(x, 256)`
- [postgres -> bigquery] `WITH cte AS (SELECT 1 AS foo UNION ALL SELECT 2) SELECT foo FROM cte` :: expected `WITH cte AS (SELECT 1 AS foo UNION ALL SELECT 2) SELECT foo FROM cte` actual `WITH cte AS (SELECT 1 UNION ALL SELECT 2) SELECT foo FROM cte`
- [postgres -> bigquery] `WITH cte AS (SELECT 1 AS foo, 2) SELECT foo FROM cte` :: expected `WITH cte AS (SELECT 1 AS foo, 2) SELECT foo FROM cte` actual `WITH cte AS (SELECT 1, 2) SELECT foo FROM cte`

## spark->spark  (13)

- [spark -> spark] `ALTER TABLE StudentInfo DROP COLUMNS (LastName, DOB)` :: expected `ALTER TABLE StudentInfo DROP COLUMNS (LastName, DOB)` actual `ALTER TABLE StudentInfo DROP COLUMNS`
- [spark -> spark] `CREATE TABLE blah (col_a INT) COMMENT "Test comment: blah" PARTITIONED BY (date STRING) USING ICEBER` :: expected `CREATE TABLE blah ( col_a INT, date STRING ) COMMENT 'Test comment: blah' PARTITIONED BY ( date ) USING ICEBERG TBLPROPERTIES ( 'x'='1' )` a
- [spark -> spark] `LISTAGG(x, ', ')` :: expected `LISTAGG(x, ', ')` actual `ARRAY_JOIN(COLLECT_LIST(x), ', ')`
- [spark -> spark] `SELECT ARRAY_AGG(DISTINCT STRUCT('a'))` :: expected `SELECT COLLECT_LIST(DISTINCT STRUCT('a' AS col1))` actual `SELECT COLLECT_LIST(DISTINCT STRUCT('a'))`
- [spark -> spark] `SELECT CAST(STRUCT('fooo') AS STRUCT<a: VARCHAR(2)>)` :: expected `SELECT CAST(STRUCT('fooo' AS col1) AS STRUCT<a: STRING>)` actual `SELECT CAST(STRUCT('fooo') AS STRUCT<a: STRING>)`
- [spark -> spark] `SELECT DATE_ADD(MONTH, 20, col)` :: expected `SELECT DATE_ADD(MONTH, 20, col)` actual `SELECT TIMESTAMP_ADD(col, 20, MONTH)`
- [spark -> spark] `SELECT DATE_ADD(MONTH, 20, col)` :: expected `SELECT DATE_ADD(MONTH, 20, col)` actual `SELECT TIMESTAMP_ADD(col, 20, MONTH)`
- [spark -> spark] `SELECT STRUCT(1, 2)` :: expected `SELECT STRUCT(1 AS col1, 2 AS col2)` actual `SELECT STRUCT(1, 2)`
- [spark -> spark] `SELECT STRUCT(x, 1, y AS col3, STRUCT(5)) FROM t` :: expected `SELECT STRUCT(x AS x, 1 AS col2, y AS col3, STRUCT(5 AS col1) AS col4) FROM t` actual `SELECT STRUCT(x, 1, y AS col3, STRUCT(5)) FROM t`
- [spark -> spark] `SELECT TIMESTAMPDIFF(MONTH, foo, bar)` :: expected `SELECT TIMESTAMPDIFF(MONTH, foo, bar)` actual `SELECT TIMESTAMPDIFF(bar, foo, MONTH)`
- [spark -> spark] `SET VARIABLE v = (SELECT MAX(c1) FROM VALUES (1), (2) AS T(c1))` :: expected `SET VARIABLE v = (SELECT MAX(c1) FROM VALUES (1), (2) AS T(c1))` actual `SET VARIABLE v = (SELECT GREATEST(c1) FROM VALUES (1), (2) AS T(c1)
- [spark -> spark] `STRING_AGG(x, ', ')` :: expected `LISTAGG(x, ', ')` actual `ARRAY_JOIN(COLLECT_LIST(x), ', ')`
- [spark -> spark] `WITH RECURSIVE t(n) AS (SELECT * FROM VALUES (1) AS _values) SELECT n FROM t` :: expected `WITH RECURSIVE t(n) AS (SELECT * FROM VALUES (1) AS _values) SELECT n FROM t` actual `WITH t(n) AS (SELECT * FROM VALUES (1) AS _values) SEL

## duckdb->bigquery  (12)

- [duckdb -> bigquery] `CAST(start AS TIMESTAMPTZ) AT TIME ZONE 'America/New_York'` :: expected `TIMESTAMP(DATETIME(CAST(start AS TIMESTAMP), 'America/New_York'))` actual `CAST(start AS TIMESTAMP) AT TIME ZONE 'America/New_York'`
- [duckdb -> bigquery] `SELECT * FROM t, UNNEST(`t2`.`t3`) AS `col`` :: expected `SELECT * FROM t, UNNEST(`t2`.`t3`) AS `col`` actual `SELECT * FROM t, UNNEST(`t1`.`t2`.`t3`) AS `t1``
- [duckdb -> bigquery] `SELECT * FROM t1, UNNEST(`t1`) AS `col`` :: expected `SELECT * FROM t1, UNNEST(`t1`) AS `col`` actual `SELECT * FROM t1, UNNEST(`t1`) AS `t1``
- [duckdb -> bigquery] `SELECT * FROM t1, UNNEST(`t1`.`t2`.`t3`.`t4`) AS `col`` :: expected `SELECT * FROM t1, UNNEST(`t1`.`t2`.`t3`.`t4`) AS `col`` actual `SELECT * FROM t1, UNNEST(`t1`.`t2`.`t3`.`t4`) AS `t3``
- [duckdb -> bigquery] `SELECT UNNEST(ARRAY[1, 2, 3]), UNNEST(ARRAY[4, 5]), UNNEST(ARRAY[6]) FROM x` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col, IF(pos = pos_3, col_2, NULL) AS col_2, IF(pos = pos_4, col_3, NULL) AS col_3 FROM x CROSS JOIN UNN
- [duckdb -> bigquery] `SELECT UNNEST(ARRAY[1, 2, 3]), UNNEST(ARRAY[4, 5]), UNNEST(ARRAY[6])` :: expected `SELECT IF(pos = pos_2, col, NULL) AS col, IF(pos = pos_3, col_2, NULL) AS col_2, IF(pos = pos_4, col_3, NULL) AS col_3 FROM UNNEST(GENERATE_
- [duckdb -> bigquery] `SELECT UNNEST(x) + 1 AS y` :: expected `SELECT IF(pos = pos_2, y, NULL) + 1 AS y FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(x)) - 1)) AS pos CROSS JOIN UNNEST(x) AS y WITH
- [duckdb -> bigquery] `SELECT UNNEST(x) + 1` :: expected `SELECT IF(pos = pos_2, col, NULL) + 1 AS col FROM UNNEST(GENERATE_ARRAY(0, GREATEST(ARRAY_LENGTH(x)) - 1)) AS pos CROSS JOIN UNNEST(x) AS co
- [duckdb -> bigquery] `SELECT e'Hello
world'` :: expected `SELECT CAST(b'Hello\nworld' AS STRING)` actual `SELECT CAST('b\'Hello world\'' AS STRING)`
- [duckdb -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `SHA2(x, 256)`
- [duckdb -> bigquery] `STRFTIME(x, '%Y-%m-%d %H:%M:%S')` :: expected `FORMAT_DATE('%F %T', x)` actual `FORMAT_DATE('%Y-%m-%d %H:%M:%S', x)`
- [duckdb -> bigquery] `STRPTIME(x, '%-m/%-d/%y %-I:%M %p')` :: expected `PARSE_TIMESTAMP('%-m/%e/%y %-I:%M %p', x)` actual `PARSE_TIMESTAMP('%-m/%-d/%y %-I:%M %p', x)`

## spark->presto  (12)

- [spark -> presto] `SELECT AT_TIMEZONE(CAST('2012-10-31 00:00' AS TIMESTAMP WITH TIME ZONE), 'America/Sao_Paulo')` :: expected `SELECT AT_TIMEZONE(CAST('2012-10-31 00:00' AS TIMESTAMP WITH TIME ZONE), 'America/Sao_Paulo')` actual `SELECT AT_TIMEZONE(CAST(CAST('2012-10
- [spark -> presto] `SELECT EXPLODE(ARRAY(1, 2))` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col FROM UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(ARRAY[1, 2])))) AS _u(pos) CROSS JOIN UNNEST(AR
- [spark -> presto] `SELECT EXPLODE(col) AS exploded FROM schema.tbl` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.exploded) AS exploded FROM schema.tbl CROSS JOIN UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(col)))) AS _u(p
- [spark -> presto] `SELECT EXPLODE(col) FROM _u` :: expected `SELECT IF(_u_2.pos = _u_3.pos_2, _u_3.col_2) AS col_2 FROM _u CROSS JOIN UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(col)))) AS _u_2(pos) CROSS 
- [spark -> presto] `SELECT EXPLODE(x) FROM tbl` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col FROM tbl CROSS JOIN UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(x)))) AS _u(pos) CROSS JOIN UNNE
- [spark -> presto] `SELECT LEFT(x, 2), RIGHT(x, 2)` :: expected `SELECT SUBSTR(x, 1, 2), SUBSTR(x, LENGTH(x) - (2 - 1))` actual `SELECT SUBSTR(x, 1, 2), SUBSTR(x, LENGTH(x) + (2 - 1) - )`
- [spark -> presto] `SELECT POSEXPLODE(ARRAY(2, 3)) AS x` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.x) AS x, IF(_u.pos = _u_2.pos_2, _u_2.pos_2) AS pos_2 FROM UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(ARRAY
- [spark -> presto] `SELECT POSEXPLODE(ARRAY(2, 3)), EXPLODE(ARRAY(4, 5, 6)) FROM tbl` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col, IF(_u.pos = _u_2.pos_2, _u_2.pos_2) AS pos_2, IF(_u.pos = _u_3.pos_3, _u_3.col_2) AS col_2 
- [spark -> presto] `SELECT POSEXPLODE(x) AS (a, b)` :: expected `SELECT IF(_u.pos = _u_2.a, _u_2.b) AS b, IF(_u.pos = _u_2.a, _u_2.a) AS a FROM UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(x)))) AS _u(pos) CROS
- [spark -> presto] `SELECT STRUCT(1, 2)` :: expected `SELECT CAST(ROW(1, 2) AS ROW(col1 INTEGER, col2 INTEGER))` actual `SELECT ROW(1, 2)`
- [spark -> presto] `SELECT col, pos, POSEXPLODE(ARRAY(2, 3)) FROM _u` :: expected `SELECT col, pos, IF(_u_2.pos_2 = _u_3.pos_3, _u_3.col_2) AS col_2, IF(_u_2.pos_2 = _u_3.pos_3, _u_3.pos_3) AS pos_3 FROM _u CROSS JOIN UNNES
- [spark -> presto] `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUN` :: expected `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUNT(DISTINCT CASE WHEN id IS NULL THEN NUL

## bigquery->hive  (6)

- [bigquery -> hive] `EDIT_DISTANCE(col1, col2, max_distance => 3)` :: expected UnsupportedError, got `LEVENSHTEIN(col1, col2, 3)`
- [bigquery -> hive] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> hive] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `IF((2 * y) <> 0, (x + 1) / (2 * y), NULL)` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> hive] `SAFE_DIVIDE(x, y)` :: expected `IF(y <> 0, x / y, NULL)` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> hive] `SELECT EXTRACT(WEEK(THURSDAY) FROM d)` :: expected UnsupportedError, got `SELECT EXTRACT(THURSDAY FROM d)`
- [bigquery -> hive] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`

## presto->bigquery  (10)

- [presto -> bigquery] `DATE_FORMAT(x, '%Y-%m-%d %H:%i:%S')` :: expected `FORMAT_DATE('%F %T', x)` actual `FORMAT_DATE('%Y-%m-%d %H:%M:%S', x)`
- [presto -> bigquery] `SELECT * FROM UNNEST(ARRAY['7', '14']) AS x(y)` :: expected `SELECT * FROM UNNEST(['7', '14']) AS y` actual `SELECT * FROM UNNEST(['7', '14']) AS x`
- [presto -> bigquery] `SELECT * FROM UNNEST(ARRAY['7', '14']) AS x` :: expected `SELECT * FROM UNNEST(['7', '14'])` actual `SELECT * FROM UNNEST(['7', '14']) AS x`
- [presto -> bigquery] `SELECT MAX_BY(a.id, a.timestamp) FROM a` :: expected `SELECT MAX_BY(a.id, a.timestamp) FROM a` actual `SELECT ARG_MAX(a.id, a.timestamp) FROM a`
- [presto -> bigquery] `SELECT SHA256(x)` :: expected `SELECT SHA256(x)` actual `SELECT S_H_A2_DIGEST(x, 256)`
- [presto -> bigquery] `SELECT SHA512(x)` :: expected `SELECT SHA512(x)` actual `SELECT S_H_A2_DIGEST(x, 512)`
- [presto -> bigquery] `SELECT results FROM Coordinates AS c, UNNEST(c.position) AS results` :: expected `SELECT results FROM Coordinates AS c, UNNEST(c.position) AS results` actual `SELECT results FROM Coordinates AS c, UNNEST(c.position) AS _t`
- [presto -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `S_H_A2_DIGEST(x, 256)`
- [presto -> bigquery] `SHA512(x)` :: expected `SHA512(x)` actual `S_H_A2_DIGEST(x, 512)`
- [presto -> bigquery] `STRPOS(haystack, needle, occurrence)` :: expected `INSTR(haystack, needle, 1, occurrence)` actual `STR_POSITION(haystack, needle, occurrence)`

## bigquery->trino  (9)

- [bigquery -> trino] `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')` :: expected `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]', 1)` actual `REGEXP_EXTRACT_ALL('a1_a2a3_a4A5a6', '(a)[0-9]')`
- [bigquery -> trino] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `IF((2 * y) <> 0, CAST((x + 1) AS DOUBLE) / (2 * y), NULL)` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> trino] `SAFE_DIVIDE(x, y)` :: expected `IF(y <> 0, CAST(x AS DOUBLE) / y, NULL)` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> trino] `SELECT * FROM UNNEST([STRUCT('Alice' AS name, 85 AS score), STRUCT('Bob', 92), STRUCT('Diana', 95)])` :: expected `SELECT * FROM UNNEST(ARRAY[CAST(ROW('Alice', 85) AS ROW(name VARCHAR, score INTEGER)), CAST(ROW('Bob', 92) AS ROW(name VARCHAR, score INTEGE
- [bigquery -> trino] `SELECT DATETIME_DIFF(DATETIME '2017-10-15 00:00:00', DATETIME '2017-10-14 00:00:00', WEEK)` :: expected `SELECT DATE_DIFF('WEEK', DATE_TRUNC('WEEK', CAST('2017-10-14 00:00:00' AS TIMESTAMP) + INTERVAL '1' DAY), DATE_TRUNC('WEEK', CAST('2017-10-1
- [bigquery -> trino] `SELECT DATETIME_DIFF(DATETIME '2021-02-01 00:00:00', DATETIME '2021-01-31 00:00:00', MONTH)` :: expected `SELECT DATE_DIFF('MONTH', DATE_TRUNC('MONTH', CAST('2021-01-31 00:00:00' AS TIMESTAMP)), DATE_TRUNC('MONTH', CAST('2021-02-01 00:00:00' AS T
- [bigquery -> trino] `SELECT GENERATE_UUID()` :: expected `SELECT CAST(UUID() AS VARCHAR)` actual `SELECT UUID()`
- [bigquery -> trino] `SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), minute)` :: expected `SELECT DATE_DIFF('MINUTE', FROM_UNIXTIME(0), FROM_UNIXTIME(60))` actual `SELECT TIMESTAMPDIFF(FROM_UNIXTIME(60), FROM_UNIXTIME(0), MINUTE)`
- [bigquery -> trino] `TIMESTAMP_DIFF(a, b, MONTH)` :: expected `DATE_DIFF('MONTH', b, a)` actual `TIMESTAMPDIFF(a, b, MONTH)`

## duckdb->spark  (9)

- [duckdb -> spark] `SELECT * FROM parquet.`name.parquet`` :: expected `SELECT * FROM parquet.`name.parquet`` actual `SELECT * FROM READ_PARQUET('name.parquet')`
- [duckdb -> spark] `SELECT RANGE(1, 1)` :: expected `SELECT ARRAY()` actual `SELECT GENERATE_SERIES(1, 1)`
- [duckdb -> spark] `SELECT RANGE(1, 2)` :: expected `SELECT SEQUENCE(1, 1)` actual `SELECT GENERATE_SERIES(1, 2)`
- [duckdb -> spark] `SELECT RANGE(1, 5)` :: expected `SELECT SEQUENCE(1, 4)` actual `SELECT GENERATE_SERIES(1, 5)`
- [duckdb -> spark] `SELECT RANGE(1, 5, 2)` :: expected `SELECT SEQUENCE(1, 3, 2)` actual `SELECT GENERATE_SERIES(1, 5, 2)`
- [duckdb -> spark] `SELECT RANGE(5, 1, -1)` :: expected `SELECT SEQUENCE(5, 2, -1)` actual `SELECT GENERATE_SERIES(5, 1, -1)`
- [duckdb -> spark] `SELECT RANGE(5, 1, 0)` :: expected `SELECT ARRAY()` actual `SELECT GENERATE_SERIES(5, 1, 0)`
- [duckdb -> spark] `WITH t AS (SELECT 2 AS c) SELECT RANGE(1, c) FROM t` :: expected `WITH t AS (SELECT 2 AS c) SELECT IF((c - 1) < 1, ARRAY(), SEQUENCE(1, (c - 1))) FROM t` actual `WITH t AS (SELECT 2 AS c) SELECT GENERATE_SE
- [duckdb -> spark] `WITH t AS (SELECT 5 AS c) SELECT RANGE(1, c) FROM t` :: expected `WITH t AS (SELECT 5 AS c) SELECT IF((c - 1) < 1, ARRAY(), SEQUENCE(1, (c - 1))) FROM t` actual `WITH t AS (SELECT 5 AS c) SELECT GENERATE_SE

## hive->duckdb  (9)

- [hive -> duckdb] `COLLECT_LIST(x)` :: expected `ARRAY_AGG(x) FILTER(WHERE x IS NOT NULL)` actual `ARRAY_AGG(x)`
- [hive -> duckdb] `DATE_FORMAT('2020-01-01', 'yyyy-MM-dd HH:mm:ss')` :: expected `STRFTIME(CAST('2020-01-01' AS TIMESTAMP), '%Y-%m-%d %H:%M:%S')` actual `STRFTIME(TIME_STR_TO_TIME('2020-01-01'), '%Y-%m-%d %H:%M:%S')`
- [hive -> duckdb] `LOCATE('a', x)` :: expected `STRPOS(x, 'a')` actual `STR_POSITION(x, 'a')`
- [hive -> duckdb] `LOCATE('a', x, 3)` :: expected `CASE WHEN STRPOS(SUBSTRING(x, 3), 'a') = 0 THEN 0 ELSE STRPOS(SUBSTRING(x, 3), 'a') + 3 - 1 END` actual `STR_POSITION(x, 'a', 3)`
- [hive -> duckdb] `SELECT a FROM x LATERAL VIEW EXPLODE(ARRAY(y)) t AS a` :: expected `SELECT a FROM x CROSS JOIN UNNEST([y]) AS t(a)` actual `SELECT a FROM x LATERAL VIEW UNNEST([y]) t AS a`
- [hive -> duckdb] `SELECT a FROM x LATERAL VIEW EXPLODE(y) t AS a` :: expected `SELECT a FROM x CROSS JOIN UNNEST(y) AS t(a)` actual `SELECT a FROM x LATERAL VIEW UNNEST(y) t AS a`
- [hive -> duckdb] `SELECT a FROM x LATERAL VIEW POSEXPLODE(y) t AS pos, col` :: expected `SELECT a FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, col FROM UNNEST(y) WITH ORDINALITY AS t(col, pos))` actual `SELECT a FROM x LATER
- [hive -> duckdb] `SELECT a, b FROM x LATERAL VIEW EXPLODE(y) t AS a LATERAL VIEW EXPLODE(z) u AS b` :: expected `SELECT a, b FROM x CROSS JOIN UNNEST(y) AS t(a) CROSS JOIN UNNEST(z) AS u(b)` actual `SELECT a, b FROM x LATERAL VIEW UNNEST(y) t AS a LATER
- [hive -> duckdb] `from_unixtime(x, "yyyy-MM-dd'T'HH")` :: expected `STRFTIME(TO_TIMESTAMP(x), '%Y-%m-%d''T''%H')` actual `UNIX_TO_STR(x, '%Y-%mstrict-%dstrict''T''%Hstrict')`

## postgres->postgres  (6)

- [postgres -> postgres] `SELECT 'prefix' || JSON_EXTRACT_PATH_TEXT(a, VARIADIC '{}') FROM t` :: expected `SELECT 'prefix' || JSON_EXTRACT_PATH_TEXT(a, VARIADIC '{}') FROM t` actual `SELECT 'prefix' || JSON_EXTRACT_PATH_TEXT(a) FROM t`
- [postgres -> postgres] `SELECT BTRIM(x, 'ab')` :: expected `SELECT TRIM('ab' FROM x)` actual `SELECT BTRIM(x, 'ab')`
- [postgres -> postgres] `SELECT a #> (n IN (1, 2))` :: expected `SELECT a #> (n IN (1, 2))` actual `SELECT a #> n IN (1, 2)`
- [postgres -> postgres] `SELECT a -> ('x' || 'y')` :: expected `SELECT a -> ('x' || 'y')` actual `SELECT JSON_EXTRACT_PATH(a, 'x' || 'y')`
- [postgres -> postgres] `SELECT a -> (1 + 2)` :: expected `SELECT a -> (1 + 2)` actual `SELECT JSON_EXTRACT_PATH(a, 1 + 2)`
- [postgres -> postgres] `SELECT a -> (NOT x)` :: expected `SELECT a -> (NOT x)` actual `SELECT JSON_EXTRACT_PATH(a, NOT x)`

## bigquery->postgres  (7)

- [bigquery -> postgres] `DATE_ADD(CURRENT_DATE(), INTERVAL -1 DAY)` :: expected `CURRENT_DATE + INTERVAL '-1 DAY'` actual `CURRENT_DATE + INTERVAL '-1 'DAY''`
- [bigquery -> postgres] `DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY)` :: expected `CURRENT_DATE - INTERVAL '1 DAY'` actual `CURRENT_DATE - INTERVAL '1 'DAY''`
- [bigquery -> postgres] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `CASE WHEN (2 * y) <> 0 THEN CAST((x + 1) AS DOUBLE PRECISION) / (2 * y) ELSE NULL END` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> postgres] `SAFE_DIVIDE(x, y)` :: expected `CASE WHEN y <> 0 THEN CAST(x AS DOUBLE PRECISION) / y ELSE NULL END` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> postgres] `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)` :: expected `SELECT CAST(DATE_TRUNC('MONTH', CAST('2008-11-25' AS DATE)) + INTERVAL '1 MONTH' - INTERVAL '1 DAY' AS DATE)` actual `SELECT CAST(DATE_SUB(D
- [bigquery -> postgres] `SELECT TIME('2008-12-25 15:30:00')` :: expected `SELECT CAST('2008-12-25 15:30:00' AS TIME)` actual `SELECT TS_OR_DS_TO_TIME('2008-12-25 15:30:00')`
- [bigquery -> postgres] `SELECT b'a'` :: expected `SELECT CAST(e'a' AS BYTEA)` actual `SELECT CAST('e''a''' AS BYTEA)`

## bigquery->clickhouse  (5)

- [bigquery -> clickhouse] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER(x)`
- [bigquery -> clickhouse] `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)` :: expected `SELECT LAST_DAY(CAST('2008-11-25' AS Nullable(DATE)))` actual `SELECT LAST_DAY(CAST('2008-11-25' AS Nullable(DATE)), MONTH)`
- [bigquery -> clickhouse] `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))` :: expected UnsupportedError, got `SELECT dateTrunc(WEEK(SUNDAY), ts)`
- [bigquery -> clickhouse] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `TO_HEX(x)`
- [bigquery -> clickhouse] `UPPER(TO_HEX(x))` :: expected `HEX(x)` actual `UPPER(x)`

## bigquery->mysql  (5)

- [bigquery -> mysql] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> mysql] `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)` :: expected `SELECT LAST_DAY(CAST('2008-11-25' AS DATE))` actual `SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)`
- [bigquery -> mysql] `SELECT TIME('2008-12-25 15:30:00')` :: expected `SELECT CAST('2008-12-25 15:30:00' AS TIME)` actual `SELECT TS_OR_DS_TO_TIME('2008-12-25 15:30:00')`
- [bigquery -> mysql] `SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))` :: expected UnsupportedError, got `SELECT DATE_ADD('0000-01-01 00:00:00', INTERVAL (TIMESTAMPDIFF(WEEK_START(SUNDAY), '0000-01-01 00:00:00', ts)) WEEK_ST
- [bigquery -> mysql] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`

## postgres->mysql  (5)

- [postgres -> mysql] `SELECT * FROM t1 LEFT OUTER JOIN t2 ON t1.x = t2.x UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 ON` :: expected `SELECT * FROM t1 LEFT OUTER JOIN t2 ON t1.x = t2.x UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 ON t1.x = t2.x WHERE NOT EXISTS(SELECT 1 F
- [postgres -> mysql] `SELECT * FROM t1 LEFT OUTER JOIN t2 USING (x) UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 USING (` :: expected `SELECT * FROM t1 LEFT OUTER JOIN t2 USING (x) UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 USING (x) WHERE NOT EXISTS(SELECT 1 FROM t1 WHE
- [postgres -> mysql] `SELECT * FROM t1 LEFT OUTER JOIN t2 USING (x, y) UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 USIN` :: expected `SELECT * FROM t1 LEFT OUTER JOIN t2 USING (x, y) UNION ALL SELECT * FROM t1 RIGHT OUTER JOIN t2 USING (x, y) WHERE NOT EXISTS(SELECT 1 FROM 
- [postgres -> mysql] `SELECT * FROM x LEFT JOIN y ON x.id = y.id UNION ALL SELECT * FROM x RIGHT JOIN y ON x.id = y.id WHE` :: expected `SELECT * FROM x LEFT JOIN y ON x.id = y.id UNION ALL SELECT * FROM x RIGHT JOIN y ON x.id = y.id WHERE NOT EXISTS(SELECT 1 FROM x WHERE x.id
- [postgres -> mysql] `UPDATE foo SET a = bar.a, b = bar.b FROM bar WHERE foo.id = bar.id` :: expected `UPDATE foo JOIN bar ON TRUE SET foo.a = bar.a, foo.b = bar.b WHERE foo.id = bar.id` actual `UPDATE foo SET a = bar.a, b = bar.b FROM bar WHE

## presto->spark  (5)

- [presto -> spark] `CREATE TABLE x (w VARCHAR, y INTEGER, z INTEGER) WITH (PARTITIONED_BY=ARRAY['y', 'z'])` :: expected `CREATE TABLE x (w STRING, y INT, z INT) PARTITIONED BY (y, z)` actual `CREATE TABLE x (w STRING, y INT, z INT) PARTITIONED BY ARRAY('y', 'z'
- [presto -> spark] `JSON_FORMAT(CAST(MAP_FROM_ENTRIES(ARRAY[('action_type', 'at')]) AS JSON))` :: expected `TO_JSON(MAP_FROM_ENTRIES(ARRAY(('action_type', 'at'))))` actual `TO_JSON(CAST(MAP_FROM_ENTRIES(ARRAY(('action_type', 'at'))) AS JSON))`
- [presto -> spark] `SELECT CAST(ARRAY [1, 23, 456] AS JSON)` :: expected `SELECT TO_JSON(ARRAY(1, 23, 456))` actual `SELECT CAST(ARRAY(1, 23, 456) AS JSON)`
- [presto -> spark] `SELECT JSON_EXTRACT_SCALAR(TRY(FILTER(CAST(JSON_EXTRACT('{"k1": [{"k2": "{\"k3\": 1}", "k4": "v"}]}'` :: expected `SELECT GET_JSON_OBJECT(FILTER(FROM_JSON(GET_JSON_OBJECT('{"k1": [{"k2": "{\\"k3\\": 1}", "k4": "v"}]}', '$.k1'), 'ARRAY<MAP<STRING, STRING>>
- [presto -> spark] `WITH RECURSIVE t(n) AS (VALUES (1) UNION ALL SELECT n+1 FROM t WHERE n < 100 ) SELECT SUM(n) FROM t` :: expected `WITH RECURSIVE t(n) AS (SELECT * FROM VALUES (1) AS _values UNION ALL SELECT n + 1 FROM t WHERE n < 100) SELECT SUM(n) FROM t` actual `WITH 

## bigquery->spark2  (4)

- [bigquery -> spark2] `EDIT_DISTANCE(col1, col2, max_distance => 3)` :: expected UnsupportedError, got `LEVENSHTEIN(col1, col2, 3)`
- [bigquery -> spark2] `SAFE_DIVIDE(x + 1, 2 * y)` :: expected `IF((2 * y) <> 0, (x + 1) / (2 * y), NULL)` actual `SAFE_DIVIDE(x + 1, 2 * y)`
- [bigquery -> spark2] `SAFE_DIVIDE(x, y)` :: expected `IF(y <> 0, x / y, NULL)` actual `SAFE_DIVIDE(x, y)`
- [bigquery -> spark2] `SELECT GENERATE_UUID()` :: expected `SELECT CAST(UUID() AS STRING)` actual `SELECT UUID()`

## hive->bigquery  (4)

- [hive -> bigquery] `DATE_ADD('2020-01-01', 1)` :: expected `DATE_ADD(CAST(CAST('2020-01-01' AS DATETIME) AS DATE), INTERVAL 1 DAY)` actual `TS_OR_DS_ADD('2020-01-01', 1, DAY)`
- [hive -> bigquery] `DATE_FORMAT('2020-01-01', 'yyyy-MM-dd HH:mm:ss')` :: expected `FORMAT_DATE('%Y-%m-%d %H:%M:%S', CAST('2020-01-01' AS DATETIME))` actual `FORMAT_DATE('%Y-%m-%d %H:%M:%S', TIME_STR_TO_TIME('2020-01-01'))`
- [hive -> bigquery] `DATE_SUB('2020-01-01', 1)` :: expected `DATE_ADD(CAST(CAST('2020-01-01' AS DATETIME) AS DATE), INTERVAL (1 * -1) DAY)` actual `TS_OR_DS_ADD('2020-01-01', 1 * -1, DAY)`
- [hive -> bigquery] `SELECT REPEAT(' ', 2)` :: expected `SELECT REPEAT(' ', 2)` actual `SELECT SPACE(2)`

## postgres->presto  (2)

- [postgres -> presto] `SELECT UNNEST(ARRAY[1])` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col FROM UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(ARRAY[1])))) AS _u(pos) CROSS JOIN UNNEST(ARRAY
- [postgres -> presto] `SELECT UNNEST(c) FROM t` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col FROM t CROSS JOIN UNNEST(SEQUENCE(1, GREATEST(CARDINALITY(c)))) AS _u(pos) CROSS JOIN UNNEST

## clickhouse->clickhouse  (2)

- [clickhouse -> clickhouse] `SELECT LTRIM(s), RTRIM(s), TRIM(s)` :: expected `SELECT LTRIM(s), RTRIM(s), TRIM(s)` actual `SELECT trimLeft(s), trimRight(s), trimBoth(s)`
- [clickhouse -> clickhouse] `SELECT trimLeft(s, 'xy'), trimRight(s, 'xy'), trimBoth(s, 'xy')` :: expected `SELECT TRIM(LEADING 'xy' FROM s), TRIM(TRAILING 'xy' FROM s), TRIM(BOTH 'xy' FROM s)` actual `SELECT trimLeft(s, 'xy'), trimRight(s, 'xy'), 

## hive->presto  (3)

- [hive -> presto] `COLLECT_LIST(x)` :: expected `ARRAY_AGG(x) FILTER(WHERE x IS NOT NULL)` actual `ARRAY_AGG(x)`
- [hive -> presto] `SELECT * FROM x LATERAL VIEW POSEXPLODE(MAP(col, 'val')) t AS pos, key, value` :: expected `SELECT * FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, key, value FROM UNNEST(MAP(ARRAY[col], ARRAY['val'])) WITH ORDINALITY AS t(key, v
- [hive -> presto] `SELECT a FROM x LATERAL VIEW POSEXPLODE(y) t AS pos, col` :: expected `SELECT a FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, col FROM UNNEST(y) WITH ORDINALITY AS t(col, pos))` actual `SELECT a FROM x CROSS

## mysql->duckdb  (3)

- [mysql -> duckdb] `SELECT DATE_FORMAT('2007-10-04 22:23:00', '%r')` :: expected `SELECT STRFTIME(CAST('2007-10-04 22:23:00' AS TIMESTAMP), '%I:%M:%S %p')` actual `SELECT STRFTIME(TS_OR_DS_TO_TIMESTAMP('2007-10-04 22:23:00
- [mysql -> duckdb] `SELECT DATE_FORMAT('2021-01-01 22:23:00', '%x')` :: expected `SELECT STRFTIME(CAST('2021-01-01 22:23:00' AS TIMESTAMP), '%G')` actual `SELECT STRFTIME(TS_OR_DS_TO_TIMESTAMP('2021-01-01 22:23:00'), '%x')
- [mysql -> duckdb] `a / b` :: expected `a / NULLIF(b, 0)` actual `a / b`

## mysql->postgres  (3)

- [mysql -> postgres] `SELECT DATEDIFF(x, y)` :: expected `SELECT (CAST(x AS DATE) - CAST(y AS DATE))` actual `SELECT CAST(AGE(CAST(x AS TIMESTAMP), CAST(y AS TIMESTAMP)) AS BIGINT)`
- [mysql -> postgres] `SELECT JSON_EXTRACT_PATH(a, VARIADIC '{}') FROM t` :: expected `SELECT JSON_EXTRACT_PATH(a, VARIADIC '{}') FROM t` actual `SELECT JSON_EXTRACT_PATH(a) FROM t`
- [mysql -> postgres] `SELECT JSON_EXTRACT_PATH_TEXT(a, VARIADIC '{}') FROM t` :: expected `SELECT JSON_EXTRACT_PATH_TEXT(a, VARIADIC '{}') FROM t` actual `SELECT JSON_EXTRACT_PATH_TEXT(a) FROM t`

## postgres->duckdb  (1)

- [postgres -> duckdb] `SELECT BTRIM(x, 'ab')` :: expected `SELECT TRIM(x, 'ab')` actual `SELECT BTRIM(x, 'ab')`

## postgres->spark  (3)

- [postgres -> spark] `GENERATE_SERIES('2019-01-01'::TIMESTAMP, NOW(), '1day')` :: expected `EXPLODE(SEQUENCE(CAST('2019-01-01' AS TIMESTAMP), CAST(CURRENT_TIMESTAMP() AS TIMESTAMP), INTERVAL '1' DAY))` actual `EXPLODE(GENERATE_SERIE
- [postgres -> spark] `SELECT * FROM GENERATE_SERIES(a, b)` :: expected `SELECT * FROM EXPLODE(SEQUENCE(a, b))` actual `SELECT * FROM GENERATE_SERIES(a, b)`
- [postgres -> spark] `SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount)` :: expected `SELECT PERCENTILE_APPROX(amount, 0.5)` actual `SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount NULLS LAST)`

## bigquery->base  (2)

- [bigquery -> base] `LOWER(TO_HEX(x))` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`
- [bigquery -> base] `TO_HEX(x)` :: expected `LOWER(HEX(x))` actual `LOWER_HEX(x)`

## clickhouse->bigquery  (2)

- [clickhouse -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `SHA2(x, 256)`
- [clickhouse -> bigquery] `SHA512(x)` :: expected `SHA512(x)` actual `SHA2(x, 512)`

## clickhouse->duckdb  (2)

- [clickhouse -> duckdb] `SELECT trimLeft(s, 'xy'), trimRight(s, 'xy'), trimBoth(s, 'xy')` :: expected `SELECT LTRIM(s, 'xy'), RTRIM(s, 'xy'), TRIM(s, 'xy')` actual `SELECT TRIMLEFT(s, 'xy'), TRIMRIGHT(s, 'xy'), TRIMBOTH(s, 'xy')`
- [clickhouse -> duckdb] `dateTrunc('MONTH', x)` :: expected `DATE_TRUNC('MONTH', x)` actual `DATETRUNC('MONTH', x)`

## clickhouse->postgres  (2)

- [clickhouse -> postgres] `SELECT trimLeft(s, 'xy'), trimRight(s, 'xy'), trimBoth(s, 'xy')` :: expected `SELECT TRIM(LEADING 'xy' FROM s), TRIM(TRAILING 'xy' FROM s), TRIM('xy' FROM s)` actual `SELECT TRIMLEFT(s, 'xy'), TRIMRIGHT(s, 'xy'), TRIMB
- [clickhouse -> postgres] `a / b` :: expected `CAST(a AS DOUBLE PRECISION) / b` actual `CAST(a AS DOUBLE PRECISION) / NULLIF(b, 0)`

## duckdb->presto  (2)

- [duckdb -> presto] `SELECT UNNEST(ARRAY[1, 2, 3]), UNNEST(ARRAY[4, 5]), UNNEST(ARRAY[6]) FROM x` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col, IF(_u.pos = _u_3.pos_3, _u_3.col_2) AS col_2, IF(_u.pos = _u_4.pos_4, _u_4.col_3) AS col_3 
- [duckdb -> presto] `SELECT UNNEST(ARRAY[1, 2, 3]), UNNEST(ARRAY[4, 5]), UNNEST(ARRAY[6])` :: expected `SELECT IF(_u.pos = _u_2.pos_2, _u_2.col) AS col, IF(_u.pos = _u_3.pos_3, _u_3.col_2) AS col_2, IF(_u.pos = _u_4.pos_4, _u_4.col_3) AS col_3 

## hive->spark  (2)

- [hive -> spark] `ALTER TABLE x CHANGE COLUMN a a VARCHAR(10) CASCADE` :: expected `ALTER TABLE x ALTER COLUMN a TYPE VARCHAR(10)` actual `ALTER TABLE x ALTER COLUMN a TYPE VARCHAR(10) CASCADE`
- [hive -> spark] `CREATE TABLE x (w STRING) PARTITIONED BY (y INT, z INT)` :: expected `CREATE TABLE x (w STRING, y INT, z INT) PARTITIONED BY (y, z)` actual `CREATE TABLE x (w STRING) PARTITIONED BY (y INT, z INT)`

## hive->trino  (2)

- [hive -> trino] `SELECT * FROM x LATERAL VIEW POSEXPLODE(MAP(col, 'val')) t AS pos, key, value` :: expected `SELECT * FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, key, value FROM UNNEST(MAP(ARRAY[col], ARRAY['val'])) WITH ORDINALITY AS t(key, v
- [hive -> trino] `SELECT a FROM x LATERAL VIEW POSEXPLODE(y) t AS pos, col` :: expected `SELECT a FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, col FROM UNNEST(y) WITH ORDINALITY AS t(col, pos))` actual `SELECT a FROM x CROSS

## postgres->hive  (2)

- [postgres -> hive] `GENERATE_SERIES('2019-01-01'::TIMESTAMP, NOW(), '1day')` :: expected `EXPLODE(SEQUENCE(CAST('2019-01-01' AS TIMESTAMP), CAST(CURRENT_TIMESTAMP() AS TIMESTAMP), INTERVAL '1' DAY))` actual `EXPLODE(GENERATE_SERIE
- [postgres -> hive] `SELECT * FROM GENERATE_SERIES(a, b)` :: expected `SELECT * FROM EXPLODE(SEQUENCE(a, b))` actual `SELECT * FROM GENERATE_SERIES(a, b)`

## postgres->spark2  (2)

- [postgres -> spark2] `GENERATE_SERIES('2019-01-01'::TIMESTAMP, NOW(), '1day')` :: expected `EXPLODE(SEQUENCE(CAST('2019-01-01' AS TIMESTAMP), CAST(CURRENT_TIMESTAMP() AS TIMESTAMP), INTERVAL '1' DAY))` actual `EXPLODE(GENERATE_SERIE
- [postgres -> spark2] `SELECT * FROM GENERATE_SERIES(a, b)` :: expected `SELECT * FROM EXPLODE(SEQUENCE(a, b))` actual `SELECT * FROM GENERATE_SERIES(a, b)`

## trino->bigquery  (2)

- [trino -> bigquery] `SHA256(x)` :: expected `SHA256(x)` actual `S_H_A2_DIGEST(x, 256)`
- [trino -> bigquery] `SHA512(x)` :: expected `SHA512(x)` actual `S_H_A2_DIGEST(x, 512)`

## base->bigquery  (1)

- [base -> bigquery] `SELECT
  `u`.`user_email` AS `user_email`,
  `d`.`user_id` AS `user_id`,
  `account_id` AS `account_` :: expected `SELECT `u`.`user_email` AS `user_email`, `d`.`user_id` AS `user_id`, `account_id` AS `account_id` FROM `analytics_staging`.`stg_mongodb__use

## clickhouse->presto  (1)

- [clickhouse -> presto] `dateTrunc('MONTH', x)` :: expected `DATE_TRUNC('MONTH', x)` actual `DATETRUNC('MONTH', x)`

## clickhouse->spark  (1)

- [clickhouse -> spark] `dateTrunc('MONTH', x)` :: expected `TRUNC(x, 'MONTH')` actual `DATETRUNC('MONTH', x)`

## duckdb->duckdb  (1)

- [duckdb -> duckdb] `SELECT a -> ('x' || 'y')` :: expected `SELECT a -> ('x' || 'y')` actual `SELECT a -> 'x' || 'y'`

## duckdb->postgres  (1)

- [duckdb -> postgres] `a / b` :: expected `CAST(a AS DOUBLE PRECISION) / b` actual `CAST(a AS DOUBLE PRECISION) / NULLIF(b, 0)`

## mysql->bigquery  (1)

- [mysql -> bigquery] `TIMESTAMP_DIFF(a, b, MONTH)` :: expected `TIMESTAMP_DIFF(a, b, MONTH)` actual `TIMESTAMP_DIFF(a, b)`

## mysql->clickhouse  (1)

- [mysql -> clickhouse] `a / b` :: expected `a / nullIf(b, 0)` actual `a / b`

## mysql->presto  (1)

- [mysql -> presto] `SELECT DATEDIFF(x, y)` :: expected `SELECT DATE_DIFF('DAY', DATE_TRUNC('DAY', y), DATE_TRUNC('DAY', x))` actual `SELECT DATE_DIFF('DAY', y, x)`

## postgres->doris  (1)

- [postgres -> doris] `SELECT JSON_EXTRACT(CAST('{"key": 1}' AS JSONB), '$.key')` :: expected `SELECT JSON_EXTRACT(CAST('{"key": 1}' AS JSONB), '$.key')` actual `SELECT JSON_UNQUOTE(JSON_EXTRACT(CAST('{"key": 1}' AS JSONB), '$.key'))`

## presto->hive  (1)

- [presto -> hive] `CREATE TABLE x (w VARCHAR, y INTEGER, z INTEGER) WITH (PARTITIONED_BY=ARRAY['y', 'z'])` :: expected `CREATE TABLE x (w STRING) PARTITIONED BY (y INT, z INT)` actual `CREATE TABLE x (w STRING, y INT, z INT) PARTITIONED BY ARRAY('y', 'z')`

## presto->presto  (1)

- [presto -> presto] `WITH RECURSIVE t(n) AS (VALUES (1) UNION ALL SELECT n+1 FROM t WHERE n < 100 ) SELECT SUM(n) FROM t` :: expected `WITH RECURSIVE t(n) AS (SELECT * FROM (VALUES (1)) AS _values UNION ALL SELECT n + 1 FROM t WHERE n < 100) SELECT SUM(n) FROM t` actual `WIT

## spark->hive  (1)

- [spark -> hive] `SELECT LEFT(x, 2), RIGHT(x, 2)` :: expected `SELECT SUBSTRING(x, 1, 2), SUBSTRING(x, LENGTH(x) - (2 - 1))` actual `SELECT SUBSTRING(x, 1, 2), SUBSTRING(x, -2)`

## spark->postgres  (1)

- [spark -> postgres] `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUN` :: expected `WITH tbl AS (SELECT 1 AS id, 'eggy' AS name UNION ALL SELECT NULL AS id, 'jake' AS name) SELECT COUNT(DISTINCT CASE WHEN id IS NULL THEN NUL
