# sqlglot dialect test corpus

Machine-extracted from the inline dialect test assertions of
[sqlglot](https://github.com/tobymao/sqlglot) (`tests/dialects/test_*.py`),
pinned at **v30.17.0-93-gdcc36544a** (see `reference/sqlglot`).

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
| base | 99 | 282 | 38 |
| bigquery | 360 | 288 | 27 |
| clickhouse | 323 | 79 | 23 |
| databricks | 144 | 32 | 1 |
| doris | 44 | 18 | 0 |
| dremio | 43 | 7 | 2 |
| drill | 4 | 8 | 0 |
| druid | 10 | 0 | 3 |
| duckdb | 386 | 237 | 10 |
| dune | 2 | 0 | 1 |
| exasol | 84 | 66 | 11 |
| fabric | 45 | 2 | 0 |
| hive | 61 | 94 | 2 |
| materialize | 18 | 6 | 0 |
| mysql | 324 | 103 | 34 |
| oracle | 184 | 33 | 9 |
| postgres | 426 | 99 | 17 |
| presto | 48 | 147 | 3 |
| prql | 0 | 29 | 0 |
| redshift | 132 | 52 | 2 |
| risingwave | 7 | 1 | 0 |
| singlestore | 106 | 112 | 0 |
| snowflake | 841 | 530 | 32 |
| solr | 3 | 0 | 0 |
| spark | 106 | 135 | 3 |
| sqlite | 124 | 37 | 1 |
| starrocks | 78 | 18 | 6 |
| tableau | 0 | 7 | 0 |
| teradata | 74 | 24 | 0 |
| trino | 109 | 4 | 3 |
| tsql | 227 | 216 | 21 |
| **TOTAL** | **4464** | **2667** | **249** |

Coverage: 7131 of 7278 textual `validate_*` call sites
(98.0%). Skipped calls use runtime-computed arguments (loops,
variables, f-string interpolation) and cannot be extracted statically.
