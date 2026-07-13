# REPORT-AND-HOLD: certify SEMANTIC_HAZARD hole closed (2026-07-13)

Worktree `sql-focus`. This report accompanies the mechanism fix in
`brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt` that closed the structural hole in
the certification `SEMANTIC_HAZARD` channel (the same hole proven by the doris P1 bug
fixes — see `BUGS-doris-generator-mappings-2026-07-13.md`). It exists because the
reconciliation set is **LARGE** (104 distinct newly-refusing entries) and largely
requires live-FE probe evidence to adjudicate per-entry; per the task's honesty
discipline the mechanism is shipped, the tree is GREEN and HONEST, and the full
enumeration is handed to the owner rather than force per-entry verdict flips.

## 1. What changed (mechanism)

**A — multi-key hazard lookup.** For each `Func` node in the SOURCE ast, the registry is
now consulted under EVERY name the node can be known by for the (sourceDialect→target)
pair, not just the parsed `sqlName`:

- the parsed node's `sqlNames()` (class canonical name + all aliases/overrides; for the
  unresolved `Anonymous`/`AnonymousAggFunc` shapes, the verbatim call name);
- the node rendered under the **SOURCE** dialect — its leading call name recovers the
  source SURFACE name (e.g. a node parsed as `ArrayMin` / sqlName `ARRAY_MIN` that duckdb
  spells `list_min`);
- the node rendered under the **TARGET** dialect — the emitted target call name.

Any hit counts; when several keys hit, the WORST verdict wins (mirrors the registry's own
collision policy: DIVERGENT > UNCLEAR > CONDITIONALLY_EQUIVALENT > NO_EQUIVALENT >
IDENTICAL). Operator/keyword renderings (`a || b`, `TRIM(LEADING x FROM y)`) carry no
leading call name and simply contribute no key — correct, as they are not a function name
that could match a same-name entry. This is a strict improvement: it only ADDS true
matches, never a false one. Implemented as `functionHazardKeys` + `leadingCallName` in
`Certify.kt`.

**B — verdict drives the decision; the dedicated renderer no longer blanket-skips.** The
old rule SKIPPED a hazard whenever the target generator had a dedicated renderer for the
node's class. For a TRANSLATED function that renderer is exactly what can be wrong (the 6
doris P1 bugs shipped wrong SQL with `ok=true` for precisely this reason). The new logic:

- **DIVERGENT / UNCLEAR → REFUSAL, even if a dedicated renderer exists.**
- **CONDITIONALLY_EQUIVALENT → WARNING** (unchanged).
- **IDENTICAL / NO_EQUIVALENT / absent → no SEMANTIC_HAZARD finding** (NO_EQUIVALENT
  still surfaces via the unmappable/unsupported channels; absent = no evidence = no
  finding, same as before).

Trust now lives in the DATA: an `identical` entry is probe-verified safe; a `divergent`
one is unsafe regardless of whether we have a renderer. Renderers that can't fix a case
still refuse through `unsupportedMessages` (channel 2), which composes on top.

**Target-name refinement: NOT implemented — not data-supported.** The task offered a
refinement (definitively safe when a dedicated renderer's emitted target name matches an
entry's recorded target name AND verdict is identical). The shipped metadata artifact
(`FunctionHazard`) exposes only `verdict / hazard / areas / provenance` — it does NOT
carry the per-pair recorded target side-name, and the a→b keyed map collapses same-key
collisions to the WORST verdict, so an `identical` cross-name entry is shadowed by a
`divergent` same-name entry under the shared key. The refinement therefore cannot be
implemented cleanly without a metadata-schema change (add the target side-name to
`FunctionHazard` + a target-name index). Per the task ("use only if the data supports it;
else verdict-only is fine") the shipped logic is **verdict-only**. Enabling the
refinement later would auto-reconcile a large fraction of the HELD set below (every
cross-name mapping that has a matching `identical` sibling entry, e.g. `concat -> ||`).

## 2. Newly-refused enumeration

**104 distinct (pair, source-name) entries** newly refuse under A+B (probed across the
three wired corpora — trino↔duckdb, trino↔doris, duckdb↔doris — by rendering each
DIVERGENT/UNCLEAR entry's bare-identifier source-surface name at arities 1–3 through the
real generator and diffing certify's SEMANTIC_HAZARD refusal against the old
parsed-name-only + renderer-skip logic). Category split:

- **same-name (39):** the source name reaches the target unchanged (or wrapped) and the
  entry is a same-name divergent verdict the old renderer-skip masked. Mostly GENUINE
  divergences the hole was hiding (`trim`/`ltrim`/`rtrim` collation, `regexp_*` flags,
  `log`/`log2`/`log10` base algebra, `greatest`/`least` NULL algebra, `version`, ...).
- **rewrite (16):** the generator emits an operator/`CASE`/`TRIM(... FROM ...)` form. Some
  are gate-verified equivalents (candidate false-refusals, e.g. `concat -> ||`); others
  are genuine (e.g. `greatest`/`least` CASE-wrap still divergent by NULL-skip semantics).
- **cross-name (49):** the generator maps to a different target name (`max_by -> ARG_MAX`,
  `list_min -> ARRAY_MIN`, `array_join -> ARRAY_TO_STRING`, `strftime -> DATE_FORMAT`, ...).
  Recovered by the multi-key lookup; adjudication needs per-mapping probe evidence.

### Split by verdict-adjudication status

- **Real divergence — correctly refused, leave refused (hole closed):** the vast majority.
  These are what the old channel silently shipped. Two are pinned as regression tests now
  (see §4): `greatest` (duckdb→trino, NULL algebra) and `from_iso8601_timestamp_nanos`
  (trino→doris, LOSSY nanosecond drop). Catalogued in
  `BUGS-certify-newly-caught-2026-07-13.md`.
- **Stale false-refusal — reconciled this task:** 0 (see §3; none reconciled — held instead).
- **Held for owner review:** all 104, pending per-entry live-FE probe adjudication and/or
  the target-name refinement. NO verdicts were flipped. The likely false-refusals are the
  `rewrite`/`cross-name` rows that have a matching `identical` sibling entry in the JSON
  (canonically `concat -> || operator`, which the task itself calls out); these should
  clear either by the target-name refinement or by a per-direction probe confirming the
  emitted form is result-identical.

### Full list (pair | source name | verdict | category | representative emitted SQL)

| pair | source | verdict | category | emitted (arity 3 sample) |
|------|--------|---------|----------|--------------------------|
| doris->duckdb | `bit_count` | divergent | same-name | `BIT_COUNT(c0)` |
| doris->duckdb | `concat` | divergent | rewrite | `c0 || c1 || c2` |
| doris->duckdb | `date_sub` | divergent | rewrite | `c0 - INTERVAL (c1) DAY` |
| doris->duckdb | `datediff` | divergent | cross-name | `DATE_DIFF('DAY', c1, c0)` |
| doris->duckdb | `dayofweek` | divergent | same-name | `DAYOFWEEK(CAST(c0 AS DATE))` |
| doris->duckdb | `greatest` | divergent | rewrite | `CASE WHEN c0 IS NULL OR c1 IS NULL OR c2 IS NULL THEN NULL ELSE GREATEST(c0, c1, c2) END` |
| doris->duckdb | `least` | divergent | rewrite | `CASE WHEN c0 IS NULL OR c1 IS NULL OR c2 IS NULL THEN NULL ELSE LEAST(c0, c1, c2) END` |
| doris->duckdb | `log` | divergent | same-name | `LOG(c0, c1)` |
| doris->duckdb | `log10` | divergent | cross-name | `LOG(10, c0)` |
| doris->duckdb | `log2` | divergent | cross-name | `LOG(2, c0)` |
| doris->duckdb | `ltrim` | divergent | same-name | `LTRIM(c0, c1)` |
| doris->duckdb | `regexp_extract` | divergent | same-name | `REGEXP_EXTRACT(c0, c1)` |
| doris->duckdb | `regexp_extract_all` | divergent | same-name | `REGEXP_EXTRACT_ALL(c0, c1, c2)` |
| doris->duckdb | `regexp_replace` | divergent | same-name | `REGEXP_REPLACE(c0, c1, c2, 'g')` |
| doris->duckdb | `rtrim` | divergent | same-name | `RTRIM(c0, c1)` |
| doris->duckdb | `to_days` | divergent | cross-name | `(DATE_DIFF('DAY', CAST('0000-01-01' AS DATE), CAST(c0 AS DATE)) + 1)` |
| doris->duckdb | `trim` | divergent | same-name | `TRIM(c0, c1)` |
| doris->duckdb | `version` | divergent | same-name | `VERSION()` |
| doris->trino | `array_contains` | divergent | cross-name | `CONTAINS(c0, c1, c2)` |
| doris->trino | `array_join` | divergent | same-name | `ARRAY_JOIN(c0, c1, c2)` |
| doris->trino | `array_size` | divergent | cross-name | `CARDINALITY(c0)` |
| doris->trino | `bit_count` | divergent | cross-name | `BITWISE_COUNT(c0)` |
| doris->trino | `date_add` | divergent | same-name | `DATE_ADD('DAY', CAST(c1 AS BIGINT), c0)` |
| doris->trino | `dayofweek` | divergent | cross-name | `((DAY_OF_WEEK(CAST(CAST(c0 AS TIMESTAMP) AS DATE)) % 7) + 1)` |
| doris->trino | `hex` | divergent | cross-name | `TO_HEX(c0)` |
| doris->trino | `log` | unclear | same-name | `LOG(c0, c1)` |
| doris->trino | `log10` | divergent | cross-name | `LOG(10, c0)` |
| doris->trino | `log2` | divergent | cross-name | `LOG(2, c0)` |
| doris->trino | `ltrim` | divergent | rewrite | `TRIM(LEADING c1 FROM c0)` |
| doris->trino | `regexp_extract_all` | divergent | same-name | `REGEXP_EXTRACT_ALL(c0, c1, c2)` |
| doris->trino | `rtrim` | divergent | rewrite | `TRIM(TRAILING c1 FROM c0)` |
| doris->trino | `split_by_string` | divergent | cross-name | `SPLIT(c0, c1)` |
| doris->trino | `trim` | divergent | same-name | `TRIM(c1 FROM c0)` |
| doris->trino | `unhex` | divergent | cross-name | `FROM_HEX(c0, c1)` |
| duckdb->doris | `concat` | divergent | same-name | `CONCAT(COALESCE(c0, ''), COALESCE(c1, ''), COALESCE(c2, ''))` |
| duckdb->doris | `datediff` | divergent | same-name | `DATEDIFF(c2, c1)` |
| duckdb->doris | `dayofweek` | divergent | same-name | `DAYOFWEEK(c0)` |
| duckdb->doris | `length` | divergent | cross-name | `CHAR_LENGTH(c0)` |
| duckdb->doris | `log` | divergent | same-name | `LOG(c0, c1)` |
| duckdb->doris | `log10` | divergent | cross-name | `LOG(10, c0)` |
| duckdb->doris | `log2` | divergent | cross-name | `LOG(2, c0)` |
| duckdb->doris | `ltrim` | divergent | rewrite | `TRIM(LEADING c1 FROM c0)` |
| duckdb->doris | `map` | divergent | cross-name | `ARRAY_MAP(c0, c1)` |
| duckdb->doris | `rtrim` | divergent | rewrite | `TRIM(TRAILING c1 FROM c0)` |
| duckdb->doris | `trim` | divergent | same-name | `TRIM(c1 FROM c0)` |
| duckdb->doris | `version` | divergent | same-name | `VERSION()` |
| duckdb->doris | `week` | divergent | same-name | `WEEK(c0, c1)` |
| duckdb->trino | `arg_max` | divergent | cross-name | `MAX_BY(c0, c1, c2)` |
| duckdb->trino | `arg_min` | divergent | cross-name | `MIN_BY(c0, c1, c2)` |
| duckdb->trino | `array_to_string` | divergent | cross-name | `ARRAY_JOIN(c0, c1, c2)` |
| duckdb->trino | `concat` | divergent | cross-name | `CONCAT(COALESCE(CAST(c0 AS VARCHAR), ''), COALESCE(CAST(c1 AS VARCHAR), ''), COALESCE(CAST(c2 AS VARCHAR), ''))` |
| duckdb->trino | `date_diff` | unclear | same-name | `DATE_DIFF('C0', c1, c2)` |
| duckdb->trino | `decode` | divergent | cross-name | `FROM_UTF8(c0)` |
| duckdb->trino | `greatest` | divergent | same-name | `GREATEST(c0, c1, c2)` |
| duckdb->trino | `least` | divergent | same-name | `LEAST(c0, c1, c2)` |
| duckdb->trino | `list_max` | divergent | cross-name | `ARRAY_MAX(c0)` |
| duckdb->trino | `list_min` | divergent | cross-name | `ARRAY_MIN(c0)` |
| duckdb->trino | `ltrim` | divergent | rewrite | `TRIM(LEADING c1 FROM c0)` |
| duckdb->trino | `quantile_cont` | divergent | cross-name | `PERCENTILE_CONT(c0, c1)` |
| duckdb->trino | `quantile_disc` | divergent | cross-name | `PERCENTILE_DISC(c0, c1)` |
| duckdb->trino | `regexp_extract_all` | divergent | same-name | `REGEXP_EXTRACT_ALL(c0, c1, c2)` |
| duckdb->trino | `regexp_replace` | divergent | same-name | `REGEXP_REPLACE(c0, c1, c2)` |
| duckdb->trino | `rtrim` | divergent | rewrite | `TRIM(TRAILING c1 FROM c0)` |
| duckdb->trino | `strftime` | divergent | cross-name | `DATE_FORMAT(c0, c1)` |
| duckdb->trino | `strptime` | divergent | cross-name | `DATE_PARSE(c0, c1)` |
| duckdb->trino | `trim` | divergent | same-name | `TRIM(c1 FROM c0)` |
| trino->doris | `array_join` | divergent | same-name | `ARRAY_JOIN(c0, c1, c2)` |
| trino->doris | `cardinality` | divergent | cross-name | `ARRAY_SIZE(c0)` |
| trino->doris | `contains` | divergent | cross-name | `ARRAY_CONTAINS(c0, c1, c2)` |
| trino->doris | `date_add` | divergent | same-name | `DATE_ADD(c2, INTERVAL c1 C0)` |
| trino->doris | `from_hex` | divergent | cross-name | `UNHEX(c0, c1)` |
| trino->doris | `from_iso8601_timestamp_nanos` | divergent | cross-name | `CAST(c0 AS DATETIME(6))` |
| trino->doris | `length` | divergent | cross-name | `CHAR_LENGTH(c0)` |
| trino->doris | `log` | unclear | same-name | `LOG(c0, c1)` |
| trino->doris | `log10` | divergent | cross-name | `LOG(10, c0)` |
| trino->doris | `log2` | divergent | cross-name | `LOG(2, c0)` |
| trino->doris | `ltrim` | divergent | rewrite | `TRIM(LEADING c1 FROM c0)` |
| trino->doris | `map` | divergent | cross-name | `ARRAY_MAP(c0, c1)` |
| trino->doris | `rtrim` | divergent | rewrite | `TRIM(TRAILING c1 FROM c0)` |
| trino->doris | `split` | divergent | cross-name | `SPLIT_BY_STRING(c0, c1, c2)` |
| trino->doris | `to_hex` | divergent | cross-name | `HEX(c0)` |
| trino->doris | `trim` | divergent | same-name | `TRIM(c1 FROM c0)` |
| trino->duckdb | `approx_percentile` | divergent | cross-name | `APPROX_QUANTILE(c0, c1, c2)` |
| trino->duckdb | `array_join` | divergent | cross-name | `ARRAY_TO_STRING(c0, c1, c2)` |
| trino->duckdb | `array_max` | divergent | cross-name | `LIST_MAX(c0)` |
| trino->duckdb | `array_min` | divergent | cross-name | `LIST_MIN(c0)` |
| trino->duckdb | `concat` | divergent | rewrite | `c0 || c1 || c2` |
| trino->duckdb | `date_add` | divergent | rewrite | `c2 + INTERVAL (c1) C0` |
| trino->duckdb | `date_format` | divergent | cross-name | `STRFTIME(c0, c1)` |
| trino->duckdb | `date_parse` | divergent | cross-name | `STRPTIME(c0, c1)` |
| trino->duckdb | `format` | divergent | same-name | `FORMAT(c0, c1, c2)` |
| trino->duckdb | `from_utf8` | divergent | cross-name | `DECODE(c0)` |
| trino->duckdb | `greatest` | divergent | rewrite | `CASE WHEN c0 IS NULL OR c1 IS NULL OR c2 IS NULL THEN NULL ELSE GREATEST(c0, c1, c2) END` |
| trino->duckdb | `least` | divergent | rewrite | `CASE WHEN c0 IS NULL OR c1 IS NULL OR c2 IS NULL THEN NULL ELSE LEAST(c0, c1, c2) END` |
| trino->duckdb | `listagg` | divergent | same-name | `LISTAGG(c0, ',')` |
| trino->duckdb | `ltrim` | divergent | same-name | `LTRIM(c0, c1)` |
| trino->duckdb | `max_by` | divergent | cross-name | `ARG_MAX(c0, c1, c2)` |
| trino->duckdb | `min_by` | divergent | cross-name | `ARG_MIN(c0, c1, c2)` |
| trino->duckdb | `regexp_extract_all` | divergent | same-name | `REGEXP_EXTRACT_ALL(c0, c1, c2)` |
| trino->duckdb | `regexp_replace` | divergent | same-name | `REGEXP_REPLACE(c0, c1, c2, 'g')` |
| trino->duckdb | `rtrim` | divergent | same-name | `RTRIM(c0, c1)` |
| trino->duckdb | `slice` | divergent | cross-name | `ARRAY_SLICE(c0, c1, c2)` |
| trino->duckdb | `split` | divergent | cross-name | `STR_SPLIT(c0, c1)` |
| trino->duckdb | `trim` | divergent | same-name | `TRIM(c0, c1)` |

> Probe methodology caveat: the arity-1/2/3 probes use synthetic `SELECT name(c0,...)`
> calls, so a few emitted samples show a column fed where a literal is expected (e.g.
> `DATE_DIFF('C2', ...)`, `INTERVAL c1 C0`) — those are probe artifacts, not the
> generator's behavior on well-typed input. The (pair, name, verdict) tuple is the
> authoritative unit; the SQL is illustrative.

## 3. Hazard entries reconciled

**None.** The set is LARGE (104 ≫ ~15) and per-entry adjudication requires live-FE probe
evidence that this task is not authorized to synthesize. Per the REPORT-AND-HOLD
discipline, no verdict was flipped and no registry data was edited. Registry counts are
therefore UNCHANGED:

- `TRINO_DUCKDB_HAZARD_ENTRIES` = 242
- `DUCKDB_DORIS_HAZARD_ENTRIES` = 255
- `TRINO_DORIS_HAZARD_ENTRIES` = 203

The generator itself was NOT touched (no mapping fixes in this task — cataloging only).

## 4. Tree state

GREEN and HONEST. Certify-consuming tests were updated to assert the TRUE new behavior
(not to suppress it):

- `CertifyTest.dedicatedRendererNoLongerBlanketTrusted` (was
  `dedicatedRendererMitigatesTheHazard`): `concat` trino→duckdb now REFUSES; annotated
  HELD-FOR-REVIEW (likely stale false-refusal — a probe-verified `concat -> || operator`
  IDENTICAL sibling entry exists).
- `CertifyTest.divergentHazardRefusesEvenWithDedicatedRendererAndGeneratorFlag` (was
  `mitigatedHazardStillRefusedWhenGeneratorFlagsIt`): `greatest` duckdb→trino now fires
  BOTH the SEMANTIC_HAZARD refusal and the UNSUPPORTED_TRANSLATION flag — a genuine NULL
  divergence, correctly surfaced.
- `DorisGeneratorMappingBugsTest.p3_fromIso8601TimestampNanos_datetime6`: the lossy
  nanosecond cast now correctly REFUSES (a KNOWN-lossy mapping must not certify clean).

New pinning unit tests: `multiKeyLookupHitsSourceSurfaceName`, `identicalMappingStaysOk`,
`conditionallyEquivalentStaysWarningUnderMultiKey`, `okAcceptingStillWorksOverNewFindings`.

Counts: 458 brikk-sql JVM, 36 brikk-sql-metadata, 37 brikk-sql-verify — all green.
