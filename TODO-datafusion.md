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

**34 identity items.** The two available transpile fixtures already pass.

| Theme | Items | Representative difference |
|---|---:|---|
| Window-function capitalization | 11 | `ROW_NUMBER()` -> `row_number()` |
| Function aliases and spelling | 11 | `bool_and(x)` -> `logical_and(x)` |
| Cast and type spelling | 5 | `x::INT` -> `CAST(x AS INT)` |
| Negation and operator rendering | 4 | `x != 1` -> `x <> 1` |
| Explicit null ordering | 1 | `NULLS FIRST` is omitted |
| Explicit select quantifier | 1 | `SELECT ALL` -> `SELECT` |
| Array/unnest syntax | 1 | `UNNEST(ARRAY[...])` is normalized |

Most entries are exact-spelling or canonicalization decisions, not known
semantic failures. Preserve semantics first; decide and document DataFusion's
canonical output before changing generator-wide normalization. The explicit
`NULLS FIRST` case is the first semantic-risk item to investigate.

Primary implementation areas:

- `brikk-sql/src/dev.brikk.house.sql/dialects/DatafusionGenerator.kt`
- shared generator operator, cast, select, and ordering methods
- `brikk-sql/testResources/dialect-corpus/datafusion-fixtures.json`

When an item is fixed, remove it from the committed DataFusion ledger and run:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.DatafusionFixtureTest'`
