# HANDOFF — Trino↔Doris differential probe (deepening)

For the agent that has BOTH Trino and Doris live in its codebase. Goal: deepen the
`trino↔doris` hazard registry (currently 203 entries, ~84/109 common functions) by
running a 43-row batch of common cross-engine functions on **both** engines so the
brikk-sql agent can diff and fold the new verdicts in.

**You write no Kotlin / codegen / registry files.** You run SQL on live Trino and
live Doris and write two results TSVs. The brikk-sql agent does the diff, verdicts,
JSON, and wiring.

## Branch / dir
Same repo + branch as the brikk-sql agent: **`/home/jayson/DEV/brikk/brikk-house-wip`**,
git branch **`sql-focus`**. You only add new files under `docs/research/probe-runs/`.

## Inputs you read (each `id⇥expr`, run every row as `SELECT <expr>`)
- **Trino:** `docs/research/probe-runs/trino-doris-round2.trino-input.tsv`
- **Doris:** `docs/research/probe-runs/trino-doris-round2.doris-input.tsv`

Both files share the same 43 `id`s. `trino-doris-round2.batch`
(`id⇥category⇥trino_expr⇥doris_expr`) is the combined reference.

## Outputs you write  ← WRITE-BACK PATHS
- **`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/trino-doris-round2.trino.tsv`**  (`id⇥trino_output`)
- **`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/trino-doris-round2.doris.tsv`**  (`id⇥doris_output`)

Rendering rules (same as the doris↔clickhouse rounds):
- scalar value as text, trailing whitespace trimmed; NULL → literal `NULL`;
  error/absent function → `<ERR:first line ~60 chars>` (don't drop the row — an
  error is a real result, e.g. NO_EQUIVALENT).
- strings raw UTF-8; tabs/newlines → space; if a value is an invalid-UTF-8 byte,
  render `\xNN` (as the ClickHouse rounds did) and note it inline.
- booleans however the engine returns them (`1`/`0` or `true`/`false`) — don't
  normalize; the brikk agent handles it.
- if you must tweak an expr to run on your version, keep the same function under
  test and append a third column `⇥# adjusted: <what>`.

## Notes so you don't chase false errors
A few names are SQL **special forms / catalog-omitted** but DO work — expect them to
return values, not errors: Trino `nullif`, `coalesce`, `extract(... FROM ...)`
(special forms, absent from SHOW FUNCTIONS); Doris `bitand`/`bitor`/`bitxor`, `mod`,
`nvl`, `get_json_string` (present, just not in the extracted catalog). If any truly
errors, that's a real signal — record it.

## What the batch covers (43 rows)
The audit gaps + high-divergence-risk deepening: string (char_length vs
length-bytes, left/right via substr, ends_with, strpos/instr, reverse, chr, lpad/
rpad, split_part), regex, numeric (bit ops, sqrt/exp/power/truncate, neg mod, round
half-mode), null/conditional (greatest/least with NULL, nullif, coalesce, nvl),
datetime (dayofweek + week numbering, dayofyear, quarter, extract, last_day,
date_format specifiers, datediff arg order, from_unixtime), array (cardinality/
array_size, element_at, contains, array_join, array_max, array_distinct), json
(extract_scalar, array_length). Watch especially: `length` (Trino code points vs
Doris bytes), `dayofweek`/`week` numbering, `date_format` specifiers, `greatest`/
`least` NULL handling — these are the likely divergences.

(Separately: Doris `initcap` has no Trino builtin — the brikk agent will record that
as NO_EQUIVALENT without a probe.)

## When done
Ping the brikk-sql agent; it diffs the two TSVs, assigns verdicts, and folds them
into `brikk-sql/testResources/semantics/trino-doris-hazards.json` (extends the 203),
regenerates + re-pins. Keep your raw harness output under `docs/research/probe-runs/`
(nothing thrown away).
