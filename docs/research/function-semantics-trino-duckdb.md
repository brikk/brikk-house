# Trino ↔ DuckDB function semantics — distilled from the ducklake-integrations pushdown-parity program

**Source project:** `ducklake-integrations/jvm/trino-ducklake` (read-only; docs under
`README-duckdb-format-pushdown-reference.md`, `dev-docs/TODO-pushdown-duckdb.md`, and
`dev-docs/archive/{RESEARCH-function-mapping, RESEARCH-function-community-extensions{,-detail},
PLAN-pushdown-datetime, REPORT-datetime-tz-handling, REPORT-hash-null-handling,
REPORT-string-unicode-audit, TODO-pushdown-datetime}.md`).
**Machine-readable companion:** `brikk-sql/testResources/semantics/trino-duckdb-hazards.json`
(241 pairs; verdicts: 102 identical, 52 conditionally-equivalent, 44 no-equivalent, 37 divergent,
6 unclear).
**Purpose:** semantic-evidence gate for the rename candidates and same-name entries in
`docs/research/function-gap-report.md` / `brikk-sql/testResources/semantics/gap-report.json`.
All verdicts here are *their* findings (Trino 481 vs DuckDB 1.5.3), not re-derived.

---

## Their verification methodology (what "verified" means)

The bar was **live differential testing against both engines**, not doc-reading:

1. **Probe classes** — throwaway JUnit classes run against the in-process `jdbc:duckdb:` driver
   (and the Quack container for RPC parity), emitting result tables that were pasted verbatim
   into REPORT files, then the probe class was deleted:
   `ProbeStringUnicodeAudit` (REPORT-string-unicode-audit.md#appendix-a),
   `ProbeHashNullHandling` + `ProbeConcatNullHandling` (REPORT-hash-null-handling.md),
   `ProbeDuckDbTimeZoneHandling` (REPORT-datetime-tz-handling.md),
   `ProbeDuckDbCaseFolding` (TODO-pushdown-duckdb.md#priority-order, step 3).
   Trino-side expectations came from Trino's documented spec and, where necessary, Trino 481
   source (e.g. `VarbinaryFunctions.xxhash64`, `HmacFunctions.java`) with `java.time` as ground
   truth for TZ math (REPORT-datetime-tz-handling.md#q3-extended).
2. **Per-entry semantic fixtures** — every shipped mapping carries a fixture in
   `TestTrinoFunctionAliases#semanticCases()` whose expected value is chosen "to match Trino's
   documented behaviour, not DuckDB's", with a corpus deliberately built for *divergence
   pressure* (ZWJ emoji, combining marks, Turkish İ, German ß, leap days, ISO-year traps,
   DST transitions, year-boundary TZ instants, negative epochs)
   (TODO-pushdown-duckdb.md#adding-a-new-alias--checklist; PLAN-pushdown-datetime.md#test-corpus).
3. **End-to-end dual-mode tests** — `TestDucklakeDuckDbReadMode` runs the same query with
   Trino-side filtering vs pushed-down filtering and asserts identical rows ("The pushed result
   must match Trino's own evaluation byte-for-byte",
   README-duckdb-format-pushdown-reference.md#discipline).
4. **Lockstep drift guards** — `testJavaPushableSetMatchesDuckDbMeta` /
   `testMetaMatchesFixtures` fail CI if the catalog, the translator set, and the fixture set
   diverge (TODO-pushdown-duckdb.md#test-surface-backing-the-pushdown-layer).
5. **Discipline rule:** "Lossless pushdown only… When in doubt, don't push"
   (TODO-pushdown-duckdb.md#discipline-non-negotiable). Doc-only claims were repeatedly
   overturned by probes (e.g. "DuckDB has no IP type" was wrong,
   RESEARCH-function-community-extensions-detail.md#inet; "ICU needed for zones" was wrong,
   REPORT-datetime-tz-handling.md#q2), so treat any row in the mapping doc *without* a probe or
   fixture as advisory (their ⚠️ "verify" markers → our `unclear`/`conditionally-equivalent`).

Confidence tiers for consumers of the hazards JSON, highest first:
probe REPORTs > shipped-fixture claims in TODO/README > mapping-doc table rows.

---

## Datetime / timezone

**The type-system hazard dominates everything else.** DuckDB `TIMESTAMPTZ` stores an instant
only — the writer's zone is discarded and every read reinterprets through the *session*
`TimeZone`; Trino's `TIMESTAMP WITH TIME ZONE` carries a per-value zone
(REPORT-datetime-tz-handling.md#q1). Consequences:

- **Extracts over WTZ are only conditionally equivalent** — correct iff the DuckDB session zone
  equals Trino's session zone. Smoking gun: `year(TIMESTAMPTZ '2024-12-31 22:00:00+00')` is
  2024 in UTC but **2025** in Asia/Singapore (REPORT-datetime-tz-handling.md#q3-extended).
- **DuckDB's default session zone is the host JVM/system zone, not UTC** — a "silent
  portability bomb" across dev/CI/prod (REPORT-datetime-tz-handling.md#q3).
- **Zone-literal shapes differ:** DuckDB rejects bare `±HH:MM`; integer-hour offsets must be
  rewritten `+05:00 → Etc/GMT-5` (POSIX sign *inversion*); fractional offsets (`+05:30`) are
  inexpressible except as named IANA zones (REPORT-datetime-tz-handling.md#q2).
- **`at_timezone(WTZ, zone)` is unfixable**: DuckDB's `WTZ AT TIME ZONE 'X'` /
  `timezone('X', WTZ)` return plain `TIMESTAMP`, because there is no per-value zone to rewrite —
  "fundamentally not expressible" (README#not-pushable; TODO-pushdown-duckdb.md#status chunk 4).
- **tzdb skew** between DuckDB-ICU and java.time for historical instants (Moscow pre-2014,
  Mexico City pre-2022) was flagged but never probed — `unclear`
  (REPORT-datetime-tz-handling.md#open-follow-up-probes).

**Verified identical (wall-clock tier).** `DATE` and `TIMESTAMP` (no TZ) are session-zone
invariant in both engines (probe Q1 companion). On those types the following were fixture-pinned
Trino-equal: `year/month/day/quarter`, `hour/minute/second`, `day_of_year`→`dayofyear`,
`last_day_of_month`→`last_day` (leap/century pins), `week/week_of_year`→`week` (DuckDB's bare
`week()` probed ISO: `week('2023-01-01')=52`), `year_of_week/yow`→`extract('isoyear' …)::BIGINT`,
`date_trunc` (intersection units), `date_diff` (boundary-count semantics probed:
`date_diff('month','2024-01-31','2024-02-01')=1`), `to_unixtime`→`epoch(t)::DOUBLE`
(RESEARCH-function-mapping.md#date--time; TODO-pushdown-duckdb.md round 6j).

**Looks-same-but-isn't:**

| Pair | Precise difference | Provenance |
|---|---|---|
| `day_of_week` vs `dayofweek` | Trino 1..7 Mon=1; DuckDB `dayofweek` **0..6 Sun=0**. Must map to `isodow`. | RESEARCH-function-mapping.md#date--time; PLAN#day_of_week |
| `date_add` (same name) | Trino `date_add(unit, n, x)` vs DuckDB `date_add(date, interval)` — incompatible signatures; rewrite to `x + INTERVAL n unit`. | RESEARCH-function-mapping.md#date--time |
| `date_diff` vs DuckDB `date_sub` | DuckDB's `date_sub` returns *complete units elapsed*, not boundaries crossed — name-adjacent trap. | RESEARCH-function-mapping.md#date--time |
| `date_trunc` return type | DuckDB always returns TIMESTAMP even for DATE input; Trino preserves input type. Comparisons still align via auto-cast. | RESEARCH-function-mapping.md#date--time |
| `millisecond` | Trino returns millis-of-second 0..999; only `extract('millisecond' …)::BIGINT` matches (not `epoch_ms`). | RESEARCH-function-mapping.md#date--time; TODO-pushdown-datetime.md#gotchas |
| `from_unixtime` | Returns `TIMESTAMP(3) WITH TIME ZONE` in Trino — session-zone-dependent output (their Tier C), though the instant matches `to_timestamp(d)`. 2-arg and `_nanos` forms: no equivalent. | PLAN#from_unixtime--to_unixtime |
| precision defaults | Trino timestamp(3) vs DuckDB micros(6); also `now()`/`current_timestamp`. | PLAN#precision; RESEARCH-function-mapping.md#date--time |
| `to_milliseconds` (same name) | Trino takes INTERVAL, DuckDB takes INTEGER — not 1:1. | RESEARCH-function-mapping.md#date--time |

**Never translatable (Tier D):** `date_format`/`format_datetime`/`parse_datetime`/`date_parse`
vs `strftime`/`strptime` — Joda/MySQL format language vs C strftime; safe only if a transpiler
owns the format-string translation (PLAN#date_format--date_parse).

## Hash / NULL handling

**Verified identical / fixable with a wrapper:**
- Core `md5`/`sha1`/`sha256` NULL-propagate exactly like Trino (`md5(NULL)→NULL`, digest of `''`
  matches), but return **hex VARCHAR vs Trino's VARBINARY** — the `unhex(…)` wrap is mandatory
  (REPORT-hash-null-handling.md#core-duckdb-hash-null-propagation).
- Encoding functions all NULL-propagate: `to_hex/from_hex/to_base64/from_base64/url_encode/
  url_decode` (REPORT-hash-null-handling.md#round-4-encoding-macros).

**Looks-same-but-isn't:**
- **`concat`** — probe-confirmed divergent on *every* NULL-bearing case: DuckDB
  `concat('a',NULL,'c','d')='acd'`, `concat(NULL,NULL)=''`; Trino returns NULL. The `||`
  operator NULL-propagates in both — the only safe rewrite
  (REPORT-hash-null-handling.md#related-concat--concat_ws-null-behaviour). `concat_ws` is
  probe-aligned on all NULL shapes (elements skipped, NULL separator → NULL, all-NULL → `''`).
- **DuckDB variadic `hash()`** treats NULL as a distinguished sentinel
  (`hash(NULL)=13787848793156543929`, NULL ≠ `''` inside composition) — "directly opposite to
  Trino's null-propagation"; never a target for any Trino hash
  (REPORT-hash-null-handling.md#duckdbs-variadic-hash).
- **`xxhash64`** — algorithm matches `xxh64` (frozen spec) but Trino serializes the u64
  **big-endian** into VARBINARY; DuckDB returns UBIGINT. Byte-order conversion required
  (TODO-pushdown-duckdb.md#round-6c).
- **`hmac_sha256`** vs community `crypto_hmac` — VARCHAR-only signature; a BLOB→VARCHAR bridge
  *escapes non-printable bytes* and silently hashes the wrong input. Unfixable in SQL; they
  ported it to native C++. Trino arg order is `(data, key)` (TODO-pushdown-duckdb.md#round-6b-ext).
- **`murmur3`** — DuckDB `murmurhash3_x64_128` packs `(h1<<64)|h2`; Trino emits `LE(h1)++LE(h2)`.
  Their reconstruction was inferred from source, "not yet confirmed against a live Trino-481
  value" → `unclear` (TODO-pushdown-duckdb.md#round-6c).

**Needed their parity extension (no core/extension equivalent was correct):** `sha512`,
`xxhash64`, `hmac_sha256` — native C++ over vendored xxHash/WjCryptLib; community
`crypto`/`hashfuncs` were dropped for per-version availability lag *and* load-time telemetry
(TODO-pushdown-duckdb.md#round-6b-core note). **Unfixable / no cover anywhere:** `crc32`,
`spooky_hash_v2_*`, `checksum` (RESEARCH-function-community-extensions.md#still-uncovered).

## String / Unicode

**Verified identical (full Unicode probe corpus, DuckDB 1.5.3):** `length` (code points; emoji =
1), `substring/{2,3}` (1-based code-point index; both engines split combining marks and ZWJ
sequences identically), `replace/3`, `strpos/2`, `starts_with`, `lpad`/`rpad` (code-point pad,
emoji/CJK), `concat_ws/{2..5}`, `translate`, `regexp_like`→`regexp_matches` (RE2 both sides,
`\p{…}` classes identical), `regexp_extract/{2,3}`, and the comparison operators (`=`,`<`,… —
UTF-8 byte order is monotonic with code-point order; precomposed ≠ decomposed in *both*)
(REPORT-string-unicode-audit.md#summary-table, #comparison-operators).

**Looks-same-but-isn't (probe-confirmed):**

| Pair | Precise difference | Provenance |
|---|---|---|
| `lower` | Turkish `İ`: DuckDB `'i'` vs Trino `'i'+U+0307` (simple vs full case folding). | REPORT-string-unicode-audit.md#summary-table |
| `upper` | `ß`: DuckDB `'ẞ'` (U+1E9E) vs Trino `'SS'` (length-changing full folding). | ibid. |
| `reverse` | DuckDB is grapheme-cluster-aware (ZWJ family emoji returned *unchanged*); Trino reverses code points. | REPORT-string-unicode-audit.md#reverse1 |
| `trim`/`ltrim`/`rtrim` | DuckDB bare trim strips space+EM SPACE but **not tab/LF/CR/FF/VT**; Trino strips the Java `Character.isWhitespace` set. `trim('\thello\t')` diverges. | REPORT-string-unicode-audit.md#trim1-ltrim1-rtrim1 |
| `split_part` | Out-of-bounds: Trino NULL vs DuckDB `''`. | RESEARCH-function-mapping.md#string-functions |
| `split` | Empty delimiter: Trino → char array; DuckDB → single-element array. | ibid. |
| `codepoint` | Trino requires varchar(1); DuckDB `unicode()` takes any string ("NOT 1:1"). | ibid. |
| `from_utf8` | Invalid UTF-8: Trino has replacement-char form; DuckDB `decode` errors. | ibid. |
| `format` | Java `Formatter` vs fmt/printf format languages. | ibid. |
| `greatest`/`least` | **Trino: any NULL → NULL. DuckDB: skips NULLs.** Same name, different NULL algebra. | RESEARCH-function-mapping.md#comparison-and-conditional |
| `regexp_replace` | DuckDB replaces first match by default; Trino replaces all — must force `'g'`. 2-arg Trino form = remove-all (`''` + `'g'`). | TODO-pushdown-duckdb.md#round-6a |
| `regexp_extract_all` | Empty-match handling differs. | RESEARCH-function-mapping.md#pattern-matching |

**Needed their parity extension (SQL/macros could NOT fix):** `lower`, `upper` (ICU full case
folding), `reverse` (code-point), the trim family (Java whitespace set), `normalize/1` (NFC via
`icu::Normalizer2`). Explicitly probed and ruled out: collations (`NOCASE` fails `İ ↔ i+U+0307`
and `ß ↔ ss`) and `nfc_normalize` as substitutes
(REPORT-string-unicode-audit.md#collations-as-a-pushdown-widening-tool;
TODO-pushdown-duckdb.md#priority-order step 3).

**Unfixable / no equivalent:** `normalize/2` (NFD/NFKC/NFKD — their vendored ICU ships NFC
only), `word_stem`, `luhn_check`, `to_base64url`, `to_base32`, `split_to_map`, `strpos/3`,
`regexp_count`, `regexp_position` (README#not-pushable;
RESEARCH-function-community-extensions.md#still-uncovered).

**Clean renames verified:** `levenshtein_distance`→`levenshtein`, `hamming_distance`→`hamming`,
`to_hex`→`hex`, `from_hex`→`unhex`, `to_utf8`→`encode`, `chr`, `translate`, `url_encode/decode`
(RESEARCH-function-mapping.md#string-functions).

## Numeric

**Verified identical:** `abs` (both throw on `abs(MIN_INT)`), `ceil/floor`, `sqrt`/`ln`
(NaN domains match), `exp`, `log2`, `log10`, `cbrt`, `pow/power`, trig + forward hyperbolics,
`degrees/radians`, `sign` (NaN→NaN probed), integer `mod` (truncated division, sign follows
dividend), `pi()`, `truncate/1`→`trunc`, bitwise and/or/xor/not/left-shift (operator bodies)
(RESEARCH-function-mapping.md#numeric--math-functions, #bitwise).

**Looks-same-but-isn't:**
- **float `mod`** — Trino IEEE-remainder-ish vs DuckDB `fmod`; they type-gated it out
  (RESEARCH-function-mapping.md#numeric).
- **`round(x, d)`** — "Trino half-up; DuckDB half-away-from-zero (since 0.10) — verify per
  version… Do NOT push when d > 0 until verified" → `unclear` (ibid.).
- **`log` name collision** — DuckDB single-arg `log(x)` IS log10; Trino `log(b,x)` is
  log-base-b. Map `log10→log10` explicitly; rewrite `log(b,x)` as `ln(x)/ln(b)` (ibid.).
- **`bitwise_right_shift`** — signed/unsigned semantics differ on negatives (DuckDB `>>` is
  arithmetic for signed) (README#3-functions; RESEARCH-function-mapping.md#bitwise).
- **`bit_count`** — Trino requires an explicit width arg; DuckDB infers (ibid.).
- **DuckDB `^` is exponentiation**, `#`/`xor()` is XOR — operator trap (ibid.).
- **`truncate/2`** — DuckDB `trunc` is 1-arg only; needs a pow-shim
  (TODO-pushdown-duckdb.md#round-6a).
- `to_base` (DuckDB adds padding arg), `sequence` (inclusive) vs `range` (exclusive) vs
  `generate_series` (inclusive) (RESEARCH-function-mapping.md).

**No equivalent:** `from_base`, statistical CDFs (`beta_cdf`, `normal_cdf`, `t_cdf`, …), `e()`
(use `exp(1)`), `width_bucket`, big-endian/IEEE-754 byte codecs
(RESEARCH-function-community-extensions.md#still-uncovered).

## Collections / aggregates (selected, from the mapping doc — mostly not probe-verified)

- **`slice(a, start, LENGTH)` vs `list_slice(a, begin, END)`** — same-name-adjacent, different
  third argument meaning. "Do not translate by name" (RESEARCH-function-mapping.md#array--list).
- **`array_min/array_max`** — Trino propagates NULL elements; DuckDB skips (ibid.).
- **`element_at(map, k)`** — missing key: Trino NULL; DuckDB empty LIST (#map-functions).
- **`array_join`** — Trino's null-replacement arg vs DuckDB silently dropping NULLs (#array--list).
- **DuckDB `array_unique` returns a COUNT**, not the distinct list (`array_distinct` must map to
  `list_distinct`) (ibid.).
- **`repeat`** — Trino repeats an element; DuckDB repeats the whole list (ibid.).
- **`min_by/max_by` → `arg_min/arg_max`** — NULL semantics differ (DuckDB has separate `_null`
  variants); "do not push by name" (#aggregate-functions).
- **`approx_percentile` vs `quantile_cont/disc`** — approximate vs exact (ibid.).
- Sketches (`theta`, `tdigest`, HLL): computed values map via the `datasketches` extension, but
  **serialized sketch state is never wire-compatible** across engines (#aggregate-functions;
  RESEARCH-function-community-extensions-detail.md#datasketches).
- Verified-identical set: `count/sum/avg/min/max/bool_and/bool_or/count_if`, variance/stddev
  family, `corr/covar`, `regr_slope/intercept`, `histogram`, `geometric_mean`, window functions
  (`row_number`…`lag`; note Trino 481 doc bug: `cume_dist` actually returns double)
  (#aggregate-functions, #window-functions).

## Extensions: what filled gaps, what was rejected

- **Their own `trino_parity` extension** (final state, 95 catalog entries): native C++ for
  `lower/upper/reverse/trim family/normalize(NFC)/sha512/xxhash64/hmac_sha256`; macros for the
  rest (TODO-pushdown-duckdb.md#status; README#3-functions).
- **Rejected: netquack `url_extract_*`** — systematic NULL-vs-`''` divergence on absent
  components, `extract_port` VARCHAR vs Trino BIGINT, `extract_path('http://ex.com')` `'/'` vs
  Trino `''` (TODO-pushdown-duckdb.md#round-6d).
- **Rejected: crypto `crypto_hmac`** — VARCHAR-only, corrupts binary input (see Hash section).
- **Community-extension availability is itself a hazard** — published per
  (extension × DuckDB version × platform); `crypto`/`hashfuncs`/`netquack` 404'd for weeks after
  the 1.5.3 release; `crypto`/`hashfuncs` also ship load-time telemetry
  (REPORT-hash-null-handling.md#extension-availability; TODO-pushdown-duckdb.md#round-6b-core).
- **Viable when loaded:** `inet` (core) for IP types (`contains(net, addr)` → `net >>= addr`
  operator; Trino's two IP types vs DuckDB's one INET-with-CIDR), `datasketches`
  (computed values only), `splink_udfs` `soundex` (but its `ngrams` is a *string* n-gram, not
  Trino's array `ngrams` — don't conflate)
  (RESEARCH-function-community-extensions-detail.md#inet, #datasketches, #splink-udfs).

## Notes for brikk-sql (checked 2026-07-12)

- **Correct already:** Trino `concat` → `||` chain (`dialectConcatCoalesce=true` triggers
  `concatToDpipeSql`, `generator/Generator.kt:3151`); Trino `DAY_OF_WEEK`/`DOW` → `DayOfWeekIso`
  → DuckDB `ISODOW` (`dialects/PrestoParser.kt:289`, `dialects/DuckdbGenerator.kt:1678`).
- **Contradiction 1 — `GREATEST`/`LEAST`:** Presto parser records `ignore_nulls=false`
  (`dialects/PrestoParser.kt:396`), but `DuckdbGenerator` has no Greatest/Least override, so the
  call renders as bare `GREATEST(...)` in DuckDB, which *skips* NULLs — exactly the divergence
  their research flags as never-translatable-by-name
  (RESEARCH-function-mapping.md#comparison-and-conditional).
- **Contradiction 2 — `REGEXP_REPLACE`:** no DuckDB-side handling
  (no `RegexpReplace` registration in `dialects/DuckdbGenerator.kt`), so Trino's replace-ALL
  semantics render as DuckDB's replace-FIRST default; their parity macro had to force the `'g'`
  flag (TODO-pushdown-duckdb.md#round-6a).
- **Flag-only (untranspilable):** `lower`/`upper`/`trim`/`reverse` pass through by name; correct
  for ASCII, divergent on İ/ß/tab-whitespace/grapheme inputs per the probe audit — no SQL rewrite
  exists (they needed native C++), so these belong in a warn/annotation tier, not a rewrite tier.
