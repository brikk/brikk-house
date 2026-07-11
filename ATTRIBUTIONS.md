# Attributions

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

Two Doris-derived artifacts are included, both Apache License 2.0
(https://github.com/apache/doris):

- `vendor/lib/doris-fe-sql-parser-*.jar` — a locally-built snapshot of Doris's standalone
  `fe-sql-parser` module (pre-release; see vendor/README.md for provenance and refresh
  procedure). Vendored in coordination with the doris-intellij-plugin project.
- `brikk-sql/.../dialects/GeneratedDorisFunctionCatalog.kt` — function names/aliases/kinds
  extracted from Doris's runtime function registry
  (`fe/fe-core/.../catalog/Builtin*Functions.java`) by `tools/generate_doris_functions.py`
  (adapted from the doris-intellij-plugin extraction script).

## GoogleSQL pipe syntax

The pipe-syntax (`|>`) operator semantics implemented in `brikk-sql` (first-class
`PipeQuery`/stage nodes, desugaring behavior, operator set) follow the
[GoogleSQL pipe query syntax](https://cloud.google.com/bigquery/docs/reference/standard-sql/pipe-syntax)
specification published by Google (Apache 2.0-licensed documentation in the
googlesql/ZetaSQL project). No code from ZetaSQL is included.
