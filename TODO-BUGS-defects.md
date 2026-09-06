# TODO - in-scope defects (parse failures / mis-parses / crashes / bad analysis)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-93-gdcc36544a`. The read-only reference clone at `reference/sqlglot/`
> is the structural and behavioral oracle.
>
> The corpus gates under `brikk-sql/test@jvm/` compare parser ASTs and semantic
> analysis exactly with the generated fixtures under `brikk-sql/testResources/`.
> Their committed `*-known-failures.json` files remain authoritative.

---

**No remaining qualification cases. All 13 cases exposed by ASTRA-014 are resolved.**

ASTRA-014 enabled previously skipped supported cases. ASTRA-008 resolved the final
10 cases; `build/astra-008-core.log` lists their exact stale keys and reports
388/388 qualification assertions passing. Those 10 entries were removed from
`brikk-sql/brikk-sql/testResources/qualify-corpus/known-failures.json`, leaving it
empty. See `docs/corpus-coverage.md` for the historical baseline and current status.

- [x] G1: eight BigQuery UNNEST alias, field, star-expansion, and correlation cases,
  resolved under ASTRA-008.
- [x] G2: two BigQuery implicit UNNEST conversions, resolved under ASTRA-008.
- [x] G3: unwrap parenthesized SELECT operands during set-operation column discovery, one case.
- [x] G4: preserve StarRocks TableFromRows default output-column aliases, two cases.

This closes the exposed qualification cases, not all struct-array or offset
transpilation conversions. Remaining BigQuery parity cases are in `TODO-bigquery.md`.

The previous 162-item inventory remains resolved:

- 157 parser and semantic-analysis defects now match the pinned sqlglot oracle.
- 2 DataFusion entries were fixed: regex operators now parse natively, and the
  SLT extractor no longer treats upstream `query error` blocks as accepted SQL.
- 3 Trino `ALTER TABLE ... SET PROPERTIES` AST differences are intentional,
  verifier-backed grammar-legality extensions rather than defects. They remain
  in the exact parser and annotation parity ledgers and are documented in
  `docs/brikk-extensions.md` section 8.
