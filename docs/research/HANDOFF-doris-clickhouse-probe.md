# HANDOFF — Doris↔ClickHouse differential probe (Doris side)

Self-contained handoff for the Doris live-probe agent (the one that ran the
`doris-probe-worklist` / `REPORT-doris-differential-probe-2026-07-13`). Goal:
produce **live Doris outputs** for a fixed probe batch so the brikk-sql agent can
diff them against **live ClickHouse outputs (already captured via chdb here)** and
build the `doris↔clickhouse` semantic-hazard registry — the one remaining
core-pair gap (we have trino↔duckdb, duckdb↔doris, trino↔doris, duckdb↔clickhouse,
trino↔clickhouse).

**You do NOT write any Kotlin, codegen, or registry files.** You only run SQL on a
live Doris and write one results TSV. The brikk-sql agent does all the
composition, verdict assignment, JSON, and wiring.

## Branch / dir
Same repo + branch as the brikk-sql agent: **`/home/jayson/DEV/brikk/brikk-house-wip`**,
git branch **`sql-focus`**. Write your results file here (a new file — nothing is
overwritten). Use your own live-Doris worktree/harness for execution (e.g. the
`doris-focus` worktree + JDBC `jdbc:mysql://127.0.0.1:9030/?user=root`, per
`HANDOFF-doris-probe-continuation.md`).

## Input you read
`docs/research/probe-runs/doris-clickhouse.doris-input.tsv` — 57 rows, tab-separated:

```
<id>\t<doris_expr>
```

`doris_expr` is a scalar expression in **Doris** syntax with literal inputs (no
tables). Evaluate each as `SELECT <doris_expr>`. (The full batch with the paired
ClickHouse expressions + category is in `doris-clickhouse-probe.batch` for
reference, but you only need the doris-input file.)

## Output you write  ← THIS IS THE WRITE-BACK PATH
**`docs/research/probe-runs/doris-clickhouse.doris.tsv`**

One line per input row, tab-separated, **same `id`s, any order**:

```
<id>\t<rendered_output>
```

Rendering rules (so it diffs cleanly against the ClickHouse side):
- **Scalar value** rendered as text, exactly as the engine returns it, trimmed of
  trailing whitespace/newline. Numbers as-is (`3`, `2.35`, `1704067200`).
- **NULL** → the literal token `NULL`.
- **Error / unsupported / unknown function** → `<ERR:first line of message, ~60 chars>`
  (do NOT drop the row — an error is itself a meaningful result, e.g. a function
  Doris lacks). Keep going past errors.
- **Strings**: return the raw UTF-8 text. If a value contains a tab or newline,
  replace it with a space. For byte-vs-codepoint cases the raw text is enough; if
  your harness already codepoint-renders (`"x"[U+0078 …]`) that's fine too — just
  be consistent and say which in a header comment (`# rendered=raw` or
  `# rendered=codepoint`).
- **Booleans**: however Doris returns them (`1`/`0` or `true`/`false`) — don't
  normalize; the brikk agent handles `1`↔`true` when diffing.

If a row's `doris_expr` needs a tiny syntactic fix to run on your Doris version
(e.g. a cast form), fix it, run it, and note the change inline as a third column
`<id>\t<output>\t# adjusted: <what>` so we keep provenance. Do not change which
function is being tested.

## What the batch covers (57 rows)
Curated cross-dialect landmines, the categories that diverge on almost every
engine pair, chosen from the conservative `duckdb`/`trino`-pivot composition to
resolve the *uncertain* ones (things that diverge from the pivot on both legs, so
composition can't tell if Doris and ClickHouse actually agree):
- **string/unicode**: lower/upper (+ UTF8 variants), length vs char_length, ascii,
  reverse, substring (incl. negative start), locate/position, trim/ltrim with
  chars, concat-with-NULL, lpad, repeat, replace, split_part
- **regex**: regexp_replace (first-vs-all), regexp_extract
- **numeric**: round (half-even vs half-away, negatives, decimals), truncate, log/
  ln/log10/log2, mod of negatives, pow, floor/ceil of negatives, sign, abs
- **datetime**: dayofweek (Sun vs Mon numbering), iso week, year/month/day/hour,
  date_trunc month, datediff, unix_timestamp / from_unixtime
- **null/conditional**: coalesce, nullif, if, greatest/least with NULL
- **hash/encoding**: md5, hex(int), crc32

## After you write the file
Ping the brikk-sql agent. It will:
1. diff `doris-clickhouse.doris.tsv` vs `doris-clickhouse.clickhouse.tsv` (already
   present) with value normalization,
2. assign verdicts (identical / conditionally-equivalent / divergent) with the
   real evidence,
3. write `brikk-sql/testResources/semantics/doris-clickhouse-hazards.json`, wire it
   into `HazardRegistry`, regenerate the registry, and pin tests.

## Keep the raw data (do not delete)
Per the "nothing thrown away" rule: the batch + both engines' raw output TSVs stay
committed under `docs/research/probe-runs/` as the permanent, reusable evidence for
this pair (and extendable for future functions / other pairs). Do not delete your
harness output; copy it into the write-back path above.

---

## Round 2 (deepening — common functions not in round 1)

Same contract as above. New batch of 44 common cross-engine functions (string
rtrim/rpad/left/right/chr/starts_with/ends_with/instr/initcap/space, regex,
numeric sqrt/exp/cbrt/bitand/bitor/bitxor/gcd/power/abs/floor/ceil, datetime
minute/second/dayofyear/quarter/last_day/to_date/date_add/date_sub/date_format/
extract, array size/contains/element_at/join/max/distinct, json extract/length,
nvl, hex/unhex/sha1).

- **Read:** `docs/research/probe-runs/doris-clickhouse-round2.doris-input.tsv`
  (`id⇥doris_expr`, 44 rows). Full paired batch with the ClickHouse expressions +
  category is `doris-clickhouse-round2.batch`; ClickHouse outputs already captured
  in `doris-clickhouse-round2.clickhouse.tsv`.
- **Write back:** `docs/research/probe-runs/doris-clickhouse-round2.doris.tsv`
  (`id⇥rendered_output`, same rules as round 1: raw UTF-8, NULL→`NULL`,
  errors→`<ERR:…>`, don't drop rows — a missing/error function is a real result).

Absolute write-back path:
`/home/jayson/DEV/brikk/brikk-house-wip/docs/research/probe-runs/doris-clickhouse-round2.doris.tsv`

When done, ping the brikk-sql agent to diff + fold the new verdicts into
`doris-clickhouse-hazards.json` (extends the 47 from round 1).
