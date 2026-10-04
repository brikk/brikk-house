# ENG-02 — compiler analysis and diagnostics hardening

The implementation uses the published SQL 0.17.0 dependency. Compiler tests run
through in-process K2 and the real Toolchain smoke consumer; they are not evidence
of live IDE compatibility (that remains ENG-03).

## Type identity and inputs

- Raw declaration analysis resolves names through the declaration's package,
  explicit/star imports and import aliases before body resolution. Traits and
  generated output indexes retain qualified names rather than overwriting
  equally named types in another package.
- Type aliases expand in their own declaration context, including which alias
  parameter becomes the `Rel` argument. A class called `String`, `Instant` or
  `Rel` is not a Kotlin scalar or Brikk relation merely because of its short name.
- SQL scalar mapping on resolved interfaces uses the actual `ClassId`. Ambiguous
  type identity refuses rather than taking an arbitrary entry.
- Generic refinement matches written arguments to declaration names and unwraps
  named-argument expressions; reordered/mixed arguments and omitted scalar
  defaults no longer shift a relation onto the wrong parameter.
- Core `Shape`/`Partial` marker bounds are recognized alongside annotated traits;
  conflicting column types in intersected bounds are diagnosed, and compatible
  bounds use the stricter nullability.

## Nullability

Trait satisfaction checks both class identity and nullability. Nullable output
cannot implement a non-null trait property, even when that property is `Any`.
Unknown SQL nullability is conservatively nullable. Declared input shapes carry
their nullability into subsequent SQL analysis, so a generic identity pipeline
does not erase it.

The demo's `HasPayload`, `LoginInput` and JSON-derived fixture properties were
corrected to nullable contracts where the schema/SQL did not prove non-nullness;
the implementation does not weaken the check to keep the old demo compiling.

## SQL scopes and placeholders

Column validation delegates to SQL's strict scope qualification against the
catalog and bound slot schemas. A column in some unrelated table, or hidden by
a CTE projection, cannot justify an unresolved reference. Correlated subqueries,
CTE projections and legal output aliases have positive regression coverage.
This disposable analysis rendering does **not** replace executable `Rel` SQL.

`:object.field` is explicitly rejected by the compiler. It is **not** implemented
as a Kotlin getter chain, nor silently truncated to a binding named `object`.
Write an explicit local and interpolate it instead:

```kotlin
@BrikkSql
fun events(filter: Filter) {
    val since = filter.since
    return Sql.postgres("SELECT event_id FROM public.events WHERE event_at >= $since")
}
```

The rule applies to parsed parameter/dot nodes, not dot-looking text in comments
or strings. SQL analysis still owns the semantics of ordinary column qualification.

## Diagnostics

Parser errors, unbound parameters and uniquely identified unresolved columns use
proven UTF-16 intervals mapped into the authored Kotlin literal/template. Escaped
characters map to their complete escape; interpolation maps to its entry;
trimmed SQL keeps its source alignment. Folded constants, unavailable source and
ambiguous/repeated column names fall back to a whole-literal anchor rather than
underlining the wrong occurrence.

A plain helper with an inferred return type receives a warning when its local
refined SQL shape escapes as `Rel<Shape>`. The hint suggests chaining inline,
an explicit return shape, or a named `@BrikkSql` pipe. The warning does not claim
to overcome Kotlin's type approximation. As with other compiler warnings, K2
may suppress it when compilation already has errors.

## Regression evidence

`BrikkSqlPluginTest` covers package collisions, generated output collisions across
packages, import/type aliases, inherited traits, unrelated builtin-like classes,
nullable trait inputs/outputs, generic argument mapping, scoped column failures
and legal scopes, dotted binds, literal error locations and the helper warning.
`TypeMapTest` pins nominal scalar identity and nullable/`Any` compatibility;
`SqlLiteralRangesTest` checks escape/trimming alignment and safe fallback.

The existing parser/shape/IR/template and smoke tests remain part of the full gate.

Verification: `./kotlin do assemblePluginJar`, `./kotlin build`, and the full
`./kotlin test` pass: **132 tests, zero failures/skips** (25 additional regressions
over the ENG-01 baseline). No SQL sibling source or unpublished dependency was used.
