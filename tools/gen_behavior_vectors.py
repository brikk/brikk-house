#!/usr/bin/env python3
"""Generate the deterministic behavior-matrix test-vector catalog.

Emits vendor/data/behavior-matrix/vectors.json — a list of {id, function, area, sql}
probe vectors covering the semantic areas the StarRocks<->Doris (and future pairs)
differential probe must exercise:

  null      NULL algebra / NULL-in-NULL-out vs skip
  arity     arity / coercion / return-type edges
  rounding  integer / decimal / floating rounding & division-by-zero
  string    unicode / case / length / indexing (1-based!) / trimming
  regex     regex match / replace / extract
  datetime  dates / timestamps / time zones / formatting
  array     array/map/json semantics (indexing, contains, element_at)
  agg       aggregate/window edge behavior over tiny inputs
  boundary  overflow / empty / extreme inputs

Vectors use only literal inputs (no tables) so they run identically on any MySQL-protocol
engine via `SELECT <sql>`. Deterministic: re-running yields byte-identical output.

Usage: python3 tools/gen_behavior_vectors.py
"""

from __future__ import annotations

import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "vendor" / "data" / "behavior-matrix" / "vectors.json"

# (function, area, sql-expression). function is the shared registry name being probed.
VECTORS: list[tuple[str, str, str]] = [
    # ---- null algebra -------------------------------------------------------
    ("concat", "null", "concat('a', NULL, 'b')"),
    ("concat_ws", "null", "concat_ws(',', 'a', NULL, 'b')"),
    ("coalesce", "null", "coalesce(NULL, NULL, 3)"),
    ("nullif", "null", "nullif(1, 1)"),
    ("ifnull", "null", "ifnull(NULL, 'x')"),
    ("if", "null", "if(NULL, 'a', 'b')"),
    ("greatest", "null", "greatest(1, NULL, 3)"),
    ("least", "null", "least(1, NULL, 3)"),
    ("length", "null", "length(NULL)"),
    ("abs", "null", "abs(NULL)"),
    ("upper", "null", "upper(NULL)"),
    ("array_contains", "null", "array_contains([1, NULL, 3], NULL)"),

    # ---- rounding / division ------------------------------------------------
    ("round", "rounding", "round(2.5)"),
    ("round", "rounding", "round(3.5)"),
    ("round", "rounding", "round(-2.5)"),
    ("round", "rounding", "round(2.345, 2)"),
    ("truncate", "rounding", "truncate(2.567, 1)"),
    ("floor", "rounding", "floor(-2.5)"),
    ("ceil", "rounding", "ceil(2.1)"),
    ("divide_zero_int", "rounding", "1 / 0"),
    ("divide_zero_float", "rounding", "1.0 / 0.0"),
    ("mod", "rounding", "mod(-5, 3)"),
    ("mod_zero", "rounding", "mod(5, 0)"),
    ("pmod", "rounding", "pmod(-5, 3)"),
    ("pow", "rounding", "pow(2, 10)"),
    ("bin", "rounding", "bin(5)"),

    # ---- string: unicode / case / length / indexing (1-based) ---------------
    ("length", "string", "length('héllo')"),
    ("char_length", "string", "char_length('héllo')"),
    ("upper", "string", "upper('héllo')"),
    ("lower", "string", "lower('HÉLLO')"),
    ("substring", "string", "substring('hello', 2, 3)"),
    ("substring", "string", "substring('hello', 0, 3)"),
    ("substring", "string", "substring('hello', -2, 2)"),
    ("left", "string", "left('hello', 2)"),
    ("right", "string", "right('hello', 2)"),
    ("locate", "string", "locate('l', 'hello')"),
    ("instr", "string", "instr('hello', 'l')"),
    ("lpad", "string", "lpad('x', 4, 'ab')"),
    ("rpad", "string", "rpad('x', 4, 'ab')"),
    ("trim", "string", "trim('  x  ')"),
    ("ltrim", "string", "ltrim('  x  ')"),
    ("repeat", "string", "repeat('ab', 3)"),
    ("reverse", "string", "reverse('abc')"),
    ("ascii", "string", "ascii('A')"),
    ("split_part", "string", "split_part('a,b,c', ',', 2)"),
    ("replace", "string", "replace('aaa', 'a', 'bb')"),
    ("concat_empty", "string", "concat('', 'x')"),

    # ---- regex --------------------------------------------------------------
    ("regexp_replace", "regex", "regexp_replace('abc123', '[0-9]+', '#')"),
    ("regexp_extract", "regex", "regexp_extract('abc123', '([a-z]+)([0-9]+)', 2)"),
    ("regexp_extract", "regex", "regexp_extract('abc123', '[0-9]+', 0)"),

    # ---- datetime / timezone ------------------------------------------------
    ("date_format", "datetime", "date_format('2024-03-05 13:07:09', '%Y-%m-%d')"),
    ("date_add", "datetime", "date_add('2024-01-31', INTERVAL 1 MONTH)"),
    ("date_sub", "datetime", "date_sub('2024-03-31', INTERVAL 1 MONTH)"),
    ("datediff", "datetime", "datediff('2024-03-01', '2024-01-01')"),
    ("date_trunc", "datetime", "date_trunc('month', '2024-03-15 12:34:56')"),
    ("day_of_week", "datetime", "dayofweek('2024-03-05')"),
    ("day_of_year", "datetime", "dayofyear('2024-03-05')"),
    ("last_day", "datetime", "last_day('2024-02-10')"),
    ("to_date", "datetime", "to_date('2024-03-05 12:00:00')"),
    ("unix_timestamp", "datetime", "unix_timestamp('1970-01-01 00:00:01')"),
    ("from_unixtime", "datetime", "from_unixtime(0)"),
    ("hour", "datetime", "hour('2024-03-05 13:07:09')"),
    ("months_add", "datetime", "months_add('2024-01-31', 1)"),

    # ---- array / map / json (indexing 1-based, element_at, contains) --------
    ("array_length", "array", "array_length([10, 20, 30])"),
    ("array_contains", "array", "array_contains([1, 2, 3], 2)"),
    ("array_position", "array", "array_position([10, 20, 30], 20)"),
    ("element_at", "array", "element_at([10, 20, 30], 1)"),
    ("element_at", "array", "element_at([10, 20, 30], -1)"),
    ("array_slice", "array", "array_slice([1, 2, 3, 4, 5], 2, 2)"),
    ("array_sort", "array", "array_to_string(array_sort([3, 1, 2]), ',')"),
    ("array_join", "array", "array_join([1, 2, 3], '_')"),
    ("array_distinct", "array", "array_to_string(array_distinct([1, 1, 2]), ',')"),
    ("array_max", "array", "array_max([1, 5, 3])"),
    ("array_avg", "array", "array_avg([1, 2, 3])"),
    ("arrays_overlap", "array", "arrays_overlap([1, 2], [2, 3])"),
    ("json_extract", "array", "json_extract('{\"a\": 1}', '$.a')"),
    ("json_length", "array", "json_length('[1, 2, 3]')"),
    ("get_json_string", "array", "get_json_string('{\"a\": \"x\"}', '$.a')"),

    # ---- aggregate / window (tiny inline inputs) ----------------------------
    ("count_distinct", "agg", "(SELECT count(DISTINCT c) FROM (SELECT 1 c UNION ALL SELECT 1 UNION ALL SELECT 2) t)"),
    ("sum", "agg", "(SELECT sum(c) FROM (SELECT 1 c UNION ALL SELECT 2 UNION ALL SELECT NULL) t)"),
    ("avg", "agg", "(SELECT avg(c) FROM (SELECT 1 c UNION ALL SELECT 2) t)"),
    ("group_concat", "agg", "(SELECT group_concat(c) FROM (SELECT 'a' c UNION ALL SELECT 'b') t)"),
    ("any_value", "agg", "(SELECT any_value(c) FROM (SELECT 7 c) t)"),
    ("min", "agg", "(SELECT min(c) FROM (SELECT 3 c UNION ALL SELECT 1) t)"),
    ("stddev", "agg", "(SELECT round(stddev(c), 4) FROM (SELECT 1 c UNION ALL SELECT 2 UNION ALL SELECT 3) t)"),
    ("variance", "agg", "(SELECT round(variance(c), 4) FROM (SELECT 1 c UNION ALL SELECT 2 UNION ALL SELECT 3) t)"),

    # ---- boundary / overflow ------------------------------------------------
    ("abs_min", "boundary", "abs(-9223372036854775807)"),
    ("cast_overflow", "boundary", "cast(300 AS TINYINT)"),
    ("substring_oob", "boundary", "substring('abc', 10, 5)"),
    ("array_empty", "boundary", "array_length([])"),
    ("concat_num", "boundary", "concat(1, 2)"),
    ("power_big", "boundary", "pow(10, 18)"),
]


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    seen = set()
    vectors = []
    for i, (fn, area, sql) in enumerate(VECTORS):
        vid = f"{area}_{fn}_{i:03d}"
        assert vid not in seen, f"duplicate vector id {vid}"
        seen.add(vid)
        vectors.append({"id": vid, "function": fn, "area": area, "sql": sql})
    OUT.write_text(json.dumps({"vectors": vectors}, indent=1) + "\n")
    areas: dict[str, int] = {}
    for v in vectors:
        areas[v["area"]] = areas.get(v["area"], 0) + 1
    print(f"wrote {len(vectors)} vectors -> {OUT.relative_to(ROOT)}")
    print("by area: " + ", ".join(f"{k}={v}" for k, v in sorted(areas.items())))


if __name__ == "__main__":
    main()
