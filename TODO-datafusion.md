# TODO - DataFusion dialect parity

> DataFusion is a Brikk-native dialect; upstream SQLGlot has no DataFusion
> dialect to port. Its behavior is based on Polyglot's DataFusion fixtures and
> DataFusion SQL documentation. Keep this work separate from SQLGlot parity.
>
> The authoritative cases are in
> `brikk-sql/testResources/dialect-corpus/datafusion-fixtures-known-failures.json`.
> `DatafusionFixtureTest` requires exact parse-and-generate identity and rejects
> both new failures and stale ledger entries.

---

**0 identity or transpile items.** All 275 Polyglot-derived identity fixtures
and both available transpile directions pass exactly. The refreshed curated
DataFusion SLT corpus also parses 1208/1208 accepted statements with no ledger.

DataFusion-specific parser metadata preserves readable source choices that the
shared SQLGlot AST otherwise canonicalizes, including function aliases, `::`
casts, type spellings, predicate/operator spellings, `SELECT ALL`, and explicit
null ordering. These rules are isolated to the Brikk-native DataFusion dialect.

Primary implementation areas:

- `brikk-sql/src/dev.brikk.house.sql/dialects/DatafusionGenerator.kt`
- shared generator operator, cast, select, and ordering methods
- `brikk-sql/testResources/dialect-corpus/datafusion-fixtures.json`

When an item is fixed, remove it from the committed DataFusion ledger and run:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.DatafusionFixtureTest'`
