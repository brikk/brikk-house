# Executed corpus coverage

ASTRA-014, SQLGlot pin `v30.17.0-93-gdcc36544a`.

`brikk-sql/brikk-sql/testResources/corpus-policy.json` defines the SQLGlot-backed,
native-only, and deliberately excluded dialects. Both semantic corpus generators
read it. `CorpusCoverageTest` compares it with Kotlin's canonical registry and
checks the concrete transpile test classes. A supported dialect flagged unavailable,
an unknown dialect/setting, or an unclassified fixture fails the gate.

## Semantic gates

This table preserves the ASTRA-014 coverage baseline, including the 13 qualification
failures first exposed by that work. Current resolution is recorded below.

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

The additional qualification executions exposed 13 existing defects/parity gaps.
All 13 are now resolved. They continue to execute; no comparison or pass-rate gate
was weakened.

- G1, resolved under ASTRA-008: eight BigQuery UNNEST alias, star-expansion,
  struct-field, and correlation cases. These included output-spelling differences,
  missing values, and wrong correlation bindings.
- G2, resolved under ASTRA-008: two missing BigQuery implicit UNNEST conversions.
- G3, resolved: one parenthesized SELECT operand rejected by `Resolver` during
  set-operation column discovery.
- G4, resolved: two StarRocks `TableFromRows` default-column-list omissions in
  `QualifyTables`.

The coverage task did not fix these groups. G3 has since been fixed: wrapped SELECT
operands resolve correctly, including repeated parentheses, while wrapped non-query
operands still raise OptimizeError. Its exact ledger entry was removed after the
public shape and resolver regressions passed. G4 has also been fixed by applying
the existing dialect default-column mapping without replacing explicit aliases;
both ledger entries were removed.

ASTRA-008 has now resolved G1 and G2. `build/astra-008-core.log` reports 388/388
qualification assertions passing, with the same 39 exclusions and zero oracle-failed
skips. Its qualification gate failed only because the final 10 ledger entries were
stale; those exact keys have been removed, leaving `qualify-corpus/known-failures.json`
empty. The historical table above still records the original 13 failures. This
qualification result does not establish complete struct-array or offset conversion
support across dialects; remaining BigQuery transpile parity is tracked in
`TODO-bigquery.md`.

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
pins this count too. Transpile reports distinguish executed assertions from
exclusions and extractor failures. ASTRA-015 now gives every executed assertion
its own ledger identity, including otherwise identical duplicate occurrences.

## Assertion identity and failures

ASTRA-015 applies to the core `brikk-sql` ledger gates: named and base AST parser/
generator corpora, named transpile corpora, annotation, qualification, scope,
lineage, and both native DataFusion fixture gates. Strict non-ledger tests remain
strict. The separate `brikk-sql-oracle` native-verifier ledger is not converted.

IDs use `assertion:v1:sha256:<digest>:<occurrence>`. The digest covers a corpus
namespace and canonical assertion inputs: exact SQL, expected output/error,
dialect, options, and relevant schema/AST payloads. Object keys are sorted;
arrays, SQL whitespace, and identifier case are not normalized. Display labels,
source-line numbers, and generated source-call IDs are not assertion identity.
The occurrence is counted only among identical descriptors, before execution,
so passing duplicates cannot overwrite failing ones. These are assertion-multiset
identities, not persistent upstream source-call provenance.

Populated ledger rows contain `id`, `signature`, a human-readable `case`/`sql`/`key`,
and `reason`. Failure signatures hash full compared values for SQL/AST mismatches,
or the phase, fully qualified exception class, and full nullable exception message.
Diagnostics may be truncated; signatures are not. Human explanations do not
authorize failures and can retain reviewed extension rationale.

The contract rejects unledgered assertions, stale IDs, changed signatures, duplicate
ledger IDs, and unsigned legacy rows. Legacy rows can load only to produce a fresh
actual artifact before failing `MIGRATION_REQUIRED`; they never authorize a failure.
An expected UnsupportedError passes only when generation throws that error or
succeeds with unsupported diagnostics. Warnings cannot hide a subsequent unrelated
exception, and parsing errors cannot satisfy a generation-error expectation.

The reviewed migration after ASTRA-009 converted 206 exemptions into 208 failing
assertions, retaining all existing explanations. The only count increase comes
from two identical duplicate BigQuery byte-literal write assertions for DuckDB and
Postgres. There were no new display-key failures or mismatch-to-exception changes.
No coverage deferral, dialect policy, pass-rate threshold, or fixture SQL changed.
Generator option execution is unchanged; this task does not claim to close the
existing read-direction pretty/identify behavior gaps.

After a fix, remove only the stale IDs reported by the gate. A changed signature
requires review of the full actual output, not a wholesale actual-ledger copy.

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
