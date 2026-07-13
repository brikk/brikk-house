# Attributions

This repository is licensed under the Apache License 2.0 (see LICENSE). The components
below are derived from or include third-party work; their notices are retained here as
required by their licenses.

## sqlglot

`brikk-sql` is a Kotlin port of [sqlglot](https://github.com/tobymao/sqlglot) by Toby Mao
and contributors, licensed under the MIT License. The port covers the tokenizer, parser,
AST node catalog, SQL generator, and dialect implementations, and is pinned to upstream
version `v30.12.0-44-g93d16591`. Test fixtures under `brikk-sql/testResources/`
(identity/dialect/serde corpora) are derived from sqlglot's MIT-licensed test suite.

Ported code carries `// sqlglot: <symbol>` provenance comments referencing the Python
counterparts. Generated files (token tables, AST catalog, function registries) are
produced by the scripts in `tools/` from the pinned upstream source.

```
MIT License

Copyright (c) 2026 Toby Mao

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache Doris

Three Doris-derived artifacts are included, both from https://github.com/apache/doris and
https://github.com/apache/doris-website, both Apache License 2.0:

- `vendor/lib/doris-fe-sql-parser-*.jar` — a locally-built snapshot of Doris's standalone
  `fe-sql-parser` module (pre-release; see vendor/README.md for provenance and refresh
  procedure). Vendored in coordination with the doris-intellij-plugin project.
- `brikk-sql-metadata/.../GeneratedDorisFunctionCatalog.kt` — function names/aliases/kinds
  extracted from Doris's runtime function registry
  (`fe/fe-core/.../catalog/Builtin*Functions.java`) by `tools/generate_doris_functions.py`
  (adapted from the doris-intellij-plugin extraction script).
- `vendor/data/doris-since-versions.json` — per-function "first documented in" version,
  extracted from `apache/doris-website`'s function docs
  (`docs/sql-manual/sql-functions/`, `versioned_docs/version-*/sql-manual/sql-functions/`)
  by `tools/extract_doris_since_versions.py`; joined onto `FunctionDef.sinceVersion`. See
  vendor/README.md for the extraction method and its honesty caveats.

## Trino

`vendor/data/trino-functions-481.tsv` and the generated
`brikk-sql-metadata/.../GeneratedTrinoFunctionCatalog.kt` contain the built-in function
registry (names, signatures, kinds) extracted from [Trino](https://github.com/trinodb/trino)
version 481 via its own `SHOW FUNCTIONS` statement (see vendor/README.md for the generation
method). Trino is licensed under the Apache License 2.0. No Trino code is included — only
registry data produced by running the engine.

## DuckDB

The generated `brikk-sql-metadata/.../GeneratedDuckdbFunctionCatalog.kt` contains the
built-in function registry (names, signatures, kinds) extracted from
[DuckDB](https://github.com/duckdb/duckdb) v1.5.4 via its own `duckdb_functions()` view
(see vendor/README.md). No DuckDB code is included — only registry data produced by running
the engine. DuckDB is licensed under the MIT License:

```
Copyright 2018-2026 Stichting DuckDB Foundation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## polyglot (DataFusion fixture corpus)

`brikk-sql/testResources/dialect-corpus/datafusion-fixtures.json` is imported by
`tools/import_polyglot_datafusion_fixtures.py` from the hand-authored DataFusion fixture
suite of [polyglot](https://github.com/tobilg/polyglot) (a Rust sqlglot-alike), files
`crates/polyglot-sql/tests/custom_fixtures/datafusion/*.json`. sqlglot ships no DataFusion
dialect, so these fixtures (not a Python oracle) gate brikk's brikk-native `datafusion`
dialect, whose design also follows polyglot's `crates/polyglot-sql/src/dialects/datafusion.rs`.
polyglot is licensed under the MIT License:

```
MIT License

Copyright (c) 2026 TobiLG <github@tobilg.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache DataFusion (sqllogictest parse corpus)

`brikk-sql/testResources/dialect-corpus/datafusion-slt-parse.json` is extracted by
`tools/extract_datafusion_slt_corpus.py` from a curated subset of
[Apache DataFusion](https://github.com/apache/datafusion)'s own sqllogictest suite
(`datafusion/sqllogictest/test_files/*.slt`). It contains only SQL statement text (no
DataFusion code) used as a real-engine parse-acceptance backstop for brikk's `datafusion`
dialect. DataFusion is licensed under the Apache License 2.0.

## GoogleSQL pipe syntax

The pipe-syntax (`|>`) operator semantics implemented in `brikk-sql` (first-class
`PipeQuery`/stage nodes, desugaring behavior, operator set) follow the
[GoogleSQL pipe query syntax](https://cloud.google.com/bigquery/docs/reference/standard-sql/pipe-syntax)
specification published by Google (Apache 2.0-licensed documentation in the
googlesql/ZetaSQL project). No code from ZetaSQL is included.
