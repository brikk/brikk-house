# ClickHouse ↔ DuckDB / Trino differential-probe report (2026-07-13)

Live differential probing of same-name (bucket-A) function pairs to populate the
`duckdb↔clickhouse` and `trino↔clickhouse` semantic-hazard registries, per
`HANDOFF-clickhouse-probe-program.md`. Method mirrors the doris program
(`REPORT-doris-differential-probe-2026-07-13.md`): verdicts are **live** engine-vs-engine
results over a divergence-pressure corpus, never doc-reading.

## Engines & method

| | |
|---|---|
| ClickHouse | **26.5.1.1** — `chdb` 4.2.1 (embedded `clickhouse-local` engine for Python; `chdb-core` 26.5.0). No server needed. |
| DuckDB | **1.5.4** — python `duckdb` module (embedded). |
| Trino | not run live — trino↔clickhouse verdicts are **composed**: live ClickHouse results joined with the probe-verified Trino behavior in `trino-duckdb-hazards.json`. Every such row's provenance is tagged `composed: clickhouse-live + trino-duckdb-hazards`; a live-Trino agent can upgrade provenance later. |
| Session | Both engines pinned to **UTC** before every probe (`SET TimeZone='UTC'` / `SETTINGS session_timezone='UTC'`) — ClickHouse DateTime is timezone-typed, so this is mandatory for apples-to-apples datetime results. |

- Harness: file-driven, both engines in-process (`/tmp/opencode/harness.py`). Values are
  codepoint-rendered; SAME/DIFF is numeric/bool-normalised (so `1`≡`true`, `3`≡`3.0`) with
  the raw type preserved for adjudication.
- **Raw evidence** (mandatory data-capture): `docs/research/probe-runs/*.batch` (inputs) +
  `*.tsv` (full codepoint-rendered results). 276 probe rows across 13 batches; never
  overwritten. Verdicts below are derived from those TSVs and are re-derivable.
- Catalog (step 0): `vendor/data/clickhouse-functions-26.5.1.1.tsv` (1787 functions, 230
  aggregate, 255 case-insensitive, 218 aliases). Provenance in `vendor/README.md`.
- Worklist (step 1): bucket-A same-name intersections computed on lowercased names
  (ClickHouse recognises SQL-standard names as case-insensitive aliases): **171**
  duckdb∩clickhouse, **115** trino∩clickhouse.

## Deliverable tallies

- `duckdb-clickhouse-hazards.json`: **152** pairs — 66 identical, 52 divergent, 34 conditionally-equivalent.
- `trino-clickhouse-hazards.json`: **107** pairs — 47 identical, 34 divergent, 26 conditionally-equivalent.
- Generator mapping bugs found: `BUGS-clickhouse-generator-mappings-2026-07-13.md`.

## The ClickHouse "shape" (heuristic — but VERIFY per function)

ClickHouse diverges from DuckDB on axes that recur across whole families:

1. **Strings are BYTE-oriented by default.** `length`/`reverse`/`substring`/`lpad`/`ascii`
   operate on bytes; the `*UTF8` variants are code-point-aware. DuckDB is code-point (and
   grapheme, for `reverse`) by default.
2. **`lower`/`upper` are ASCII-only.** Non-ASCII passes through unchanged; `lowerUTF8`
   /`upperUTF8` are the Unicode variants (and even those diverge on İ/ß).
3. **Out-of-range → type DEFAULT, not NULL.** Array OOB indexing, `indexOf` misses, window
   `lag`/`lead`/`nth_value` out-of-frame, and **empty-group aggregates** all return the
   type default (0 / '' / nan) where DuckDB/Trino return NULL.
4. **Domain errors are absorbed to NULL**, not thrown: `sqrt(-1)`, `ln(0)`, `acos(2)`,
   `pow(-8,0.5)` → NULL in ClickHouse; DuckDB throws, Trino throws/NaN.
5. **Integer arithmetic wraps / auto-widens silently** (no overflow check like DuckDB);
   `sum` over fixed-width ints overflow-wraps.
6. **`round` is banker's (half-even)** by default; DuckDB is half-away-from-zero.
7. **Date/DateTime floor at 1970** (epoch-based unsigned Date). Pre-1970 dates clamp.
8. **Names are case-SENSITIVE** except the 255 SQL-standard case-insensitive aliases.

Trino, by contrast, tends to align with ClickHouse on **NULL-propagating `concat`** and
**replace-all `regexp_replace`** (both differ from DuckDB there), but diverges on Unicode
folding, `round` (half-up), and the 1970 range.

---

## Findings by area

<a id="unicode-case-folding"></a>
### Unicode case folding — `lower`/`upper`/`lcase`/`ucase`
Raw: `*-string-unicode-null.tsv`, `*-collisions.tsv`. ClickHouse `lower('İ')`='İ',
`upper('ß')`='ß', `lower('CAFÉ')`='cafÉ' — **ASCII-only, non-ASCII untouched**. DuckDB folds
full Unicode (`ß`→`ẞ`). `lowerUTF8('İ')`='i'+U+0307, `upperUTF8('ß')`='SS' (matches Trino's
Java folding) — Unicode-aware but still ≠ DuckDB. Verdict: **divergent** vs both; the plain
`lower`/`upper` names silently no-op on non-ASCII.

<a id="string-length-bytes"></a>
### String length is a BYTE count — `length`/`octet_length` vs `char_length`
`length('😀')`=4, `length('中文')`=6, `length(ZWJ-family)`=18 in ClickHouse (bytes) vs 1/2/5
in DuckDB (code points). `char_length`/`character_length` (== `lengthUTF8`) count code
points and are **identical** to DuckDB. `octet_length` diverges the other way: ClickHouse
takes a VARCHAR; DuckDB's `octet_length` has no bare-VARCHAR overload (BLOB only) and errors.

<a id="reverse-byte-vs-grapheme"></a>
### `reverse` is a byte reverse
ClickHouse `reverse` reverses bytes, mangling multi-byte UTF-8 into U+FFFD; `reverseUTF8`
reverses code points (splitting combining marks). DuckDB `reverse` is grapheme-cluster-aware
(ZWJ emoji family survives). **Divergent** on all non-ASCII.

<a id="trim-whitespace-set"></a>
### `trim` whitespace set
ClickHouse `trim`/`trimLeft`/`trimRight` strip **only the ASCII space (0x20)**: NBSP
(U+00A0) and EM SPACE (U+2003) survive; DuckDB additionally strips those. Neither strips
tab/newline. Trino (Java `Character.isWhitespace`) strips tab/LF/CR/FF/VT too — so ClickHouse
is the *narrowest* of the three. **Divergent**.

<a id="concat-null-propagation"></a>
### `concat` / `concat_ws` NULL algebra
ClickHouse `concat('a',NULL,'c')`→**NULL** (propagates any NULL arg); DuckDB→'ac' (skips).
Same for `concat_ws` elements. This makes duckdb↔clickhouse **divergent** but trino↔clickhouse
**aligned on NULL** (Trino also propagates). `concat_ws` NULL separator → NULL in all three.
The brikk generator already compensates duckdb→clickhouse by wrapping args in `COALESCE(...,'')`.

<a id="substring-byte-and-start0"></a>
### `substring` byte-indexed + `start=0` edge
ClickHouse `substring` is BYTE-indexed (splits `😀`) and `substring('abcde',0,2)`→'' (start 0
is not treated as 1); DuckDB is code-point-indexed and clamps start 0→1. `substringUTF8`
fixes the multibyte axis only. Negative-length also diverges (`substring('abcdef',2,-1)`:
ClickHouse 'bcde' vs DuckDB 'a'). **Divergent**.

<a id="position-instr"></a>
### `position` / `instr` / `ascii`
`instr` is the sharp one: ClickHouse `instr` aliases **`positionCaseInsensitive`** —
`instr('Hello','h')`=1 (case-insensitive) vs DuckDB 0 (case-sensitive). `position` differs in
argument order/shape (`position(haystack,needle)` vs DuckDB's `position(needle IN haystack)`;
DuckDB's comma form is `strpos`). `ascii('😀')`=240 (first **byte**) vs DuckDB 128512 (code
point). All **divergent** except that mapping `instr`→`POSITION` (as the generator does) is
correct because both are case-sensitive.

<a id="replace-regexp-first-vs-all"></a>
### `replace` (all) and `regexp_replace` (first-vs-all)
`replace` == `replaceAll` (all occurrences) — **identical** to DuckDB and Trino. But
`regexp_replace` aliases **`replaceRegexpAll`** (ALL matches): **identical to Trino**
(replace-all), **divergent from DuckDB** (first-only unless 'g'). Empty-pattern also differs
(ClickHouse no-op vs DuckDB inserts at every position).

<a id="regexp-extract-groups"></a>
### `regexp_extract` group semantics — identical
Probed backslash-free (`([0-9]+)-([0-9]+)`) to avoid the literal-escaping confound below:
group 0 = whole match, group N = Nth capture, in both. RE2 in both. **Identical.**

<a id="literal-escaping"></a>
### String-literal backslash escaping (lexer-level, not a function pair)
Not a function hazard but a transpiler trap: ClickHouse **unescapes** C-style backslashes in
string literals (`'x\\dy'`→`x\dy`); DuckDB keeps them literal (`x\\dy`). This silently
corrupts regex patterns carried as literals across a transpile. Documented; no registry row.

<a id="pad-bytes"></a>
### `lpad`/`rpad` pad by bytes
ClickHouse `leftPad`/`rightPad` count bytes; `leftPadUTF8`/`rightPadUTF8` count code points.
ASCII aligned. **Divergent** on multibyte.

<a id="boolean-rendering"></a>
### Boolean / predicate return type
`isnan`/`isfinite`/`match`/`json_exists` return UInt8 `0/1` in ClickHouse vs BOOLEAN
`true/false` in DuckDB. Same truth value — **conditionally-equivalent** (type-layer), not
divergent (mirrors the doris boolean-rendering note).

<a id="format-spec"></a>
### `format` / `printf`
ClickHouse `format` uses positional `{0}`/`{1}` placeholders (Python/.NET style); DuckDB
`format` uses fmt/printf `%`-style — **divergent** spec dialects. ClickHouse `printf` uses C
`%`-specifiers and is **identical** to DuckDB `printf`.

<a id="hash-return-shape"></a>
### Hash functions — digest identical, return SHAPE differs
`MD5`/`SHA1`/`SHA256`/`SHA512` compute the same digest but ClickHouse returns raw
`FixedString` bytes; DuckDB returns a lowercase hex VARCHAR (wrap `lower(hex(...))` to
compare); Trino returns VARBINARY. **Conditionally-equivalent.** `CRC32`=891568578 matches
Trino's crc32 (DuckDB has none). `xxHash64` matches Trino XXH64 bits (UInt64 vs VARBINARY).

<a id="numeric-div-zero"></a>
### Division / modulo by zero — three-way split
Float `1.0/0.0`: ClickHouse **NULL**, DuckDB **+inf**. `intDiv`/`modulo`/`gcd(0,x)`:
ClickHouse **throws** (code 153); DuckDB `//`/`mod` return **NULL**, `gcd(0,5)`=5. **Divergent.**

<a id="numeric-overflow"></a>
### Integer overflow — wrap/widen vs throw
`toInt32(2147483647)+toInt32(1)`→2147483648 (ClickHouse auto-widens/wraps, no error);
`abs(INT32_MIN)`→2147483648; DuckDB **throws** overflow on both. **Divergent.**

<a id="rounding-bankers"></a>
### `round` is banker's (half-even)
`round(2.5)`=2, `round(3.5)`=4, `round(-2.5)`=-2 in ClickHouse (round-half-to-even);
DuckDB gives 3/4/-3 (half-away-from-zero); Trino is half-up. `roundBankers` == ClickHouse
`round`. **Divergent** vs both peers.

<a id="numeric-domain-null-vs-throw"></a>
### Domain errors absorbed to NULL
`sqrt(-1)`, `ln(0)`, `ln(-1)`, `acos(2)`, `asin(2)`, `log10(0)`, `pow(-8,0.5)` → **NULL** in
ClickHouse; DuckDB **throws**, Trino throws/NaN. `factorial(-1)`→**1** in ClickHouse(!) vs
DuckDB throw. **Divergent.**

<a id="log-collision"></a>
### `log` base collision
ClickHouse single-arg `log(x)` = **natural log** (== `ln`); DuckDB `log(x)` = **log10**; Trino
`log(b,x)` is base-b (two-arg, ClickHouse errors on two-arg log). `log10`/`log2` are
unambiguous and **identical**. Mapping `log`→`log` silently changes the base.

<a id="bin-padding"></a>
### `bin` zero-padding
`bin(5)`='00000101' (padded to a full byte) in ClickHouse vs '101' in DuckDB. **Divergent.**

<a id="xor-logical-vs-bitwise"></a>
### `xor` is logical, not bitwise
ClickHouse `xor(5,3)`=0 (both truthy → logical xor false); DuckDB `xor(5,3)`=6 (bitwise).
**Divergent.** (ClickHouse bitwise xor is `bitXor`.)

<a id="array-indexing-oob"></a>
### Array indexing — OOB returns default, not NULL
`arrayElement([10,20,30],5)`=0, `arrayElement(['a','b'],5)`=''  (type default) vs DuckDB
NULL; index 0 also → default vs NULL. `indexOf` miss → 0 vs DuckDB `list_position` NULL.
Negative indices align (from end). `cardinality` collides: DuckDB `cardinality` is **MAP-only**
(errors on a list), ClickHouse `cardinality` == `length` (array element count). **Divergent.**

<a id="aggregate-empty-group"></a>
### Empty-group aggregates → default, not NULL
Over an empty set: ClickHouse `sum`→0, `avg`→nan, `min`/`max`→0 (type default); DuckDB→NULL
for all. `count`→0 in both. Over an all-NULL row: **both** →NULL (aligned) — the divergence is
specifically the **empty group**. **Divergent.**

<a id="window-default-fill"></a>
### Window `lag`/`lead`/`nth_value` fill default, not NULL
Out of frame, ClickHouse (`lagInFrame` etc.) fills the type default (0); DuckDB/Trino return
NULL. `row_number`/`rank`/`dense_rank`/`ntile`/`first_value`/`last_value`/`percent_rank`/
`cume_dist` are **identical**.

<a id="aggregate-sum-overflow"></a>
### `sum` overflow-wraps fixed-width ints
`sum` of two near-max Int64 → wraps to Int64 min in ClickHouse (silent); DuckDB widens to the
correct value. Folded into the `sum` divergent row.

<a id="datetime-range-1970"></a>
### Date/DateTime range floors at 1970
`toDate('1969-12-31')` clamps → `year`=1970; `toLastDayOfMonth('1900-02-15')`→'1970-01-31'.
ClickHouse Date is epoch-based (unsigned days); pre-1970 dates are unrepresentable. DuckDB/
Trino handle the full proleptic range. Value-correct for 1970+; **divergent** below.

<a id="datetime-dayofweek-numbering"></a>
### `dayofweek` numbering
ClickHouse `toDayOfWeek` is ISO Mon=1..Sun=7; DuckDB `dayofweek` is Sun=0..Sat=6. Sunday:
7 vs 0. **Divergent.**

<a id="datetime-week-mode"></a>
### `week` mode
ClickHouse `toWeek` default mode 0 is Sunday-based (`2023-01-01`→1); DuckDB `week` is ISO-8601
(→52). Map to `toISOWeek` for parity. **Divergent.**

<a id="datetime-yearweek"></a>
### `yearweek`
`toYearWeek('2024-12-30')`=202452 (Sunday weeks, calendar year) vs DuckDB 202501 (ISO week +
ISO year). Both numbering and year differ at boundaries. **Divergent.**

<a id="datetime-millisecond"></a>
### `millisecond`
ClickHouse `toMillisecond` returns only the sub-second millisecond component (123); DuckDB
`millisecond` returns seconds×1000+ms (30123). **Divergent.**

<a id="date-trunc-return-type"></a>
### `date_trunc` return type
Aligned on the intersecting unit set for representable dates, but return type differs for some
units (ClickHouse `dateTrunc('month',DateTime)` yielded a Date-shaped value vs DuckDB
TIMESTAMP), and the 1970 floor applies. **Conditionally-equivalent.**

<a id="date-format-spec"></a>
### `date_format` specifier dialect
ClickHouse `formatDateTime` uses MySQL-style `%`-codes where **`%M` is the MONTH NAME**
(`formatDateTime(...,'%M')`→'March') — colliding with strftime `%M`=minute. Trino
`date_format` uses its own MySQL/Joda patterns. Format strings must not be passed through
verbatim. **Divergent.**

<a id="datetime-timezone"></a>
### Timezone-typed datetimes & session functions
ClickHouse DateTime is **timezone-typed** (`DateTime('tz')` / `session_timezone`); `hour`,
`date_diff`, `date_trunc`, `from_unixtime`, `to_unixtime`, `now`, `current_date`/`today`,
`timezone` are all session-zone-sensitive and must be evaluated with a pinned matching zone.
Precision/type also differ (`now()` → DateTime(sec) vs TIMESTAMP WITH TIME ZONE).
**Conditionally-equivalent** with a zone pin.

<a id="age-signature"></a>
### `age` / `to_days` signature collisions
`age`: ClickHouse `age('unit',start,end)`→integer count; DuckDB `age(ts,ts)`→INTERVAL — 2-arg
`age` errors in ClickHouse. `to_days`: DuckDB `to_days(n)` builds an INTERVAL of n days;
ClickHouse `TO_DAYS(date)` returns a day-number (days since year 0). Completely different
intent. **Divergent.**

<a id="transform-collision"></a>
### `transform` / `ngrams` shape collisions
Trino `transform(array, lambda)` maps a lambda over elements → ClickHouse equivalent is
`arrayMap`; ClickHouse `transform(x, from, to, default)` is a scalar **value-remap** (CASE-like)
— a different function entirely. Trino `ngrams(array,n)` returns sub-arrays; ClickHouse
`ngrams(string,n)` returns string substrings. **Divergent.**

<a id="map-shape"></a>
### `map` constructor shape
Trino/DuckDB `map(keys_array, values_array)` vs ClickHouse `map(k1,v1,k2,v2,...)` (flat kv
list). Value-equal, call-incompatible. **Conditionally-equivalent.**

<a id="quantile-shape"></a>
### Approximate aggregates — `quantile`/`median`/`histogram`/`approx_top_k`/`entropy`
ClickHouse `quantile`/`median` are **approximate** (reservoir) with parametric
`quantile(level)(x)` syntax; DuckDB `quantile`/`median` are exact. `histogram(bins)(x)` is an
adaptive-bin approximate aggregate vs DuckDB's exact value→count map. `topK`/`approx_top_k`
sketches are not interchangeable. **Conditionally-equivalent** (results comparable only for
simple/small inputs).

<a id="hash-return-shape-2"></a>

<a id="nondeterministic"></a>
### Non-deterministic / session values
`rand`/`random` (and range differs: ClickHouse UInt32 vs DuckDB [0,1) double), `now`, `today`,
`version`, `current_user`, `current_database`, `uuid`, order-dependent `any_value`/`argMin`/
`argMax`/`groupArray` — never pushable/comparable. **Conditionally-equivalent.**

<a id="case-sensitivity"></a>
### Name case-sensitivity
ClickHouse resolves `lower`/`SUBSTRING`/`LENGTHUTF8`?… only the **255 case-insensitive**
aliases work in any case; camelCase functions are case-**sensitive** (`lengthUTF8` works,
`LENGTHUTF8` errors; `arrayElement` works, `ArrayElement` errors; `monthName`/`widthBucket`
must keep their case). A transpiler that normalises identifier case will break these.

<a id="identical-baseline"></a>
### Identical baseline (probed representatives)
Trig (`sin`/`cos`/`tan`/`asin`/`acos`/`atan`/`atan2`/`sinh`/`cosh`/`tanh`), `cbrt`, `exp`,
`degrees`/`radians`, `sqrt` (in-domain), `power`/`pow` (finite real), `log2`/`log10`, `lgamma`,
`sign`, `abs` (in-range), `pi`, `e`, `hex`, `translate`, `left`/`right`, `repeat`, `printf`,
`soundex`, `to_base64`/`from_base64`, `lcm`, `gcd` (non-zero), extract fns for 1970+ dates
(`month`/`day`/`minute`/`second`/`quarter`/`dayofyear`), `count`, sample/pop `stddev`/`var`,
`corr`/`covar_*`, `argMin`/`argMax` values, window rank family, `range`/`flatten`/
`array_to_string` — all probe-verified result-identical (ULP-level agreement for
transcendentals). Raw: `*-baseline-math.tsv`, `*-window-extra.tsv`.

## Limits / not probed live

- **Trino side is composed**, not live-probed (flagged per row). A live-Trino re-probe can
  upgrade provenance.
- Table functions (`unnest`) not probed (need a table-function harness).
- `date_format`/`formatDateTime` specifier coverage probed at the `%M` collision + basic
  `%Y-%m-%d %H:%M:%S`; a full specifier matrix is future work.
- No `unclear` verdicts remain — every emitted pair is backed by a live ClickHouse result or a
  clearly-labelled composition.
