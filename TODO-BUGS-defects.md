# TODO - in-scope defects (parse failures / mis-parses / crashes / bad analysis)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-72-gbac1a897b`. The read-only reference clone at `reference/sqlglot/`
> is the structural and behavioral oracle.
>
> The corpus gates under `brikk-sql/test@jvm/` compare parser ASTs and semantic
> analysis exactly with the generated fixtures under `brikk-sql/testResources/`.
> Their committed `*-known-failures.json` files remain authoritative.

---

**0 actionable items.**

The previous 162-item inventory is resolved:

- 157 parser and semantic-analysis defects now match the pinned sqlglot oracle.
- 2 DataFusion entries were fixed: regex operators now parse natively, and the
  SLT extractor no longer treats upstream `query error` blocks as accepted SQL.
- 3 Trino `ALTER TABLE ... SET PROPERTIES` AST differences are intentional,
  verifier-backed grammar-legality extensions rather than defects. They remain
  in the exact parser and annotation parity ledgers and are documented in
  `docs/brikk-extensions.md` section 8.
