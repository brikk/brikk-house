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
> **This file = native round-trip generation diffs.** Parse in dialect D, generate back in D,
> and the string differs — formatting, escape sequences, time-format specifiers, quoting, etc.
> Fix = the reference `<node>_sql` in `generator.py` / `generators/<D>.py`.
---


**64 items** across 5 groups.


## bigquery  (51)

- [bigquery] `CREATE MODEL project_id.mydataset.mymodel
INPUT(
  f1 INT64,
  f2 FLOAT64,
  f3 STRING,
  f4 ARRAY<I` :: output mismatch: expected `CREATE MODEL project_id.mydataset.mymodel INPUT(f1 INT64, f2 FLOAT64, f3 STRING, f4 ARRAY<INT64>) OUTPUT(out1 INT64, out` a
- [bigquery] `CREATE OR REPLACE MODEL m
TRANSFORM(
  ML.FEATURE_CROSS(STRUCT(f1, f2)) AS cross_f,
  ML.QUANTILE_BU` :: output mismatch: expected `CREATE OR REPLACE MODEL m TRANSFORM(ML.FEATURE_CROSS(STRUCT(f1, f2)) AS cross_f, ML.QUANTILE_BUCKETIZE(f3) OVER () AS bu` a
- [bigquery] `CREATE TABLE FUNCTION a(x INT64) RETURNS TABLE <q STRING, r INT64> AS SELECT s, t` :: output mismatch: expected `CREATE TABLE FUNCTION a(x INT64) RETURNS TABLE <q STRING, r INT64> AS SELECT s, t` actual `CREATE FUNCTION a(x INT64) RETUR
- [bigquery] `CREATE TABLE IF NOT EXISTS foo AS SELECT * FROM bla EXCEPT DISTINCT (SELECT * FROM bar) LIMIT 0` :: output mismatch: expected `CREATE TABLE IF NOT EXISTS foo AS SELECT * FROM bla EXCEPT DISTINCT (SELECT * FROM bar) LIMIT 0` actual `CREATE TABLE IF NO
- [bigquery] `EXPORT DATA OPTIONS (URI='gs://bucket/folder/*.csv') AS (SELECT 1)` :: UnsupportedError: Unsupported expression type Export
- [bigquery] `EXPORT DATA OPTIONS (URI='gs://path*.csv.gz', FORMAT='CSV') AS SELECT * FROM all_rows` :: UnsupportedError: Unsupported expression type Export
- [bigquery] `EXPORT DATA WITH CONNECTION myproject.us.myconnection OPTIONS (URI='gs://path*.csv.gz', FORMAT='CSV'` :: UnsupportedError: Unsupported expression type Export
- [bigquery] `JSON_KEYS(PARSE_JSON('{"a": {"b":1}}'), 1, mode => 'lax')` :: output mismatch: expected `JSON_KEYS(PARSE_JSON('{"a": {"b":1}}'), 1, mode => 'lax')` actual `JSON_KEYS(PARSE_JSON('{"a": {"b":1}}'), 1)`
- [bigquery] `LAST_DAY(DATETIME '2008-11-10 15:30:00', WEEK(SUNDAY))` :: output mismatch: expected `LAST_DAY(CAST('2008-11-10 15:30:00' AS DATETIME), WEEK)` actual `LAST_DAY(CAST('2008-11-10 15:30:00' AS DATETIME), WEEK(SUN
- [bigquery] `PARSE_TIMESTAMP('%FT%H:%M:%E*S%z', x)` :: output mismatch: expected `PARSE_TIMESTAMP('%FT%H:%M:%E*S%z', x)` actual `PARSE_TIMESTAMP('%Y-%m-%dT%H:%M:%E*S%z', x)`
- [bigquery] `SAFE.PARSE_DATE('%Y-%m-%d', '2024-01-15')` :: output mismatch: expected `SAFE.PARSE_DATE('%F', '2024-01-15')` actual `SAFE.PARSE_DATE('%Y-%m-%d', '2024-01-15')`
- [bigquery] `SAFE.PARSE_DATETIME('%Y-%m-%d %H:%M:%S', '2024-01-15 10:30:00')` :: output mismatch: expected `SAFE.PARSE_DATETIME('%F %T', '2024-01-15 10:30:00')` actual `SAFE.PARSE_DATETIME('%Y-%m-%d %H:%M:%S', '2024-01-15 10:30:00'
- [bigquery] `SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', '2024-01-15 10:30:00')` :: output mismatch: expected `SAFE.PARSE_TIMESTAMP('%F %T', '2024-01-15 10:30:00')` actual `SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', '2024-01-15 10:30:0
- [bigquery] `SAFE.SUBSTR('foo', 0, -2)` :: output mismatch: expected `SAFE.SUBSTR('foo', 0, -2)` actual `SAFE.SUBSTRING('foo', 0, -2)`
- [bigquery] `SELECT '\n\r\a\v\f\t'` :: output mismatch: expected `SELECT '\n\r\a\v\f\t'` actual `SELECT '  '`
- [bigquery] `SELECT * FROM AI.FORECAST((SELECT * FROM citibike_trips), data_col => 'num_trips', timestamp_col => ` :: output mismatch: expected `SELECT * FROM AI.FORECAST((SELECT * FROM citibike_trips), data_col => 'num_trips', timestamp_col => 'date', horizon => 3` a
- [bigquery] `SELECT * FROM AI.FORECAST(TABLE citibike_trips, data_col => 'num_trips', timestamp_col => 'date', ho` :: output mismatch: expected `SELECT * FROM AI.FORECAST(TABLE citibike_trips, data_col => 'num_trips', timestamp_col => 'date', horizon => 30)` actual `S
- [bigquery] `SELECT * FROM AI.GENERATE_TABLE(MODEL `mydataset.gemini_model`, (SELECT 'Q' AS prompt), STRUCT('name` :: output mismatch: expected `SELECT * FROM AI.GENERATE_TABLE(MODEL `mydataset.gemini_model`, (SELECT 'Q' AS prompt), STRUCT('name STRING' AS output_s` a
- [bigquery] `SELECT * FROM AI.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUC` :: output mismatch: expected `SELECT * FROM AI.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUCT(0.15 AS temperatur` a
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE) ` :: output mismatch: expected `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE) ORDER BY time` actual `
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: output mismatch: expected `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, value_columns => [('` a
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: output mismatch: expected `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, value_columns => [('` a
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: output mismatch: expected `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, value_columns => [('` a
- [bigquery] `SELECT * FROM ML.FEATURES_AT_TIME(TABLE mydataset.feature_table, time => '2022-06-11 10:00:00+00', n` :: output mismatch: expected `SELECT * FROM ML.FEATURES_AT_TIME(TABLE mydataset.feature_table, time => '2022-06-11 10:00:00+00', num_rows => 1, ignore` a
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, (SELECT * FROM mydataset.query_table), STRUCT()` :: output mismatch: expected `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, (SELECT * FROM mydataset.query_table), STRUCT())` actual `SELECT * FRO
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, STRUCT(2 AS horizon))` :: output mismatch: expected `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, STRUCT(2 AS horizon))` actual `SELECT * FROM ML.M_L_FORECAST(`mydatase
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, TABLE `mydataset.mybqtable`, STRUCT(2 AS horizo` :: output mismatch: expected `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, TABLE `mydataset.mybqtable`, STRUCT(2 AS horizon, 4 AS confidence_l` a
- [bigquery] `SELECT * FROM ML.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUC` :: output mismatch: expected `SELECT * FROM ML.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUCT(0.15 AS temperatur` a
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL `my_project`.my_dataset.my_model, (SELECT * FROM input_data))` :: output mismatch: expected `SELECT * FROM ML.PREDICT(MODEL `my_project`.my_dataset.my_model, (SELECT * FROM input_data))` actual `SELECT * FROM ML.PRED
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.CONVERT_COLOR_SPACE(ML.RESIZ` :: output mismatch: expected `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.CONVERT_COLOR_SPACE(ML.RESIZE_IMAGE(ML.DECODE_IM` a
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.RESIZE_IMAGE(ML.DECODE_IMAGE` :: output mismatch: expected `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.RESIZE_IMAGE(ML.DECODE_IMAGE(data), 480, 480, FA` a
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT custom_label, column1, column2 FROM mydata` :: output mismatch: expected `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT custom_label, column1, column2 FROM mydataset.mytable), STRUCT` a
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT label, column1, column2 FROM mydataset.myt` :: output mismatch: expected `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT label, column1, column2 FROM mydataset.mytable))` actual `SELECT 
- [bigquery] `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mymodel`, (SELECT comment AS text_content FROM mydataset` :: output mismatch: expected `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mymodel`, (SELECT comment AS text_content FROM mydataset.mytable), STRUCT('t` a
- [bigquery] `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mytranslatemodel`, TABLE `mydataset.mybqtable`, STRUCT('` :: output mismatch: expected `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mytranslatemodel`, TABLE `mydataset.mybqtable`, STRUCT('translate_text' AS t` a
- [bigquery] `SELECT * FROM UNNEST(x) WITH OFFSET EXCEPT DISTINCT SELECT * FROM UNNEST(y) WITH OFFSET` :: output mismatch: expected `SELECT * FROM UNNEST(x) WITH OFFSET AS offset EXCEPT DISTINCT SELECT * FROM UNNEST(y) WITH OFFSET AS offset` actual `SELECT
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: output mismatch: expected `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_table)` actual `SELECT * 
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: output mismatch: expected `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_table, 'query_column_t` a
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: output mismatch: expected `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_table, query_column_to` a
- [bigquery] `SELECT * FROM foo AS t0 FOR SYSTEM_TIME AS OF '2026-02-12T23:22:21.743416+00:00'` :: output mismatch: expected `SELECT * FROM foo AS t0 FOR SYSTEM_TIME AS OF '2026-02-12T23:22:21.743416+00:00'` actual `SELECT * FROM foo FOR SYSTEM_TIME
- [bigquery] `SELECT * FROM test QUALIFY a IS DISTINCT FROM b WINDOW c AS (PARTITION BY d)` :: output mismatch: expected `SELECT * FROM test QUALIFY a IS DISTINCT FROM b WINDOW c AS (PARTITION BY d)` actual `SELECT * FROM test WINDOW c AS (PARTI
- [bigquery] `SELECT AI.GENERATE_BOOL(MODEL `mydataset.gemini_model`, 'Is sky blue?')` :: output mismatch: expected `SELECT AI.GENERATE_BOOL(MODEL `mydataset.gemini_model`, 'Is sky blue?')` actual `SELECT AI.GENERATE_BOOL(`mydataset.gemini_
- [bigquery] `SELECT CAST(date AS TIMESTAMP FORMAT ('YYYY-MM-DD HH24:MI:SS'))` :: output mismatch: expected `SELECT PARSE_TIMESTAMP('%F %T', date)` actual `SELECT PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', date)`
- [bigquery] `SELECT FORMAT_TIMESTAMP('%F %T', CURRENT_TIMESTAMP(), 'Europe/Berlin') AS ts` :: output mismatch: expected `SELECT FORMAT_TIMESTAMP('%F %T', CURRENT_TIMESTAMP(), 'Europe/Berlin') AS ts` actual `SELECT FORMAT_TIMESTAMP('%Y-%m-%d %H:
- [bigquery] `SELECT PARSE_DATETIME('%a %b %e %I:%M:%S %Y', 'Thu Dec 25 07:30:00 2008')` :: output mismatch: expected `SELECT PARSE_DATETIME('%a %b %e %I:%M:%S %Y', 'Thu Dec 25 07:30:00 2008')` actual `SELECT PARSE_DATETIME('%a %b %-d %I:%M:%
- [bigquery] `SELECT PARSE_TIMESTAMP('%c', 'Thu Dec 25 07:30:00 2008', 'UTC')` :: output mismatch: expected `SELECT PARSE_TIMESTAMP('%c', 'Thu Dec 25 07:30:00 2008', 'UTC')` actual `SELECT PARSE_TIMESTAMP('%a %b %e %H:%M:%S %Y', 'Th
- [bigquery] `SELECT a, b, c, d, e FROM GAP_FILL(TABLE foo, ts_column => 'b', partitioning_columns => ['a'], value` :: output mismatch: expected `SELECT a, b, c, d, e FROM GAP_FILL(TABLE foo, ts_column => 'b', partitioning_columns => ['a'], value_columns => [('c', '` a
- [bigquery] `SELECT label, predicted_label1, predicted_label AS predicted_label2 FROM ML.PREDICT(MODEL mydataset.` :: output mismatch: expected `SELECT label, predicted_label1, predicted_label AS predicted_label2 FROM ML.PREDICT(MODEL mydataset.mymodel2, (SELECT * ` a
- [bigquery] `SELECT y + 1 z FROM x GROUP BY y + 1 ORDER BY z` :: output mismatch: expected `SELECT y + 1 AS z FROM x GROUP BY z ORDER BY z` actual `SELECT y + 1 AS z FROM x GROUP BY y + 1 ORDER BY z`
- [bigquery] `WITH t AS (SELECT '{"x-y": "z"}' AS c) SELECT JSON_EXTRACT(c, '$.x-y') FROM t` :: output mismatch: expected `WITH t AS (SELECT '{"x-y": "z"}' AS c) SELECT JSON_EXTRACT(c, '$.x-y') FROM t` actual `WITH t AS (SELECT '{"x-y": "z"}' AS 
- [bigquery] `select array_contains([1, 2, 3], 1)` :: output mismatch: expected `SELECT EXISTS(SELECT 1 FROM UNNEST([1, 2, 3]) AS _col WHERE _col = 1)` actual `SELECT ARRAY_CONTAINS([1, 2, 3], 1)`

## spark  (6)

- [spark] `SELECT TRANSFORM(name, age) ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' LINES TERMINATED BY '\n' N` :: UnsupportedError: Unsupported expression type QueryTransform
- [spark] `SELECT TRANSFORM(x) USING 'x' AS (x INT) FROM t` :: UnsupportedError: Unsupported expression type QueryTransform
- [spark] `SELECT TRANSFORM(zip_code, name, age) ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpl` :: UnsupportedError: Unsupported expression type QueryTransform
- [spark] `SELECT TRANSFORM(zip_code, name, age) USING 'cat' AS (a STRING, b STRING, c STRING) FROM person WHER` :: UnsupportedError: Unsupported expression type QueryTransform
- [spark] `SELECT TRANSFORM(zip_code, name, age) USING 'cat' AS (a, b, c) FROM person WHERE zip_code > 94511` :: UnsupportedError: Unsupported expression type QueryTransform
- [spark] `SELECT TRANSFORM(zip_code, name, age) USING 'cat' FROM person WHERE zip_code > 94500` :: UnsupportedError: Unsupported expression type QueryTransform

## doris  (4)

- [doris] `CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1)` :: output mismatch: expected `CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1)` actual `CREATE MATERIALIZED VIEW test_table (c1, c2) KEY (c1
- [doris] `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (DATE_TRUNC(c2, 'MONTH'))` :: output mismatch: expected `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (DATE_TRUNC(c2, 'MONTH'))` actual `CREATE TABLE test_table (c1 INT, 
- [doris] `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c1, c2)` :: output mismatch: expected `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c1, c2)` actual `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITIO
- [doris] `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2)` :: output mismatch: expected `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2)` actual `CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY

## postgres  (2)

- [postgres] `
            WITH
              json_data AS (SELECT '{"field_id": [1, 2, 3]}'::JSON AS data),
     ` :: output mismatch: expected `WITH json_data AS (SELECT CAST('{"field_id": [1, 2, 3]}' AS JSON) AS data), field_ids AS (SELECT 'field_id' AS field_id)` a
- [postgres] `x::JSON -> 'duration' ->> -1` :: output mismatch: expected `CAST(x AS JSON) -> 'duration' ->> -1` actual `JSON_EXTRACT_PATH_TEXT(CAST(x AS JSON) -> 'duration', -1)`

## trino  (1)

- [trino] `JSON_QUERY(content, 'strict $.HY.*' WITHOUT CONDITIONAL WRAPPER)` :: output mismatch: expected `JSON_QUERY(content, 'strict $.HY.*' WITHOUT CONDITIONAL WRAPPER)` actual `JSON_QUERY(content, 'strict $.HY.*' WITHOUT WRAPP
