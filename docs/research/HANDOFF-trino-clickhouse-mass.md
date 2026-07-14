# HANDOFF — Trino→ClickHouse MASS probe (Trino side)

For the agent with live Trino. Goal: get live Trino outputs for 182 auto-generated
scalar function calls so the brikk-sql agent can diff against the ClickHouse side
(already captured via chdb + rename-recovery) and mass-deepen trino↔clickhouse
(currently 110) the way duckdb↔clickhouse was just taken 168→213.

**No Kotlin/registry work.** Run 182 `SELECT <call>` on live Trino 481, write one TSV.

## Branch / dir
`/home/jayson/DEV/brikk/brikk-house-wip`, branch `sql-focus`. Add one file under
`docs/research/probe-runs/`.

## Input you read
`docs/research/probe-runs/trino-clickhouse-mass.trino-input.tsv` — `fn⇥trino_call`,
182 rows. Each is a scalar call with typed literal args (`abs(5)`, `atan2(2.5, 2.5)`,
`bar(2.5, 5)`, …). Run each verbatim as `SELECT <trino_call>`.

(The auto-generated args are typed but generic; some calls may hit Trino
type/domain errors — that's fine, record `<ERR:…>` and move on. `trino-clickhouse-mass.batch`
has the transpiled ClickHouse expr per row for reference.)

## Output you write  ← WRITE-BACK PATH
**`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/trino-clickhouse-mass.trino.tsv`**
— `fn⇥output`, same 182 fn ids, any order. Rendering as before: scalar text
(trailing ws trimmed); NULL→`NULL`; error→`<ERR:first line ~60 chars>` (keep the
row); strings raw UTF-8, tabs/newlines→space, invalid bytes as `\xNN`; booleans as
returned (`true`/`false`), un-normalized.

## What happens next
The brikk-sql agent diffs your Trino outputs against the ClickHouse outputs
(`trino-clickhouse-mass.clickhouse.tsv`, already captured — 79 ran clean; the other
~103 are ClickHouse-side rename/gap cases the brikk agent will recover with chdb the
same way it did for duckdb: `list_*`→`array*`, snake→camelCase, etc.). Verified
verdicts get folded into `trino-clickhouse-hazards.json`; confirmed renames also feed
a Trino→ClickHouse generator-gap report. Keep your raw harness output under
`docs/research/probe-runs/`.
