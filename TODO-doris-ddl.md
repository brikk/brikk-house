# TODO - Doris DDL coverage

> Brikk-native work (docs/brikk-extensions.md #19); sqlglot's Doris dialect only
> transpiles queries *into* Doris and never parses Doris DDL, so nothing here is
> sqlglot parity and none of it is expected to arrive via an upstream sync. Mark
> every site `// brikk-native (docs/brikk-extensions.md #19)`.
>
> Goal: `SHOW CREATE TABLE` output for every table we own parses to a structured
> `Create` (not `Command`) and round-trips; the operational statements a pipeline
> runtime issues (refresh MV, create index, alter partitions) parse structurally.
>
> Verification: hand tests in `brikk-sql/test/.../DorisDialectTest.kt` (parse ->
> `Create`, stable re-parse) and, for every rendering we emit, acceptance by the
> real Doris FE parser in `brikk-sql-verify` `SqlVerifierTest.dorisAccepts*`.
> When an item is done, move it to the "Done" section with the test name.
>
> Survey source: 144-statement parse probe + FE-parser check, Sep 2026 (throwaway,
> not committed). Re-run a similar probe before claiming anything else is covered.

Run: `./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.DorisDialectTest'`
and `./kotlin test -m brikk-sql-verify --include-classes='dev.brikk.house.sql.verify.SqlVerifierTest'`.

---

## A. `SHOW CREATE TABLE` output that still fails to parse (blockers)

All done, including the two edge cases (see "Done").

## B. Parses, but the generator renders invalid Doris

All done except:

- [ ] `BUCKETS AUTO` is dropped (absent = AUTO, so harmless; low priority).

## C. Statement-level DDL

Table-level DDL and the operational statements are all structured now (see "Done").

Deliberately left as opaque `Command` (cluster administration, no consumer in the
pipeline runtime; revisit if one appears):

- `CREATE | DROP | ALTER CATALOG`, `ALTER DATABASE .. SET PROPERTIES | SET DATA QUOTA`.
- `CREATE WORKLOAD GROUP`, `CREATE RESOURCE`, `CREATE STORAGE POLICY`, `CREATE ENCRYPTKEY`,
  `CREATE [ALIAS] FUNCTION`, `CREATE JOB .. ON SCHEDULE .. DO ..`.

Not a bug (checked against the FE grammar): a sync materialized view takes `PROPERTIES`
*before* `AS SELECT`, which sqlglot already parses; the trailing form is rejected by the FE.

## Done

- Sep 2026 (rest): group B renderings (`KEY`, `STRUCT<x:INT>`, `DEFAULT CURRENT_TIMESTAMP[(n)]`
  / `ON UPDATE` / `CURRENT_DATE`, `AS (expr)`, `AUTO_INCREMENT(n)`, `SHOW CREATE .. db.t`,
  `ALTER .. SET (..)`); C2 `ADD COLUMN .. TO rollup`; C3 `ADD COLUMN (..)`, `ORDER BY ..
  [FROM]`, `ENABLE FEATURE`, `MODIFY DISTRIBUTION | ENGINE | COMMENT`, `SHOW CREATE
  MATERIALIZED VIEW [ON t]`, `SHOW [TEMPORARY] PARTITIONS`, `SHOW DATA`; `BUILD INDEX`,
  `PAUSE | RESUME MATERIALIZED VIEW JOB`, `CANCEL MATERIALIZED VIEW TASK`, `RECOVER ..` as
  structured nodes (COMMAND body re-parse). Tests: `DorisDialectTest`
  `{commandWordStatementsParseStructurally, remainingAlterTableActions, dorisShowStatements,
  columnDefinitionRenderingsAreDorisNotMysql}`, `SqlVerifierTest.dorisAcceptsBrikkDdlRenderings`.

- Sep 2026 (statements): `REFRESH MATERIALIZED VIEW | CATALOG | DATABASE` (`DorisRefresh`),
  `CREATE INDEX .. USING .. PROPERTIES .. COMMENT` (`DorisIndexParameters`), `DROP INDEX ..
  ON db.t`, `ALTER TABLE ADD | DROP | REPLACE | MODIFY PARTITION`, `RENAME COLUMN | PARTITION |
  ROLLUP`, `ADD ROLLUP` (with `FROM base`), `REPLACE WITH TABLE`, `SET (..)` stable,
  `ALTER MATERIALIZED VIEW REFRESH | RENAME | SET | REPLACE WITH`, optional MV `REFRESH`
  method (`REFRESH ON COMMIT`), `DESC t ALL`, `CREATE TABLE .. LIKE .. WITH ROLLUP [(..)]`;
  `BUILD | CANCEL | PAUSE | RECOVER | RESUME ..` degrade to `Command`. A-adjacent:
  `VARIANT<MATCH_NAME 'a*':INT>` (`DorisVariantField.match`), `PARTITION IF NOT EXISTS` in
  CREATE TABLE lists (accepted, flag dropped). Tests: `DorisDialectTest` statement section,
  `SqlVerifierTest.dorisAcceptsBrikkDdlRenderings`.

- Sep 2026 (group A): `DATETIMEV2` / `DATEV2`; `AGG_STATE<fn(type [NOT NULL], ..)>`;
  typed `VARIANT<'a':T, .., properties(..)>`; partition definition lists mixing
  `LESS THAN (..)`, bare `MAXVALUE`, `[(..), (..))`, `VALUES IN`, `FROM..TO..INTERVAL`
  (numeric bounds, optional unit), per-partition `("k"="v")`, multi-column `LESS THAN`
  with per-column `MAXVALUE` (was rendered as a bracket range); `UNIQUE KEY .. ORDER BY (..)`
  and function RANGE partitions without `AUTO` verified. Tests:
  `DorisDialectTest.{legacyTypeSpellingsFoldIntoCurrentTypes,
  aggStateTypeKeepsFunctionSignatureAndArgNullability, typedVariantWithFieldsAndProperties,
  partitionDefinitionListsMixAllDorisEntryForms, uniqueKeyTableWithSortKeyAndFunctionRangePartition}`,
  `SqlVerifierTest.dorisAcceptsBrikkDdlRenderings`.

- Sep 2026, commit `70a3716` (docs/brikk-extensions.md #19): `LARGEINT`, `IPV4`/`IPV6`,
  `DECIMALV2`/`DECIMALV3`, `BITMAP`/`HLL`/`QUANTILE_STATE` types; `AGGREGATE KEY` +
  column aggregator suffix; `PARTITION BY RANGE|LIST (..) ()` empty definition list;
  `AUTO PARTITION BY`; `INDEX .. USING INVERTED PROPERTIES (..)`; `ROLLUP (..)` with the
  Doris `rollupDef` grammar. Tests: `DorisDialectTest` DDL section,
  `DorisTokenizerTest.dialectConfigAddsDorisStorageTypeKeywords`,
  `SqlVerifierTest.dorisAcceptsBrikkDdlRenderings`.
