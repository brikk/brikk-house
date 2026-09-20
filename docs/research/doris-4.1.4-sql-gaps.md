# Doris 4.1.4 SQL grammar and function gaps

Investigated 2026-09-19 against Brikk commit `4bc4ddc1e2d12a5bbf7fb3f359b334de3327cb0d`.

Implementation follow-up, 2026-09-20: the grammar, type-generation, and catalog work
below is implemented. `Doris414Test` and `Doris414GrammarTest` cover the new forms;
the latter uses the exact release grammar in the refreshed vendored parser. The
function catalog preserves its original master snapshot and adds a separately
source-pinned release supplement. See `vendor/README.md` for regeneration and both
parser source pins. The remaining sections record the original investigation.

Verification after implementation:

- `./kotlin test -m brikk-sql`: 818 tests passed, including all parser, generator,
  transpile, annotation, and serialization corpus gates.
- `./kotlin test -m brikk-sql-metadata`: 48 tests passed.
- `./kotlin test -m brikk-sql-verify`: 169 tests passed.
- Both Doris `VerifyCorpusGateTest` methods in `brikk-sql-oracle` passed against
  the refreshed native parser. Existing failure ledgers did not change.
- Catalog regeneration reproduced the committed generated Kotlin byte-for-byte.

Release gate, 2026-09-20: integrated `main` at `e9ab8d1`, including the 0.15.1
temporary-partition INSERT fix. Plugin assembly, full repository build, full test
suite, and Maven Local publication all succeeded. The full suite reported 1,149
passing tests, zero failures, and two assumption-aborted opt-in chDB tests because
`brikk.chdb.integrationLibrary` was not configured.

The release needs three missing function catalog entries, two new statement/expression
parsers, and structured nested-column ADD/MODIFY support. A related existing generator
bug changes TIMESTAMPTZ to DATETIME. Multimodal EMBED signatures are already present.

## Sources and verification

- [Release notes](https://doris.apache.org/releases/v4.1/release-4.1.4/).
- Compared the complete Doris lexer, parser, and all five Nereids builtin registries
  between the release tags, rather than relying only on the release notes.
  - `4.1.3`: `7126cf65d96ebc43fce0906f51e92c1a2ccf24a6`.
  - `4.1.4`: `ad35a140c7fd0b842f18c23300bac581f7d04326`.
- Release-tag grammar lives under `fe/fe-core/src/main/antlr4/org/apache/doris/nereids/`.
  Several original PRs change the newer `fe/fe-sql-parser/` location instead.
- Compared release registry entries with `vendor/data/doris-registry/`, then checked
  local parser, generator, and metadata implementations.
- `./kotlin build -m brikk-sql` succeeded. A temporary diagnostic test ran 22
  statements through `parseOne(sql, "doris")` and `.sql("doris")`. Results below
  describe local parsing/generation, not acceptance or execution by a live 4.1.4 server.
  The temporary test was removed after recording its results.

The release notes omit nested-column evolution, DEFAULT expressions, the two Variant
functions, and VECTOR_SEARCH. All are present in the tagged release.

## Grammar work

### 1. Parse DEFAULT with a column argument

[Backport #66538](https://github.com/apache/doris/pull/66538), from
[original #65851](https://github.com/apache/doris/pull/65851), adds Iceberg V3 default
handling and this grammar production:

```antlr
DEFAULT LEFT_PAREN qualifiedName RIGHT_PAREN
```

Examples:

```sql
UPDATE t SET c = DEFAULT(c);
```

Upstream also exercises DEFAULT in MERGE update assignments and insert values. Its
argument is a qualified column name. This is a grammar special form, absent from the
ordinary builtin function registry. The resolved value depends on the write schema.

Local results:

- `UPDATE t SET c = DEFAULT(c)` fails parsing.
- `SELECT DEFAULT(t.c) FROM t` fails parsing. This is a grammar probe, not a claim
  that every expression context is executable upstream.
- Existing `INSERT INTO t VALUES (DEFAULT)` parses and regenerates successfully.

Required work:

- Add a Doris expression parser and AST representation that preserve the referenced
  column and its qualification; generate `DEFAULT(column)`.
- Add DEFAULT to `DorisGrammarBuiltins.kt` after implementing parsing.
- Cover UPDATE and both MERGE branches, plus existing bare DEFAULT behavior.
- Keep qualification/lineage handling aware that this references a target-column
  default. Treating it as an ordinary scalar function loses that distinction.

The upstream feature consumes Iceberg defaults. It does not add DDL for authoring
Iceberg initial-default or write-default metadata.

### 2. Structure nested-column ADD and MODIFY

[Backport #66032](https://github.com/apache/doris/pull/66032) includes
[original #65329](https://github.com/apache/doris/pull/65329).

The grammar accepts dotted paths in ADD, DROP, MODIFY, RENAME, and MODIFY COMMENT.
RENAME also accepts optional TO. Paths include struct fields, `arr.element.field`,
and `map_col.value.field`.

| SQL | Current Brikk result |
| --- | --- |
| `ALTER TABLE t ADD COLUMN s.b INT NULL` | Opaque `Command` |
| `ALTER TABLE t ADD COLUMN arr.element.b INT NULL AFTER a` | Opaque `Command` |
| `ALTER TABLE t MODIFY COLUMN s.b BIGINT` | Opaque `Command` |
| `ALTER TABLE t MODIFY COLUMN s.b COMMENT 'nested comment'` | Opaque `Command` |
| `ALTER TABLE t DROP COLUMN s.b` | Structured `Alter`, round-trips |
| `ALTER TABLE t RENAME COLUMN s.b TO c` | Structured `Alter`, emits legal `RENAME COLUMN s.b c` |
| `ALTER TABLE t RENAME COLUMN m.value.b c` | Structured `Alter`, preserves the path |

Required work:

- Extend Doris ADD/MODIFY parsing and rendering to retain nested paths in column
  definitions and comment-only changes. Check FIRST, AFTER, NULL, and comments.
- Preserve each quoted path component. A single quoted name containing a dot must
  remain distinguishable from a multi-component path.
- Preserve omitted nullability and comments. Omitting them means preserve the
  existing field metadata; explicit NULL or an empty comment has different meaning.
- Add regression coverage for already-working DROP/RENAME and the optional TO form.

The new `columnDefWithPath` production deliberately excludes DEFAULT and ON UPDATE
for nested paths. The multi-column ADD form still uses the older `columnDefs` rule.
Do not generalize every top-level column clause to nested fields. Upstream execution
also restricts type promotions, required-field additions, and map-key evolution.

### 3. Accept tablet-level compaction

[Original #66611](https://github.com/apache/doris/pull/66611), with
[backport #67203](https://github.com/apache/doris/pull/67203), adds:

```sql
ADMIN COMPACT TABLET 12345 WHERE TYPE = 'CUMULATIVE';
```

The tablet ID is an integer literal and WHERE TYPE is mandatory. Supported type
values are BASE, CUMULATIVE, and FULL, checked case-insensitively by the command.
This differs from the older table-level form, whose type filter is optional.

Brikk currently throws a parse error. At minimum, recognize ADMIN as an opaque
command, consistent with the existing administrative-statement policy. If structured
coverage is desired, add a Doris compaction node with tablet ID and compaction type,
plus a matching generator. The latter follows the BUILD/RECOVER command-body pattern.

### 4. Optional structured SHOW COMPUTE GROUPS support

[PR #66697](https://github.com/apache/doris/pull/66697) enables an existing statement
in non-cloud deployments. It adds no lexer/parser production.

Brikk already preserves `SHOW COMPUTE GROUPS` as a `Command`. Add it to
`DorisParserTables.SHOW_PARSERS` if callers need a structured Show node. This is a
coverage improvement, not a new 4.1.4 grammar requirement.

## New registered functions

The complete registry comparison found these additions between 4.1.3 and 4.1.4.
All three are missing from the local Doris catalog. No aggregate, window, or
table-generating registrations changed between these tags.

| Name | Kind and signature | Source | Work here |
| --- | --- | --- | --- |
| `PARSE_TO_VARIANT` | Scalar, `VARCHAR -> VARIANT`; `PropagateNullable` | [#66188](https://github.com/apache/doris/pull/66188), backport of [#65561](https://github.com/apache/doris/pull/65561) | Add registry entry and extracted signature; generate a STRICT null-propagation profile |
| `TRY_PARSE_TO_VARIANT` | Scalar, `VARCHAR -> VARIANT`; `AlwaysNullable` | Same PRs | Add registry entry and extracted signature; generate an ALWAYS_NULLABLE profile |
| `VECTOR_SEARCH` | Table-valued, property arguments, dynamic result schema | [#65730](https://github.com/apache/doris/pull/65730) | Add TABLE_VALUED catalog entry; preserve dynamic signature/schema |

### Variant functions

Both calls already parse and regenerate as generic functions:

```sql
SELECT PARSE_TO_VARIANT('{"k":1}');
SELECT TRY_PARSE_TO_VARIANT('{');
```

They primarily need function metadata and type inference coverage, rather than new
function-call syntax. Their Java signatures are in the release's
`nereids/trees/expressions/functions/scalar/ParseToVariant.java` and
`TryParseToVariant.java`.

Do not equate the two functions or map them blindly to another engine's JSON parser.
TRY converts input errors into SQL NULL. The upstream tests also distinguish SQL NULL
from a Variant containing JSON null and exercise the configuration-dependent
permissive path, which can retain malformed JSON as a string. The relevant BE setting
is spelled `variant_throw_exeception_on_invalid_json` upstream.

### VECTOR_SEARCH

This is a Lance relation TVF:

```sql
SELECT *
FROM VECTOR_SEARCH(
    "table" = "lance_catalog.db.items",
    "column" = "embedding",
    "query_vector" = "[0,0,0,0]",
    "top_k" = "5",
    "metric" = "l2"
);
```

Brikk parses and regenerates the property-argument call successfully. The catalog
entry is missing, so parsing alone does not establish builtin recognition or typing.

The release's `VectorSearchTableValuedFunction.java` defines required properties
`table`, `column`, and `query_vector`. Optional properties are `top_k`, `offset`,
`metric`, `filter`, `nprobes`, `refine_factor`, `ef`, and `use_index`. The table name
must include catalog, database, and table. Results contain the source table's columns
plus nullable FLOAT `_distance`. Avoid inventing a fixed scalar return signature.

### Multimodal EMBED is already covered by metadata

[PR #66461](https://github.com/apache/doris/pull/66461) backports
[#62147](https://github.com/apache/doris/pull/62147) and
[#66242](https://github.com/apache/doris/pull/66242). It adds these overloads to the
existing EMBED function:

```text
EMBED(STRING, JSON)  -> ARRAY<FLOAT>
EMBED(VARCHAR, JSON) -> ARRAY<FLOAT>
```

Both are already in `GeneratedDorisFunctionCatalog.kt`, alongside the four text
overloads. The JSON-argument call also passes the local parser/generator probe.
The registered name is EMBED. This PR does not introduce an AI_EMBED or TO_FILE
registration. Existing FILE is a separate, already-registered TVF.

## Existing TIMESTAMPTZ generation bug

The release's TIMESTAMPTZ fixes, especially
[#66292](https://github.com/apache/doris/pull/66292), exposed a local round-trip defect:

```sql
-- Input
SELECT CAST('2024-01-15 12:00:00 +00:00' AS TIMESTAMPTZ(6));
-- Current output
SELECT CAST('2024-01-15 12:00:00 +00:00' AS DATETIME(6));
```

`DorisGenerator.TYPE_MAPPING` explicitly maps `DType.TIMESTAMPTZ` to DATETIME.
The same conversion occurs in CREATE TABLE column definitions. The parser accepts
the type and preserves spaced-offset partition-bound strings; generation loses the
timezone-aware type.

Fix the type mapping and add native round-trip coverage for CAST, CREATE TABLE,
partition bounds, and precision. Audit the corresponding type-sensitive conversion
rules. This is an existing compatibility defect, not a new datatype in 4.1.4.

## Changes that reuse existing SQL syntax

| Release item | Finding |
| --- | --- |
| ANN indexes on MoW tables, [#67155](https://github.com/apache/doris/pull/67155) | Validation change. Existing USING ANN, UNIQUE KEY, and PROPERTIES syntax applies. Local CREATE INDEX probe succeeds. |
| Iceberg/Paimon properties, [#66428](https://github.com/apache/doris/pull/66428) | Existing `ALTER TABLE catalog.db.t SET (...)`; local structured parse/generation succeeds. |
| Partition-filter block rules, [#62196](https://github.com/apache/doris/pull/62196) | Adds property `require_partition_filter` to existing SQL_BLOCK_RULE statements. Local CREATE is preserved as Command. No new grammar rule. |
| OceanBase CDC jobs, [#65588](https://github.com/apache/doris/pull/65588) | Adds a datasource and configuration handling to existing streaming jobs. No grammar change in the PR. |
| CDC schema changes, [#65325](https://github.com/apache/doris/pull/65325) and [#64850](https://github.com/apache/doris/pull/64850) | Source schema-event handling, not new Doris ADD/DROP syntax. |
| `count_substrings`, `explode_bitmap`, COALESCE, and other function bugfixes | Existing functions with execution fixes, not new registrations. |

## Removed support

- [#64856](https://github.com/apache/doris/pull/64856), backported by
  [#67009](https://github.com/apache/doris/pull/67009), removes the workload-policy
  `set_session_variable` action and its dedicated keyword/parser alternative.
  The generic action grammar can still parse the name; command validation rejects
  it. Brikk has no dedicated implementation to remove. A parser-only oracle cannot
  certify that an action remains supported.
- [#67147](https://github.com/apache/doris/pull/67147), from
  [#66817](https://github.com/apache/doris/pull/66817), removes
  `PLAN REPLAYER PLAY 'path'` and the PLAY keyword. DUMP remains in the grammar.
  Brikk already fails the removed PLAY statement.
- [#65712](https://github.com/apache/doris/pull/65712), from
  [#61646](https://github.com/apache/doris/pull/61646), removes ICEBERG_META in favor
  of native Iceberg system tables. It is already absent from the local registry.

## Implementation targets and order

1. Fix TIMESTAMPTZ generation in
   `brikk-sql/brikk-sql/src/dev.brikk.house.sql/dialects/DorisGenerator.kt`.
2. Add the three missing function definitions through
   `vendor/data/doris-registry/`, `vendor/data/doris-signatures.json`,
   `tools/extract_doris_signatures.py`, and `tools/generate_doris_functions.py`.
   Regenerate `GeneratedDorisFunctionCatalog.kt`; do not hand-edit it.
3. Implement DEFAULT and nested-column ADD/MODIFY in
   `brikk-sql/brikk-sql/src/dev.brikk.house.sql/dialects/DorisParser.kt`, with
   matching AST/generator support and DEFAULT grammar-builtin recognition.
4. Add ADMIN compaction acceptance. Structured SHOW COMPUTE GROUPS is lower priority.
5. Refresh the native grammar oracle and add acceptance tests for the implemented
   forms. The current vendored parser is pinned to `7027772afcb`, dated 2026-07-02,
   and predates the new productions. The release tag does not have the standalone
   `fe-sql-parser` module used by that jar's documented build procedure; refreshing
   it requires an explicitly matched grammar/artifact source.

The local function registry is pinned to `d8fd23f7f38`, not a 4.1.4 release snapshot.
It already contains registrations absent from 4.1.4, such as LEVENSHTEIN, HAMMING_DISTANCE,
several REGR aggregates, map aggregates, and BINLOG. A wholesale replacement with
4.1.4 would remove existing coverage. Choose and document a coherent catalog pin
that includes the new entries, or introduce explicit version-aware catalog support.
Do not relabel the current mixed coverage as an exact 4.1.4 catalog.
