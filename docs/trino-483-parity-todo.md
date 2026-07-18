# Trino 483 parity — TODO / future work

Tracking Trino catch-up for brikk-sql (parser/generator), brikk-sql-metadata (function
catalog), and brikk-sql-verify (native-grammar oracle).

Reference grammar is pinned at `reference/trino` = **483-SNAPSHOT**
(`core/trino-grammar/.../SqlBase.g4`). Line numbers below are from that grammar.

---

## Done (2026-07)

- **Function catalog → 483.** Regenerated `GeneratedTrinoFunctionCatalog.kt` from
  `vendor/data/trino-functions-483.tsv` (stock `trinodb/trino:483` container `SHOW
  FUNCTIONS`, Method A — bundles geospatial + ML plugins). 442 defs / 808 overloads
  (was 320 / 654). Picks up `ends_with`, `title_case`, `ST_*` (80), `geometry_collect_agg`,
  `bing_tile*`, DataSketches, `cosine_distance`, `learn_*`/`regress`/`classify`, etc.
- **brikk-sql-verify oracle → 483.** Bumped `io.trino:trino-parser:481` → `483`
  (`module.yaml`, `TrinoVerifier.kt` KDoc). Module builds and all 30 verify tests pass;
  no accept/reject fixtures shifted.

---

## Grammar features NOT yet handled by the brikk parser

Audited across release notes 476→483; each confirmed present in the 483 `SqlBase.g4`.
Most are also absent from upstream sqlglot, so these are brikk-native extensions
(parser + generator + corpus fixtures), not port work.

| Feature | Release | Grammar (SqlBase.g4) | Notes |
|---|---|---|---|
| `AT LOCAL` operator | 482 | `#atLocal` (L597) | Only `AT TIME ZONE` is parsed today (`parseAtTimeZone`, Parser.kt:1648). |
| `MATCH` predicate | 482 | `MATCH UNIQUE? (SIMPLE\|PARTIAL\|FULL)? '(' query ')'` `#match` (L591) | Standard-SQL referential predicate. |
| `UNIQUE` predicate | 482 | `UNIQUE '(' query ')'` `#unique` (L626) | `UNIQUE` is already a grammar-builtin *name* (function-shaped), but the standalone predicate form isn't modeled. |
| `NEAREST` join clause | 481 | `NEAREST '(' ... MATCH booleanExpression ')'` `#nearest` (L492) | Function-shaped relation primary. Add `NEAREST` to `TRINO_GRAMMAR_BUILTINS` **together with** parser support (currently omitted on purpose so it doesn't mask a parse failure — see TrinoGrammarBuiltins.kt header note). |
| Row literal field aliases | 479 | `row(1 AS a, 2 AS b)` | `AS` alias inside `ROW(...)`. |
| Column `DEFAULT <expr>` | 477/479 | `CREATE TABLE` / `ADD COLUMN` / `ALTER COLUMN` | Set/drop column default values. |
| Table branches in `FROM` | 477 | branch reference | Iceberg-style branch selection. |
| SQL/JSON path `like_regex` | 482 | JSON path expr | Inside `JSON_*` path arguments. |
| SQL/JSON path `datetime()` | 483 | JSON path expr | Inside `JSON_*` path arguments. |
| `number` type modeling | 480 | type | Currently tokenized as an alias of `DECIMAL` (`TrinoTokenizerTables.kt:292`). Parses, but not modeled as Trino's distinct `number`. |
| `variant` type modeling | 481 | type | Same caveat as `number`. |

### Handled / pass-through (no work needed, but worth a corpus test)

- `SYMMETRIC` / `ASYMMETRIC` in `BETWEEN` (482) — Parser.kt:1502.
- `OVERLAPS` predicate (483) — ParserTables.kt:712.
- `IS [NOT] TRUE/FALSE/UNKNOWN` (482) — generic `parseIs`.
- `PIVOT` (483) — `parsePivot`.
- Named args `name => value` (482) — `FARROW`→`Kwarg` (ParserTables.kt:500).
- Method-call / JSON simplified accessor `x.y.foo()`, `j.a.b[0].decimal(18,2)`, `j.*`
  (482/483) — parse & round-trip as `Dot`/`Column`/`Bracket` chains. **Semantic gap**:
  modeled as column dereferences, not JSON-path accessors — matters for the
  annotate/typing/lineage layer, not for transpile fidelity.
- `OVERLAY` (482) — already in `TRINO_GRAMMAR_BUILTINS`.

---

## brikk-sql-verify — native Trino parser oracle (DONE, on 483)

Bumped `io.trino:trino-parser:481` → **`483`** (`module.yaml`, `TrinoVerifier.kt` KDoc),
matching the vendored function catalog. JDK-25 runtime still required (class-file 69).
All 30 verify tests pass; no accept/reject fixtures shifted.

Note: the 482/483 *grammar* additions above are still unimplemented in the brikk parser,
so the brikk-side corpus won't exercise them yet — the oracle is simply ready for them.

---

## Refresh cadence (per Trino version bump)

1. `docker run -d trinodb/trino:<version>`; wait for readiness; dump `SHOW FUNCTIONS`
   `--output-format=TSV` → `vendor/data/trino-functions-<version>.tsv` (see vendor/README.md).
2. `python3 tools/generate_trino_functions.py` → regenerate the catalog; update the
   pinned count in `FunctionCatalogTest.kt`.
3. Bump `io.trino:trino-parser:<version>` in brikk-sql-verify.
4. Re-verify the grammar-builtins list against that version's `SqlBase.g4`.
