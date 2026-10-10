# Automatic Brikk-house context for the DuckDB and Doris IntelliJ plugins

Status: proposal for the dialect-plugin maintainers, 2026-10-04. No dialect-plugin
implementation is included in this document.

2026-10-10: the DuckDB agent reports the first increment implemented and tested:
host-scoped pipes, scalar-bind injection and physical-table resolution. The next
increment uses the [relation-shape contract](IDE-relation-shape-contract.md) to
read declared and compiler-generated Kotlin relation inputs. This report is not
an independent verification of the dialect-plugin implementation in this repository.

The shared Brikk semantic adapter now lives in
[brikk-engine-kotlin-intellij-support](../brikk-engine-kotlin-intellij-support/README.md).
The peers should delegate their host detection, entry classification and shape
discovery to it. Their tested injection mechanics and SQL implementations remain
in the dialect plugins.

## Request

Automatically recognize **Brikk-house context** in the existing DuckDBSQL and
DorisSQL IDE support. Keep the native dialect, enable pipe syntax, and make Kotlin SQL
interpolations mean the same thing to SQL inspections as they do to the Brikk
compiler.

The main requirement is that `$tenant`, when it references a scalar Kotlin value,
is a SQL bind parameter equivalent to `:tenant`, not a SQL column named `tenant`.
Keep the original Kotlin reference for usages, rename and navigation.

Do not require a manual mode switch for recognized Brikk Kotlin calls, and do not
change ordinary DuckDB or Doris SQL globally. This is an internal editing profile,
not a new database dialect or a promise that either database executes pipe syntax.

## Observed problem

The sample contains:

```kotlin
@BrikkSql
fun customerDirectory(tenant: String) = Sql.duckdb("""
    SELECT tenant, LOWER(TRIM(customer_id)) AS customer_key
    FROM sample.customers
    WHERE tenant = $tenant
""".trimIndent())
```

With a DDL datasource attached, IntelliJ reports:

```text
Condition 'tenant = tenant' is always 'true'
```

These are two independent representations of the same source:

| Consumer | SQL it sees |
| --- | --- |
| Current Kotlin language injection | `WHERE tenant = tenant` |
| Brikk compiler template analysis | `WHERE tenant = :tenant` |

The installed Kotlin injection implementation,
`KotlinLanguageInjectionPerformerBase.makePlaceholder`, substitutes the expression
text for a simple nonconstant interpolation. It removes the `$` before the SQL
inspection sees the injected document. Both sides then resolve to the same
database column.

This warning is an editor false positive. The compiler does not remove the
filter. Changing the SQL dialect lexer to recognize `$name` alone cannot fix
an injected document that already contains bare `name`.

## Activation and scope

Automatic activation should use the injected SQL host's Kotlin context:

1. The injected language identifies DuckDBSQL or DorisSQL.
2. The SQL argument belongs to the resolved entry point
   `dev.brikk.house.sql.runtime.Sql.duckdb` or `.doris`.
3. The containing function resolves to the annotation
   `dev.brikk.house.sql.runtime.BrikkSql`.

This is enough context to enable pipes and Brikk interpolation semantics without
a user setting. Resolve symbols rather than matching arbitrary methods named
`duckdb` or `doris`, or arbitrary annotations with the short name `BrikkSql`.
Language injection alone is not sufficient: those same dialects are also used
by ordinary SQL strings with no Brikk binding semantics.

The runtime's `@BrikkSqlDialect` annotation is an additional source of dialect
metadata. Extending detection to custom wrappers needs a defined compiler-compatible
contract; do not assume any wrapper with a matching annotation has the same body rules.

Make the automatically selected profile visible for diagnostics, but do not make
users enable it for every file. The existing pipe option should be enabled for
this host context, not globally.

The runtime already requests the injected languages with `@Language("DuckDBSQL")`
and `@Language("DorisSQL")`. Preserve those native dialect identities. A scoped
Kotlin injection adapter may need to be shared by both dialect plugins; avoid
registering two competing performers for the same host string.

Initial scope is embedded Kotlin SQL. An explicit setting could be useful later
for standalone `.sql` files or other ambiguous hosts. Those files have no Kotlin
symbols from which to infer reference roles. A setting is not a prerequisite for
the embedded Kotlin feature, nor a substitute for retrying temporarily unavailable
symbol resolution on initial file opening.

## Required interpolation semantics

Use the referenced Kotlin declaration to classify each template entry. Do not
decide its meaning from its spelling alone.

| Kotlin template entry | SQL editing representation | Meaning |
| --- | --- | --- |
| `$tenant` or `${tenant}`, scalar parameter | `:tenant` | Named bind, never a database column |
| `$value`, local or supported nonconstant property | `:value` | Named bind; never evaluate the property/getter |
| `$src()` with `src` a `Rel` parameter | `src()` | Relation-input slot, not a scalar bind |
| `$COLS`, compiler-supported SQL constant | Constant's literal SQL text | Intentional SQL text insertion |
| `${normalize(tenant)}` or `${object.field}` | Unsupported interpolation | Do not invent compiler support |

For a computed value, the supported spelling is a local followed by a simple
reference:

```kotlin
val tenantKey = normalize(tenant)
// Inside the supported Brikk SQL body:
// WHERE tenant = $tenantKey
```

Constant handling must match the compiler's supported cases. Do not substitute
arbitrary expressions just because IntelliJ's constant evaluator can fold them.
Currently, a computed `const val` initializer is not accepted as an SQL fragment.

The preferred virtual SQL text uses the compiler's `:name` bind spelling. In
Brikk-house mode, both dialect plugins should recognize that token as a parameter
expression. If an IDE API requires `?` as a temporary inspection placeholder,
retain the original name and source mapping separately. Do not present that
virtual text as the compiler's final SQL.

Keep plain SQL `:tenant` supported. Do not convert relation slots into
`:src()` or `?()`. A missing relation-schema integration must not turn a `Rel`
parameter into a scalar parameter merely to eliminate warnings.

## Injection and source mapping

Investigate a scoped injection performer or a supported interpolation-placeholder
hook. This must change the virtual SQL document, not rewrite the Kotlin file or
wait for the compiler's IR rewrite.

Requirements:

- Preserve Kotlin references in `$name` and `${name}`. SQL inspections must not
  reinterpret them as database columns.
- Preserve SQL-to-host ranges across interpolation, multiline strings,
  `trimIndent()`, `trimMargin()`, escapes and Unicode. Diagnostics and quick fixes
  must target the authored source, not synthetic placeholder text.
- Distinguish actual Kotlin template entries from literal dollar characters.
  JSON paths such as `'$.customer_id'`, escaped Kotlin dollars and dialect-native
  SQL syntax must not be rewritten by a global regular expression.
- Preserve comments, quoted identifiers and whitespace outside the virtual
  placeholder substitutions.
- Refresh the injected view when the referenced symbol, imports or its `Rel`
  type changes. Initial file opening must work without a first edit.
- If symbol classification is temporarily unavailable, avoid manufacturing a
  database-column reference or a confident “always true” diagnostic. Retry when
  resolution is available; do not cache a guessed classification as final.

Pipe parsing and scalar-bind recognition are the first increment. Supplying the
columns of `Rel<T>` slots and generated call-site shapes is a separate integration
with the compiler's schema/type information. Keep that limitation explicit.

## Datasource and execution boundaries

For the DuckDB sample, editing should resolve physical tables against the DDL
datasource built from
`samples/duckdb-pipelines/resources/schema.sql`. The compiler and
sample database creation already use that same file. Brikk-house mode should
not require a live connection just to understand a bind parameter.

The datasource provides physical database objects. It cannot discover Kotlin
relation inputs or their generated shapes by introspecting the database.

Do not enable “execute injected SQL” by treating placeholder substitutions as
actual runtime values. Query execution needs the compiled/composed relation,
its binding map, the selected connection and the normal pipe lowering. A SQL
inspection placeholder is not executable query output.

## About `:foo` and Kotlin usages

Kotlin does not treat `:foo` inside a string as a reference to the Kotlin variable
`foo`. That is why the `$foo` form is useful. Keep it as the primary spelling;
do not attempt to change Kotlin string syntax to fix SQL inspections.

An IDE-only reference/navigation bridge from a plain `:foo` token to its matching
function parameter could be a later feature. It would need separate usage and
rename support and must follow the compiler's binding scope. It would not turn
`:foo` into a Kotlin language reference automatically.

## Acceptance cases for both plugins

| Case | Expected result |
| --- | --- |
| Recognized `Sql.duckdb` or `Sql.doris` inside `@BrikkSql` | Brikk profile activates automatically, no mode switch |
| Same injected dialect on an unrelated Kotlin call | Native behavior, no automatic Brikk bind interpretation |
| `WHERE tenant = $tenant` with a real `tenant` column | Left side resolves to the column; right side is a bind; no tautology warning |
| `WHERE tenant = ${tenant}` | Same result, Kotlin reference preserved |
| Rename Kotlin parameter `tenant` | Template reference and virtual bind name update |
| `WHERE tenant = :tenant` | Named SQL bind remains supported |
| `WHERE tenant = tenant` without interpolation | Ordinary SQL inspection remains active |
| `FROM $src() |> WHERE id > $minId` | Relation slot and scalar bind remain distinct; pipes parse |
| Supported `$COLS` SQL constant | SQL text insertion, not a bind |
| Unsupported computed constant or complex interpolation | Compiler-compatible refusal, not a guessed SQL value |
| `json_extract_string(payload, '$.customer_id')` | JSON path remains unchanged |
| Escaped dollar, SQL string/comment, native DuckDB parameter text | No blanket template substitution; preserve native semantics |
| Multiline/trimmed SQL and repeated binds | Correct host ranges and parameter identities |
| Native mode on unrelated SQL | No Brikk behavior leaks into other files |
| Open file after IDE startup, without editing | Correct initial injection and inspection behavior |

Add injection-level tests that inspect the virtual SQL text, plus real SQL
inspection tests with a datasource. A lexer test alone does not cover the
observed bug because the offending substitution happens before SQL parsing.

## Compiler references

The existing behavior to match is in:

- [SqlTemplate.kt](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlTemplate.kt)
- [SqlTemplateFir.kt](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/SqlTemplateFir.kt)
- [Views.kt](../samples/duckdb-pipelines/src/dev.brikk.house.samples.duckdb/Views.kt)
- [Runtime language annotations](../brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Types.kt)

Keep shared injection behavior and acceptance cases consistent across the DuckDB
and Doris plugins. Reuse a common adapter if their project structure permits it;
do not maintain two different interpretations of Brikk template entries.
