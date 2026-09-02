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

## B. Parses, but the generator renders invalid Doris (matters once we emit DDL)

All FE-parser verified rejections:

- [ ] `k INT KEY` -> `k INT PRIMARY KEY` (MySQL inheritance). Doris wants bare `KEY`.
- [ ] `STRUCT<x:INT>` -> `STRUCT<x INT>`. Doris requires the colon.
- [ ] `DEFAULT CURRENT_TIMESTAMP[(n)]` -> `DEFAULT NOW()`. Doris only allows
      `BITMAP_EMPTY | CURRENT_DATE | CURRENT_TIMESTAMP[(n)] | E | NULL | PI | literal`.
      Also `ON UPDATE CURRENT_TIMESTAMP`.
- [ ] `b INT AS (a + 1)` -> `GENERATED ALWAYS AS (..) VIRTUAL`. Doris rejects `VIRTUAL`
      (and `GENERATED ALWAYS`); render `AS (expr)`.
- [ ] `AUTO_INCREMENT(100)` start value is dropped.
- [ ] `SHOW CREATE TABLE db.t` -> `SHOW CREATE TABLE t FROM db` (FE rejects; keep the
      dotted name).
- [ ] `ALTER TABLE t SET ("k" = "v")` renders without parens (FE rejects) and the
      re-parse flips to `SET PROPERTIES (..)` — unstable.
- [ ] `BUCKETS AUTO` is dropped (absent = AUTO, so harmless; low priority).

## C. Statement-level DDL

### C1. Hard parse failures (not even `Command`)

All done (structured nodes or `Command` degradation; see "Done").

### C2. Mis-parses (wrong AST, no error)

- [x] `ALTER TABLE t MODIFY PARTITION (p1, p2) SET (..)` -> now `DorisModifyPartition`.
- [ ] `ALTER TABLE t ADD COLUMN c INT TO r1` -> `ADD COLUMN c INT NULL, TO r1` (rollup
      target dropped into a bogus second action).

### C3. Opaque `Command` (text preserved, no structure)

Operationally relevant first:

- [x] `CREATE INDEX ...` — structured (`DorisIndexParameters`).
- [x] `ALTER MATERIALIZED VIEW ...` — structured.
- [x] `ALTER TABLE .. ADD | DROP | REPLACE | MODIFY PARTITION`, `RENAME COLUMN | PARTITION | ROLLUP`,
      `ADD ROLLUP`, `REPLACE WITH TABLE` — structured.
- [ ] `ALTER TABLE t ADD COLUMN (c1 INT, c2 STRING)` multi-column form.
- [ ] `ALTER TABLE t ORDER BY (cols) [FROM rollup]`, `ENABLE FEATURE "..."`,
      `MODIFY DISTRIBUTION DISTRIBUTED BY ..`, `MODIFY ENGINE TO ..`, `MODIFY COMMENT '..'`.
- [ ] `CREATE MATERIALIZED VIEW mv AS SELECT .. PROPERTIES (..)` (sync MV with trailing
      PROPERTIES).
- [ ] `SHOW CREATE MATERIALIZED VIEW mv`, `SHOW PARTITIONS FROM t`, `SHOW DATA FROM t`.
- [ ] `PAUSE | RESUME MATERIALIZED VIEW JOB ON mv`, `CANCEL MATERIALIZED VIEW TASK n ON mv`,
      `BUILD INDEX idx ON t [PARTITIONS (..)]`, `RECOVER TABLE | PARTITION | DATABASE` —
      degrade to `Command` now; structured nodes if a runtime needs to inspect them.

Lower priority (cluster administration, not table DDL):

- [ ] `CREATE | DROP | ALTER CATALOG`, `ALTER DATABASE .. SET PROPERTIES | SET DATA QUOTA`.
- [ ] `CREATE WORKLOAD GROUP`, `CREATE RESOURCE`, `CREATE STORAGE POLICY`,
      `CREATE ENCRYPTKEY`, `CREATE [ALIAS] FUNCTION`, `CREATE JOB .. ON SCHEDULE .. DO ..`.

## Done

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
