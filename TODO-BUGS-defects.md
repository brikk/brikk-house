# TODO - in-scope defects (parse failures / mis-parses / crashes / bad analysis)

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
> **This file = the genuinely-wrong subset (highest priority).** In-scope SQL that we FAIL to
> parse, MIS-parse into a different AST, CRASH on, or mis-analyse (scope/qualify/lineage).
> Unlike the other three files (valid-but-non-canonical output), these are defects. Parser
> logic: `parser/Parser.kt` + `dialects/<D>Parser.kt` (reference `parser.py` / `parsers/<d>.py`).
> `[CRASH]` markers are exceptions thrown by our code — treat as robustness bugs.
---


**166 items** across 10 groups.


## bigquery  (98)

- [bigquery] `ARRAY(SELECT AS STRUCT e.x AS y, e.z AS bla FROM UNNEST(bob))::ARRAY<STRUCT<y STRING, bro NUMERIC>>` :: ast-mismatch at #28: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `CAST(STRUCT<a INT64>(1) AS STRUCT<a INT64>)` :: ParseError: Expected AS after CAST. Line 1, Col: 19. CAST(STRUCT<a INT64>(1) AS STRUCT<a INT64>) _(gates: transpile)_
- [bigquery] `CAST(encrypted_value AS STRING FORMAT 'BASE64')` :: CAST ... FORMAT is not supported yet. Line 1, Col: 46. _(gates: annotate,parser)_
- [bigquery] `CREATE OR REPLACE TABLE `a.b.c` CLONE `a.b.d`` :: ast-mismatch at #1: expected Table (k=this) _(gates: parser)_
- [bigquery] `CREATE OR REPLACE VIEW test (tenant_id OPTIONS (description='Test description on table creation')) A` :: Expecting ). Line 1, Col: 46. _(gates: parser)_
- [bigquery] `CREATE TABLE db.example_table (x int) PARTITION BY x cluster by x` :: ParseError: Expecting (. Line 1, Col: 65. CREATE TABLE db.example_table (x int) PARTITION BY x cluster by x _(gates: transpile)_
- [bigquery] `CREATE TABLE t CLUSTER BY col1, col2` :: Expecting (. Line 1, Col: 30. _(gates: annotate,parser)_
- [bigquery] `CREATE TABLE x (a STRING OPTIONS (description='x')) OPTIONS (table_expiration_days=1)` :: Expecting ). Line 1, Col: 32. _(gates: annotate,parser)_
- [bigquery] `CREATE TABLE x (a STRUCT<b STRING OPTIONS (description='b')>)` :: Expecting >. Line 1, Col: 41. _(gates: annotate,parser)_
- [bigquery] `CREATE VIEW `d.v` OPTIONS (expiration_timestamp=TIMESTAMP '2020-01-02T04:05:06.007Z') AS SELECT 1 AS` :: ast-mismatch at #1: expected Table (k=this) _(gates: parser)_
- [bigquery] `DATETIME_DIFF('2017-12-18', '2017-12-17', WEEK(MONDAY))` :: ast-mismatch: payload count expected=11 actual=10 _(gates: annotate,parser)_
- [bigquery] `DATE_TRUNC(col, MONTH, 'UTC+8')` :: ast-mismatch at #1: expected Literal (k=unit) _(gates: parser)_
- [bigquery] `EXPORT DATA OPTIONS (URI='gs://bucket/folder/*.csv') AS (SELECT 1)` :: ast-mismatch at #1: expected {"i":0,"k":"connection","v":false} _(gates: parser)_
- [bigquery] `EXPORT DATA OPTIONS (URI='gs://path*.csv.gz', FORMAT='CSV') AS SELECT * FROM all_rows` :: ast-mismatch at #1: expected {"i":0,"k":"connection","v":false} _(gates: parser)_
- [bigquery] `FOR record IN (SELECT word, word_count FROM bigquery-public-data.samples.shakespeare LIMIT 5) DO SEL` :: Required keyword: 'this' missing for Comprehension. Line 1, Col: 96. _(gates: annotate,parser)_
- [bigquery] `JSON_EXTRACT_STRING_ARRAY(PARSE_JSON('{"fruits": ["apples", "oranges", "grapes"]}'), '$.fruits')` :: ast-mismatch at #5: expected JSONPath (k=expression) _(gates: parser)_
- [bigquery] `JSON_KEYS(PARSE_JSON('{"a": {"b":1}}'), 1, mode => 'lax')` :: ast-mismatch: payload count expected=14 actual=8 _(gates: annotate,parser)_
- [bigquery] `LOG(n, b)` :: ast-mismatch at #2: expected Identifier (k=this) _(gates: parser)_
- [bigquery] `MERGE INTO dataset.NewArrivals USING (SELECT * FROM UNNEST([('microwave', 10, 'warehouse #1'), ('dry` :: ast-mismatch at #13: expected Array (k=expressions) _(gates: parser)_
- [bigquery] `REGEXP_EXTRACT(`foo`, 'bar: (.+?)', 1, 1)` :: ast-mismatch at #8: expected Literal (k=position) _(gates: parser)_
- [bigquery] `REGEXP_EXTRACT(svc_plugin_output, r'\\\((.*)')` :: ast-mismatch at #8: expected {"i":7,"k":"this","v":"1"} _(gates: parser)_
- [bigquery] `REGEXP_EXTRACT(x, '(?<)')` :: ast-mismatch at #8: expected {"i":0,"k":"null_if_pos_overflow","v":true} _(gates: parser)_
- [bigquery] `REGEXP_SUBSTR(value, pattern, position, occurrence)` :: ast-mismatch at #9: expected Column (k=position) _(gates: parser)_
- [bigquery] `SAFE_CAST(encrypted_value AS STRING FORMAT 'BASE64')` :: CAST ... FORMAT is not supported yet. Line 1, Col: 51. _(gates: annotate,parser)_
- [bigquery] `SAFE_CAST(some_date AS DATE FORMAT 'DD MONTH YYYY')` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 50. SAFE_CAST(some_date AS DATE FORMAT 'DD MONTH YYYY') _(gates: transpile)_
- [bigquery] `SAFE_CAST(some_date AS DATE FORMAT 'YYYY-MM-DD') AS some_date` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 47. SAFE_CAST(some_date AS DATE FORMAT 'YYYY-MM-DD') AS som _(gates: transpile)_
- [bigquery] `SELECT 'foo' 'bar'` :: Adjacent string literals are not supported yet. Line 1, Col: 18. _(gates: parser)_
- [bigquery] `SELECT 'foo'/* c */'bar'` :: Adjacent string literals are not supported yet. Line 1, Col: 24. _(gates: parser)_
- [bigquery] `SELECT * FROM (SELECT * FROM `t`) AS a UNPIVOT((c) FOR c_name IN (v1, v2))` :: ast-mismatch at #31: expected {"i":11,"k":"value_columns_first","v":false} _(gates: parser)_
- [bigquery] `SELECT * FROM AI.FORECAST((SELECT * FROM citibike_trips), data_col => 'num_trips', timestamp_col => ` :: ast-mismatch at #4: expected AIForecast (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM AI.FORECAST(TABLE citibike_trips, data_col => 'num_trips', timestamp_col => 'date', ho` :: Expecting ). Line 1, Col: 46. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM AI.GENERATE_TABLE(MODEL `mydataset.gemini_model`, (SELECT 'Q' AS prompt), STRUCT('name` :: Expecting ). Line 1, Col: 62. _(gates: parser)_
- [bigquery] `SELECT * FROM AI.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUC` :: Expecting ). Line 1, Col: 61. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE) ` :: Required keyword: 'ts_column' missing for GapFill. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: Required keyword: 'ts_column' missing for GapFill. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: Required keyword: 'ts_column' missing for GapFill. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM GAP_FILL(TABLE device_data, ts_column => 'time', bucket_width => INTERVAL '1' MINUTE, ` :: Required keyword: 'ts_column' missing for GapFill. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.FEATURES_AT_TIME((SELECT 1), num_rows => 1)` :: ast-mismatch at #4: expected FeaturesAtTime (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM ML.FEATURES_AT_TIME(TABLE mydataset.feature_table, time => '2022-06-11 10:00:00+00', n` :: Expecting ). Line 1, Col: 49. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, (SELECT * FROM mydataset.query_table), STRUCT()` :: Expecting ). Line 1, Col: 51. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, STRUCT(2 AS horizon))` :: Expecting ). Line 1, Col: 51. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.FORECAST(MODEL `mydataset.mymodel`, TABLE `mydataset.mybqtable`, STRUCT(2 AS horizo` :: Expecting ). Line 1, Col: 51. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.GENERATE_TEXT(MODEL `mydataset.gemini_model`, TABLE `mydataset.prompt_table`, STRUC` :: Expecting ). Line 1, Col: 61. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL `my_project`.my_dataset.my_model, (SELECT * FROM input_data))` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 43. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.CONVERT_COLOR_SPACE(ML.RESIZ` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 41. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL my_dataset.vision_model, (SELECT uri, ML.RESIZE_IMAGE(ML.DECODE_IMAGE` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 41. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT custom_label, column1, column2 FROM mydata` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.PREDICT(MODEL mydataset.mymodel, (SELECT label, column1, column2 FROM mydataset.myt` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 40. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mymodel`, (SELECT comment AS text_content FROM mydataset` :: Invalid expression / Unexpected token. Line 1, Col: 184. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM ML.TRANSLATE(MODEL `mydataset.mytranslatemodel`, TABLE `mydataset.mybqtable`, STRUCT('` :: Invalid expression / Unexpected token. Line 1, Col: 168. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM UNNEST(x) WITH OFFSET EXCEPT DISTINCT SELECT * FROM UNNEST(y) WITH OFFSET` :: ast-mismatch at #5: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `SELECT * FROM VECTOR_SEARCH((SELECT * FROM mydataset.base_table), 'column_to_search', (SELECT * FROM` :: ast-mismatch at #4: expected VectorSearch (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: Required keyword: 'column_to_search' missing for VectorSearch. Line 1, Col: 43. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: Required keyword: 'column_to_search' missing for VectorSearch. Line 1, Col: 43. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM VECTOR_SEARCH(TABLE mydataset.base_table, 'column_to_search', TABLE mydataset.query_ta` :: Required keyword: 'column_to_search' missing for VectorSearch. Line 1, Col: 43. _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM `a.b.com:project-id.mydataset.mytable`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `a.b.com:project-id.region-us.INFORMATION_SCHEMA.JOBS`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `domain.com:project-id.mydataset.mytable`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `domain.com:project-id.region-us.INFORMATION_SCHEMA.JOBS`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `domain.com:project-id.region-us.INFORMATION_SCHEMA`.JOBS` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `my-project.my-dataset.my-table`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM `proj.dataset.INFORMATION_SCHEMA.SOME_VIEW`` :: ast-mismatch at #3: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM proj.region_or_dataset.INFORMATION_SCHEMA.TABLES` :: ast-mismatch at #4: expected Identifier (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM q UNPIVOT(values FOR quarter IN (b, c))` :: ast-mismatch: payload count expected=26 actual=25 _(gates: annotate,parser)_
- [bigquery] `SELECT * FROM region_or_dataset.INFORMATION_SCHEMA.TABLES AS some_name` :: ast-mismatch at #4: expected Identifier (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM region_or_dataset.INFORMATION_SCHEMA.TABLES` :: ast-mismatch at #4: expected Identifier (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM x-0.a` :: ast-mismatch at #5: expected {"i":4,"k":"this","v":"a"} _(gates: parser)_
- [bigquery] `SELECT * FROM x-0.y` :: ast-mismatch at #5: expected {"i":4,"k":"this","v":"y"} _(gates: parser)_
- [bigquery] `SELECT * FROM x.*` :: ast-mismatch at #4: expected Identifier (k=this) _(gates: parser)_
- [bigquery] `SELECT * FROM x.y*` :: ast-mismatch at #5: expected {"i":4,"k":"this","v":"y*"} _(gates: parser)_
- [bigquery] `SELECT AI.EMBED('hello')` :: ast-mismatch at #5: expected AIEmbed (k=expression) _(gates: parser)_
- [bigquery] `SELECT AI.GENERATE('Write a haiku')` :: ast-mismatch at #5: expected AIGenerate (k=expression) _(gates: parser)_
- [bigquery] `SELECT AI.GENERATE_BOOL(MODEL `mydataset.gemini_model`, 'Is sky blue?')` :: Expecting ). Line 1, Col: 54. _(gates: annotate,parser)_
- [bigquery] `SELECT AI.SIMILARITY('a', 'b')` :: ast-mismatch at #5: expected AISimilarity (k=expression) _(gates: parser)_
- [bigquery] `SELECT CAST('20201225' AS TIMESTAMP FORMAT 'YYYYMMDD' AT TIME ZONE 'America/New_York')` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 53. SELECT CAST('20201225' AS TIMESTAMP FORMAT 'YYYYMMDD' A _(gates: transpile)_
- [bigquery] `SELECT CAST('20201225' AS TIMESTAMP FORMAT 'YYYYMMDD')` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 53. SELECT CAST('20201225' AS TIMESTAMP FORMAT 'YYYYMMDD') _(gates: transpile)_
- [bigquery] `SELECT CAST('2026-03-24' AS STRING FORMAT ('YYYY'))` :: CAST ... FORMAT is not supported yet. Line 1, Col: 43. _(gates: parser)_
- [bigquery] `SELECT CAST(CURRENT_DATE AS STRING FORMAT 'DAY') AS current_day` :: CAST ... FORMAT is not supported yet. Line 1, Col: 47. _(gates: parser)_
- [bigquery] `SELECT CAST(TIMESTAMP '2008-12-25 00:00:00+00:00' AS STRING FORMAT 'YYYY-MM-DD HH24:MI:SS TZH:TZM' A` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 98. SELECT CAST(TIMESTAMP '2008-12-25 00:00:00+00:00' AS STRING FOR _(gates: transpile)_
- [bigquery] `SELECT CAST(TIMESTAMP '2008-12-25 00:00:00+00:00' AS STRING FORMAT 'YYYY-MM-DD HH24:MI:SS TZH:TZM') ` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 98. SELECT CAST(TIMESTAMP '2008-12-25 00:00:00+00:00' AS STRING FOR _(gates: transpile)_
- [bigquery] `SELECT CAST(date AS STRING FORMAT ('YYYY')) FROM (SELECT DATE('2026-03-24') AS date)` :: CAST ... FORMAT is not supported yet. Line 1, Col: 35. _(gates: parser)_
- [bigquery] `SELECT CAST(date AS STRING FORMAT ('YYYY-MM-DD'))` :: CAST ... FORMAT is not supported yet. Line 1, Col: 35. _(gates: parser)_
- [bigquery] `SELECT CAST(date AS TIMESTAMP FORMAT ('YYYY-MM-DD HH24:MI:SS'))` :: CAST ... FORMAT is not supported yet. Line 1, Col: 38. _(gates: parser)_
- [bigquery] `SELECT CAST(timestamp AS STRING FORMAT ('YYYY-MM-DD') AT TIME ZONE 'UTC')` :: CAST ... FORMAT is not supported yet. Line 1, Col: 40. _(gates: parser)_
- [bigquery] `SELECT STRUCT<ARRAY<STRING>>(["2023-01-17"])` :: ast-mismatch at #1: expected Cast (k=expressions) _(gates: parser)_
- [bigquery] `SELECT STRUCT<STRING>((SELECT 'foo')).*` :: ast-mismatch at #1: expected Dot (k=expressions) _(gates: parser)_
- [bigquery] `SELECT STRUCT<a INT64, b STRUCT<c STRING>>(1, STRUCT('c_str'))` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 32. SELECT STRUCT<a INT64, b STRUCT<c STRING>>(1, STRUCT(' _(gates: transpile)_
- [bigquery] `SELECT `db.t`.`c` FROM `db.t`` :: ast-mismatch at #1: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `SELECT `p.d.UdF`(data) FROM `p.d.t`` :: ast-mismatch at #10: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT `p.d.UdF`(data).* FROM `p.d.t`` :: ast-mismatch at #12: expected Table (k=this) _(gates: parser)_
- [bigquery] `SELECT `p.d.t`.`c`.`f` FROM `p.d.t`` :: ast-mismatch at #1: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `SELECT a, b, c, d, e FROM GAP_FILL(TABLE foo, ts_column => 'b', partitioning_columns => ['a'], value` :: Required keyword: 'ts_column' missing for GapFill. Line 1, Col: 44. _(gates: annotate,parser)_
- [bigquery] `SELECT foo IN UNNEST(bar) AS bla` :: ast-mismatch at #8: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `SELECT label, predicted_label1, predicted_label AS predicted_label2 FROM ML.PREDICT(MODEL mydataset.` :: Required keyword: 'expression' missing for Predict. Line 1, Col: 99. _(gates: annotate,parser)_
- [bigquery] `WITH foo AS (SELECT [1, 2, 3] AS array_col) SELECT array_col[offset] FROM foo CROSS JOIN UNNEST(arra` :: ast-mismatch at #17: expected Column (k=expressions) _(gates: parser)_
- [bigquery] `WITH t AS (SELECT '{"x-y": "z"}' AS c) SELECT JSON_EXTRACT(c, '$.x-y') FROM t` :: ast-mismatch at #6: expected JSONPath (k=expression) _(gates: parser)_
- [bigquery] `cast(x as date format 'MM/DD/YYYY')` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 34. cast(x as date format 'MM/DD/YYYY') _(gates: transpile)_
- [bigquery] `cast(x as time format 'YYYY.MM.DD HH:MI:SSTZH')` :: ParseError: CAST ... FORMAT is not supported yet. Line 1, Col: 46. cast(x as time format 'YYYY.MM.DD HH:MI:SSTZH') _(gates: transpile)_

## scope  (22)

- [scope] `((SELECT 1) EXCEPT (SELECT 2))` :: scope #2 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `((SELECT 1)) LIMIT 1` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `(SELECT 1 UNION SELECT 2) ORDER BY x LIMIT 1 OFFSET 1` :: scope #2 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `(SELECT 1) ORDER BY x LIMIT 1 OFFSET 1` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE ALGORITHM=UNDEFINED DEFINER=foo@% VIEW a SQL SECURITY DEFINER AS (SELECT a FROM b)` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE TABLE T3 AS (SELECT DISTINCT A FROM T1 EXCEPT (SELECT A FROM T2) LIMIT 1)` :: scope #0 is_correlated_subquery: expected false got true _(gates: scope)_
- [scope] `CREATE TABLE a.b AS (SELECT 1) NO PRIMARY INDEX` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE TABLE a.b AS (SELECT 1) PRIMARY AMP INDEX index1 (a) UNIQUE INDEX index2 (b)` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE TABLE a.b AS (SELECT 1) UNIQUE PRIMARY INDEX index1 (a) UNIQUE INDEX index2 (b)` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE TABLE z AS ((WITH cte AS (SELECT 1) SELECT * FROM cte))` :: scope #1 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `CREATE TABLE z AS (WITH cte AS (SELECT 1) SELECT * FROM cte)` :: scope #1 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `DELETE FROM event AS event USING sales AS s WHERE event.eventid = s.eventid` :: kind mismatch: expected scopes, got skipped _(gates: scope)_
- [scope] `DELETE FROM event USING sales AS s WHERE event.eventid = s.eventid` :: kind mismatch: expected scopes, got skipped _(gates: scope)_
- [scope] `DELETE FROM event USING sales WHERE event.eventid = sales.eventid` :: kind mismatch: expected scopes, got skipped _(gates: scope)_
- [scope] `DELETE FROM event USING sales, bla WHERE event.eventid = sales.eventid` :: kind mismatch: expected scopes, got skipped _(gates: scope)_
- [scope] `DELETE FROM t1 WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t1.id)` :: scope #0 scope_type: expected "SUBQUERY" got "ROOT" _(gates: scope)_
- [scope] `INSERT INTO result_table (WITH test AS (SELECT * FROM source_table) SELECT * FROM test)` :: scope #1 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `INSERT INTO x (SELECT * FROM y)` :: scope #0 scope_type: expected "DERIVED_TABLE" got "SUBQUERY" _(gates: scope)_
- [scope] `MERGE INTO a USING (SELECT id FROM b) AS s ON a.id = s.id WHEN MATCHED THEN UPDATE SET a.x = s.id` :: scope count mismatch: expected 1, got 2 _(gates: scope)_
- [scope] `UPDATE customers SET total_spent = (SELECT 1 FROM t1) WHERE EXISTS (SELECT 1 FROM t2)` :: scope count mismatch: expected 2, got 3 _(gates: scope)_
- [scope] `UPDATE tbl1 SET col = 1 WHERE EXISTS (SELECT 1 FROM tbl2 WHERE tbl1.id = tbl2.id)` :: scope #0 scope_type: expected "SUBQUERY" got "ROOT" _(gates: scope)_
- [scope] `WITH baz AS (SELECT 1 AS col) UPDATE bar SET cid = baz.col1 FROM baz` :: scope count mismatch: expected 2, got 1 _(gates: scope)_

## qualify  (19)

- [CRASH] [qualify] `qualify_columns::SELECT baz FROM (SELECT 1 AS foo, 2 AS bar UNION BY NAME SELECT 3 AS bar, 4 AS baz)` :: OptimizeError: Column 'baz' could not be resolved. _(gates: qualify)_
- [CRASH] [qualify] `qualify_columns::SELECT piv.* FROM (SELECT id, jan, feb FROM unpivotable) UNPIVOT(revenue FOR month ` :: OptimizeError: Unknown table: piv _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * EXCEPT (y.b) FROM x AS x JOIN y AS y ON x.b = y.b` :: output mismatch: expected: SELECT x.a AS a, x.b AS b, y.c AS c FROM x AS x JOIN y AS y ON x.b = y.b actual: SELECT x.a AS a, y.c AS c FROM x AS x JOIN _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM (SELECT * FROM (SELECT * FROM x CROSS JOIN y) AS s) AS t` :: output mismatch: expected: SELECT * FROM (SELECT * FROM (SELECT x.a AS a, x.b AS b, y.b AS b, y.c AS c FROM x AS x CROSS JOIN y AS y) AS s) AS t actua _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM (SELECT * FROM x CROSS JOIN y) AS s` :: output mismatch: expected: SELECT * FROM (SELECT x.a AS a, x.b AS b, y.b AS b, y.c AS c FROM x AS x CROSS JOIN y AS y) AS s actual: SELECT s.a AS a, s _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM (SELECT *, a AS extra FROM x CROSS JOIN y) AS s` :: output mismatch: expected: SELECT * FROM (SELECT x.a AS a, x.b AS b, y.b AS b, y.c AS c, x.a AS extra FROM x AS x CROSS JOIN y AS y) AS s actual: SELE _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM (SELECT 1 AS foo, 2 AS bar UNION ALL BY NAME SELECT 3 AS bar, 4 AS ba` :: output mismatch: expected: SELECT _0.foo AS foo, _0.bar AS bar, _0.baz AS baz FROM (SELECT 1 AS foo, 2 AS bar UNION ALL BY NAME SELECT 3 AS bar, 4 AS  _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM (SELECT a AS k, a AS k FROM x) AS s` :: output mismatch: expected: SELECT * FROM (SELECT x.a AS k, x.a AS k FROM x AS x) AS s actual: SELECT s.k AS k, s.k AS k FROM (SELECT x.a AS k, x.a AS  _(gates: qualify)_
- [qualify] `qualify_columns::SELECT * FROM ROWS FROM (GENERATE_SERIES(1, 3), GENERATE_SERIES(10, 12)) AS t(a, b)` :: ParseError: ROWS FROM is not supported yet. Line 1, Col: 18. SELECT * FROM ROWS FROM (GENERATE_SERIES(1, 3), GENERATE_SERIES(10, 12)) AS t(a, b) _(gates: qualify)_
- [qualify] `qualify_columns::SELECT SUM(x.a) AS d FROM x JOIN y ON x.b = y.b GROUP BY UPPER(d)` :: output mismatch: expected: SELECT SUM(x.a) AS d FROM x AS x JOIN y AS y ON x.b = y.b GROUP BY UPPER(d) actual: SELECT SUM(x.a) AS d FROM x AS x JOIN y _(gates: qualify)_
- [qualify] `qualify_columns::SELECT SUM(x.a) AS d FROM x JOIN y ON x.b = y.b GROUP BY d` :: output mismatch: expected: SELECT SUM(x.a) AS d FROM x AS x JOIN y AS y ON x.b = y.b GROUP BY d actual: SELECT SUM(x.a) AS d FROM x AS x JOIN y AS y O _(gates: qualify)_
- [qualify] `qualify_columns::SELECT id FROM t WHERE id > (SELECT AVG(id) FROM t AS t2 WHERE t2.k = t.k)` :: output mismatch: expected: SELECT t.id AS id FROM t AS t WHERE t.id > (SELECT AVG(t2.id) AS _col_0 FROM t AS t2 WHERE t2.k = t.k) actual: SELECT t.id  _(gates: qualify)_
- [qualify] `qualify_columns::SELECT id FROM t WHERE id > (SELECT AVG(id) FROM u WHERE u.name = t.name)` :: output mismatch: expected: SELECT t.id AS id FROM t AS t WHERE t.id > (SELECT AVG(u.id) AS _col_0 FROM u AS u WHERE u.name = t.name) actual: SELECT t. _(gates: qualify)_
- [qualify] `qualify_columns::SELECT id FROM t WHERE id > (SELECT AVG(u) FROM u WHERE u.k = t.k)` :: output mismatch: expected: SELECT t.id AS id FROM t AS t WHERE t.id > (SELECT AVG(u.u) AS _col_0 FROM u AS u WHERE u.k = t.k) actual: SELECT t.id AS i _(gates: qualify)_
- [qualify] `qualify_columns::SELECT s.* FROM (SELECT * FROM x CROSS JOIN y) AS s` :: output mismatch: expected: SELECT s.* FROM (SELECT x.a AS a, x.b AS b, y.b AS b, y.c AS c FROM x AS x CROSS JOIN y AS y) AS s actual: SELECT s.a AS a, _(gates: qualify)_
- [qualify] `qualify_columns::WITH t AS (SELECT 1 AS id, 100 AS jan, 200 AS feb, 7 AS north, 8 AS south) SELECT *` :: output mismatch: expected: WITH t AS (SELECT 1 AS id, 100 AS jan, 200 AS feb, 7 AS north, 8 AS south) SELECT t.id AS id, t.month AS month, t.revenue A _(gates: qualify)_
- [qualify] `qualify_columns::WITH t AS (SELECT 1 AS id, 100 AS jan, 200 AS feb, 7 AS north, 8 AS south) SELECT u` :: output mismatch: expected: WITH t AS (SELECT 1 AS id, 100 AS jan, 200 AS feb, 7 AS north, 8 AS south) SELECT u.month AS month, u.headcount AS headcoun _(gates: qualify)_
- [qualify] `qualify_tables::SELECT t.a, x FROM t, UNNEST(t.arr) AS x` :: output mismatch: expected: SELECT _0.a, x FROM c.db.t AS _0, UNNEST(_0.arr) AS _1 actual: SELECT t.a, x FROM c.db.t AS _1, UNNEST(t.arr) AS _2 _(gates: qualify)_
- [qualify] `qualify_tables::SELECT t.a, x, z FROM t, UNNEST(t.arr) AS x, UNNEST(x.b) AS z` :: output mismatch: expected: SELECT _0.a, x, z FROM c.db.t AS _0, UNNEST(_0.arr) AS _1, UNNEST(_1.b) AS _2 actual: SELECT t.a, x, z FROM c.db.t AS _3, U _(gates: qualify)_

## clickhouse  (9)

- [clickhouse] `CREATE TABLE data5 ("x" UInt32, "y" UInt32) ENGINE=MergeTree ORDER BY (round(y / 1000000000), cityHa` :: ast-mismatch at #45: expected {"i":36,"k":"safe","v":false} _(gates: parser)_
- [clickhouse] `SELECT * FROM (SELECT a FROM b SAMPLE 1 / 10 OFFSET 1 / 2)` :: ast-mismatch at #23: expected {"i":15,"k":"safe","v":false} _(gates: parser)_
- [clickhouse] `SELECT and(1, 2)` :: ast-mismatch at #1: expected Paren (k=expressions) _(gates: parser)_
- [clickhouse] `SELECT and(1, 2, 3)` :: ast-mismatch at #1: expected Paren (k=expressions) _(gates: parser)_
- [clickhouse] `SELECT arrayConcat([1, 2], [3, 4])` :: ast-mismatch at #9: expected Array (k=expressions) _(gates: parser)_
- [clickhouse] `SELECT or(0, 1, -2)` :: ast-mismatch at #1: expected Paren (k=expressions) _(gates: parser)_
- [clickhouse] `SELECT or(1, 2)` :: ast-mismatch at #1: expected Paren (k=expressions) _(gates: parser)_
- [clickhouse] `SELECT or(and(3, 0), 5)` :: ast-mismatch at #1: expected Paren (k=expressions) _(gates: parser)_
- [clickhouse] `arrayConcat([1, 2], [3, 4])` :: ast-mismatch at #8: expected Array (k=expressions) _(gates: parser)_

## postgres  (7)

- [postgres] `'x' 'y' 'z'` :: Adjacent string literals are not supported yet. Line 1, Col: 7. _(gates: annotate,parser)_
- [postgres] `SELECT * FROM ROWS FROM (FUNC1(col1) AS alias1("col1" TEXT), FUNC2(col2) AS alias2("col2" INT)) WITH` :: ROWS FROM is not supported yet. Line 1, Col: 18. _(gates: annotate,parser)_
- [postgres] `SELECT * FROM ROWS FROM (FUNC1(col1, col2))` :: ROWS FROM is not supported yet. Line 1, Col: 18. _(gates: annotate,parser)_
- [postgres] `SELECT * FROM table1, ROWS FROM (FUNC1(col1) AS alias1("col1" TEXT)) WITH ORDINALITY AS alias3("col3` :: Invalid expression / Unexpected token. Line 1, Col: 26. _(gates: annotate,parser)_
- [postgres] `SELECT id, email, CAST(deleted AS TEXT) FROM users WHERE deleted NOTNULL` :: ast-mismatch at #23: expected Is (k=this) _(gates: parser)_
- [postgres] `WITH RECURSIVE search_graph(id, link, data, depth) AS (SELECT g.id, g.link, g.data, 1 FROM graph AS ` :: WITH ... SEARCH is not supported yet. Line 1, Col: 220. _(gates: annotate,parser)_
- [postgres] `WITH t(c) AS (SELECT 1) SELECT * INTO UNLOGGED foo FROM (SELECT c AS c FROM t) AS temp` :: SELECT INTO is not supported yet. Line 1, Col: 46. _(gates: annotate,parser)_

## base  (3)

- [base] `SELECT 1 FROM a.b.table1 AS t UNPIVOT((c3) FOR c4 IN (a, b))` :: ast-mismatch: payload count expected=40 actual=39 _(gates: annotate,parser)_
- [base] `SELECT a FROM test PIVOT(SUM(x) FOR y IN ('z', 'q')) UNPIVOT(x FOR y IN (z, q)) AS x` :: ast-mismatch at #56: expected {"i":38,"k":"value_columns_first","v":false} _(gates: parser)_
- [base] `SELECT a FROM test UNPIVOT(x FOR y IN (z, q)) AS x` :: ast-mismatch at #28: expected {"i":10,"k":"value_columns_first","v":false} _(gates: parser)_

## trino  (3)

- [trino] `ALTER TABLE people SET PROPERTIES foo = 123, 'foo bar' = 456` :: ast-mismatch at #0: expected Command _(gates: parser)_
- [trino] `ALTER TABLE people SET PROPERTIES x = 'y'` :: ast-mismatch at #0: expected Command _(gates: parser)_
- [trino] `ALTER TABLE people SET PROPERTIES x = DEFAULT` :: ast-mismatch at #0: expected Command _(gates: parser)_

## datafusion  (2)

- [datafusion] `identity|SELECT x ~ 'pattern' FROM t` :: ParseError: Invalid expression / Unexpected token. Line 1, Col: 10. SELECT x ~ 'pattern' FROM t _(gates: transpile)_
- [datafusion] `select.slt:856` :: ParseError: Required keyword: 'expression' missing for Mul. Line 1, Col: 26. SELECT DISTINCT ALL * FROM aggregate_simple |sql=SELECT DISTINCT ALL * FR _(gates: transpile)_

## lineage  (2)

- [lineage] `test_chained_pivots_consuming_alias_columns#1` :: graph mismatch: expected: {"downstream": [{"downstream": [], "expression": "sales AS sales UNPIVOT(score FOR month IN (jan, feb)) AS u1(a, b, c, d) UN _(gates: lineage)_
- [lineage] `test_chained_pivots_consuming_alias_columns#2` :: graph mismatch: expected: {"downstream": [{"downstream": [], "expression": "sales AS sales UNPIVOT(score FOR month IN (jan, feb)) AS u1(a, b, c, d) UN _(gates: lineage)_

## duckdb  (1)

- [duckdb] `SELECT $🦆$foo$🦆$` :: ast-mismatch at #1: expected RawString (k=expressions) _(gates: parser)_
