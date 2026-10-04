# ENG-05 — runtime-first virtual views and optional SQL drafts

**Decision: runtime-first.** We define virtual parameterized views / pipe
sequences. Their input/output shapes are checked at compile time; the graph is
glued together at runtime. Only then are the final SQL and target dialect known.

Compile time is for validation and shape detection/generation, not final graph
rendering or application execution. The existing IR rewrite embeds stage templates
and constructs `Rel` values with input/binding calls; it does not evaluate a user
pipeline or turn a draft file into an execution artifact.

## Execution contract

- Each `@BrikkSql` declaration supplies a SQL template and a shape contract.
  Kotlin types check compatibility when virtual views/pipes are connected.
- Runtime chooses actual `Rel` inputs, branch choices, sharing, scalar values
  and target dialect. Composition, CTE naming, binding collision handling,
  source-preserving pipe lowering and requested translation happen there.
- `Rel.render()` / `renderWithDiagnostics()` are authoritative for the actual
  graph. `bindings()` supplies its binding names/values. Driver-specific
  adaptation/execution remains separate.
- No dbt-style build-produced final SQL artifact, restricted static graph DSL,
  compile-time execution of user code or cross-stage pre-render cache is promised.
  A constant graph may happen to be predictable; that is not a restriction on
  ordinary Kotlin composition.
- Compile-time shape checks are based on the supplied offline schema/declared
  contracts. They do not prove current deployment schema or all query semantics.

## Optional rough draft dump

Pass the compiler option `dumpSql=<file>`. It is **off by default**. For example:

```yaml
settings:
  kotlin:
    freeCompilerArgs:
      # Keep the existing -Xplugin / schema options as well.
      - -P
      - plugin:dev.brikk.house.sql.compiler:dumpSql=build/sql-drafts/{module}.draft.sql
```

The optional `{module}` token expands to a safe readable compiler-module name
plus a stable hash. Use it when options are inherited by main/test compilations,
or give each compilation its own explicit file. Without it, the last invocation
writes the requested file. Relative paths use the compiler process's working
directory; use an absolute path when that context is uncertain.

The public synthetic smoke consumer opts in and produces main/test reports under
`build/sql-drafts`. Production consumers should leave this unset unless inspecting
SQL and keep the output private where appropriate.

Each report begins with `BRIKK SQL ROUGH DRAFT v1` and contains stages visited
by that IR invocation:

- Qualified enclosing function name and source dialect.
- Rel input slot names and named scalar parameters.
- A SHA-256 of each template for identification, not schema/build attestation.
- The exact template passed to `Rel`: explicit Kotlin literal escapes/trimming
  and supported interpolation substitution have already been applied. Scalar
  entries are placeholders; Rel entries remain `slot()` calls.

Authored whitespace, hints/comments and SQL operator spelling remain intact.
There is **no** composition, CTE assignment, global binding renaming, star
expansion, pipe lowering, target translation or optimization. A template containing
`src()` or `|>` is not claimed to be executable database SQL. Generic shapes may
be partial until their input is chosen; the report is not a final graph contract.

Runtime arguments/getters are never evaluated or dumped. Authored SQL literals
and compile-time SQL constants are retained, so this is not a redaction facility:
do not author secrets as SQL literals or publish private SQL reports.

## Lifecycle and failure behavior

Drafts are **observational output, never compiler/runtime input**. Deleting or
changing a report cannot change execution. There is no executable artifact cache
or binding plan to invalidate.

One report is atomically replaced per invocation, including an empty stage slice;
deleted declarations do not leave per-function SQL files behind in that report.
An existing foreign file or symlink path is refused, rather than overwritten.
An explicitly requested write failure becomes a compiler error without a raw
plugin exception or silently missing dump.

The report is intentionally **not a complete module inventory or successful/current
build marker**:

- Incremental IR may contain only a slice of the module.
- A failed frontend check leaves the previous report untouched. A later backend
  failure can occur after a draft was emitted.
- If a build skips compilation, it does not refresh/recreate this sidecar. The
  option does not turn it into a tracked Toolchain output or force recompilation.
- Reports for renamed modules/old paths may remain; no directories are cleaned.

For a fresh diagnostic report, ensure a real compilation occurs. For actual final
SQL, construct the intended runtime graph and inspect `renderWithDiagnostics()`.
Always use build status and schema-input tracking—not a draft timestamp—as the
validation/freshness signal.

## Acceptance

- Plugin assembly/full build and **159 tests, zero failures/skips** (nine new
  regressions over ENG-04).
- Tests verify exact draft templates, retained hints/slots/pipes, absent runtime
  binding values and no evaluation of a runtime getter, disabled-by-default
  configuration, safe paths/writes, invocation replacement/empty slices and the
  distinction between stale drafts and failed builds.
- A runtime branch chooses between compatible live/archive virtual views; it
  produces different final SQL even though both stage templates appear in the
  draft. Execution/rendering works after deleting the report.
- The real Toolchain smoke build produces separate main/test draft files.
- All **eight** artifact fixtures pass on the actual `2.4.20-ij262-34` compiler,
  including dump-option loading/template inspection and subsequent runtime
  composition/bindings. All seven incremental schema refresh checks remain green.
- ENG-03/ENG-04 live IDE and target Doris gates remain open; this decision does
  not claim those validations.
