# TODO - in-scope defects (parse failures / mis-parses / crashes / bad analysis)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-93-gdcc36544a`. The read-only reference clone at `reference/sqlglot/`
> is the structural and behavioral oracle.
>
> The corpus gates under `brikk-sql/test@jvm/` compare parser ASTs and semantic
> analysis exactly with the generated fixtures under `brikk-sql/testResources/`.
> Their committed `*-known-failures.json` files remain authoritative.

---

**No remaining qualification cases.** The qualification ledger is empty.
Completed work and its verification history are in `docs/corpus-coverage.md`.

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
