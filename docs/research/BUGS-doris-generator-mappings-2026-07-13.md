# brikk-sql generator: Doris function-mapping bugs (2026-07-13)

Filed from the Doris differential-probe program (bucket B/C, see
`REPORT-doris-differential-probe-2026-07-13.md`). Each row is a **cross-name mapping the
generator emits** that is semantically wrong. The registry now carries a matching
`divergent` hazard so `certify` refuses the confident-but-wrong ones as belt-and-braces,
but the **generator mapping itself should be fixed** — that is the real bug.

Severity legend:
- **P1 confident-but-wrong** — `certify` returns `ok=true`, so the wrong SQL SHIPS
  (runtime error or silently wrong result). Highest priority.
- **P2 catalog staleness** — `certify ok=true` but the emitted name is not on the live FE.
- **P3 fail-loud** — `certify` already REFUSES (UNMAPPABLE), so nothing wrong ships, but
  the mapping is broken / the target is wrong.

All reproductions: `SqlFragment("SELECT <expr>", "<duckdb|trino>").certify("doris").result.sql`.

## P1 — confident-but-wrong (ships wrong SQL)

| # | source | emits (doris) | problem | correct mapping |
|---|--------|---------------|---------|-----------------|
| 1 | duckdb `list_has_any(a,b)` | `a && b` | Doris `&&` is logical-AND; arrays can't cast to boolean → runtime error | `arrays_overlap(a,b)` |
| 2 | duckdb `epoch_ms(ms)` | `from_unixtime(ms, 3)` | Doris `from_unixtime` takes SECONDS (ms overflows → INVALID_ARGUMENT) and its 2nd arg is a FORMAT STRING, not fractional precision (literal `3` → `'3'`) | seconds-based conversion, e.g. a ms→datetime path that divides by 1000 and does not pass `3` as a format |
| 3 | duckdb `string_split_regex(s,p)` | `split_by_string(s,p)` | `split_by_string` splits on `p` as a LITERAL, not a regex (`'a1b2c','[0-9]'` → `['a1b2c']` vs `['a','b','c']`) | a regex-splitting Doris function |
| 4 | duckdb `struct_pack(a:=1,...)` | `struct(1 AS a, ...)` | Doris `STRUCT` rejects `expr AS name` alias syntax (error); bare `STRUCT` loses field names | `named_struct('a',1,...)` |
| 5 | trino `json_array_contains(j,v)` | `j MEMBER OF(v)` | Errors at runtime on Doris (both operand orders) | `json_contains(j, v)` |
| 6 | trino `json_extract_scalar(j,p)` | `json_extract(j,p)` | Trino unwraps to a raw scalar; `json_extract` KEEPS JSON quotes on string scalars (`'"hi"'` vs `'hi'`). Numeric scalars happen to match | `json_unquote(json_extract(j,p))` (or a scalar-extracting fn) |

## P2 — catalog staleness (certify ok=true, live FE lacks the function)

| # | source | emits (doris) | problem | fix |
|---|--------|---------------|---------|-----|
| 7 | duckdb `array_length(a)` | `array_length(a)` (verbatim) | brikk-sql-metadata Doris catalog lists `ARRAY_LENGTH` so certify passes, but the live FE errors `Can not found function 'ARRAY_LENGTH'` | remove `ARRAY_LENGTH` from the Doris function catalog and/or map `array_length` → `array_size` (live = 3, identical) |

## P3 — fail-loud (certify refuses; mapping still broken / target wrong)

| # | source | emits (doris) | problem | correct mapping |
|---|--------|---------------|---------|-----------------|
| 8 | duckdb `strftime(ts,fmt)` | `DATE_FORMAT(TS_OR_DS_TO_TIMESTAMP(ts), fmt)` | leaks the internal `TS_OR_DS_TO_TIMESTAMP` AST node (no Doris renderer → UNMAPPABLE). `date_format(cast(ts as datetime), fmt)` works and matches live | render the arg as a plain cast, not the internal node |
| 9 | trino `from_iso8601_timestamp_nanos(s)` | `cast(s AS datetime)` | Trino keeps nanoseconds; Doris `DATETIME` cast drops ALL fractional seconds | a datetime(6) cast at least; nanos are unrepresentable in Doris DATETIME (document as lossy) |
| 10 | duckdb `map_extract(m,k)` | `MAP_EXTRACT(...)` (unmappable; also mismaps `MAP()`→`ARRAY_MAP`) | DuckDB `map_extract` returns a LIST `[v]`; Doris `element_at` returns scalar `v` — shape differs, so even a rename is not a clean equivalent | `element_at(m,k)` with awareness of the list-vs-scalar shape difference |

## Missing mappings (enhancements — Doris has an equivalent, generator refuses)

These fail loud today (safe), but a mapping would extend coverage. Verified live:

- duckdb `greatest_common_divisor`→`gcd`, `least_common_multiple`→`lcm`,
  `list_position`→`array_position`, `suffix`→`ends_with`, `get_current_timestamp`→`now`,
  `st_aswkb`→`st_asbinary` — **identical / trivially-equivalent**.
- duckdb `list_intersect`→`array_intersect` (set equal, order not guaranteed),
  `list_zip`→`array_zip` (struct field naming), `row`→`named_struct` (needs synthesized
  field names).
- duckdb `list_slice(a,begin,END)`→`array_slice(a,start,LENGTH)` — **needs arg conversion
  (end→length)**; a naive rename would diverge.
- trino `is_nan`→`isnan` (Doris fn is `isnan`, not `is_nan`).
- table functions: `unnest`→`LATERAL VIEW explode`, `json_each`→`explode_json_object`
  (structural rewrites, not scalar renames).

## Not bugs (documented for completeness)

- duckdb `datesub` — not a DuckDB function (ClickHouse-ism); refusal is correct.
- `date_diff` (both source dialects) maps only the `'day'` unit → `DATEDIFF(end,start)`;
  other units silently drop the unit arg. Confirm before mapping non-day units.
