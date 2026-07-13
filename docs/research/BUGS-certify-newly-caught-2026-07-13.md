# certify newly-caught divergences (2026-07-13)

Filed when the certification `SEMANTIC_HAZARD` hole was closed (multi-key lookup +
verdict-driven decision; see `REPORT-certify-hazard-hole-closed-2026-07-13.md` and the
mechanism in `brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt`). Each row here is a
mapping our generator emits that `certify` USED to pass with `ok=true` (the divergent
hazard was masked by the blanket dedicated-renderer skip and/or a parsed-name-only lookup
miss) and NOW correctly REFUSES.

**This file CATALOGS the caught divergences — it does NOT fix generators.** Per task
scope, no generator mappings were changed. Where the refusal is a GENUINE divergence the
correct action is "leave refused" (the hole is now doing its job); where it may be a stale
false-refusal for our actual mapping, it is HELD for owner review in the REPORT (no verdict
flips). Two genuine cases are pinned as regression tests below; the remaining 100+ are
enumerated in the REPORT and await per-entry live-FE probe adjudication.

## Genuine divergences pinned as regression tests

| # | pair | mapping | emitted SQL | why it is a real divergence | test |
|---|------|---------|-------------|-----------------------------|------|
| 1 | duckdb→trino | `greatest`/`least` | `GREATEST(a, b)` (verbatim) | NULL algebra: Trino returns NULL if ANY arg is NULL; DuckDB SKIPS NULLs. A verbatim name-map diverges when args may be NULL. certify now fires SEMANTIC_HAZARD (divergent) *and* the renderer's own UNSUPPORTED_TRANSLATION flag. | `CertifyTest.divergentHazardRefusesEvenWithDedicatedRendererAndGeneratorFlag` |
| 2 | trino→doris | `from_iso8601_timestamp_nanos` → `CAST(x AS DATETIME(6))` | `CAST(s AS DATETIME(6))` | LOSSY: Trino keeps nanosecond (9-digit) precision; Doris DATETIME tops out at microseconds (6 digits) — the final 3 digits are silently dropped. The P3 doris fix documented this as lossy/unrepresentable. A known-lossy mapping must not certify clean. | `DorisGeneratorMappingBugsTest.p3_fromIso8601TimestampNanos_datetime6` |

> Note on #2: the shipped hazard TEXT
> (`from_iso8601_timestamp_nanos -> CAST(x AS DATETIME)`, "drops ALL fractional seconds")
> predates the P3 fix, which upgraded the cast to `DATETIME(6)` (micros retained,
> sub-micro dropped). The verdict stays honestly `divergent` (still lossy at nanosecond
> scale); only the residual-divergence description is now narrower than the text. A text
> refresh is deferred with the rest of the reconciliation set (owner review) — the verdict
> itself is correct.

## Held for review (not fixed, not flipped)

The remaining newly-refused entries (102 of the 104 distinct total) span same-name
divergent passthroughs the renderer-skip masked (`trim`/`ltrim`/`rtrim`, `regexp_*`,
`log`/`log2`/`log10`, `format`, `listagg`, `version`, ...) and cross-name mappings the
multi-key lookup now catches (`max_by→ARG_MAX`, `list_min→ARRAY_MIN`,
`array_join→ARRAY_TO_STRING`, `strftime→DATE_FORMAT`, `concat→||`, ...). Full table +
category split in `REPORT-certify-hazard-hole-closed-2026-07-13.md` §2. Each needs a
per-entry live-FE probe (result-identical → reconcile verdict to identical with
provenance; still divergent → leave refused) and/or the target-name refinement (§1 of the
REPORT), both explicitly out of scope for this mechanism task.
