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

## Batch 7 (2026-07-13) — array family {#batch7-array}

Live DuckDB↔Doris via the shared-expr harness; Trino side bridged through live
DuckDB + the prior `trino-duckdb-hazards.json` corpus (provenance notes "Doris
live; Trino from prior evidence"). Array-returning outputs render via JDBC
`getString` differently per engine, so every array result was re-probed with
`array_size(...)` (Doris) / `array_length(...)` (DuckDB) for length AND `x[1]`
element access for values — never adjudicated on a raw SAME/DIFF of the array
render. Divergence pressure: NULL elements, out-of-bounds index, duplicates,
mixed ordering.

- **Length-accessor NAME TRAP (`array_size` vs `array_length`):** Doris has NO
  `array_length` (`Can not found function 'array_length'`); it uses `array_size`.
  DuckDB has `array_length`/`len`/`length` but NO `array_size`. So the length
  wrapper itself is non-portable even where the underlying fn is identical.
- **`cardinality` — `divergent` (domain):** DuckDB `cardinality` operates on MAPs
  ONLY (`Binder Error: Cardinality can only operate on MAPs` on an array arg);
  Doris `cardinality([1,2,3])=3` works on arrays. Portable array-length target is
  Doris `array_size`. Map `cardinality`→`cardinality` by name is fine (identical).
- **`array_cross_product` — `identical` (but note the semantics):** this is the
  3-D VECTOR cross product, NOT a cartesian product —
  `array_cross_product([1,2,3],[4,5,6])=[-3,6,-3]` element-wise SAME on both.
- **`array_append` — `identical`:** `array_append([1,2],3)=[1,2,3]` both, same name.
- **`array_first`/`array_last` — `divergent` (SIGNATURE trap):** Doris forms are
  LAMBDA/predicate fns with the array as the SECOND arg
  (`array_first(x->x>0,[10,20,30])=10`, `array_last(x->x>0,…)=30`). Trino
  `array_first(array)`/`array_last(array)` take the plain array. Rewrite via
  `array_first(x->true,arr)` or `element_at(arr,±1)`.
- **`array_max`/`array_min` — `divergent` (NULL propagation):** Doris SKIPS NULL
  elements (`array_max([1,NULL,2])=2`, `array_min([1,NULL,2])=1`); Trino
  PROPAGATES NULL (any NULL element → NULL). Non-NULL arrays align.
- **`element_at` — `divergent` (OOB):** Doris `element_at([10,20,30],9)=NULL`
  (returns NULL, no throw); Trino element_at on an array THROWS out-of-bounds.
  In-bounds 1-based access aligns.
- **`array_position` — `identical`:** 1-based; NOT-FOUND returns `0`
  (`array_position([10,20,30],99)=0`) — matches Trino. (DuckDB bridge differs:
  `list_position` not-found → NULL, so do not adjudicate this via DuckDB.)
- **`contains` → `array_contains` — `divergent`:** Doris has NO `contains`
  (`Can not found function 'contains'`); membership is `array_contains(...)` → `1`
  (TINYINT). Trino `contains` returns BOOLEAN — NAME map + boolean 0/1 type map.
- **`arrays_overlap` — `conditionally-equivalent`:** values match (overlap→1,
  disjoint→0) but Doris returns TINYINT 0/1 vs Trino BOOLEAN.
- **`array_distinct` — `conditionally-equivalent`:** Doris preserves
  first-occurrence order (`array_distinct([1,1,2,3,3])=[1,2,3]`, matches Trino);
  the DuckDB bridge (`list_distinct`) reverses to `[3,2,1]` — order must be
  checked per element, not by raw render. On Doris `array_distinct` DOES exist.
- **`array_except`/`array_remove`/`array_union` — `identical` on Doris:** all three
  exist by name on Doris and match Trino set semantics
  (`array_except([1,2,3,4],[2,4])=[1,3]`, `array_remove([1,2,3,2],2)=[1,3]` removes
  all occurrences, `array_union([1,2,3],[3,4])` size 4). The prior corpus had these
  as `no-equivalent` on the DuckDB side (DuckDB lacks them) — Doris has them.
- **`array_join` — `divergent`:** `array_join([1,NULL,3],'-')='1-3'` (NULL element
  SKIPPED with no nullReplacement arg); carries the 3-arg/DuckDB-`array_to_string`
  divergence from prior corpus forward.
- **`split` → `split_by_string` — `divergent`:** Doris exposes `split_by_string`
  (and `split_part`), NOT a Trino `split(str,delim,limit)`; map by name, verify
  empty-delimiter/limit per case.
- **`sequence` — `identical`:** inclusive stop (`sequence(1,5)` size 5, last=5),
  matches Trino. (DuckDB bridge: `generate_series` inclusive, `range` exclusive.)
- **`shuffle` — `conditionally-equivalent`:** nondeterministic permutation, equal
  as a multiset only. (DuckDB has no `shuffle`; Doris does.)
- **`array_sort` — `conditionally-equivalent`:** ascending default aligns; NULL
  placement to be verified per element.
- **`cosine_similarity` — `conditionally-equivalent`:** `=1.0` for identical
  vectors, ULP/render only.
- **`cosine_distance` — `conditionally-equivalent`:** Doris `0.02536809` vs
  DuckDB/Trino `0.025368153802923787` — ULP/precision-render diff only.

No fns marked `unclear` or blocked this batch. `array_agg` skipped (done in
batch 5). Registry additions: duckdb→doris +2 (`array_append`, `array_cross_product`;
`cardinality` already present from a prior batch, verdict consistent);
trino→doris +22.

## Batch 8 (2026-07-13) — JSON family {#batch8-json}

Live DuckDB↔Doris via the shared-expr harness on a single portable JSON literal
`'{"a":1,"b":[10,20],"c":{"d":"x"}}'` plus path expressions (`$.a`, `$.b[0]`,
`$.c.d`, missing `$.z`). Trino side bridged through the prior
`trino-duckdb-hazards.json` corpus (provenance notes "Doris live; Trino from
prior evidence"). Boolean-returning fns render Doris `1/0` vs DuckDB `true/false`
(type mapping). Divergence pressure: quote retention, JSON-type vocabulary,
NULL/missing path, invalid-JSON handling, array-render form.

- **`json_extract` — `identical`:** every node matched live — `$.a`='1',
  `$.b`='[10,20]', `$.b[0]`='10', nested `$.c.d`='"x"', missing `$.z`=NULL. BOTH
  return a JSON value with **quotes RETAINED** on strings
  (`json_extract('{"a":"x"}','$.a')`='"x"'). JSONPath dialect parses identically.
  Prior corpus confirms Trino `json_extract` == DuckDB, so Trino→Doris identical too.
- **`json_extract_string` — `identical`:** the UNQUOTING counterpart —
  `json_extract_string('{"a":"x"}','$.a')`='x' (no quotes) both; numeric/nested
  match; missing path NULL both. Maps 1:1 to DuckDB; Doris `get_json_string` is a
  live synonym (DuckDB lacks `get_json_string`/`get_json_int`, Doris-native names).
  Trino counterpart is `json_extract_scalar`.
- **`json_array` / `json_object` / `json_quote` — `identical`:**
  `json_array(1,2,3)`='[1,2,3]', `json_object('k',1,'m',2)`='{"k":1,"m":2}',
  `json_quote('x')`='"x"' — rendered identically (compact, key order preserved).
- **`json_contains` — `conditionally-equivalent`:** same truth on both
  (`'[1,2,3]','2'`→true/1; `'{"a":1}','5'`→false/0); only divergence is boolean
  render (DuckDB true/false vs Doris 1/0).
- **`json_valid` — `divergent` (REAL SEMANTIC):** `json_valid('not json')` —
  DuckDB=**false**, Doris=**1 (true!)**. Doris fails to flag malformed input as
  invalid, defeating the function's purpose. Well-formed input is true/1 on both.
  Do NOT rely on Doris `json_valid` to reject bad JSON.
- **`json_keys` — `conditionally-equivalent`:** same key set/order (a,b,c); render
  differs — DuckDB `getString`→'[a, b, c]' (unquoted) vs Doris '["a", "b", "c"]'
  (JSON-quoted elements). Element-wise equivalent, parse required.
- **`json_type` — `divergent` (two counts):** (1) VOCABULARY — DuckDB returns
  storage tokens OBJECT/ARRAY/VARCHAR/UBIGINT/BOOLEAN/NULL (`'1'`→UBIGINT,
  `'"x"'`→VARCHAR), not JSON-standard type names. (2) AVAILABILITY — on this live
  Doris FE build `json_type` AND `jsonb_type` do NOT resolve for any input form
  (VARCHAR, cast-as-json, cast-as-jsonb): FE errors `Can not found function
  'json_type'`. Root-caused to the FE build's registered function set, not the
  harness. Divergent on both counts.
- **`json_each` — `unclear`:** DuckDB `json_each` is a TABLE function (`Binder
  Error: ... used as a scalar function`), not probeable through the scalar
  shared-expr harness. Doris exposes `json_each`/`json_each_text` as table
  functions too but a row-set/ordering comparison was not run. Needs a
  table-function harness.
- **`json_parse` (Trino) — `conditionally-equivalent`:** Doris `json_parse` works
  live (`'[1,2,3]'`→[1,2,3], `'{"a":1}'`→{"a":1}), matching Trino's VARCHAR→JSON
  role. Error handling DIVERGES: Trino throws on malformed; Doris
  `json_parse('not json')`='null' (silently returns JSON null).
- **`json_format` (Trino) — `no-equivalent`:** serialize JSON→VARCHAR; no Doris
  function by that name (DuckDB lacks it too; prior corpus = no-equivalent). Doris
  uses `cast(json AS string)` instead.

Confirmed live on Doris (Doris-native names, not DuckDB/Trino sources, so not
added as pairs): `get_json_string`='x', `get_json_int`=1, `json_length('[1,2,3]')`
=3, `json_exists_path('{"a":1}','$.a')`=1 / `'$.z'`=0. Registry additions:
duckdb→doris +10, trino→doris +3. One `unclear` (`json_each`, table fn).

## Batch 9 (FINAL content batch) — misc / map / regex / no-equivalent tail {#batch9-misc}

Everything not covered by batches 1-8: format, version, xor, isinf/isnan, the map
family, uuid/random/rand, to_json, ST_* geo, regexp_extract, plus the Trino-only
math/string tail (e, crc32, normal_cdf, regexp_count, truncate, width_bucket,
parse_data_size). All probe-verified live on the Doris FE; Trino side bridged
through live DuckDB / re-adjudicated against Doris with Trino's known semantics.

**duckdb→doris:**
- **`format` — `identical`:** Doris `format` is the SAME fmt/printf brace dialect as
  DuckDB. Verified: `format('{} and {}','a','b')`='a and b', `format('{:d}',42)`='42',
  `format('{:05d}',42)`='00042', `format('{:.2f}',3.14159)`='3.14' — all match; and
  `format('%d and %s',42,'x')` returns the LITERAL `%d and %s` on BOTH (%-not-a-spec).
  This is NOT Trino's Java-Formatter %-dialect.
- **`version` — `divergent`:** same name, incomparable values by design — DuckDB
  `version()`='v1.5.4' (engine version) vs Doris='5.7.99' (MySQL-protocol compat
  version the FE reports).
- **`xor` — `no-equivalent`:** DuckDB `xor(5,3)`=6 is bitwise-int (no boolean xor —
  `xor(true,false)` is a Binder Error). Doris FE does NOT resolve `xor()` for ints
  (errCode=2 function-not-found). Doris uses `bitxor()` / `#`.
- **`isinf` / `isnan` — `conditionally-equivalent`:** same detection, boolean render
  only (DuckDB true/false vs Doris 1/0). `isinf(inf)`=true/1, `isinf(1.0)`=false/0;
  `isnan(nan)`=true/1, `isnan(1.0)`=false/0.
- **`uuid` / `random` — `conditionally-equivalent`:** UUID is 36 chars both; random in
  [0,1) both. Non-deterministic → never pushable.
- **`to_json` — `identical`:** `to_json([1,2,3])`='[1,2,3]' both.
- **`regexp_extract` — `divergent` (SIGNATURE):** 2-arg whole-match form
  `regexp_extract('abc123','[0-9]+')`=123 in DuckDB but ERRORS on Doris (FE
  can-not-found for that arity). The 3-arg group form is aligned: `('([0-9]+)',0)`=123,
  `('([a-z]+)([0-9]+)',2)`=123 both (group 0 = whole match). Doris REQUIRES the group
  index arg.
- **`map` — `divergent` (CONSTRUCTOR):** DuckDB `map([k..],[v..])` (two parallel lists)
  vs Doris `map(k,v,k,v)` (flattened scalars). Feeding DuckDB syntax to Doris does NOT
  build the same map: Doris `map_keys(map([1,2],['a','b']))`=`[[1, 2]]` (array became a
  single key) vs DuckDB `[1,2]`. Must translate the constructor.
- **`map_keys` / `map_values` / `map_entries` / `map_contains_entry` /
  `map_contains_value` — `conditionally-equivalent`:** same semantics once each engine's
  own constructor is used. Doris `map_keys(map(1,'a',2,'b'))`='[1, 2]', `map_values`=
  '["a", "b"]' (JSON-quoted render vs DuckDB unquoted), `map_entries`=
  '[{"key":1, "value":"a"}, …]' — Doris struct field names key/value ALIGN with DuckDB's
  struct(key,value). `map_contains_entry(map(1,'a',2,'b'),1,'a')`=1 and
  `map_contains_value(…,'a')`=1. Divergent map() constructor + render/boolean mapping.
- **`st_astext` / `st_geomfromwkb` — `conditionally-equivalent`:** Doris ST_* are native;
  `st_astext(st_point(1,2))`='POINT (1 2)', WKB round-trip
  `st_astext(st_geomfromwkb(st_asbinary(st_point(1,2))))`='POINT (1 2)' (Doris also has
  `st_geometryfromwkb`). DuckDB matches the SAME WKT but only with the `spatial`
  extension LOADed (verified via python-duckdb); the plain JDBC harness connection lacks
  it → conditionally-equivalent.

**trino→doris** (Doris live; Trino from prior evidence — several were `no-equivalent`
vs DuckDB but Doris HAS them, so re-adjudicated against Doris):
- **`e` — `identical`:** `e()`=2.718281828459045 = Trino Euler's number.
- **`crc32` — `identical`:** `crc32('abc')`=891568578 = standard CRC-32 of 'abc'.
- **`normal_cdf` — `identical`:** `normal_cdf(0,1,0.5)`=0.6914624612740131 = Φ(0.5); arg
  order (mean, sd, value) matches Trino.
- **`regexp_count` — `identical`:** `regexp_count('a1b2c3','[0-9]')`=3.
- **`truncate` — `identical`:** `truncate(3.14159,2)`=3.14 (truncate toward zero).
- **`width_bucket` — `identical`:** `width_bucket(5.0,0.0,10.0,5)`=3 — signature and
  bucketing match Trino (re-adjudicated; DuckDB corpus had said "different shape").
- **`parse_data_size` — `identical`:** `parse_data_size('1kB')`=1024 (kB=1024 bytes).
- **`regexp_extract` — `conditionally-equivalent`:** group semantics match but Trino's
  2-arg whole-match form has no Doris arity; use the 3-arg explicit-group form.
- **`rand` / `random` / `uuid` — `conditionally-equivalent`:** non-deterministic; Trino's
  bounded `random(n)`/`random(m,n)` bound semantics were not probed on Doris.
- **`map` — `divergent`; `map_keys` / `map_values` / `map_entries` —
  `conditionally-equivalent`:** same constructor-signature divergence as duckdb→doris;
  `map_entries` element type Trino row(K,V) vs Doris named struct(key,value) needs a
  type-layer mapping.

**Table functions — `unclear` (not scalar-probeable):** `query`, `parquet_bloom_probe`,
`parquet_file_metadata`, `parquet_kv_metadata`, `unnest` are TABLE functions; they cannot
be evaluated through the scalar shared-expr harness. Marked `unclear` with reason
"table function, not scalar-probeable" — need a table-function harness.

Registry additions: duckdb→doris +22 (2 identical, 2 divergent, 12 conditionally-equiv,
1 no-equivalent, 5 unclear table-fns), trino→doris +16 (7 identical, 1 divergent,
7 conditionally-equiv, 1 unclear table-fn `unnest`).

## Batch 10 (2026-07-13) — round + substring (deferred from batch 2) {#batch10-round-substring}

- **`round` identical (DuckDB↔Doris):** resolves the prior UNCLEAR. Both are
  half-away-from-zero and decimal-aware across every probed `.5` boundary
  (`round(2.5)`=3, `round(3.5)`=4, `round(1.25,1)`=1.3, `round(1.35,1)`=1.4,
  `round(0.15,1)`=0.2, `round(-1.25,1)`=-1.3, `round(1.005,2)`=1.01,
  `round(12345,-2)`=12300). Trino side left `conditionally-equivalent` (Java HALF_UP
  is also away-from-zero, likely aligned, but not re-probed live).
- **`substring` conditionally-equivalent:** aligned for start>=1, negative start
  (counts from end), 2-arg form, and over-length — but **diverges at start=0**:
  `substring('héllo',0,2)` = `'h'` (DuckDB, includes leading) vs `''` (Doris, MySQL
  pos=0 rule). Normalize start=0 before pushing.

This completes the bucket-A worklist (duckdb→doris and trino→doris both fully probed).

## Batch 11 (2026-07-13) — Bucket B cross-name mappings, duckdb→doris (56) {#batch11-bucketb-duckdb}

Enumerated from `gap-report.json` bucket-B entries (the functions `certify` actively
*renames* to a different Doris name), then re-confirmed each rename against the LIVE
doris generator (`DifferentialProbe.transpile`, `SqlFragment.certify`) — several
gap-report `renderedSql` templates were stale (e.g. `bit_and`→`BIT_AND` was really
`GROUP_BIT_AND`; `list_prepend`→`ARRAY_PREPEND` was really `ARRAY_PUSHFRONT`). Each
confident (`certify ok`) rename was differential-probed DuckDB `f(...)` vs the exact
generator-emitted Doris `g(...)`.

Split: **24 identical, 21 conditionally-equivalent, 4 divergent (generator bugs),
7 no-equivalent (certify refuses — unmappable)**.

- **Confident-but-wrong = brikk-sql generator BUGS (certify `ok=true`, ships wrong SQL):**
  - `list_has_any(a,b)` → `a && b` — Doris `&&` is logical-AND, cannot cast arrays to
    boolean (runtime error). Correct: `ARRAYS_OVERLAP(a,b)` (live = 1).
  - `epoch_ms(ms)` → `FROM_UNIXTIME(ms, 3)` — Doris `from_unixtime` wants SECONDS (ms
    overflows → INVALID_ARGUMENT) AND its 2nd arg is a FORMAT STRING not a precision
    (the literal `3` renders as `'3'`).
  - `string_split_regex(s,pat)` → `SPLIT_BY_STRING(s,pat)` — literal split, not regex
    (`'a1b2c','[0-9]'` → `['a1b2c']` vs DuckDB `['a','b','c']`).
  - `struct_pack(a:=1,...)` → `STRUCT(1 AS a, ...)` — Doris STRUCT rejects `AS name`
    alias syntax (error); bare `STRUCT` loses field names. Correct: `NAMED_STRUCT`.
- **Generator bug that fail-louds** (certify already refuses): `strftime` → leaks an
  internal `TS_OR_DS_TO_TIMESTAMP` node (UNMAPPABLE); the correct `DATE_FORMAT(ts,fmt)`
  works and matched live — so it is only a plumbing bug, marked `conditionally-equivalent`.
- **no-equivalent (Doris genuinely lacks; certify correctly REFUSES):** `get_bit`
  (GETBIT), `jaro_winkler_similarity`, `unicode` (ORD; ascii is byte-oriented),
  `make_date` (DATE_FROM_PARTS; MAKEDATE has different semantics), `make_timestamp`
  (TIMESTAMP_FROM_PARTS), `time_bucket` (DATE_BIN), `quantile_disc` (PERCENTILE_DISC).
- **conditionally-equivalent highlights:** boolean-as-0/1 (`list_contains`,
  `regexp_matches`), float32-precision vector distances (`list_cosine_distance`,
  `list_distance`), nondeterministic aggregate ORDER (`list`/collect_list, `arg_max`,
  `arg_min`, `string_agg`), date-vs-timestamp result type (`strptime`), double-vs-int
  (`epoch`), session/tz (`to_timestamp`, `get_current_time`).
- **`date_diff` caveat:** only the `'day'` unit maps (→`DATEDIFF(end,start)`); other
  units silently drop the unit arg — probe per-unit before trusting non-day.

## Batch 12 (2026-07-13) — Bucket B cross-name mappings, trino→doris (36) {#batch12-bucketb-trino}

Same enumeration/method; trino side adjudicated Doris-live against documented Trino
semantics + prior trino≈duckdb evidence (provenance "Doris live; Trino from prior
evidence"). Split: **17 identical, 13 conditionally-equivalent, 4 divergent,
2 no-equivalent**.

- **Generator bugs (confident-but-wrong):**
  - `json_array_contains(json,v)` → `json MEMBER OF(v)` — errors at runtime (both
    operand orders). Correct: `JSON_CONTAINS(json, v)` (live = 1).
  - `regexp_split(s,pat)` → `SPLIT_BY_STRING` — literal, not regex (same class as duckdb
    `string_split_regex`).
- **Semantic divergences:**
  - `json_extract_scalar` → `JSON_EXTRACT` keeps JSON quotes on string scalars
    (`'{"a":"hi"}'` → `'"hi"'` vs Trino `'hi'`); numeric scalars match. Correct needs
    `JSON_UNQUOTE(JSON_EXTRACT(...))`.
  - `from_iso8601_timestamp_nanos` → `CAST(x AS DATETIME)` drops ALL fractional seconds
    (Trino keeps nanoseconds).
- **no-equivalent:** `from_utf8` (DECODE), `to_utf8` (ENCODE) — both UNMAPPABLE, certify
  refuses; no working Doris byte↔utf8 equivalent found.
- **identical:** bitwise operators/aggs (`bitwise_and/or/xor(_agg)`), `chr`, `date_diff`
  (day), `day_of_month/week/year`, `last_day_of_month`, `slice`, `strpos`(arg-reorder),
  `week_of_year` (ISO, incl. year boundary 2021-01-01→53). **conditionally-equivalent:**
  approximate aggs (`approx_distinct`, `approx_percentile`), nondeterministic
  (`arbitrary`), boolean-render (`regexp_like`), binary-render (`sha256`, `sha512`),
  map/type render (`split_to_map`, `to_unixtime`, `date_parse`, `from_iso8601_timestamp`),
  float precision (`euclidean_distance`).

## Batch 13 (2026-07-13) — Bucket C-real (14 duckdb + 2 trino) {#batch13-bucketc}

The gap-report bucket-C `c1/c2` candidates turned out to be **hints the generator does
NOT actually implement**: transpiling each, all but `array_length`/`filter`/`is_nan`
emit the source name VERBATIM and `certify` REFUSES them (UNMAPPABLE). So most are
**missing mappings** (fail-loud), even though Doris *does* register an equivalent. Each
was probed DuckDB-`f` vs the Doris candidate `g` to record what the mapping WOULD be.

- **CONFIDENT-BUT-WRONG (certify ok=true) — CATALOG BUG:** `array_length` → emitted
  verbatim `ARRAY_LENGTH`; the brikk-sql-metadata Doris catalog lists it so certify
  passes, but the **live FE errors 'Can not found function ARRAY_LENGTH'**. Correct is
  `ARRAY_SIZE`/`SIZE` (live = 3). Marked `divergent`; filed (drop `array_length` from the
  Doris catalog and/or map → `array_size`).
- **Real semantic divergence:** `map_extract(m,k)` returns a **LIST** `[1]` in DuckDB vs
  Doris `element_at` scalar `1` — different result shape (`divergent`).
- **Missing-mapping opportunities (fail-loud; a mapping would be safe):** `gcd`, `lcm`,
  `list_position`→`array_position` (identical); `list_intersect`→`array_intersect`
  (set equal, order not guaranteed); `list_slice`→`array_slice` (**arg semantics differ:
  DuckDB end-index vs Doris length — naive rename diverges**); `list_zip`→`array_zip`,
  `row`→`named_struct` (struct field naming), `suffix`→`ends_with` (bool), `st_aswkb`→
  `st_asbinary` (binary), `get_current_timestamp`→`now`; trino `is_nan`→`isnan`
  (Doris fn is `isnan`, not `is_nan`). trino `filter`→`array_filter` (identical, lambda).
- **no-equivalent:** `datesub` (not a DuckDB function — ClickHouse-ism, binder error),
  `unbin` (binary-digit→blob; no Doris equivalent).

## Batch 14 (2026-07-13) — Residual unclears resolved / firmed {#batch14-unclears}

Nine `unclear` verdicts from batch A were revisited.
- **Resolved to conditionally-equivalent (table-function row-set probe via the scalar
  harness, wrapping the TVF in an aggregation subquery):** `unnest` (duckdb + trino) —
  DuckDB/Trino `unnest` == Doris `LATERAL VIEW EXPLODE`, identical ordered row-set
  (1,2,3); `json_each` (duckdb) — == Doris `EXPLODE_JSON_OBJECT`, same key/value pairs
  (a=1,b=2). Both need a structural LATERAL VIEW rewrite, not a scalar rename.
- **Remain `unclear`, now with firm specific reasons (cannot be resolved live here):**
  `query` (both register a QUERY TVF but with different semantics — DuckDB executes a SQL
  string, Doris is JDBC catalog federation; needs an external catalog);
  `parquet_bloom_probe`/`parquet_file_metadata`/`parquet_kv_metadata` (need a real
  Parquet file readable by the distributed Doris BE — repo AGENTS.md forbids staging temp
  Parquet on shared storage); trino `log` & `url_encode` (Doris side confirmed live, but
  the Trino side needs a live-Trino re-probe in the trino project — no live Trino here).

## Worklist status / continuation — COMPLETE

The bucket-A worklist is **fully probed** (batches 1–10). Final registry:
- **duckdb→doris: 186 pairs** — 80 identical, 55 divergent, 44 conditionally-equivalent,
  6 unclear, 1 no-equivalent.
- **trino→doris: 168 pairs** — 83 identical, 35 divergent, 44 conditionally-equivalent,
  3 unclear, 3 no-equivalent.

Every same-name (bucket-A) function from `doris-probe-worklist.md` now has a verdict on
both sides (verified via `tools/generate_hazards_registry.py` key counts). The throwaway
`DifferentialProbe.kt` harness was deleted from doris-focus and the cluster brought down;
doris-focus is green (`:doris-ducklake:test :doris-ducklake:detekt` BUILD SUCCESSFUL) and
back at its original commit.

### Residual follow-ups (not blockers)
- **6 unclear (duckdb) / 3 unclear (trino):** the DuckDB **table functions** (`json_each`,
  `query`, `parquet_bloom_probe`, `parquet_file_metadata`, `parquet_kv_metadata`) and
  `unnest` — not probeable through the scalar shared-expr harness; need a table-function
  harness. Plus `log` (trino single-arg) and `url_encode`/`round`/`substring` edges flagged
  for a **live-Trino re-probe** (their trino-side provenance says "Trino from prior
  evidence" — the trino agent should re-confirm the space-encoding, negative `.5`, and
  `start=0` boundaries in its own project).
- Pattern confirmed across the whole catalog: Doris is Java/MySQL-family (NULL propagation,
  MySQL `%` date specs, boolean-as-0/1, full case-folding) so it broadly matches Trino and
  diverges from DuckDB — with sharp exceptions worth re-reading in the batch sections:
  `ascii`/`length` (byte-oriented, diverge from BOTH), `log` single-arg (log10 vs ln),
  bare `stddev`/`variance` (population vs sample), `regr_avgx/count/...` (INTERNAL_ERROR on
  the live FE), `json_valid` (Doris accepts malformed JSON), Doris `to_hex`/`from_hex`
  (broken — use `hex`/`unhex`), and the domain-error contract (DuckDB throws / Doris NULL /
  Trino NaN).
