# TODO - type-inference parity (in scope)

> The annotated corpus gates compare Brikk's full annotated ASTs with the
> pinned SQLGlot oracle. Their committed
> `brikk-sql/testResources/annotate-corpus/known-failures-*.json` ledgers are
> authoritative.

---

**0 actionable items.**

Every annotated corpus, including the newly ported StarRocks dialect, matches
the oracle except for 3 protected Trino `ALTER TABLE ... SET PROPERTIES`
entries. Those are parser-AST differences caused by Brikk's intentional,
verifier-backed grammar-legality extension, not type-inference defects. They
remain in the Trino annotation ledger and are documented in
`docs/brikk-extensions.md` section 8.

Run the authoritative gate with:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.AnnotateTypesCorpusTest'`
