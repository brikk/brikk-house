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

## Batch 3 (2026-07-13) — numeric / math tail {#batch3-numeric}

Live DuckDB (1.5.4) ↔ Doris differential; Trino side via prior evidence, bridged
through live DuckDB (prior corpus verdict was Trino==DuckDB for these). Two
systematic findings dominate this family:

1. **Domain-error contract diverges three ways.** For out-of-domain input, the
   live **DuckDB THROWS** (`acos(2)`, `asin(2)`, `atanh(2)`, `ln(0)`, `ln(-1)`,
   `log2(0)`, `log10(-1)`, `sqrt(-1)`, `factorial(-1)`, `cot(0)`), **Doris returns
   NULL** (`cot(0)`→`Infinity`), and **prior Trino evidence returns NaN**. Note:
   this live DuckDB throwing contradicts the older prior-corpus note "NaN on x<=0
   in both" — a DuckDB version drift, not a Doris issue. `acosh(0.5)` (<1) is the
   one case DuckDB returns NaN rather than throwing; Doris still returns NULL.
2. **Transcendental last-ULP.** Irrational-returning functions agree to ~15 sig
   digits but the final digit of the `getString` rendering routinely differs
   (`exp(0.5)`, `cbrt(27)`, `asinh(1)`, `ln(3)`, `log10(3)`, `log(2,10)`). Marked
   `conditionally-equivalent` where a probed point differed; `identical` where all
   probed points matched exactly (`sin cos tan cosh sinh tanh atan atan2`).

**Standout name trap — `log`:** single-arg `log(x)` means **log10 in DuckDB**
(`log(10)`=1.0, PostgreSQL convention) but **natural log in Doris** (`log(10)`=
2.302585). Two-arg `log(b,x)` agrees (to ULP). Never map single-arg `log` by name.

**`signbit`:** return TYPE differs — DuckDB BOOLEAN (`false`) vs Doris TINYINT
(`0`). Exact-arithmetic functions (`ceil floor sign pi degrees radians even bin
pow power`) are identical.

Registry additions: duckdb→doris +33 (12 divergent, 3 conditionally-equivalent,
18 identical); trino→doris +26 (6 divergent, 2 conditionally-equivalent, 1
unclear, 17 identical).

## Batch 4 (2026-07-13) — string / hash / encoding tail {#batch4-string}

Live DuckDB↔Doris; Trino via prior evidence + live-DuckDB bridge. Findings:

- **`left` negative count divergent:** `left('héllo',-1)` -> DuckDB `'héll'` (all but
  last |n|); Doris `''`. Positive + unicode aligned.
- **`url_encode` space divergent:** DuckDB percent-encodes space (`%20`); Doris
  form-encodes as `+`. (`url_decode` aligned.) Trino space-handling left `unclear`
  pending live re-probe.
- **`to_hex` / `from_hex` are traps on the Doris side:** Doris `to_hex()` returns
  NULL for both int and string; Doris `from_hex()` does NOT hex-decode (returns the
  hex of the input bytes). The working Doris equivalents are `hex()` (encode) and
  `unhex()` (decode) — both match. Trino->Doris must remap, never by name.
- **Doris lacks `format_number` and `levenshtein_distance`** (`levenshtein` exists
  and matches). **DuckDB lacks `hamming_distance`, `soundex`, `levenshtein_distance`,
  `format_number`** (Catalog Errors) — these live only on the Trino->Doris side.
- **`octet_length` conditionally-equivalent:** byte-count semantics align
  (`octet_length(encode('héllo'))=6` == Doris `octet_length('héllo')=6`) but DuckDB
  has no bare-VARCHAR overload (binder error; needs a BLOB arg).
- **Boolean rendering (systematic):** Doris renders BOOLEAN results as TINYINT `0/1`
  over the MySQL protocol, vs DuckDB/Trino `true/false`. This is a type-layer
  representation, not a semantic divergence — `starts_with` and `signbit` marked
  `conditionally-equivalent` (boolean type mapping required), not divergent.
- Identical (live): `bit_length` (BYTES*8), `hex`, `unhex`, `from_base64`,
  `to_base64`, `levenshtein`, `lpad`, `rpad` (incl over-length truncation), `repeat`,
  `replace`, `translate`, `url_decode`, `right`, `instr`, `concat_ws`, `sha1`,
  `char_length`, `substr`, `hamming_distance`, `soundex`.

Registry additions: duckdb→doris +21; trino→doris +19; `signbit` revised
divergent→conditionally-equivalent.

## Batch 5 (2026-07-13) — aggregate / window functions {#batch5-agg}

Live DuckDB↔Doris via the UNION-ALL scalar-subquery form; window fns wrapped with
`group_concat(...)` over `OVER(ORDER BY x)`. Trino via prior evidence + live-DuckDB
bridge. Divergence pressure: NULL elements, empty input, ties, ordering.

- **`regr_avgx/avgy/count/r2/sxx/sxy/syy` are BROKEN on this Doris FE:** every one
  throws `errCode = 2 ... [INTERNAL_ERROR]` at execution (reproduced on 2- and 3-row
  inputs) while DuckDB returns values — `divergent`. Notably `regr_intercept` and
  `regr_slope` use the *same query shape* and work (ULP-only), so it is the specific
  regr_* variants that are unusable, not the harness form.
- **`stddev` / `variance` DEFAULT MISMATCH (trino→doris):** Trino/DuckDB bare
  `stddev`==`stddev_samp` (N-1) and `variance`==`var_samp`; Doris bare `stddev`/
  `variance` are POPULATION (N). Over {1,2,4}: Trino stddev 1.5275 vs Doris 1.2472;
  variance 2.3333 vs 1.5556. Must remap Trino `stddev`→Doris `stddev_samp` and
  `variance`→`var_samp`, never by name — `divergent`.
- **`skewness` / `kurtosis` sample-vs-population divergence:** not ULP — different
  formulas. `skewness{1,2,4,8}` DuckDB 1.1376 (bias-corrected sample) vs Doris 0.6568
  (population moment); `kurtosis` DuckDB 0.7577 vs Doris -1.0990 (sign flips) —
  `divergent`.
- **`sem` divergent:** DuckDB sem = stddev_samp/√N = 0.7201 over {1,2,4}; Doris sem
  = 0.8819 — Doris does not use the standard-error-of-mean definition.
- **`histogram` incompatible:** DuckDB/Trino return a MAP(value→count) (`{1=2, 2=1}`);
  Doris `histogram` returns an approximate-quantile **bucket JSON string** — different
  type and semantic, `divergent`, never interchangeable by name.
- **`cardinality` different domain:** DuckDB `cardinality` operates ONLY on MAP
  (`Binder Error: Cardinality can only operate on MAPs` for a list — use `len`);
  Doris `cardinality(array(1,2,3))=3` works on arrays — `divergent` (domain).
- **`array_agg`/`map_agg` order-sensitive (type layer):** return LIST/MAP; input-order
  dependent without ORDER BY (DuckDB kept [3,1,2], Doris returned [1,2,3]) —
  `conditionally-equivalent`, add explicit ORDER BY for parity.
- **Statistical ULP-only (`conditionally-equivalent`):** `corr`, `covar_pop`,
  `covar_samp`, `stddev_samp`, `var_pop`, `var_samp`, `regr_intercept`, `regr_slope`
  — last-ULP accumulation-order diffs, value-equivalent. `stddev_pop` exact-matched.
- **Boolean rendering:** `bool_and`/`bool_or` value-identical but Doris renders BOOLEAN
  as TINYINT 0/1 — `conditionally-equivalent` (type mapping).
- **Nondeterministic:** `any_value`, `max_by`, `min_by` order-sensitive;
  `approx_count_distinct` HLL states non-interchangeable — all
  `conditionally-equivalent`.
- **`percent_rank`/`cume_dist` numeric rendering:** values identical, Doris renders
  integral doubles as `0`/`1` vs DuckDB `0.0`/`1.0` — `conditionally-equivalent`.
- **Identical (live):** `avg`, `sum`, `count`/`count(*)`, `max`, `min`, `median`
  (NULL-skip + empty→NULL verified), and window fns `row_number`, `rank`,
  `dense_rank`, `ntile`, `lag`, `lead`, `first_value`, `last_value`, `nth_value`
  (ties, NULL edges, and default frames all align).

Registry additions: duckdb→doris +43; trino→doris +37.

## Batch 6 (2026-07-13) — datetime / timezone {#batch6-datetime}

Live DuckDB↔Doris via the shared-expr harness (fresh connections, so the session
zone could not be pre-`SET` — session/format-sensitive fns are marked
`conditionally-equivalent`). Trino side bridged through live DuckDB + the prior
`trino-duckdb-hazards.json` corpus (provenance notes "Doris live; Trino from prior
evidence"). Divergence pressure: leap day `2024-02-29`, ISO-week edge `2023-01-01`
(a Sunday), Sunday/Monday pins `2024-01-07`/`2024-01-08`, fractional seconds
`.123456`, epoch `0`, and both format-spec dialects.

- **`date_format` — CORRECTED (the brief's guess was wrong):** Doris `date_format`
  uses **MySQL `%`-specifiers**, NOT Java `yyyy`. Probe-verified live:
  `date_format(ts,'%Y-%m-%d')='2024-01-02'`, `%M`→`January`; bare `'yyyy'`→literal
  `yyyy` (unformatted). Trino `date_format`/`date_parse` are ALSO MySQL `%`-style
  (only Trino `format_datetime`/`parse_datetime` are Joda). So **trino→doris
  `date_format` is `conditionally-equivalent`** on the `%`-spec dialect (NOT
  divergent). DuckDB has no `date_format` at all (uses `strftime` with `%`-specs) —
  `strftime('%Y-%m-%d')` matches Doris `date_format('%Y-%m-%d')`.
- **`date_add` / `date_sub` / `datediff` — signature/direction traps:** DuckDB has NO
  `date_sub(date,INTERVAL)`; its `date_sub`/`datediff` are 3-arg `(VARCHAR unit, DATE,
  DATE)` date-DIFFs (`datediff('day','2024-02-01','2024-03-01')=29`, reversed→`-29`).
  Doris `date_sub(date,INTERVAL)` subtracts, `datediff(a,b)=a-b` (2-arg). Same names,
  incompatible arity/order → **`divergent`**. Trino `date_add('day',n,ts)` also arg-order
  divergent from Doris `date_add(date,INTERVAL n unit)`. `date_add` value/instant does
  match (2024-01-02) but DuckDB returns TIMESTAMP vs Doris DATE →
  `conditionally-equivalent` on the duckdb side.
- **Week family — mode mismatch (`divergent`):** Doris default `week`/`yearweek` is
  mode 0 (Sunday-start); DuckDB/Trino are ISO-8601. `week('2023-01-01')`: DuckDB/Trino
  `52` vs Doris `1`; `yearweek('2023-01-01')`: DuckDB `202252` vs Doris `202301`.
  `weekofyear` IS ISO on both (`52`) — the parity target. Trino `year_of_week`/`yow`
  (ISO year `2022`) has no direct Doris name (`no-equivalent`); Doris `yearweek` packs
  YYYYWW under mode 0.
- **Weekday numbering — `divergent`:** pinned Sunday `2024-01-07` / Monday
  `2024-01-08`. `dayofweek`: DuckDB Sun=0..Sat=6; Doris Sun=1..Sat=7; Trino `dow` ISO
  Mon=1..Sun=7 — three different bases. `weekday`: DuckDB=dow(Sun=0), Doris=ISO Mon=0.
- **`microsecond` — `divergent`:** `microsecond(ts '…05.123456')` DuckDB `5123456`
  (includes seconds×1e6) vs Doris `123456` (sub-second only).
- **`to_days` / `to_seconds` — `divergent` (different function):** in DuckDB these are
  INTERVAL constructors (`to_days(INTEGER)->INTERVAL`; `to_days(DATE)` is a Binder
  Error) whereas Doris returns a day/second count since year 0 (`739251`/`63871286400`).
- **`date_trunc` — `conditionally-equivalent`:** values align for day..year/quarter;
  only fractional-second rendering differs (DuckDB `.0`, Doris none).
- **Identical (rename/value):** `dayname`, `dayofmonth`, `dayofyear`, `day`, `month`,
  `year`, `quarter`, `hour`, `minute`, `second`, `monthname`, `last_day`, `century`,
  `weekofyear`, `date`, `from_iso8601_date` (leap-day safe both).
- **Session/context-dependent (`conditionally-equivalent`):** `now`, `current_date`,
  `from_unixtime` (epoch `0`→`1970-01-01 00:00:00`), `to_iso8601` (date aligns; TS
  fractional precision differs 6 vs 3 digits), and the metadata accessors
  `current_database` (empty on Doris = no db selected), `current_catalog`
  (DuckDB `memory` vs Doris `internal`), `current_user`/`session_user`/`user`
  (Doris renders `'root'@'host'`, and Doris `current_user` `'root'@'%'` differs from
  `session_user` `'root'@'172.30.80.1'`).

Registry additions: duckdb→doris +32; trino→doris +22.

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
