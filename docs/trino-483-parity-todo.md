# Trino 483 parity TODO

Reference grammar: Trino 483
(`core/trino-grammar/src/main/antlr4/io/trino/grammar/sql/SqlBase.g4`).

## Outstanding work

| Feature | Release | Required work |
|---|---:|---|
| `NEAREST` relation | 481 | Model and parse `NEAREST (FROM relation [WHERE ...] MATCH ...)`, integrate it with relation scoping, add Trino generation and corpus coverage, then add `NEAREST` to `TRINO_GRAMMAR_BUILTINS`. |

## Verification

For each completed item, add Trino parser/generator/serde corpus cases and an
acceptance fixture for the native Trino parser oracle where applicable.
