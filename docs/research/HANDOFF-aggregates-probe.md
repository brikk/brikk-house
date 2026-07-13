# HANDOFF — cross-engine AGGREGATE probe (Trino + Doris side)

For the agent with live Trino + Doris. Goal: run 17 aggregate probes on both
engines so the brikk-sql agent can diff against the duckdb + clickhouse outputs
(already captured) and fold aggregate verdicts into ALL relevant pair registries
(trino↔doris, duckdb↔doris, doris↔clickhouse, trino↔duckdb, trino↔clickhouse,
duckdb↔clickhouse). Aggregates were a gap in every pair's coverage audit.

**No Kotlin / codegen / registry work** — run SQL, write two TSVs.

## Branch / dir
`/home/jayson/DEV/brikk/brikk-house-wip`, branch `sql-focus`. Add files under
`docs/research/probe-runs/`.

## Inputs (each `id⇥full_SELECT`, run verbatim — these are complete statements with
their own inline VALUES/UNION dataset, no tables needed)
- **Trino:** `docs/research/probe-runs/aggregates-round.trino-input.tsv`
- **Doris:** `docs/research/probe-runs/aggregates-round.doris-input.tsv`

The shared dataset is `x = [1, 2, 2, 3, NULL, 5]` (numeric) and
`s = ['b','a','c','a', NULL]` (string). `aggregates-round.batch`
(`id⇥from⇥duckdb_agg⇥clickhouse_agg⇥trino_agg⇥doris_agg`) is the combined reference.

## Outputs you write  ← WRITE-BACK PATHS
- **`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/aggregates-round.trino.tsv`**  (`id⇥output`)
- **`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/aggregates-round.doris.tsv`**  (`id⇥output`)

Rendering: scalar as text (trailing ws trimmed); NULL→`NULL`; error/absent→
`<ERR:~60 chars>` (keep the row — a missing aggregate is a real NO_EQUIVALENT
signal); booleans as returned (`1`/`0` or `true`/`false`), un-normalized; if you must
adjust an expr to run, keep the same aggregate under test and append `⇥# adjusted:…`.

The `SELECT round(stddev(x),6)` etc. return a single scalar. For `string_agg`/
`array_agg` the dataset order is forced with `ORDER BY s` so the result is
deterministic.

## What to watch (likely divergences)
- **`stddev_default` / `var_default`** — the headline. DuckDB/ClickHouse/(Trino?)
  bare `stddev`/`variance` are SAMPLE (n-1: stddev≈1.516575, var=2.3). **Doris bare
  `stddev`/`variance` are POPULATION** (n: stddev≈1.356466, var=1.84) — if so, that's
  a real divergent-default hazard. Please double-check the Doris values.
- `median` — Trino has no exact median (input uses `approx_percentile(x,0.5)`, hence
  approximate); Doris uses `percentile(x,0.5)`. Expect possible divergence/approx.
- `sum_allnull` — sum over an all-NULL selection should be NULL (not 0) on both.
- `string_agg`/`array_agg` — NULL should be skipped; sorted result `a,a,b,c`.
- If Trino `variance` or Doris any aggregate errors as "does not exist", record the
  `<ERR:…>` — the ClickHouse side already showed no bare `variance` there.

## When done
Ping the brikk-sql agent. It diffs all four engines' outputs, assigns per-pair
verdicts, and folds them into the relevant `*-hazards.json` files (+ regenerates,
re-pins). Keep raw harness output under `docs/research/probe-runs/`.
