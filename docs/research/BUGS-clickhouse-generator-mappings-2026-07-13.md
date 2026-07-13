# brikk-sql generator: ClickHouse function-mapping bugs (2026-07-13)

Filed from the ClickHouse differential-probe program (see
`REPORT-clickhouse-differential-probe-2026-07-13.md`). Each row is what the brikk-sql
ClickHouse **generator actually emits** (via `transpile("SELECT <expr>", "<src>", "clickhouse")`
on this branch) whose OUTPUT was then run against live ClickHouse 26.5.1.1 (chdb). The
registry now carries a matching `divergent` hazard for the ships-wrong ones, so a
certify-gated consumer refuses them (belt-and-braces) — but the **generator mapping itself is
wrong** and should be fixed at the `renderedSql`/template source.

Severity legend (verified against live ClickHouse, not docs):
- **P1 ships-wrong** — ClickHouse ACCEPTS the emitted SQL but the result is semantically wrong
  vs the source engine. Highest priority (silent data corruption).
- **P2 invalid-name** — the emitted function name does not exist in ClickHouse (errors at
  runtime); whether it ships depends on whether brikk's ClickHouse catalog wrongly lists it.
- **P3 shape/signature** — emitted SQL is rejected by ClickHouse (wrong arity/operator).

## P1 — ships-wrong (ClickHouse accepts, result silently wrong)

| # | source | emits (clickhouse) | live result | problem | correct mapping |
|---|--------|--------------------|-------------|---------|-----------------|
| 1 | duckdb/trino `lower(x)` / `upper(x)` | `LOWER(x)` / `UPPER(x)` | `LOWER('İ')`='İ' | ClickHouse `LOWER`/`UPPER` are **ASCII-only** — non-ASCII passes through unchanged; DuckDB/Trino fold full Unicode | `lowerUTF8`/`upperUTF8` (still note İ/ß edge divergence) |
| 2 | duckdb `round(x)` | `ROUND(x)` | `round(2.5)`=2 | ClickHouse `round` is **banker's** (half-even); DuckDB is half-away-from-zero (`round(2.5)`=3) | no pure rename is correct — gate, or emit an explicit half-away shim |
| 3 | duckdb `log(x)` | `LOG(x)` | `LOG(100)`=4.60517 | ClickHouse single-arg `LOG` = **natural log**; DuckDB `log(x)` = **log10**. Silently changes base | `log10(x)` |
| 4 | duckdb `regexp_replace(s,p,r)` | `REGEXP_REPLACE(s,p,r)` | replaces **ALL** | ClickHouse `REGEXP_REPLACE`=`replaceRegexpAll` (all matches); DuckDB replaces only the **first** unless 'g' | `replaceRegexpOne` for the no-flag DuckDB form (matches Trino's all-form as-is) |
| 5 | duckdb `week(d)` | `WEEK(d)` | `WEEK('2023-01-01')`=1 | ClickHouse `WEEK`=`toWeek` mode 0 (**Sunday**-based); DuckDB `week` is **ISO-8601** (=52) | `toISOWeek(d)` |
| 6 | duckdb `millisecond(t)` | `millisecond(t)` | =123 | ClickHouse `toMillisecond` = **sub-second component only**; DuckDB `millisecond` = seconds×1000+ms (=30123) | compute `second*1000 + toMillisecond` |
| 7 | duckdb `bin(x)` | `bin(x)` | `bin(5)`='00000101' | ClickHouse `bin` **zero-pads to a full byte**; DuckDB `bin(5)`='101' | strip leading zeros, or document the padding difference |
| 8 | duckdb `to_days(x)` | `TO_DAYS(x)` | `TO_DAYS(date)`=739252 | NAME/INTENT collision: DuckDB `to_days(n)` builds an **INTERVAL of n days**; ClickHouse `TO_DAYS(date)` returns a **day-number** (days since year 0). Different function | interval arithmetic (`INTERVAL n DAY`), never `TO_DAYS` |

`round` also inherits the Date/DateTime **1970 floor** for any datetime operand
(`REPORT#datetime-range-1970`) — a whole-class caveat, not a single mapping.

## P2 — invalid name (emitted function does not exist in ClickHouse)

| # | source | emits (clickhouse) | live result | fix |
|---|--------|--------------------|-------------|-----|
| 9  | duckdb `dayofweek(d)` | `DAY_OF_WEEK(d)` | `Function with name 'DAY_OF_WEEK' does not exist` | `toDayOfWeek` (ISO Mon=1..Sun=7) — and note the numbering divergence vs DuckDB Sun=0 |
| 10 | trino `week(d)` | `WEEK_OF_YEAR(d)` | `Function 'WEEK_OF_YEAR' does not exist` | `toISOWeek` |
| 11 | trino `to_unixtime(t)` | `TIME_TO_UNIX(t)` | `Function 'TIME_TO_UNIX' does not exist` (leaked internal node name) | `toUnixTimestamp(t)` |
| 12 | duckdb `log10(x)` | `LOG(10, x)` | `Number of arguments for function log doesn't match` (ClickHouse has no 2-arg log) | `log10(x)` |

These fail loudly at runtime, so they only *ship* if brikk's ClickHouse function catalog
wrongly lists the name (worth auditing — same class as the doris `ARRAY_LENGTH` P2).

## P3 — shape / operator (emitted SQL rejected by ClickHouse)

| # | source | emits (clickhouse) | live result | fix |
|---|--------|--------------------|-------------|-----|
| 13 | duckdb `xor(a,b)` | `a ^ b` | `Syntax error` (ClickHouse has no `^` operator) | `bitXor(a,b)` (DuckDB `xor` is bitwise) |
| 14 | duckdb `age(a,b)` | `age(a,b)` | `An incorrect number of arguments` (ClickHouse `age` needs `('unit',start,end)`) | rebuild as unit-diff or interval; never a 2-arg passthrough |

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
