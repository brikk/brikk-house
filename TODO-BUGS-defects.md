# TODO - in-scope defects (parse failures / mis-parses / crashes / bad analysis)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-93-gdcc36544a`. The read-only reference clone at `reference/sqlglot/`
> is the structural and behavioral oracle.
>
> The corpus gates under `brikk-sql/test@jvm/` compare parser ASTs and semantic
> analysis exactly with the generated fixtures under `brikk-sql/testResources/`.
> Their committed `*-known-failures.json` files remain authoritative.

---

**12 remaining qualification cases in three defect groups.**

ASTRA-014 enabled previously skipped supported cases. Their exact keys and reviewed
causes are in `brikk-sql/brikk-sql/testResources/qualify-corpus/known-failures.json`;
see `docs/corpus-coverage.md` for the execution inventory. These remain open:

- [ ] G1: eight BigQuery UNNEST alias, field, star-expansion, and correlation cases,
  under ASTRA-008's coordinated parser/resolver/generator work.
- [ ] G2: two BigQuery implicit UNNEST conversions, also ASTRA-008.
- [x] G3: unwrap parenthesized SELECT operands during set-operation column discovery.
- [ ] G4: preserve StarRocks TableFromRows default output-column aliases, two cases.

The previous 162-item inventory remains resolved:

- 157 parser and semantic-analysis defects now match the pinned sqlglot oracle.
- 2 DataFusion entries were fixed: regex operators now parse natively, and the
  SLT extractor no longer treats upstream `query error` blocks as accepted SQL.
- 3 Trino `ALTER TABLE ... SET PROPERTIES` AST differences are intentional,
  verifier-backed grammar-legality extensions rather than defects. They remain
  in the exact parser and annotation parity ledgers and are documented in
  `docs/brikk-extensions.md` section 8.
