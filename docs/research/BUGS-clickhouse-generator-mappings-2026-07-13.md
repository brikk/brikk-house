# brikk-sql generator: ClickHouse function-mapping bugs (2026-07-13)

Filed from the ClickHouse differential-probe program (see
`REPORT-clickhouse-differential-probe-2026-07-13.md`). Each row is what the brikk-sql
ClickHouse **generator actually emits** (via `transpile("SELECT <expr>", "<src>", "clickhouse")`
on this branch) whose OUTPUT was then run against live ClickHouse 26.5.1.1 (chdb). The
registry now carries a matching `divergent` hazard for the ships-wrong ones, so a
certify-gated consumer refuses them (belt-and-braces) — but the **generator mapping itself is
wrong** and should be fixed at the `renderedSql`/template source.

## STATUS (2026-07-13): generator fixes applied

Fixed at the generator source (`ClickhouseGenerator.kt`), pinned in
`ClickhouseGeneratorMappingBugsTest`, mirroring the Doris BUGS-fix template (commit
`0c9c309`). **8 of 14 rows FIXED, 6 DEFERRED.** All fixes are **live-differential-verified**
via chdb (ClickHouse **26.5.1.1** — the exact BUGS probe version) vs DuckDB 1.5.4:
emitted SQL run in both engines, results diffed (see the harness note below).

**SOURCE-UNAWARE-GENERATOR CONSTRAINT (2026-07-13).** The ClickHouse generator does not
know the source dialect, so any transform fires on ClickHouse→ClickHouse too — which
`toStandardSql`/pipe desugaring uses. A rewrite that changes a *native ClickHouse*
function's semantics therefore corrupts pipe output. Rows whose target name is a
ClickHouse-native function with different semantics (**row 1 lower/upper**, **row 5 duckdb
week** — same node ClickHouse parses its own `week` to; **row 2 round**, **row 7 bin**) are
consequently **NOT transformed here** even though the cross-dialect rewrite is verified
correct; they render faithfully (matching upstream sqlglot) and the cross-dialect
divergence is left to the divergent hazard. The correct auto-fix needs source-aware
generation — tracked in `SPIKE-source-aware-generator-transforms-2026-07-13.md`.

Where the corrected mapping is result-identical AND safe (the node never originates from
ClickHouse, or the rename preserves ClickHouse semantics), the hazard was reconciled
`divergent -> identical`: duckdb `log->log10`, `xor->bitXor`,
`millisecond->(toSecond*1000+toMillisecond)`, and trino `week->toISOWeek` (Trino week is the
WeekOfYear node, which ClickHouse never emits). The duckdb `regexp_replace->replaceRegexpOne`
fix was reconciled to `conditionally-equivalent` (first-match case correct, residual
empty-pattern divergence surfaced). Registry byte-clean; counts unchanged (duckdb↔clickhouse
152: 48 divergent / 69 identical / 35 cond-eq; trino↔clickhouse 107: 32 / 49 / 26).

**6 DEFERRED**: rows 1 (lower/upper), 5 (duckdb week), 2 (round), 7 (bin) — all have
verified cross-dialect rewrites but need **source-aware generation** to avoid CH→CH
corruption (SPIKE follow-up). `age` (return-type mismatch: DuckDB interval vs ClickHouse
scalar) and `to_days` (DuckDB-parser-level source ambiguity) are genuinely unmappable at
the generator. Per-row status is in the tables below.

> Note on certify policy #2: the still-divergent fixed rows (lower/upper unicode edge,
> dayofweek numbering) now have dedicated renderers, so policy #2 surfaces them as
> non-blocking WARNINGs rather than refusals — the residual divergence is still visible.

Severity legend (verified against live ClickHouse, not docs):
- **P1 ships-wrong** — ClickHouse ACCEPTS the emitted SQL but the result is semantically wrong
  vs the source engine. Highest priority (silent data corruption).
- **P2 invalid-name** — the emitted function name does not exist in ClickHouse (errors at
  runtime); whether it ships depends on whether brikk's ClickHouse catalog wrongly lists it.
- **P3 shape/signature** — emitted SQL is rejected by ClickHouse (wrong arity/operator).

## P1 — ships-wrong (ClickHouse accepts, result silently wrong)

| # | source | emits (clickhouse) | live result | problem | correct mapping | status |
|---|--------|--------------------|-------------|---------|-----------------|--------|
| 1 | duckdb/trino `lower(x)` / `upper(x)` | `LOWER(x)` / `UPPER(x)` | `LOWER('İ')`='İ' | ClickHouse `LOWER`/`UPPER` are **ASCII-only** — non-ASCII passes through unchanged; DuckDB/Trino fold full Unicode | `lowerUTF8`/`upperUTF8` (still note İ/ß edge divergence) | **DEFERRED** (source-aware follow-up) — `lowerUTF8`/`upperUTF8` verified correct for cross-dialect, but `lower`/`upper` are ClickHouse-native (ASCII) and the source-unaware generator would corrupt CH→CH pipe output. Rendered faithfully as `LOWER`/`UPPER`; `divergent` hazard gates the cross-dialect case. See SPIKE. |
| 2 | duckdb `round(x)` | `ROUND(x)` | `round(2.5)`=2 | ClickHouse `round` is **banker's** (half-even); DuckDB is half-away-from-zero (`round(2.5)`=3) | no pure rename is correct — gate, or emit an explicit half-away shim | **DEFERRED** (shim `sign(x)*floor(abs(x)*pow(10,d)+0.5)/pow(10,d)` is **live-VERIFIED** — matches DuckDB on 2.5→3, 0.5→1, -2.5→-3, 2.345,2→2.35 — but `round` is CH-native and the generator is source-unaware, so it would regress CH→CH banker's rounding; awaits policy call. `divergent` hazard gates it meanwhile) |
| 3 | duckdb `log(x)` | `LOG(x)` | `LOG(100)`=4.60517 | ClickHouse single-arg `LOG` = **natural log**; DuckDB `log(x)` = **log10**. Silently changes base | `log10(x)` | **FIXED** (`clickhouseLogSql`: single-arg/base-10 -> `log10`, base-2 -> `log2`, other base -> `log(v)/log(b)`; hazard reconciled -> `identical`) |
| 4 | duckdb `regexp_replace(s,p,r)` | `REGEXP_REPLACE(s,p,r)` | replaces **ALL** | ClickHouse `REGEXP_REPLACE`=`replaceRegexpAll` (all matches); DuckDB replaces only the **first** unless 'g' | `replaceRegexpOne` for the no-flag DuckDB form (matches Trino's all-form as-is) | **FIXED** (`clickhouseRegexpReplaceSql`: DuckDB first-only -> `replaceRegexpOne`, `g`/Trino -> `replaceRegexpAll`, other flags flagged; hazard reconciled -> `conditionally-equivalent`, residual empty-pattern edge kept surfaced) |
| 5 | duckdb `week(d)` | `WEEK(d)` | `WEEK('2023-01-01')`=1 | ClickHouse `WEEK`=`toWeek` mode 0 (**Sunday**-based); DuckDB `week` is **ISO-8601** (=52) | `toISOWeek(d)` | **DEFERRED** (source-aware follow-up) — `toISOWeek` verified correct, but DuckDB `week` parses to the `Week` node that ClickHouse ALSO parses its own `week` to, so a source-unaware rewrite corrupts CH→CH (Sunday→ISO). Rendered faithfully as `WEEK`; `divergent` hazard kept. (Trino `week` = the `WeekOfYear` node → still fixed, row 10.) See SPIKE. |
| 6 | duckdb `millisecond(t)` | `millisecond(t)` | =123 | ClickHouse `toMillisecond` = **sub-second component only**; DuckDB `millisecond` = seconds×1000+ms (=30123) | compute `second*1000 + toMillisecond` | **FIXED** (`anonymousSql` -> `(toSecond(t)*1000 + toMillisecond(t))`; live-VERIFIED 30123/0/5789/56001; `millisecond` is not a CH name so no CH→CH risk; hazard reconciled -> `identical`) |
| 7 | duckdb `bin(x)` | `bin(x)` | `bin(5)`='00000101' | ClickHouse `bin` **zero-pads to a full byte**; DuckDB `bin(5)`='101' | strip leading zeros, or document the padding difference | **DEFERRED** (shim `if(x=0,'0',substring(bin(x),position(bin(x),'1')))` is **live-VERIFIED** — matches DuckDB on 5→'101', 0→'0', 255→'11111111', 1→'1' — but `bin` is CH-native, same source-unaware CH→CH regression concern as round; awaits policy call. `divergent` hazard gates it) |
| 8 | duckdb `to_days(x)` | `TO_DAYS(x)` | `TO_DAYS(date)`=739252 | NAME/INTENT collision: DuckDB `to_days(n)` builds an **INTERVAL of n days**; ClickHouse `TO_DAYS(date)` returns a **day-number** (days since year 0). Different function | interval arithmetic (`INTERVAL n DAY`), never `TO_DAYS` | **DEFERRED** (the `ToDays` node is source-ambiguous — interval-builder for DuckDB vs day-number for MySQL-family sources — so an interval rewrite would be wrong for other sources; `divergent` hazard gates it) |

`round` also inherits the Date/DateTime **1970 floor** for any datetime operand
(`REPORT#datetime-range-1970`) — a whole-class caveat, not a single mapping.

## P2 — invalid name (emitted function does not exist in ClickHouse)

| # | source | emits (clickhouse) | live result | fix | status |
|---|--------|--------------------|-------------|-----|--------|
| 9  | duckdb `dayofweek(d)` | `DAY_OF_WEEK(d)` | `Function with name 'DAY_OF_WEEK' does not exist` | `toDayOfWeek` (ISO Mon=1..Sun=7) — and note the numbering divergence vs DuckDB Sun=0 | **FIXED** (DayOfWeek -> `toDayOfWeek`; name now valid, but hazard kept `divergent` for the residual Sun=0 vs Mon=1 numbering) |
| 10 | trino `week(d)` | `WEEK_OF_YEAR(d)` | `Function 'WEEK_OF_YEAR' does not exist` | `toISOWeek` | **FIXED** (WeekOfYear -> `toISOWeek`; hazard reconciled -> `identical`) |
| 11 | trino `to_unixtime(t)` | `TIME_TO_UNIX(t)` | `Function 'TIME_TO_UNIX' does not exist` (leaked internal node name) | `toUnixTimestamp(t)` | **FIXED** (TimeToUnix -> `toUnixTimestamp`; name now valid, hazard kept `conditionally-equivalent` for the fractional-seconds divergence) |
| 12 | duckdb `log10(x)` | `LOG(10, x)` | `Number of arguments for function log doesn't match` (ClickHouse has no 2-arg log) | `log10(x)` | **FIXED** (covered by `clickhouseLogSql`, row 3) |

These fail loudly at runtime, so they only *ship* if brikk's ClickHouse function catalog
wrongly lists the name (worth auditing — same class as the doris `ARRAY_LENGTH` P2).

## P3 — shape / operator (emitted SQL rejected by ClickHouse)

| # | source | emits (clickhouse) | live result | fix | status |
|---|--------|--------------------|-------------|-----|--------|
| 13 | duckdb `xor(a,b)` | `a ^ b` | `Syntax error` (ClickHouse has no `^` operator) | `bitXor(a,b)` (DuckDB `xor` is bitwise) | **FIXED** (BitwiseXor -> `bitXor`; hazard reconciled -> `identical`) |
| 14 | duckdb `age(a,b)` | `age(a,b)` | `An incorrect number of arguments` (ClickHouse `age` needs `('unit',start,end)`) | rebuild as unit-diff or interval; never a 2-arg passthrough | **DEFERRED** (return-type mismatch confirmed live: DuckDB `age(a,b)` yields an **INTERVAL** ('60 days'), ClickHouse `age('unit',start,end)` a **scalar** count — no single-expression map preserves the type; `divergent` hazard gates it) |

## Correct mappings confirmed (documented for completeness — do NOT change)

Verified emitting semantically-correct ClickHouse:
- duckdb `length` → **`CHAR_LENGTH`** (code-point count — correctly avoids the byte-count trap).
- duckdb `concat(a,b)` → **`CONCAT(COALESCE(a,''), COALESCE(b,''))`** (replicates DuckDB's
  NULL-skip against ClickHouse's NULL-propagating concat). Trino `concat` → `CONCAT(a,b)` (no
  wrap needed — Trino also propagates NULL).
- duckdb `instr` → **`POSITION`** (both case-sensitive; avoids ClickHouse `instr`=case-insensitive).
- trino `transform(a, x->…)` → **`arrayMap(x->…, a)`** (avoids ClickHouse `transform`=value-remap).
- `regexp_replace` (trino) → `REGEXP_REPLACE` — correct for Trino (both replace-all).
- trino `from_unixtime` → `fromUnixTimestamp(CAST(x AS Nullable(Int64)))`, `date_format` →
  `formatDateTime` (format-string dialect caveat still applies), `width_bucket` → `widthBucket`.

## Not bugs (documented)

- `ln` → `LN` (both natural log — correct; contrast with the `log` collision #3).
- `substr`/`substring` → `SUBSTRING`: byte-vs-codepoint + start=0 divergence is a genuine
  semantic hazard (registry `divergent`), not a wrong *name* — `substringUTF8` would fix
  multibyte but not the start=0 edge.

## Verification harness (2026-07-13)

Each fix was checked by a Python differential harness: `duckdb` 1.5.4 evaluates the source
expression, `chdb` 4.2.1 (embedded ClickHouse, reporting `version()` = **26.5.1.1** — the
same build the probe program used) evaluates the generator's emitted ClickHouse SQL, and
the two results are compared on concrete inputs.

- **All 10 FIXED rows match** (equal results where a match is expected).
- The two intentionally-retained divergences reproduce exactly: `lower('İ')` DuckDB `i` vs
  ClickHouse `i̇` (Turkish dotted-I full-fold edge), and `dayofweek('2023-01-01')` DuckDB
  `0` (Sun=0) vs ClickHouse `7` (ISO Sun=7) — both kept as `divergent` hazards.
- The DEFERRED `round`/`bin` shims were validated against DuckDB (see their rows); they are
  correct but withheld only because the target names are ClickHouse-native (CH→CH
  regression risk in a source-unaware generator).
