# sqlglot catch-up — fixes to triage/port

Tracking upstream **sqlglot** bug fixes landed since our port's pin, to decide which to apply to brikk-sql.

- **Our pin:** `v30.12.0-44-g93d16591` (commit `93d16591`)
- **Upstream at harvest:** `v30.17.0-72-gbac1a897b` (`bac1a897b`)
- **Harvested:** 2026-08-26  (353 commits total; 144 fixes; 79 actionable after filtering non-ported dialects)
- **Scope:** fix commits touching files we ported (our dialects + base `dialect.py`; `parser`/`generator`/`tokens`/`expressions`; optimizer `annotate_types`/`qualify_columns`/`qualify`/`scope`; `lineage`; `typing/`). Non-ported dialects (snowflake/tsql/oracle/sqlite/starrocks-only/databricks/redshift) are listed under Excluded.

**Per item:** review `git -C reference/sqlglot show <hash>`, decide apply/skip, port to Kotlin + add/adjust a corpus case, then check the box with a one-line note. `!` in the subject = upstream behavior change.

**Autonomous-run policy (2026-08-26):** our corpus gates are pinned to sqlglot `v30.12.0-44`; a fix that *changes transpile output* would break those gates, and they can't be regenerated piecemeal (regen bakes in ALL unported upstream changes → other gates fail). So during the unattended run I only APPLY fixes that are **output-neutral** (internal correctness) or **additive** (new parse/annotation support not covered by existing gates), each verified by `./kotlin build` + targeted tests. Output-changing `!` fixes are **DEFERRED** (left unchecked, annotated) for a coordinated full resync. `[x]` = applied or consciously skipped-N/A; `[ ]` + `DEFERRED/SKIP` note = needs human/coordinated work.

## Progress — autonomous run (2026-08-26, branch `sqlglot-catchup`)

Full ordered pass complete: every item triaged. Applied only self-contained, output-neutral/additive
fixes (each `./kotlin build` + targeted corpus gates green); deferred the rest with reasons.

- **APPLIED (6):** `e17ab3023` (annotator-cache uncache), `618804ce1` (NATURAL JOIN in qualify),
  `a1e3338e3` (postgres `U&'` unicode strings), `b0a8635ff` (`OUT` as identifier),
  `86195827b` (preserve identifier meta), `88419b1aa` (JSON-path TokenError fallback).
- **SKIPPED N/A (1):** `b9fd4c9a5` (Python-only type annotation).
- **DEFERRED (72), by category:**
  - *output-changing* — alters transpile/qualify/annotate output vs our pinned corpus (the majority).
  - *typing batch* — `feat: annotate X for mysql/hive/...`; needs typing-metadata refresh + custom-annotator hand-port.
  - *strict-time cluster* — `6581f8c38`/`0a374bf42`/`66316e335` (Hive/Spark `MM/dd` strictness).
  - *VALUES-set-op cluster* — `a85eeff21`/`a247168f9`.
  - *feature* — new-syntax parse support (trino UDFs, clickhouse refreshable MV, DROP/ANALYZE multi-table, ...).
  - *tokenizer* — "do not treat X as a single token" (`START WITH`, `CHARACTER SET`, ...).
  - *non-ported dialect* / *review* (ambiguous internal — verify vs our null-safe Kotlin).

**Recommendation:** the 72 deferred are dominated by output-changing behavior + typing/feature work that
can't be validated against corpus gates pinned to `v30.12.0-44`. Rather than 72 piecemeal ports, do a
**coordinated resync**: bump the `reference/sqlglot` pin (→ `v30.17.0`), regenerate corpora + typing
metadata, reconcile the known-failure ledgers, then work the deferred list against the refreshed gates.
The 6 applied here are safe standalone and don't require that.

### Resync feasibility spike (2026-08-26) — findings (attempted, then reverted clean)

Bumped `reference/sqlglot` to `v30.17.0-72-gbac1a897b` and ran the source generators. Results:
- **Env:** gen scripts need `sqlglot` importable; a `reference/sqlglot/sqlglot/_version.py` shim
  (`__version__`/`__version_tuple__`) is enough (no full pip install). The reference clone is local to
  this worktree (not shared with the `sql-focus` worktree).
- **Toolchain is healthy** at the current pin (regen reproduces committed files byte-for-byte except the
  git-describe stamp) — so regen itself works.
- **BUT the regen is NOT a clean "overwrite generated, never touch" step:**
  1. It **drops hand-maintained code embedded in generated files** — e.g. `DType.intoExpr(...)` lives inside
     the generated `ast/DataType.kt`; `gen_ast_nodes` rewrites the file and silently removes it →
     `AnnotateTypes.kt` stops compiling. Every regenerated `Generated*.kt`/`DataType.kt` must be audited for
     lost hand additions (or the generators taught to emit/preserve them).
  2. Reaching even a **building** state requires porting some deferred behavioral fixes first: upstream
     removed the `TIMESTAMP_SNAPSHOT`/`VERSION_SNAPSHOT` tokens (deferred `eeaf1b832`), so `Parser.kt`
     references dangle until that time-travel change is ported. The corpus/ledger review checkpoint can't be
     reached before this reconciliation.
- **Conclusion:** the resync is a focused, *attended* reconciliation (audit hand-in-generated code + port
  coupled token/AST changes, then reconcile gates) — not a safe unattended auto-regen. Recommend doing it
  as a dedicated reviewed effort. Two prep items worth doing first: (a) move hand methods like `intoExpr`
  OUT of generated files (or make the generator emit them) so regen stops clobbering them; (b) port the
  token-removal fixes (`eeaf1b832` et al.) as part of it.

### Prep DONE (2026-08-26) — regen no longer silently loses hand code

Regenerated all source generators at the current pin and diffed to find every hand edit embedded in
generated files. Only two existed:
- **`DType.intoExpr(...)`** (was inside generated `ast/DataType.kt`) → **externalized** to a hand file
  `ast/DTypeExtensions.kt` as `fun DType.intoExpr(...)` (extension fn; callers just add an import). Regen
  no longer clobbers it. **This is the rule going forward: anything expressible as an extension fn lives
  in a hand file, never in a `Generated*.kt`.**
- **`U&'`/`u&'` unicode-string entries** in generated `PostgresTokenizerTables.FORMAT_STRINGS`
  (from `a1e3338e3`) → **CANNOT be externalized** (it's data inside a generated map the tokenizer reads;
  not an extension point). It *is* auto-generated once the pin is ≥ `a1e3338e3` (i.e. the resync absorbs
  it and it regenerates identically). Until then it's a minimal hand-patch inside a generated file,
  protected by a **guard test** `PostgresTokenizerTest.unicodeStringLiteralsAreTokenized_generatedPatchGuard`
  that fails loudly if a regen wipes it and nobody re-applies it.

**Pattern for non-externalizable generated-map patches:** keep the minimal in-map patch + add a behavioral
guard test (asserts the patched behavior) so a forgotten re-patch after regen breaks a test, not silently.

**Net:** `code-gen` now reproduces every generated file with **zero** hand-code loss except the single
guarded `U&'` map patch (which the resync itself removes the need for). Env note: gen scripts need
`reference/sqlglot/sqlglot/_version.py` (a 2-line `__version__`/`__version_tuple__` shim; gitignored).

### Resync scope spike #2 (2026-08-26) — attempted full regen at `bac1a897b`, reverted clean

Ran source generators at the new pin to size the reconciliation. It's a **multi-session, attended**
effort (not a single work-through). Three coupled workstreams, each surfaced concretely:

1. **Compile reconciliation (AST/token removals).** Regen removes `TIMESTAMP_SNAPSHOT`/`VERSION_SNAPSHOT`
   tokens (`eeaf1b832`) → `Parser.parseVersion()` (Parser.kt ~L2905) must be rewritten to match time-travel
   phrases as token *sequences* (`VERSION_PHRASES` table), mirroring upstream `_parse_version`. Bounded
   (one function + the tokenizer entries auto-remove). Expect a few more such dangling refs to fix.

2. **Typing batch (`gen_typing_metadata` hard-fails on each unclassified annotator).** Bigger than the
   earlier "typing" estimate — needs a `classify` rule + `AnnotatorRef` + Kotlin impl per NEW annotator.
   Ones used by our ported dialects (worked out during the spike; re-apply during resync):
   - `_annotate_bit_func` (mysql/doris #8261): UNKNOWN→UNKNOWN; BINARY/VARBINARY→VARBINARY; else UBIGINT.
   - `_annotate_reverse` (mysql): BINARY/VARBINARY/UNKNOWN `this` → byArgs("this"); else VARCHAR.
   - `_annotate_truncate` (mysql): TEXT `this` → DOUBLE; else byArgs("this").
   - `_annotate_regexp_replace` (mysql): any UNKNOWN arg → UNKNOWN; any BINARY arg → LONGBLOB; else LONGTEXT
     (args: this, expression, replacement).
   - `_annotate_compress` (mysql): `this` in {CHAR,VARCHAR,BINARY,VARBINARY,TINYBLOB,ENUM,INT,BIGINT,
     DECIMAL,DOUBLE,DATE,DATETIME}→VARBINARY; in {TEXT,MEDIUMTEXT,LONGTEXT,BLOB,MEDIUMBLOB,LONGBLOB,JSON}
     →LONGBLOB; TINYTEXT→BLOB; else UNKNOWN.
   - PLUS inline-lambda annotators (not `def`s), e.g. clickhouse `DataType.build("Float64",...)` — several
     more; discover by iterating `gen_typing_metadata` (it fails loudly on each, by design).
   `BINARY_TYPES` is not a generated `DataType.*` set here — define it hand-side if needed.

3. **Corpus reconciliation.** After it builds, regen ast/token/parser/qualify/scope/lineage/serde corpora;
   many gate cases will diverge (the deferred behavioral fixes) → ledger or fix, then **review the ledger
   diff** for real regressions vs expected catch-up gaps.

**Recommendation:** schedule this as a focused effort (likely a dedicated branch + a few sessions), in the
order 1 → 2 → 3. The prep above makes it safe (regen won't silently drop hand code). Reverted clean; branch
`sqlglot-catchup` remains at the 6 applied fixes + prep, building green.

## Actionable (79) — oldest first

- [x] `e17ab3023` (2026-07-13) Fix(optimizer)!: evict mutated projections from the annotator cache (#7868)
  - APPLIED: added `TypeAnnotator.uncache(expr, deep)`; wired into `expandStarsInScope` (annotatedAhead flag + uncache on struct-field replace / star replace / scope pre-set). Build + Qualify/AnnotateTypes/Scope tests green.
  - files: optimizer/annotate_types.py, optimizer/qualify_columns.py
- [ ] `6581f8c38` (2026-07-14) fix(hive)!: parse month/day without leading 0 (#7773)
  - files: dialects/dialect.py, dialects/hive.py, dialects/spark.py, dialects/spark2.py, generator.py
  - DEFERRED (output-changing, large): introduces STRICT_TIME_PARSING + `_with_strict_time_mapping`/`_with_strict_time_inverse` metaclass helpers + PARSE_INVERSE_TIME_MAPPING + generator strtotime/strtounix + Hive/Spark/Spark2 flags. Changes Hive/Spark `MM/dd`↔`M/d` output → breaks pinned hive/spark date corpus gates. Do with item `0a374bf42` in a coordinated resync.
- [ ] `0a374bf42` (2026-07-15) Refactor!: simplify lax/strict %m, %d transpilation to hive hierarchy (#7873)
  - files: dialects/dialect.py, dialects/hive.py, dialects/spark.py, dialects/spark2.py, generator.py
  - DEFERRED (same strict-time theme as `6581f8c38`): output-changing hive/spark %m/%d refactor; do together in the coordinated resync.
- [x] `618804ce1` (2026-07-17) fix(optimizer)!: resolve NATURAL JOIN common columns in qualify [CLAUDE] (#7880)
  - files: optimizer/qualify_columns.py
  - APPLIED: `expandUsing` now treats NATURAL joins as USING over common columns (synthesize using-identifiers from the intersection when both schemas known + non-empty; else keep NATURAL). Additive (no qualify-corpus NATURAL cases); build + qualify gate green (matches ledger). TODO: add a dedicated NATURAL JOIN qualify unit test.
- [ ] `a85eeff21` (2026-07-18) fix!: properly support VALUES as a set operation operand (#7897)
  - files: optimizer/qualify_columns.py, optimizer/scope.py, parser.py
  - DEFERRED (output-changing): parser wraps VALUES set-op operands as `SELECT * FROM (VALUES ...) AS _values` — our ast/parser corpus has round-tripping `VALUES (1) UNION ...` cases that this would rewrite. NOTE: the scope/qualify `.left`/`.right`→args-access sub-change is a safe latent-crash fix (do with the resync, or folds in with item `a247168f9`).
- [ ] `a247168f9` (2026-07-20) Fix: use `.this` and `.expression` instead of `.left` and `.right` in more places
  - files: optimizer/annotate_types.py, optimizer/qualify_columns.py, optimizer/resolver.py
  - DEFERRED (cluster with `a85eeff21`): annotate_types/resolver `.left`/`.right`→`.this`/`.expression` is output-neutral robustness, but the qualify_columns part builds on the deferred #7897 `_expand_alias_refs` change. Apply the whole VALUES-set-op cluster together in the resync.
- [ ] `24d6d735c` (2026-07-20) fix(presto)!: respect date part boundary semantics in DATE_DIFF transpilation (#7911)
  - files: dialects/dialect.py
  - DEFERRED (output-changing): presto DATE_DIFF date-part boundary semantics + WEEK(<day>) DOW; touches duckdb/presto generators + bigquery parser. Transpile-output change.
- [ ] `da43c5c14` (2026-07-20) feat(optimizer)!: annotate truncate for mysql (#7914)
  - files: typing/mysql.py
  - DEFERRED (typing batch): adds a custom `Trunc` annotator for MySQL (TEXT→DOUBLE else by-args). Do with the other 'annotate X for mysql' feats via a typing-metadata refresh + custom-annotator hand-port; may shift annotate-corpus results.
- [x] `a1e3338e3` (2026-07-20) fix(postgres): support unicode escape string literals U&'...' closes #7898
  - files: dialects/postgres.py
  - APPLIED: Postgres tokenizer U&'/u&' -> UNICODE_STRING + PostgresGenerator.supportsUescape=true. Additive; parser/postgres/token gates green.
- [ ] `169b8364b` (2026-07-20) fix(duckdb): position table alias correctly when combined with time-travel syntax closes #7916
  - files: generator.py, parser.py
  - DEFERRED (output): duckdb table-alias positioning with time-travel — generator output change.
- [ ] `8be76d20a` (2026-07-21) feat(duckdb): convert ARRAY_AGG IGNORE NULLS to FILTER clause (#7893)
  - files: generator.py
  - DEFERRED (output/feature): duckdb ARRAY_AGG IGNORE NULLS -> FILTER transpilation.
- [ ] `acb635a9d` (2026-07-21) fix(optimizer)!: don't normalize quoted derived output aliases in qualify_outputs (#7921)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output): qualify_outputs stops normalizing quoted derived output aliases — changes qualified output.
- [ ] `c090f35c8` (2026-07-21) fix(optimizer)!: dedupe colliding star expanded aliases (#7872)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output): star-expansion colliding-alias dedup — changes expanded aliases.
- [ ] `1b442c97d` (2026-07-22) fix!: handle non-literal star ILIKE patterns and Snowflake backslash escapes (#7918)
  - files: dialects/dialect.py, optimizer/qualify_columns.py, parser.py
  - DEFERRED (output): non-literal star ILIKE patterns + Snowflake backslash escapes.
- [ ] `66316e335` (2026-07-22) fix(hive)!: parse single-digit hour/minute/second without padding CLAUDE (#7925)
  - files: dialects/dialect.py, dialects/hive.py, dialects/spark.py, dialects/spark2.py
  - DEFERRED (strict-time cluster): hive single-digit hour/min/sec padding — same theme as 6581f8c38.
- [x] `b0a8635ff` (2026-07-24) Fix(parser): treat `TokenType.OUT` as an identifier token (#7933)
  - files: parser.py
  - APPLIED: added TokenType.OUT to ID_VAR_TOKENS (usable as identifier). Additive; all parser corpus gates green.
- [ ] `166cd4de9` (2026-07-24) feat(optimizer)!: annotate substringindex for mysql (#7945)
  - files: typing/mysql.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `26078eb7c` (2026-07-24) feat(optimizer)!: annotate unhex for mysql (#7944)
  - files: typing/mysql.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [x] `b9fd4c9a5` (2026-07-27) Fix(typing): on_qualify expects a `Table` argument
  - files: optimizer/qualify.py
  - SKIP (N/A): Python-only type annotation (`on_qualify` Callable arg Expr->Table). No runtime effect; our Kotlin onQualify is already concretely typed.
- [ ] `5268b3463` (2026-07-28) fix(duckdb)!: division by zero returns inf, not NULL (#7969)
  - files: dialects/duckdb.py
  - DEFERRED (output/semantics): duckdb division-by-zero returns inf not NULL.
- [ ] `8ef659e41` (2026-07-28) fix(clickhouse)!: division by zero returns inf, not NULL
  - files: dialects/clickhouse.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `c1475e799` (2026-07-28) feat(trino): parse WITH FUNCTION ... RETURNS ... RETURN inline UDFs [CLAUDE] (#7934)
  - files: generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `7ad56f653` (2026-07-28) fix(optimizer)!: update annotate_types.py for maybe_coerce and explode functions (#7973)
  - files: optimizer/annotate_types.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `6b12cb844` (2026-07-28) Fix some latent bugs hidden due to # type: ignore
  - files: optimizer/annotate_types.py, parser.py
  - DEFERRED (review): mostly Python null-safety (`x.type.this if x.type else UNKNOWN`) which our null-safe Kotlin handles differently, plus a MATCH_RECOGNIZE `AFTER MATCH SKIP TO FIRST/LAST` parser hunk. Needs case-by-case review, not a clean port.
- [ ] `a1b8c48c0` (2026-07-29) fix(optimizer)!: don't expand aggregate aliases into GROUP BY (#7971)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output): don't expand aggregate aliases into GROUP BY — changes qualified GROUP BY.
- [ ] `11170dc84` (2026-07-30) feat(starrocks,doris): support DROP TABLE ... FORCE [CLAUDE] (#7999)
  - files: generator.py, parser.py
  - DEFERRED (feature): `DROP TABLE ... FORCE` needs a new `force` arg on the Drop AST node + parser + generator; do via AST regen (touches generated node defs).
- [ ] `9815ccb32` (2026-07-30) feat(trino): parse routine characteristics for inline UDFs [CLAUDE] (#7981)
  - files: dialects/trino.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `d10d7eef5` (2026-07-30) feat(clickhouse): support refreshable materialized views [CODEX] (#7990)
  - files: generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `c9dcd3282` (2026-07-30) feat(optimizer)!: annotate `REGEXP_REPLACE` in MySQL (#7989)
  - files: typing/mysql.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `646295528` (2026-07-30) fix(postgres): transpile DAY/MONTH/YEAR to EXTRACT [CLAUDE] (#7984)
  - files: dialects/dialect.py
  - DEFERRED (output): postgres transpile DAY/MONTH/YEAR -> EXTRACT.
- [ ] `81c19435a` (2026-07-30) Fix(bigquery)!: do not treat `NOT DETERMINISTIC` as a single token
  - files: dialects/bigquery.py
  - DEFERRED (tokenizer): changes multi-word keyword tokenization; affects tokenizer tables + parsing — needs care + corpus re-check.
- [ ] `226beade3` (2026-07-30) fix(optimizer)!: replace find / find_all with scoped lookups (#7997)
  - files: dialects/dialect.py, parser.py
  - DEFERRED (review): internal/ambiguous; verify against our (null-safe) Kotlin before porting.
- [ ] `eeaf1b832` (2026-07-31) fix!: do not treat time travel clauses as single tokens (#8009)
  - files: dialects/bigquery.py, dialects/hive.py, parser.py, tokens.py
  - DEFERRED (tokenizer): changes multi-word keyword tokenization; affects tokenizer tables + parsing — needs care + corpus re-check.
- [ ] `03c96cbbf` (2026-07-31) fix(mysql)!: do not treat `SOUNDS LIKE` as a single token (#8006)
  - files: dialects/mysql.py
  - DEFERRED (tokenizer): changes multi-word keyword tokenization; affects tokenizer tables + parsing — needs care + corpus re-check.
- [ ] `187746cdc` (2026-07-31) fix!: do not treat `CHARACTER SET` as a single token (#8007)
  - files: parser.py, tokens.py
  - DEFERRED (tokenizer): changes multi-word keyword tokenization; affects tokenizer tables + parsing — needs care + corpus re-check.
- [ ] `d7fd83a7d` (2026-07-31) fix!: do not treat `START WITH` as a single token (#8008)
  - files: parser.py, tokens.py
  - DEFERRED (tokenizer): changes multi-word keyword tokenization; affects tokenizer tables + parsing — needs care + corpus re-check.
- [ ] `2856c3e11` (2026-08-03) fix(duckdb)!: LAST_DAY with a WEEK(<day>) part silently returns the last day of the month (#8016)
  - files: dialects/dialect.py, generator.py
  - DEFERRED (output): duckdb LAST_DAY with WEEK(<day>) part.
- [ ] `8a79d074b` (2026-08-03) feat(parser): support "replace using" for insert DML (#7950)
  - files: generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `50bf93d56` (2026-08-04) fix!: degrade BigQuery WEEK(<day>) units gracefully in other dialects (#8027)
  - files: dialects/dialect.py, generator.py
  - DEFERRED (output): degrade BigQuery WEEK(<day>) units in other dialects.
- [ ] `f89e47139` (2026-08-04) fix(optimizer)!: support multiple (UN)PIVOT operators on a source (#8032)
  - files: optimizer/annotate_types.py, optimizer/qualify_columns.py, optimizer/scope.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `4bbe7effd` (2026-08-04) feat(trino): parse BEGIN...END routine bodies with DECLARE/SET [CLAUDE] (#8004)
  - files: dialects/trino.py, generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `9fd2c39ce` (2026-08-04) Fixup
  - files: parser.py
  - DEFERRED (review): internal/ambiguous; verify against our (null-safe) Kotlin before porting.
- [ ] `1c45c8f11` (2026-08-04) fix(optimizer)!: resolve pivot chain aliases in star expansion and type annotation (#8041)
  - files: optimizer/annotate_types.py, optimizer/qualify_columns.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `32d44c50d` (2026-08-04) feat(optimizer)!: annotate bit_and for mysql (#8038)
  - files: typing/mysql.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `3329bb677` (2026-08-05) Fix(optimizer)!: annotate ArraySize as INT for Hive et al (#8046)
  - files: typing/hive.py, typing/spark.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `d4742deec` (2026-08-05) fix(optimizer)!: prevent fabrication of struct-field refs for schema-less correlated columns (#8043)
  - files: optimizer/qualify_columns.py, optimizer/scope.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `cb4a82146` (2026-08-06) fix(lineage)!: trace columns through chained (UN)PIVOT operators (#8042)
  - files: lineage.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `dedc7e367` (2026-08-06) fix(optimizer): expand qualify inner columns with QUALIFY/HAVING (#8050)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `7fa1fbd05` (2026-08-07) feat(optimizer)!: annotate ToChar return type (#8064)
  - files: typing/databricks.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `463c01528` (2026-08-06) fix(optimizer): restore FLOOR type annotation lost in ANNOTATORS refactor [CLAUDE] (#8067)
  - files: typing/__init__.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `30c278e0f` (2026-08-06) fix(optimizer)!: annotate boolean binary predicates as BOOLEAN [CLAUDE] (#8069)
  - files: typing/__init__.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `b4776a409` (2026-08-08) fix(generator): avoid materializing huge integers when capping numeric type params, closes #8113
  - files: generator.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `df5ec1ecd` (2026-08-08) fix(parser): break out of the function-property loop on a failed property, closes #8112
  - files: parser.py
  - DEFERRED (review): parser breaks out of the function-property loop on a failed property (parse-robustness); verify against our property-parsing loop before porting.
- [ ] `c25225d9a` (2026-08-09) fix(generator): double the delimiter in Unicode literals instead of applying string escapes, closes #8110
  - files: generator.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `13e6c0798` (2026-08-10) fix(optimizer)!: annotate grouping properly in the hive hierarchy
  - files: typing/databricks.py, typing/hive.py, typing/spark.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `66ecca339` (2026-08-10) fix(clickhouse)!: parse MODIFY COLUMN as AlterColumn (#8091)
  - files: generator.py, parser.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `f7dfbacd3` (2026-08-10) fix(expressions)!: classify boolean binary operators as Predicate (#8073)
  - files: typing/__init__.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `617989c38` (2026-08-10) fix(clickhouse): clean up MODIFY COLUMN handling
  - files: generator.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `781075516` (2026-08-10) fix(parser)!: move JSON extraction operators to Postgres's binary-operator precedence tier (#8063)
  - files: parser.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `672efde2a` (2026-08-10) Fixup
  - files: dialects/dialect.py
  - DEFERRED (review): internal/ambiguous; verify against our (null-safe) Kotlin before porting.
- [ ] `9312212f2` (2026-08-12) Fix(optimizer)!: properly support `UNION BY NAME` in qualify, type inference (#8136)
  - files: optimizer/annotate_types.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [x] `86195827b` (2026-08-12) fix(optimizer): preserve identifier meta when restoring JSON dot part case
  - files: optimizer/annotate_types.py
  - APPLIED: preserve identifier meta when undoing JSON dot-part case normalization (rename in place if Identifier, else replace). Output-neutral; annotate/parser gates green.
- [ ] `b7386b756` (2026-08-12) fix(optimizer)!: scope DML / DDL query fragments properly (#8140)
  - files: optimizer/scope.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `29acdfde9` (2026-08-13) fix(trino): TIME literal with a timezone (#8154)
  - files: parser.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `cefce1918` (2026-08-13) fix(postgres)!: map JSONB_CONTAINS to @>, add JSONBContainsTopKey (#8156)
  - files: generator.py, parser.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `d89f392e0` (2026-08-14) fix(mysql): map %x and %r date format specifiers [CLAUDE] (#8142)
  - files: dialects/mysql.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `21092b308` (2026-08-14) fix(optimizer)!: do not normalize identifiers that name data rather than references (#8161)
  - files: dialects/bigquery.py, dialects/dialect.py, dialects/duckdb.py, dialects/postgres.py, optimizer/annotate_types.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `f64adb4c9` (2026-08-16) fix(bigquery): array_agg(<subquery> ignore nulls) AttributeError closes #8193
  - files: generator.py
  - DEFERRED (review): internal/ambiguous; verify against our (null-safe) Kotlin before porting.
- [ ] `a570cf8a9` (2026-08-17) fix(bigquery): parse FULL/LEFT before set ops as modifiers, not aliases closes #8195
  - files: parser.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `6e30ee791` (2026-08-17) fix(parser): inline digit-prefixed field parsing to avoid deepening recursion
  - files: parser.py
  - DEFERRED (review): internal/ambiguous; verify against our (null-safe) Kotlin before porting.
- [ ] `d7ada74e2` (2026-08-17) feat(optimizer)!:annotate Left function for posrgres (#8189)
  - files: typing/postgres.py
  - DEFERRED (typing batch): dialect type-annotation addition; do via typing-metadata refresh + custom-annotator hand-port; shifts annotate results.
- [ ] `f62c3446d` (2026-08-19) fix(qualify): resolve snowflake positional column refs (#8202)
  - files: dialects/dialect.py, optimizer/qualify_columns.py
  - DEFERRED (non-ported dialect): snowflake/starrocks-specific; out of our ported set (touches shared parser/qualify though).
- [ ] `038f01599` (2026-08-20) fix(parser): parse a list of tables in DROP TABLE [CLAUDE] (#8223)
  - files: generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `8efda2c6c` (2026-08-20) fix(parser)!: ANALYZE and DROP with multiple tables (#8229)
  - files: generator.py, parser.py
  - DEFERRED (feature): new-syntax parse support; port deliberately (new nodes/parser/generator), not unattended.
- [ ] `b79cbf86d` (2026-08-21) fix(optimizer): preserve UNPIVOT passthrough columns (#8238)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `930f1a32f` (2026-08-21) fix!(postgres, duckdb): preserve JSON extraction RHS grouping [CODEX] (#8237)
  - files: dialects/dialect.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [ ] `d5e3f14b1` (2026-08-24) fix(parser, optimizer): support starrocks GENERATE_SERIES parse and qualify (#8246)
  - files: parser.py
  - DEFERRED (non-ported dialect): snowflake/starrocks-specific; out of our ported set (touches shared parser/qualify though).
- [ ] `a60171e8f` (2026-08-24) fix(optimizer): apply qualified star exclusions only to the referenced source (#8247)
  - files: optimizer/qualify_columns.py
  - DEFERRED (output-changing): alters transpile/qualify/annotate output vs our pinned corpus; needs coordinated resync.
- [x] `88419b1aa` (2026-08-25) fix(dialect): fall back on TokenError when an operand isn't a JSON path (#8260)
  - files: dialects/dialect.py
  - APPLIED: base `toJsonPath` now also catches TokenError (sibling of ParseError) and falls back to the raw path literal. Additive robustness; gates green.

## Excluded — non-ported dialects (13), recorded so we don't re-triage

- `21ebde8eb` (2026-07-13) feat(optimizer)!: type annotation for databricks REGR_SXX, REGR_SXY, REGR_SYY (#7851)
- `f617f30cc` (2026-07-14) feat(optimizer)!: type annotation for databricks RINT (#7869)
- `b2c07a1ee` (2026-07-17) fix(snowflake): wrap FILTER condition inside DISTINCT/ORDER BY args [CLAUDE] (#7884)
- `281442123` (2026-07-17) fix(snowflake): parse AUTOINCREMENT parts independently [CLAUDE] (#7885)
- `a715f9f9a` (2026-07-21) feat(optimizer)!: type annotation for databricks NANVL, SIGN, SHIFTLEFT, SIGNUM, SHUFFLE (#7896)
- `aa495ef8d` (2026-07-28) feat(optimizer)!: type annotation for databricks NEGATIVE (#7975)
- `3c6d84248` (2026-07-30) feat(starrocks): parse REFRESH EXTERNAL TABLE [CLAUDE] (#8000)
- `495b81ce8` (2026-08-03) fix(snowflake): name pivot output columns after their IN-list alias (#8028)
- `978bbd276` (2026-08-03) fix(tsql): UNPIVOT outputs the value column before the name column (#8030)
- `6443883c2` (2026-08-03) fix(oracle): generate valid (UN)PIVOT syntax (#8029)
- `0c19a6dab` (2026-08-06) Fix(tsql): support nullability in ALTER COLUMN (#8053)
- `2f914c54a` (2026-08-10) fix(sqlite)!: parse ||, -> and ->> as a single tier that binds tighter than arithmetic closes #8115
- `353d0ffca` (2026-08-11) fix(sqlite): emit STORED for generated columns instead of PERSISTED (#8123)

