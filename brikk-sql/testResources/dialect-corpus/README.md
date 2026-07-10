# sqlglot dialect test corpus

Machine-extracted from the inline dialect test assertions of
[sqlglot](https://github.com/tobymao/sqlglot) (`tests/dialects/test_*.py`),
pinned at **v30.12.0-44-g93d16591** (see `reference/sqlglot`).

sqlglot is Copyright (c) 2025 Toby Mao and released under the MIT License.
This corpus is a mechanical transformation of its test suite and carries the
same license and attribution.

## Regeneration

```
python3 tools/extract_dialect_tests.py
```

## Semantics

- `identity`: parse `sql` under `dialect`, regenerate under `dialect`
  (`pretty`/`identify` as flagged); result must equal `expected` if non-null,
  else `sql`. Entries flagged `check_command_warning` parse into a bare
  `Command` node with a warning in sqlglot — the round-trip still holds.
- `transpile`: for each `read` entry, parse under that dialect and generate
  under `dialect` (unsupported errors ignored); result must equal `sql`.
  For each `write` entry, parse `sql` under `dialect` and generate under the
  entry's dialect; result must equal the entry value — unless the value is
  `{"error": "UnsupportedError"}`, in which case generation with
  `unsupported_level=RAISE` must raise.
- Dialect `""` (file `base.json`) is sqlglot's generic dialect, including the
  pipe-syntax gate tests from `test_pipe_syntax.py`.

## Stats

| dialect | identity | transpile | skipped_dynamic |
|---|---|---|---|
| athena | 52 | 1 | 0 |
| base | 99 | 277 | 38 |
| bigquery | 335 | 260 | 25 |
| clickhouse | 284 | 74 | 22 |
| databricks | 138 | 31 | 1 |
| doris | 41 | 18 | 0 |
| dremio | 39 | 5 | 2 |
| drill | 4 | 1 | 0 |
| druid | 10 | 0 | 3 |
| duckdb | 382 | 225 | 10 |
| dune | 2 | 0 | 1 |
| exasol | 84 | 66 | 11 |
| fabric | 45 | 2 | 0 |
| hive | 61 | 93 | 2 |
| materialize | 18 | 6 | 0 |
| mysql | 289 | 100 | 34 |
| oracle | 171 | 30 | 9 |
| postgres | 362 | 82 | 17 |
| presto | 44 | 147 | 3 |
| prql | 0 | 29 | 0 |
| redshift | 115 | 51 | 2 |
| risingwave | 7 | 1 | 0 |
| singlestore | 106 | 112 | 0 |
| snowflake | 811 | 527 | 32 |
| solr | 3 | 0 | 0 |
| spark | 101 | 126 | 3 |
| sqlite | 90 | 31 | 1 |
| starrocks | 67 | 16 | 6 |
| tableau | 0 | 7 | 0 |
| teradata | 74 | 24 | 0 |
| trino | 55 | 3 | 3 |
| tsql | 214 | 207 | 21 |
| **TOTAL** | **4103** | **2552** | **246** |

Coverage: 6655 of 6903 textual `validate_*` call sites
(96.4%). Skipped calls use runtime-computed arguments (loops,
variables, f-string interpolation) and cannot be extracted statically.
