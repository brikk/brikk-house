# HANDOFF — Doris xxhash_32 + week direct re-probe (doris-ducklake agent)

Small live-Doris verification to close two open items flagged during the ClickHouse
rename / source-aware work. **No Kotlin/registry work** — run a few `SELECT`s on live Doris
and write back one TSV; the brikk-sql agent reconciles.

## Why
1. **xxhash_32** — the reverse probe found Doris `xxhash_64` returns a DIFFERENT value than
   ClickHouse `xxHash64` (impl/seed), so both `xxhash_32`/`xxhash_64` were flipped to
   divergent and the forward `->xxHash*` emit was removed. `xxhash_64` is confirmed; the
   `xxhash_32` divergence was INFERRED, not directly probed. Confirm the exact value.
2. **week** — the source-aware generator now emits faithful `week` for doris->clickhouse
   (NOT `toISOWeek`), because Doris `week()` was believed to default to mode 0 (Sunday-based,
   == ClickHouse default). Confirm Doris `week()` mode on the boundary date 2023-01-01
   (mode-0 -> 1; ISO -> 52) so we can either keep `week` (identical) or revisit.

## Input
`docs/research/probe-runs/doris-xxhash-week-reprobe.input.tsv` —
`fn ⇥ clickhouse_call ⇥ clickhouse_value ⇥ doris_call`. `clickhouse_value` is the live chdb
(ClickHouse 26.5.1.1) result, precomputed. Run each `doris_call` as `SELECT <doris_call>` on
live Doris.

## Write-back
`docs/research/probe-runs/doris-xxhash-week-reprobe.results.tsv` —
`fn ⇥ doris_value ⇥ matches_clickhouse(yes/no)` (use `<ERR:…>` for rejected calls; keep the row).

## What the brikk-sql agent does next
- If Doris `xxhash_32('abc')` == 852579327 (and `''` == 46947589): the hash IS ClickHouse
  xxHash32 after all → re-land `xxhash_32 -> xxHash32` in `ClickhouseGenerator.ANON_FUNC_RENAMES`
  and flip the hazard back to conditionally-equivalent. Otherwise keep divergent (current).
- `week`: if Doris `week('2023-01-01')` == 1 (mode 0), the faithful `week` emit is correct
  (reconcile doris week -> identical). If == 52 (ISO), add `doris` to
  `ClickhouseGenerator.ISO_WEEK_SOURCES` so doris->clickhouse emits `toISOWeek`.
