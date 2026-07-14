# SPIKE: source-aware generator transforms (2026-07-13)

## ✅ IMPLEMENTED (2026-07-14) — Option A shipped

Threaded `sourceDialect: String?` through generation: `Generator` ctor +
`Generator.isCrossDialectFrom(nativeDialect)`, all 12 `Dialect.generator(...)` overrides and
generator ctors, `Dialect.generate(...)`, `transpile(read,write)` (passes `read`), and
`SqlFragment.transpileTo`/`toStandardSql` (pass the fragment's own dialect). Policy: `null`
(source unknown) = faithful/native (preserves direct `Expression.sql(...)`); rewrite fires
only when the source is a KNOWN non-target dialect.

Re-landed the gated ClickHouse rewrites: `lower`→`lowerUTF8`, `upper`→`upperUTF8`,
`translate`→`translateUTF8` (cross-dialect; faithful native on CH→CH), and `Week`→`toISOWeek`
gated on ISO-week sources only (`ISO_WEEK_SOURCES={duckdb}`; **Doris/MySQL week is mode-0 ==
ClickHouse default, so it stays faithful `week`** — a source-SPECIFIC nuance a plain
cross-dialect boolean would have gotten wrong). Reconciled hazards: duckdb `week`/`translate`
and doris/trino `translate` → identical; `lower`/`upper` stay divergent (İ/ß edge → WARNING).
Golden pins in `ClickhouseSourceAwareTransformsTest` incl. the **pipe-desugar regression**
(`FROM t |> SELECT lower(x)` now stays `lower`, not `lowerUTF8`). `./kotlin test` green (584).

### Backlog follow-up (2026-07-14, same day) — round/bin shims + golden test DONE

- **round/bin shims landed** behind the source-aware gate: `Round` half-away shim
  (`sign(x)*floor(abs(x)*pow(10,d)+0.5)/pow(10,d)`) for `HALF_AWAY_ROUND_SOURCES={duckdb}`,
  and the `bin` strip-leading-zeros shim for duckdb→clickhouse; both faithful native
  round/bin on ClickHouse→ClickHouse and on non-half-away sources (MySQL/Trino half-up
  excluded). Live-verified equal to DuckDB (2.5→3, 0.5→1, -2.5→-3, 3.5→4, 2.345@2dp→2.35;
  bin 5→'101', 0→'0', 255→'11111111'). Reconciled duckdb `round`/`bin` → identical.
- **Golden X→X round-trip test added** (`ClickhouseSourceAwareTransformsTest.
  golden_sameDialectRoundTripFaithful`) — the spike's stated gap: same-dialect round-trip must
  never apply a semantic-changing rename. Also audited `length`→`LENGTH` /
  `char_length`→`CHAR_LENGTH`: ClickHouse renders these FAITHFULLY (the `binary=true` flag on
  the Length node keeps byte-`length` vs codepoint-`CHAR_LENGTH`), so the §3 "Length→CHAR_LENGTH
  CORRUPT" concern does NOT manifest on CH→CH — pinned by the golden test.

Still open: `age`/`to_days` (genuinely unmappable), the direct Doris `xxhash_32`/`week`
re-probes (need live Doris), and extending the golden audit to the remaining dialects.

---


Research spike (no production code) assessing **Option 2** from the ClickHouse
generator-bug work: make the dialect generators aware of the SOURCE dialect so that
semantic-changing function rewrites fire only on genuine cross-dialect transpilation, not
on same-dialect generation (which `toStandardSql` / pipe desugaring uses).

## 1. Problem

The generators are **source-unaware**: `Dialect.generate(expr)` builds a target-dialect
`Generator` and renders, with no knowledge of which dialect the AST was parsed from. A
generator `TRANSFORMS` entry therefore fires identically whether we are transpiling
`duckdb -> clickhouse` (rewrite is *correct*) or generating `clickhouse -> clickhouse`
(rewrite may *corrupt* a native function).

Concrete, confirmed via live chdb (ClickHouse 26.5.1.1) + a `toStandardSql` probe:

```
clickhouse pipe:  FROM t |> SELECT lower(x)   =>   WITH __tmp1 AS (SELECT lowerUTF8(x) FROM t) ...
clickhouse pipe:  FROM t |> SELECT week(d)    =>   WITH __tmp1 AS (SELECT toISOWeek(d) FROM t) ...
```

A user writing ClickHouse-flavoured pipe SQL gets their `lower` silently swapped to
`lowerUTF8` (ASCII -> full-Unicode) and `week` to `toISOWeek` (Sunday-based -> ISO). Pipe
desugaring is a *structural* rewrite (`|>` -> nested `WITH`/`SELECT`); it must not change
function semantics.

**Interim fix shipped (Option 1, commit on `sql-focus`):** the offending ClickHouse
transforms (`lower`/`upper`/`week`) were reverted to faithful base rendering (matching
upstream sqlglot, which never added them), and the cross-dialect divergence is left to the
divergent hazard. That is safe but gives up the auto-correct for cross-dialect
transpilation. This spike is about getting the auto-correct back *without* the CH->CH
corruption.

## 2. Root cause

`GenMethod = Generator.(Expression) -> String`. Transform lambdas live in a static
`TRANSFORMS` map per generator and receive only the node. Neither the lambda nor the
generator instance knows the source dialect. Generation entry points:

- `Dialect.generate(expr, pretty, copy)` -> `generator(pretty).generate(expr)`
- `SqlFragment.transpileTo(target, ...)` -> `Dialects.forName(target).generator(...).generate(tree)`
- `SqlFragment.toStandardSql(target = dialect)` -> `Dialects.forName(target).generate(tree)`
  (note the default `target = dialect`, i.e. **same-dialect** for pipe desugaring)
- 16 `.generate(...)` / `.generator(...)` call sites in `brikk-sql/src` (grep). None pass
  a source dialect today.

## 3. Blast radius (which transforms are affected)

A transform needs source-gating iff, for a node ClickHouse ITSELF parses to, the rewrite
changes ClickHouse semantics. Audited so far (chdb-verified):

| transform | fires on CH-origin? | CH->CH effect | status |
|-----------|---------------------|---------------|--------|
| `Lower` -> `lowerUTF8` | yes | ASCII -> Unicode (CORRUPT) | reverted (Option 1) |
| `Upper` -> `upperUTF8` | yes | ASCII -> Unicode (CORRUPT) | reverted |
| `Week` -> `toISOWeek` | yes | Sunday -> ISO (CORRUPT) | reverted (duckdb); Trino `week` = `WeekOfYear` node, CH never emits it, so that one is safe and kept |
| `Length` -> `CHAR_LENGTH` | yes | bytes -> codepoints (CORRUPT) | **PRE-EXISTING** (not from this work) — same bug, not yet addressed |
| `Round` -> half-away shim | yes | banker's -> half-away (would CORRUPT) | never applied; deferred here |
| `bin` -> strip-zeros shim | yes | byte-padded -> stripped (would CORRUPT) | never applied; deferred |
| `DayOfWeek` -> `toDayOfWeek` | yes | none (`dayofweek`==`toDayOfWeek` in CH, 7==7) | safe, kept |
| `RegexpReplace` -> `replaceRegexpAll` | yes | none (CH `regexp_replace` IS `replaceRegexpAll`) | safe, kept |
| `Log`/`BitwiseXor`/`TimeToUnix`/`millisecond` | no (different node, or not a CH name) | n/a | safe, kept |

**Key takeaway:** this is NOT a ClickHouse-only or new problem. `Length -> CHAR_LENGTH`
predates this work, and every dialect generator (Doris, Trino, DuckDB, ...) plausibly has
canonicalizing transforms that are semantic no-ops cross-dialect but semantic-changers on
same-dialect generation. **Step 1 of any implementation must be a full audit** (a
mechanical differential probe per dialect: for each transform, does same-dialect
round-trip change results on representative inputs?).

## 4. Options evaluated

### Option A — thread `sourceDialect` into the generator (RECOMMENDED)

Add an optional `sourceDialect: String? = null` to `Generator` (and pass-through in each
`Dialect.generator(...)` override). Transform lambdas that are semantic-changers consult
it:

```kotlin
// in a transform lambda (this: Generator)
reg(Lower::class) { e ->
    if (ch().sourceDialect == "clickhouse") func("LOWER", e.thisArg)   // faithful
    else func("lowerUTF8", e.thisArg)                                   // cross-dialect fix
}
```

Threading:
- `Dialect.generate(expr, pretty, copy, sourceDialect=null)` -> forward to `generator(...)`.
- `Dialect.generator(pretty, sourceDialect=null)` -> pass to the `Generator` ctor.
- `transpile(sql, read, write)` -> `write.generate(parsed, sourceDialect = read)`.
- `SqlFragment.transpileTo(target)` -> pass `sourceDialect = this.dialect`.
- `SqlFragment.toStandardSql(target=dialect)` -> pass `sourceDialect = this.dialect`
  (so same-dialect pipe desugaring gets faithful rendering automatically).

Pros: minimal, localized; default `null` preserves today's behavior for the 16 call sites
not updated; the transform author decides per-transform (no magic). Naturally fixes pipe
desugaring (source==target -> faithful). Lets the verified `round`/`bin`/`lower`/`upper`/
`week` rewrites land for cross-dialect.

Cons: every semantic-changing transform must be individually taught the gate (needs the
§3 audit); `sourceDialect` is a string (stringly-typed) — acceptable, matches existing
dialect-name handling. A `null` source (direct `expr.sql("clickhouse")`) must pick a
default policy — recommend treating `null` as "cross-dialect / canonical" to preserve the
current transpile-oriented behavior, EXCEPT `toStandardSql` which always passes its own
dialect.

Effort: ~S/M. Ctor + ~5 plumbing sites + per-transform gates for the audited set.

### Option B — "faithful mode" flag (source==target)

Add `faithful: Boolean` to the generator; callers that do same-dialect generation set it;
semantic-changing transforms check `if (faithful) base else rewrite`. Essentially Option A
collapsed to a boolean.

Pros: even simpler than A (one boolean, no dialect matching). Fixes pipe desugaring
(`toStandardSql` sets `faithful=true`).
Cons: coarser — can't express "faithful for CH source, rewrite for duckdb source" when
target is CH and source is a THIRD dialect; but in practice the only same-dialect caller is
`toStandardSql`, so a boolean covers the real case. This is arguably the pragmatic 80%.

### Option C — parser-level distinct nodes

Parse each dialect's native function to a node that already encodes its semantics (e.g. CH
`lower` -> a node that renders back to `LOWER`, while the canonical `Lower` means
Unicode-lower). The generator stays source-unaware because the node carries the intent.

Pros: architecturally "correct" (semantics live in the AST); no generation-time source
plumbing. Cons: large, invasive, touches every parser and the node model; high regression
risk; diverges from sqlglot's node taxonomy. Not worth it for this problem.

### Option D — post-parse source normalization pass

A transpile-only pass (source != target) that rewrites nodes to canonical forms before
generation; same-dialect skips it. Similar effect to A/B but adds a whole pass.
Cons: another tree walk; duplicates transform knowledge. Not recommended.

## 5. Recommendation

**Option A (or its simpler cousin B) — thread source awareness into generation, gate the
semantic-changing transforms.** Concretely:

1. **Audit** every dialect generator's TRANSFORMS for same-dialect semantic changes (the
   §3 table, mechanized: parse `f(args)` under dialect D, generate under D, run both in the
   engine, diff). Produces the definitive list to gate. This is the bulk of the work and is
   independent of A-vs-B.
2. Implement Option A plumbing (`sourceDialect: String?`, default null; `toStandardSql`
   passes its dialect; `transpile`/`transpileTo` pass the read dialect).
3. Gate the audited transforms; re-land the verified cross-dialect fixes
   (`lower`/`upper`/`week`/`round`/`bin`) behind `sourceDialect != <thisDialect>`.
4. Reconcile hazards back to identical for the now-safely-transformed cross-dialect cases.

Start with B-style boolean if we want the pipe fix + re-landing fast, and only generalize
to full dialect-string if a real 3-dialect case appears. Given the only same-dialect caller
is `toStandardSql`, **B likely suffices and is lower-risk**; A is the clean general form.

## 6. Test plan

- Golden: `X -> X` round-trip identity for the audited functions across ALL dialects
  (currently untested — this is the gap that let the bug in).
- `toStandardSql` pipe tests per dialect asserting native functions survive desugaring
  (e.g. CH `FROM t |> SELECT lower(x)` stays `LOWER(x)`).
- Cross-dialect pins re-asserting the fixes (`duckdb -> clickhouse lower` -> `lowerUTF8`)
  once re-landed, plus live chdb differential (harness already exists — see the BUGS file).
- Hazard registry byte-clean after any reconciliation.

## 7. Effort / risk

- Audit: ~0.5 day (mechanical, reuse the chdb+duckdb harness).
- Option B: ~0.5 day (boolean + 5 plumbing sites + gate the audited set + tests).
- Option A: ~1 day (dialect-string plumbing + defaults policy + tests).
- Risk: low-medium. Main risk is an incomplete audit leaving a semantic-changer ungated;
  mitigated by the new X->X round-trip golden tests, which would catch any regression
  going forward.

## 8. Cross-references

- Interim revert: commit on `sql-focus` ("clickhouse: revert lower/upper/week transforms ...").
- Verified fixes + shims + harness: `BUGS-clickhouse-generator-mappings-2026-07-13.md`.
- Certify policy #2 (how divergent hazards surface once transforms are gated):
  `REPORT-certify-hazard-hole-closed-2026-07-13.md`.
</content>
