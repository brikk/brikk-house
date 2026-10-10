# DuckDB virtual-view dogfood

A public, deterministic first dogfood module. It creates a fresh **in-memory**
DuckDB database on every run: no credentials, production data or persistent DB
files. This is not a claim of Doris compatibility.

## Run

From the repository root, using the pinned Toolchain/compiler:

```sh
./kotlin do assemblePluginJar -m brikk-engine-kotlin-compiler-plugin
./kotlin run -m duckdb-pipelines --jvm-args=--enable-native-access=ALL-UNNAMED
./kotlin run -m duckdb-pipelines --jvm-args=--enable-native-access=ALL-UNNAMED -- --sql
./kotlin test -m duckdb-pipelines
```

`--sql` shows the actual runtime-composed SQL, the JDBC SQL after parameter-only
adaptation, binding **names**, and preservation diagnostic counts. Compiler rough
drafts are separately written under `build/sql-drafts/{module}.draft.sql` (the
module token expands to a safe name/hash). Drafts are not execution input.

## Model and shared code

### Suggested first review

1. Read `eventsInRange`, `extractEventFields` and `cleanEventFields` in
   [`Views.kt`](src/dev.brikk.house.samples.duckdb/Views.kt): this is the one shared
   source/extraction/cleaning path used by all four reports.
2. Review business rules, not just syntax: half-open UTC window, tenant isolation,
   trimming/case folding, bad-amount handling, and `unmatched` versus `unclassified`
   customers. Quality counts make exclusions visible rather than hiding them.
3. Check the explicit fixture rows in
   [`Database.kt`](src/dev.brikk.house.samples.duckdb/Database.kt) and expected outputs
   in [`SampleTest.kt`](test/dev.brikk.house.samples.duckdb/SampleTest.kt).
4. Run with `--sql` and inspect actual runtime SQL/preservation reports. Compiler
   rough drafts are only stage templates. The JDBC bridge is intentionally
   sample-local, not a newly promised generic execution/decoding API.
5. Add the next realistic view/scenario with expected rows before enlarging the
   sample or inventing another wiring surface.

[`resources/schema.sql`](resources/schema.sql) is the single schema source used
both by compiler validation/schema-input tracking and to create the actual DB.
There are two base tables: `sample.raw_events` and `sample.customers`.

[`Views.kt`](src/dev.brikk.house.samples.duckdb/Views.kt) defines virtual
parameterized views/pipes, **not persisted `CREATE VIEW` objects**:

```text
eventsInRange(tenant, start, end)
    → extractEventFields       shared JSON extraction
    → cleanEventFields         shared trimming/case/null/decimal normalization
        ├─ dailyRevenue
        ├─ dailyRefunds
        ├─ customerRevenue ← customerDirectory(tenant)
        └─ dataQuality
```

The four reports share exactly the same constructed cleaned-event `Rel` prefix.
They are executed as four independent queries: graph/code reuse is **not** a
compute-once/materialization or multi-write guarantee. Ordinary function calls
and named inputs are the only wiring; ENG-06 remains deferred.

`cleanedEvents` deliberately declares `Rel<CleanEvent>` as its return contract.
An inferred helper returning a call-site-local shape can otherwise escape as
`Rel<Shape>` and lose useful columns/traits (the documented ENG-02 limitation).
Generated output properties are also referenced in compile-time acceptance
functions; we do not hand-write fake generated row interfaces.

## Realistic fixture cases

18 synthetic events and three customer records cover:

- Two tenants sharing a customer ID; isolation must hold in sources and joins.
- Inclusive start/exclusive end and an event just before the window.
- Mixed case/padded identifiers, event kinds, channels and customer segments.
- Numeric **and** string JSON amounts; invalid numeric text.
- Missing JSON fields, explicit JSON nulls, blank strings and a SQL-null payload.
- An unknown customer and an existing customer with a null segment.
- Orders, refunds and an unrelated `ping` event kind.

The shared cleaner preserves malformed/null events. Financial reports explicitly
exclude unknown/invalid kinds or amounts; quality counts expose those exclusions.
The outer join distinguishes `unmatched` customers from `unclassified` known
customers and does not silently drop unmatched money.

Default window: tenant `acme`, `[2026-01-01T00:00Z, 2026-01-03T00:00Z)`, session
timezone UTC. Expected financial totals:

| Report | Results |
| --- | --- |
| Daily revenue | Jan 1: 4 orders / 177.50; Jan 2: 2 orders / 87.00 |
| Daily refunds | Jan 1: 1 refund / 10.00; Jan 2: 2 refunds / 7.50 |
| Customer revenue | gold: 3 / 205.50; unclassified: 1 / 40.00; unmatched: 2 / 19.00 |
| Quality | 15 events; 5 missing customers; 3 missing kinds; 2 invalid amounts; 1 null payload; 1 unknown kind |

## Execution bridge—not a new Engine execution API

[`Jdbc.kt`](src/dev.brikk.house.samples.duckdb/Jdbc.kt) is a **sample-local** driver
bridge. It uses SQL 0.18.0's exact parameter occurrence ranges to replace named
markers with JDBC `?`, in textual order, repeating a value when its name occurs
more than once. It does not regex-replace SQL, regenerate an AST, guess binding
names or inline values. Missing/unused/positional bindings fail closed. Native
DuckDB `$name` markers are also recognized by that API.

Instants are bound as UTC offsets and decimals as `BigDecimal`. Query results use
a small explicit JDBC row reader; this is **not** automatic materialization of
plugin-generated shape interfaces. Generic execution/decoding APIs remain future
work informed by dogfood, not a prerequisite framework added here.

## Evidence and next slices

Twelve sample tests execute DuckDB and check explicit expected rows, cleaning and
tenant/date edge cases, shared graph identity, namespaced same-name bindings,
native comment preservation, exact JDBC edits and actual DB schema parity.
DDB-001 is fixed in Central SQL 0.18.0 and covered by positive parser/compiler/
execution regressions, not failure-expectation or skipped tests. See
[findings](FINDINGS.md) and [release acceptance](SQL-018-RELEASE-ACCEPTANCE.md).

The first four reports execute successfully. Extend the module view by view:
deduplication/latest-event rules, windows, more complex JSON, late arrivals,
joins/fan-out and schema changes. Keep expected-row assertions and native SQL
preservation checks alongside every added case. Move the proven scenarios to a
real Doris fixture later; rerun its dialect/metadata/driver/IDE gates independently.

The current release gate is recorded in [SQL 0.18.0 acceptance](SQL-018-RELEASE-ACCEPTANCE.md),
including full tests, compiler artifacts, schema-refresh checks and actual sample
execution. Live IDE and Doris deployment acceptance remain separate.
