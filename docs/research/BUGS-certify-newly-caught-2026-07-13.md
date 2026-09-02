# certify newly-caught divergences (2026-07-13)

Filed when the certification `SEMANTIC_HAZARD` hole was closed (multi-key lookup +
verdict-driven decision; see `REPORT-certify-hazard-hole-closed-2026-07-13.md` and the
mechanism in `brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt`). Each row here is a
mapping our generator emits that `certify` USED to pass with `ok=true` (the divergent
hazard was masked by the blanket dedicated-renderer skip and/or a parsed-name-only lookup
miss) and is now SURFACED as a certification finding.

**UPDATE (policy #2, 2026-07-13).** Certify no longer refuses every divergent hazard: a
DIVERGENT/UNCLEAR verdict whose target has a **dedicated renderer** (a real translation)
is now a non-blocking **WARNING** (surfaced, `ok` stays true, consumer owns the risk);
only same-name passthroughs (no translation) stay REFUSALs. See the REPORT §1B/§2. The
two pinned cases below are both translated (dedicated renderer) → they are now WARNINGs;
the discipline "no silent `ok=true`" holds either way (the divergence is a visible
finding).

**This file CATALOGS the caught divergences — it does NOT fix generators.** Per task
scope, no generator mappings were changed. Two cases are pinned as regression tests
below; the remaining 100+ are enumerated + policy-#2-re-scored (91 WARNING / 13 REFUSAL)
in the REPORT and await per-entry live-FE probe adjudication.

## Genuine divergences pinned as regression tests

| # | pair | mapping | emitted SQL | why it is a real divergence | policy-#2 severity | test |
|---|------|---------|-------------|-----------------------------|--------------------|------|
| 1 | duckdb→trino | `greatest`/`least` | `CASE WHEN ... THEN NULL ELSE GREATEST(a, b) END` | NULL algebra: Trino returns NULL if ANY arg is NULL; DuckDB SKIPS NULLs. certify fires SEMANTIC_HAZARD (divergent) — now a WARNING via the dedicated CASE-wrap renderer — *and* the renderer's own UNSUPPORTED_TRANSLATION REFUSAL, so the report is still not-ok. | WARNING (hazard) + REFUSAL (unsupported flag) | `CertifyTest.divergentTranslatedHazardWarnsButGeneratorFlagStillRefuses` |
| 2 | trino→doris | `from_iso8601_timestamp_nanos` → `CAST(x AS DATETIME(6))` | `CAST(s AS DATETIME(6))` | LOSSY: Trino keeps nanosecond (9-digit) precision; Doris DATETIME tops out at microseconds (6 digits) — the final 3 digits are silently dropped. The P3 doris fix documented this as lossy/unrepresentable. Under policy #2 the translated (dedicated Cast renderer) lossy mapping is a non-blocking WARNING: surfaced, `ok` true, consumer owns the precision risk. | WARNING | `DorisGeneratorMappingBugsTest.p3_fromIso8601TimestampNanos_datetime6` |

> Note on #2: the shipped hazard TEXT
> (`from_iso8601_timestamp_nanos -> CAST(x AS DATETIME)`, "drops ALL fractional seconds")
> predates the P3 fix, which upgraded the cast to `DATETIME(6)` (micros retained,
> sub-micro dropped). The verdict stays honestly `divergent` (still lossy at nanosecond
> scale); only the residual-divergence description is now narrower than the text. A text
> refresh is deferred with the rest of the reconciliation set (owner review) — the verdict
> itself is correct.

## Held for review (not fixed, not flipped)

The remaining newly-flagged entries (102 of the 104 distinct total) span same-name
divergent passthroughs the renderer-skip masked (`trim`/`ltrim`/`rtrim`, `regexp_*`,
`log`/`log2`/`log10`, `format`, `listagg`, `version`, ...) and cross-name mappings the
multi-key lookup now catches (`max_by→ARG_MAX`, `list_min→ARRAY_MIN`,
`array_join→ARRAY_TO_STRING`, `strftime→DATE_FORMAT`, `concat→||`, ...). Under policy #2
these are 91 non-blocking WARNINGs (translated, dedicated renderer) + 13 REFUSALs
(same-name passthrough — enumerated in the REPORT §2). Full table + category split in
`REPORT-certify-hazard-hole-closed-2026-07-13.md` §2. Each still needs a per-entry
live-FE probe (result-identical → reconcile verdict to identical with provenance; still
divergent → leave as WARNING/REFUSAL) and/or the target-name refinement (option #3),
both explicitly out of scope for this mechanism task.
