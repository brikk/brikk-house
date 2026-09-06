# Executed corpus coverage

ASTRA-014, SQLGlot pin `v30.17.0-93-gdcc36544a`.

`brikk-sql/brikk-sql/testResources/corpus-policy.json` defines the SQLGlot-backed,
native-only, and deliberately excluded dialects. Both semantic corpus generators
read it. `CorpusCoverageTest` compares it with Kotlin's canonical registry and
checks the concrete transpile test classes. A supported dialect flagged unavailable,
an unknown dialect/setting, or an unclassified fixture fails the gate.

## Semantic gates

| Corpus | Executed before | Executed now | Excluded | Oracle-failed skips | Known failures |
|---|---:|---:|---:|---:|---:|
| Qualification | 324 | 388 | 39 | 0 | 13 |
| Lineage | 56 | 60 | 23 | 0 | 0 |

Qualification gained 57 previously misclassified supported cases. Another seven
DuckDB cases now run because extraction uses the pinned upstream schema, including
`pivotable`, `unpivotable`, and `t_bool.b`. They were extractor setup errors, not
oracle defects. All seven now pass. The single BigQuery uppercase-normalization
setting uses a test-only dialect adapter; this is not a general dialect-settings API.

Lineage gained four BigQuery multi-column UNPIVOT calls. Its 16 unextractable calls
and one duplicate remain individually identified in `lineage-corpus/base.json`.
One oracle-raised lineage case executes as a negative assertion. Qualification's
20 expected-error assertions execute too. Expected errors are not skipped failures.

The additional qualification executions expose 13 existing defects/parity gaps.
Their exact SQL keys and reviewed reasons are in `qualify-corpus/known-failures.json`.
They continue to execute; no comparison or pass-rate gate was weakened.

- G1: eight BigQuery UNNEST alias, star-expansion, struct-field, and correlation
  cases. Some are output-spelling differences; missing values and wrong correlation
  bindings are semantic defects. Coordinate parser/resolver/generator work under
  ASTRA-008 rather than stripping qualifiers indiscriminately.
- G2: two missing BigQuery implicit UNNEST conversions, also ASTRA-008.
- G3: one parenthesized SELECT operand rejected by `Resolver` during set-operation
  column discovery. Unwrap subqueries before dispatch without accepting invalid operands.
- G4: two StarRocks `TableFromRows` default-column-list omissions in `QualifyTables`.
  Apply the existing default-column mapping while preserving explicit aliases.

The coverage task did not fix these groups. G3 has since been fixed: wrapped SELECT
operands resolve correctly, including repeated parentheses, while wrapped non-query
operands still raise OptimizeError. Its exact ledger entry was removed after the
public shape and resolver regressions passed. G4 has also been fixed by applying
the existing dialect default-column mapping without replacing explicit aliases;
both ledger entries were removed. G1 and G2 remain open.

## Base deferral

The extracted `dialect-corpus/base.json` has no general base assertion consumer.
The exact deferred assertions are its 40 `identity` entries whose `sql` has no
`|>`, and every supported `read`/`write` direction in its 282 `transpile` entries,
1,479 directions total. All remain unexecuted because a general base gate and its
failure review have not been implemented. Do not infer their success from other gates.

Its 59 pipe identities execute in `PipeDesugarCorpusTest`; 766 transpile directions
are deliberately excluded by dialect policy. `CorpusCoverageTest` checks these exact
counts and their policy reason, so fixture growth cannot silently expand the deferral.
The 38 base `skipped_dynamic` records are extractor limitations, not executed tests.
Across all 32 SQLGlot dialect fixture files there are 249 such records; this is not
a unique upstream assertion denominator because extraction can expand loops.

Named transpile gates report executed, dialect-excluded, unextractable, and
oracle-failed directions separately. UnsupportedError expectations remain executed
negative assertions. DataFusion retains its separate native corpus gates.

The 11 named dialect fixture files also contain 2,265 exact identity assertions
without direct consumers. All their `identity` entries are explicitly deferred
under `named_identity_deferral` until an identity gate and failure review exist.
Their transpile gates and the separate parser/generator AST corpora must not be
described as executing these exact `sql`/`expected`/`pretty` assertions. The policy
pins this count too. Transpile reports distinguish failing executions from unique
ledger keys; stable case-identity improvements remain a separate task.

## Public entry points

`NestedPipeEntryPointsTest`, the set-operation/physical-input tests in
`SqlFragmentTest`, and mixed-dialect `RelTest` exercise stage composition through
public APIs. Compiler tests check generated numeric and nullable getters. These
tests prevent helper-only corpus success from hiding the integration defects fixed
under ASTRA-004/005/006/007; corpus parity alone is not execution equivalence.

Regenerate semantic corpora with `python3 tools/gen_qualify_corpus.py` and
`python3 tools/gen_lineage_corpus.py` against the pinned checkout, then run
`./kotlin test -m brikk-sql`. The generators use a fixed nine-character git-describe
abbreviation so machine-local git abbreviation settings do not change the pin label.
