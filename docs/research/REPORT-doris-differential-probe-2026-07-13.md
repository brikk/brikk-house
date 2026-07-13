# REPORT — Doris semantic differential probe (2026-07-13, batch 1)

Live differential probes for the `doris-probe-worklist.md` bucket-A functions.
Instrument: **DuckDB** (`jdbc:duckdb:` in-memory) and the **live Doris FE**
(`jdbc:mysql://127.0.0.1:9030`, compose `smoke.sh --up-only`) evaluating the
**same** scalar expression; results captured with codepoints. Throwaway probe:
`doris-ducklake/test/.../corpus/DifferentialProbe.kt` (deleted after this report).

Batch 1 = the prior-DIVERGENT string/unicode/null cluster. **DuckDB↔Doris is a
full live differential.** The Trino↔Doris column compares the Doris live result
against the probe-verified Trino behavior in `trino-duckdb-hazards.json` (Trino
not re-run live this session — flagged in provenance); a live-Trino re-run should
confirm before those are treated as gospel.

## Headline
Doris is Java/MySQL-family: on every batch-1 case it **matches Trino and diverges
from DuckDB**. So duckdb→doris pairs are DIVERGENT; trino→doris pairs are
IDENTICAL (except `ascii`, where Doris is byte-oriented and diverges from both).

## Raw results (codepoints)

| expr | DuckDB | Doris |
|---|---|---|
| `lower('İ')` (U+0130) | `i` (U+0069) | `i̇` (U+0069 U+0307) |
| `upper('ß')` (U+00DF) | `ẞ` (U+1E9E) | `SS` (U+0053 U+0053) |
| `concat('a',NULL,'c')` | `ac` | NULL |
| `concat(NULL,NULL)` | `` (empty) | NULL |
| `greatest(1,NULL,2)` | `2` | NULL |
| `least(1,NULL,2)` | `1` | NULL |
| `split_part('a,b',',',5)` | `` (empty) | NULL |
| `reverse('cafe'+◌́)` (decomposed) | `e◌́fac` grapheme-aware | `◌́efac` code-point |
| `ascii('É')` (U+00C9) | `201` (codepoint) | `195` (first UTF-8 byte) |
| `regexp_replace('aaa','a','b')` | `baa` (first) | `bbb` (all) |

## Verdicts + anchors

### lower {#lower}
duckdb→doris **divergent** — Turkish `İ`: DuckDB simple fold → `i`; Doris full
fold → `i` + combining dot (U+0307). ASCII aligned. trino→doris **identical**
(Trino prior = `i`+U+0307, same as Doris).

### upper {#upper}
duckdb→doris **divergent** — `ß`: DuckDB → `ẞ` (U+1E9E); Doris → `SS`
(length-changing full fold). trino→doris **identical** (Trino prior = `SS`).

### concat {#concat}
duckdb→doris **divergent** — DuckDB skips NULLs (`ac`, `''`); Doris propagates
NULL. trino→doris **identical** (Trino prior propagates NULL).

### greatest / least {#greatest-least}
duckdb→doris **divergent** — DuckDB skips NULLs; Doris returns NULL if any arg
NULL. trino→doris **identical** (Trino prior = NULL-if-any-NULL).

### split_part {#split-part}
duckdb→doris **divergent** — out-of-bounds index: DuckDB → `''`; Doris → NULL.
In-bounds aligned. trino→doris **identical** (Trino prior = NULL).

### reverse {#reverse}
duckdb→doris **divergent** — DuckDB is grapheme-cluster-aware (keeps `e`+combining
together); Doris reverses code-points (combining mark floats). Precomposed forms
and ASCII aligned. trino→doris **identical** (Trino prior = code-point-only).

### ascii {#ascii}
duckdb→doris **divergent** — `ascii('É')`: DuckDB → 201 (Unicode codepoint);
Doris → 195 (first UTF-8 byte, 0xC3). trino→doris **divergent** too — Trino prior
returns the codepoint (requires varchar(1)); Doris's byte-orientation diverges
from BOTH engines. ASCII aligned.

### regexp_replace {#regexp-replace}
duckdb→doris **divergent** — DuckDB replaces the FIRST match without a `g` flag
(`baa`); Doris replaces ALL (`bbb`). trino→doris **identical** (Trino prior =
replace-all default).

## Deferred to batch 2
`trim` / `ltrim` / `rtrim`: tab (U+0009) was kept by BOTH DuckDB and Doris
(NOT the trino-style strip). Needs the full whitespace-set inputs (EM SPACE
U+2003, NBSP U+00A0, LF/CR/FF/VT) to adjudicate the DuckDB↔Doris set boundary —
DuckDB strips space+EM SPACE but not tab; unclear whether Doris strips EM SPACE.

## Batch 2 (2026-07-13) — remaining scalar DIVERGENT + finds {#batch2}

Live DuckDB↔Doris differential (Trino side from prior evidence):
- `trim`/`ltrim`/`rtrim` **divergent**: Doris strips ASCII space only; DuckDB strips space+EM SPACE(U+2003)+NBSP(U+00A0); Trino strips Java-whitespace (tab/LF/CR/FF/VT). All three whitespace sets differ.
- `bit_count` **divergent**: `bit_count(-1)` → DuckDB 32 (INT32 width) vs Doris 8 — width inference differs.
- `fmod` **divergent**: `fmod(-5.3,2)` → DuckDB 0.70 vs Doris -1.30 (negative-operand definition differs).
- `regexp_extract_all` **divergent**: DuckDB returns the match array; Doris returned empty (signature/empty-match differ).
- `length` **divergent (NEW)**: Doris `length()` = BYTE count (`length('世界')`=6, `length('héllo')`=6); DuckDB/Trino = CHARACTER count (2, 5). Use `char_length` for parity. Doris diverges from BOTH engines.
- Identical (aligned live): `abs`, `mod` (incl. negatives), `coalesce`, `nullif`, `md5`.
- `round`, `format`, `substring`: tested SAME on the probed inputs but not exhaustively — left for a targeted follow-up (round half-rounding per prior UNCLEAR; format spec dialects).

## Worklist status / continuation

Done (batches 1-2): the prior-DIVERGENT scalar tier + the new `length` find + cheap
IDENTICAL confirmations. Registry: 21 duckdb→doris (16 divergent, 5 identical),
21 trino→doris (13 identical, 8 divergent).

Remaining (~300, lower signal): the IDENTICAL/none scalar tail, and the families
that need more setup — arrays (`array_join/max/min`, `[..]` literals), aggregates
(`max_by/min_by/any_value/approx_count_distinct`, need GROUP BY), datetime/timezone
(`date_add/date_format/date_trunc/hour/current_date`, need `SET time_zone` traps),
and `split`/`date_format` format-string dialects. Method to resume: recreate the
file-driven differential harness (DuckDB `jdbc:duckdb:` + live Doris FE
`jdbc:mysql://127.0.0.1:9030`, same-expr, codepoint render), feed batches of
`fn<TAB>expr` from the worklist's suggested areas, adjudicate DuckDB↔Doris live +
Doris-vs-prior-Trino. Pattern holds so far: Doris is Java/MySQL-family (matches
Trino, diverges from DuckDB); the tail is mostly mechanical confirmation.
