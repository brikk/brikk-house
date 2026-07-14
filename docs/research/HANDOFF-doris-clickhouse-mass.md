# HANDOFF — Doris→ClickHouse MASS probe (Doris side)

For the agent with live Doris. Companion to the Trino mass probe. Goal: live Doris
outputs for 413 auto-generated scalar calls so the brikk-sql agent can diff against
the ClickHouse side (chdb + rename-recovery) and mass-deepen doris↔clickhouse
(currently 94) like duckdb↔clickhouse (168→213).

**No Kotlin/registry work.** Run 413 `SELECT <call>` on live Doris, write one TSV.

## Branch / dir
`/home/jayson/DEV/brikk/brikk-house-wip`, branch `sql-focus`. Add one file under
`docs/research/probe-runs/`.

## Input you read
`docs/research/probe-runs/doris-clickhouse-mass.doris-input.tsv` — `fn⇥doris_call`,
413 rows, scalar calls with typed literal args. Run each verbatim as
`SELECT <doris_call>`. Args are generic-typed; some calls will hit domain/type
errors (and a chunk are `ai_*`/ML/geo/session functions that may error or need a
running model — just record `<ERR:…>`, don't drop the row). `doris-clickhouse-mass.batch`
has the transpiled ClickHouse expr per row for reference.

## Output you write  ← WRITE-BACK PATH
**`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/doris-clickhouse-mass.doris.tsv`**
— `fn⇥output`, same 413 fn ids. Rendering as before: scalar text (trailing ws
trimmed); NULL→`NULL`; error→`<ERR:~60 chars>` (keep the row); strings raw UTF-8,
tabs/newlines→space, invalid bytes `\xNN`; booleans as returned, un-normalized.

## What happens next
brikk-sql agent diffs your Doris outputs vs the ClickHouse outputs
(`doris-clickhouse-mass.clickhouse.tsv`, already captured — 99 ran clean; ~314 are
ClickHouse-side rename/gap cases recovered with chdb). Verified verdicts fold into
`doris-clickhouse-hazards.json`; confirmed renames feed a Doris→ClickHouse
generator-gap report. Keep raw harness output under `docs/research/probe-runs/`.
