# Relation-shape contract for the DuckDB and Doris IDE plugins

Status: v1 integration contract, 2026-10-10. The shared adapter is implemented in
[brikk-engine-kotlin-intellij-support](../brikk-engine-kotlin-intellij-support/README.md).
The helper uses Kotlin Analysis API, not a new compiler service. The SQL plugins
retain injection mechanics, alias resolution, SQL inspections and PIPE propagation.

The DuckDB agent reports that host-scoped pipes, scalar-bind injection and
physical-table resolution are implemented and tested. This contract addresses
the next increment: the columns supplied by Kotlin relation inputs.

## Decision

Use **Kotlin Analysis API over the effective `Rel<T>` type**. Compiler-generated
shapes are exposed as Kotlin class/property symbols by the FIR extensions loaded
through KEFS. They are not generated `.kt` files and do not require reading a
build-time JSON report.

An IDE-side adapter shared by DuckDB and Doris should turn those symbols into a
session-independent shape description. It must not call `BrikkSqlSession`, inspect
`shapeColumns` FIR attributes, reflect into the KEFS classloader, infer a shape
from an `Out` suffix, or evaluate runtime `Rel` objects.

V1 provides names, Kotlin type contracts, conservative SQL type families,
full/partial knowledge, and navigation targets where available. Exact native SQL
types and generated-column SQL source spans need additional compiler metadata;
they are not exposed by the current Kotlin symbol model.

## What the compiler exposes today

All runtime identities below are fully qualified under
`dev.brikk.house.sql.runtime`:

| Declaration | Meaning |
| --- | --- |
| `Rel<out T : Partial>` | A relation whose rows satisfy the static contract `T` |
| `Partial` | Minimum column guarantees; the row can contain additional columns |
| `Shape : Partial` | Marker for a full row shape, when an actual shape declaration is known |
| `@BrikkTrait` | A declared column contract, including inherited property requirements |

For a non-generic SQL function, the compiler generates a top-level interface such
as `CustomerDirectoryOut`, adds a `val` for each output column, and refines the
function's inferred return type to `Rel<CustomerDirectoryOut>`. Its base marker
is `Shape` when analysis establishes a full output, otherwise `Partial`.

A generic SQL pipe has a named minimum output contract and can also receive a
call-site-specific local shape. The local shape is an abstract Kotlin class with
generated properties and satisfied traits. It has no stable global name. Read
the actual expression type and its member scope; do not approximate it to a
public supertype or attempt to find it by class name.

Inside a generic declaration such as `fun <T : RawEvent> pipe(src: Rel<T>)`, the
available input contract is the bounds of `T`. It is not the union of the shapes
of every caller of that function.

## Queries and results

The next increment primarily asks for the shape of a **relation parameter in the
enclosing `@BrikkSql` function**. A second query reads an effective Kotlin
expression type, useful when inspecting an upstream relation or a refined call.

Implemented adapter operations, available in the Maven Local development artifact:

```text
BrikkRelationShapes.ofParameter(parameter) -> ShapeResult
BrikkRelationShapes.ofExpression(expression) -> ShapeResult
BrikkKotlinContext.inspect(host) -> ContextResult with per-parameter relation shapes
```

Keep these results distinct:

| Result | Meaning |
| --- | --- |
| `Available(shape)` | Analysis resolved a supported static shape contract |
| `Unavailable(reason)` | Missing plugin, unresolved/error type, indexing, cancellation or insufficient information |
| `Unsupported(reason)` | A resolved construction the adapter does not yet support |
| `NotRelation` | The resolved type is not Engine's `Rel` |

An available partial contract may have zero guaranteed columns. That is not the
same as an unavailable shape or a known full zero-column shape. Do not cache an
initial resolution failure as an empty successful result.

Copied result data in the helper:

```text
RelationShape
  knowledge: FULL | PARTIAL
  columns: list<ColumnContract>
  navigation: optional relation/parameter declaration target
  origin: DECLARED | GENERATED | TYPE_BOUND | UNKNOWN

ColumnContract
  name: exact Kotlin property name
  kotlinClassId: canonical copied class identity, not a KaType
  nullability: NON_NULL | NULLABLE | UNKNOWN
  sqlFamily: conservative type family, or UNKNOWN
  exactSqlType: absent in v1
  quotedSqlIdentity: unknown in v1
  navigation: optional declaration target
  navigation.kind: PROPERTY | RELATION_FALLBACK, when navigation exists
  navigation.location: optional current Kotlin file URL and UTF-16 source range
```

`origin` can improve presentation but must not control type correctness. If the
IDE cannot distinguish a generated symbol, use `UNKNOWN`; do not guess from its
name. Column list order is not SQL ordinal order in v1. Sort for display if useful,
but do not use Kotlin scope iteration order to predict `SELECT *` column order.

No `KaSession`, `KaType`, `KaSymbol` or lazy sequence from an analysis session may
escape that session. Copy names and types. Retain only supported symbol pointers
or IntelliJ smart pointers for navigation, and restore them in the proper context.

## Reading `Rel<T>`

1. Resolve the host Kotlin parameter/reference through Analysis API. Use its
   effective semantic type, with aliases expanded. Identify `Rel` by ClassId,
   never by the short string `Rel`.
2. Read the row type argument. Do not replace it with the type of some upstream
   initializer if the parameter or variable explicitly declares a weaker contract.
3. For a concrete row type, read its **type scope** so inherited properties and
   generic substitutions are included. Select non-extension instance `val`
   properties representing columns. Exclude methods, constructors, static members,
   extension properties and unrelated synthetic members.
4. For a type parameter, combine its supported trait bounds. Use the substituted
   property contracts, not uninstantiated generic member symbols. Follow inherited
   bounds and guard against cycles.
5. Merge inherited/overridden properties without duplicate completions. For
   compiler-supported intersected trait bounds, equal canonical Kotlin types use
   the stricter nullability guarantee. Conflicting types must not be resolved by
   arbitrarily taking the first property.
6. If the resulting type or properties are error/unresolved types, report that
   uncertainty. Preserve any valid column information only as an explicitly
   incomplete contract, not a successful full shape.

`Rel<*>`, `Rel<Partial>` and the bare `Rel<Shape>` approximation provide no finite
column inventory. Treat them as partial with no known columns. The `Shape` marker
alone does **not** identify the actual row's complete column set.

A concrete generated full-shape declaration with accessible properties and a
`Shape` supertype gives full inventory knowledge. An actual zero-property full
shape is possible; do not identify it solely by encountering the empty marker.
If generated members are unavailable on cold resolve, return `Unavailable` or
retain partial knowledge and retry rather than asserting a closed empty row.

Declared traits and generic bounds give partial knowledge. A concrete generated
output extending only `Partial` also gives partial knowledge. Do not promote it
to full just because all properties can be enumerated.

Nullable `Rel<T>?` does not by itself make every row property nullable. Relation
receiver nullability and column nullability are separate facts.

### Analysis API entry points

The installed Kotlin IDE API provides the building blocks below. This is an
algorithm outline, not copy-paste code for every IDE version:

- Resolve the reference or parameter symbol, or obtain the expression type.
- `KaClassType.classId`, `.typeArguments` and `.symbol` for relation/row types.
- `KaTypeParameterType.symbol.upperBounds` for generic input contracts.
- Type-scope callable signatures through `KaScopeProvider.getScope(KaType)`;
  signatures carry substituted return types and declaration symbols.
- Supertypes through `KaTypeProvider` to identify Engine's full/partial markers.
- Symbol PSI and symbol pointers for navigation where supported.

Use the Analysis API version the dialect plugin targets, with its normal read
action and cancellation rules. The helper's real IDEA 262 fixture demonstrates
that generated top-level and refined local properties are visible through these
APIs on `2.4.20-ij262-34`. It substitutes the genuine compiler plugin in the IDE
cache, without handwritten generated rows. Integration into the actual SQL plugins
remains their next gate.

## Column types and nullability

The property return type is the **Kotlin contract**. Respect aliases and canonical
class identity; a user class named `Instant` is not `java.time.Instant`.

For SQL editing, the adapter may derive the same conservative families Engine
uses for declared traits:

| Canonical Kotlin type | SQL family |
| --- | --- |
| `kotlin.Int`, `kotlin.Short` | Integer |
| `kotlin.Long` | Wide integer |
| `java.math.BigInteger` | Large integer |
| `kotlin.Float`, `kotlin.Double` | Floating point |
| `java.math.BigDecimal` | Decimal, precision/scale unknown |
| `kotlin.Boolean` | Boolean |
| `kotlin.String` | Text-compatible, exact SQL type unknown |
| `java.time.Instant` | Timestamp-compatible, original SQL variant unknown |
| `java.time.LocalDate` | Date |
| `kotlin.Any` or unsupported type | Unknown |

Do not claim that a `String` property proves `VARCHAR`, rather than JSON, UUID or
another SQL type mapped to String. Likewise, `BigDecimal` does not recover
`DECIMAL(12, 2)`, and `Instant` does not recover a native timestamp subtype.

A non-null generated getter is a non-null compiler guarantee. A nullable getter
means nullable or conservatively unknown SQL nullability. Engine maps unknown
SQL nullability to nullable Kotlin types, so the reverse mapping cannot distinguish
those cases. Preserve that uncertainty in SQL-level tooltips and inspections.

For the first increment of relation completion, Kotlin type/nullability is enough
to offer columns and basic compatible-type hints. Native SQL-type-sensitive
inspections must remain conservative where the exact SQL type is unavailable.

## Navigation contract

- Declared trait columns navigate to the property declaration, including the
  inherited property that supplies the column.
- Generated property symbols may have no source PSI. Do not manufacture a file
  or SQL offset from a generated class/property name.
- A safe initial fallback is the relation parameter declaration or the explicit
  row-type reference. Label it as a relation-contract target, not a column-definition
  target. When a producing declaration is available through supported IDE symbol
  metadata, it can be an additional target.
- Call-site-local shapes can have a refinement anchor. Exact navigation to the
  upstream SQL column is not promised by v1.

The compiler currently stores names, SQL types and quote flags in `ShapeColumn`,
but it does not publish per-column SQL origins through generated property symbols.
No exact source-location contract for generated SQL columns exists yet.

## Mapping relation slots into SQL scopes

The adapter resolves Kotlin symbols and supplies a fresh function-local map keyed
by each enclosing parameter's current name. Names are unique within that signature;
the map must never become a project-global name registry. Combine each template
entry's resolved role with this map to connect `$src` and `${src}` to their slot
contracts. Navigation retains a supported pointer to the declaration. Plain
`src()` syntax is also supported by Engine for relation inputs.

For this sample signature:

```kotlin
fun customerRevenue(
    events: Rel<CleanEvent>,
    customers: Rel<CustomerDirectoryOut>,
) = Sql.duckdb("""
    SELECT events.tenant, customers.segment
    FROM $events() AS events
    LEFT JOIN $customers() AS customers ON events.tenant = customers.tenant
""".trimIndent())
```

The Kotlin contract map contains:

- `events`: partial guarantees from `CleanEvent`, including inherited `RawEvent`
  columns. `amount` is `BigDecimal?`; `event_at` is non-null `Instant`.
- `customers`: full output of `CustomerDirectoryOut` when generated analysis is
  available, with `tenant`, `customer_key` and `segment` properties.

SQL aliases belong to the dialect's scope resolver. `FROM $events() AS e` maps
`e` to the `events` slot contract; it does not create a Kotlin parameter named `e`.
Apply alias hiding, CTE/subquery boundaries and join nullability through the SQL
resolver. A left join makes the right-hand columns nullable in that query scope
without changing their underlying Kotlin declarations.

Do not add relation slots to the global physical datasource. Different functions
can have parameters named `src` with entirely different contracts.

Partial shapes supply completion candidates for the known guarantees. A missing
name is not proof that no actual row could contain it; describe it as **not
guaranteed by this relation contract** if the compiler's strict contract checking
rejects its use. Unavailable shapes must not cause an avalanche of invented
"column does not exist" diagnostics. Existing compiler diagnostics remain authoritative.

## PIPE stage propagation

The Kotlin adapter supplies the **input** contracts. The dialect's SQL resolver
propagates those contracts through the authored query and each pipe stage:

- Filters and ordering preserve inventory knowledge, subject to supported type
  refinements.
- `EXTEND` adds or replaces columns according to the established SQL semantics;
  an open input stays open.
- `SELECT *` retains open/closed inventory knowledge.
- Explicit projections without stars and aggregates establish an enumerated
  output when every selected expression and its type resolves.
- Join/subquery semantics retain their usual aliases and nullable sides.

Do not use the enclosing function's final generated output as its input, or
reparse upstream functions to infer undeclared columns. Inside a generic function,
only the declared bounds are guaranteed even if every current caller happens to
pass a larger shape.

The compiler deliberately exposes generic named outputs as `Partial`; a concrete
call can receive a refined local shape. Stage-local SQL inference must not rewrite
that Kotlin declaration-level contract.

## Invalidation and unavailable states

Results depend on the Kotlin analysis context, not just the SQL string. Invalidate
when the host/signature/imports/traits/upstream source change, compiler-plugin
state changes, or the module's schema revision changes. The `.brikk/schema-inputs`
marker is an existing registered Kotlin source for schema revision invalidation.
Do not read or cache a last successful build report as the current editor shape.

No negative result may survive resolution becoming available. Cache immutable
descriptions only with the relevant IDE modification dependencies. Propagate
cancellation and avoid resolving the enclosing SQL function recursively from
its own input-shape request.

## Optional richer contract, separate increment

If the dialect plugins need exact SQL types or precise generated-column navigation,
Engine must expose new compiler-produced metadata through a supported IDE-readable
mechanism. Agree that mechanism with the IDE agents before implementing it.

The additional data would include SQL type parameters, quote identity, SQL
nullability confidence, ordered columns and validated source origins. Origins
can be one-to-many or absent for expressions and aggregates; a single mandatory
"source column" is not sufficient. Results also need analysis/schema revision
identity and explicit success/error state.

Current internal `FunctionAnalysis` and `ShapeColumn` objects are not this public
contract. A process-global mutable map or reflection bridge into a KEFS classloader
is not an acceptable shortcut. Neither is parsing inspection-only SQL drafts.

This richer increment is not required to start relation-column completion.

## Acceptance gates for the next IDE increment

1. Read `Rel<CleanEvent>` with inherited properties and their Kotlin nullability.
2. Read `Rel<CustomerDirectoryOut>` via generated member scopes with KEFS loaded,
   without a build or generated source file.
3. Read generic `Rel<T>` bounds, including compatible intersections, imports and
   aliases, without merging unrelated caller shapes.
4. Observe the extra properties on a directly inspected refined generic call's
   local shape. If Analysis API cannot expose them, report the blocker rather
   than approximating the type and declaring success.
5. Distinguish a partial/unknown inventory from a full inventory. Treat bare
   marker/star projections and error types conservatively.
6. Resolve slot aliases, ambiguous join columns, right-side outer-join nullability
   and PIPE-stage visibility in statement scope.
7. Navigate declared columns to actual properties; generated columns use explicitly
   labelled fallback targets where precise origins are unavailable.
8. Preserve genuine contract errors, but suppress cascading SQL errors when the
   underlying Kotlin shape is temporarily unavailable.
9. Change a trait/upstream query/schema revision and verify completion updates.
   Open after IDE startup without making an edit.

Start with gates 1 and 2 as the smallest live Analysis API proof. They establish
the transport before alias resolution and PIPE propagation build on it.

## Engine source references

- [Runtime markers and annotations](../brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Types.kt)
- [Rel type](../brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt)
- [Generated classes and properties](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/ShapeDeclarationGenerator.kt)
- [Generic call-site refinement](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/BrikkSqlCallRefinement.kt)
- [Compiler analysis and partial/full policy](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlAnalysis.kt)
- [Type mapping and nullability](../brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/TypeMap.kt)
- [DuckDB sample](../samples/duckdb-pipelines/src/dev.brikk.house.samples.duckdb/Views.kt)
