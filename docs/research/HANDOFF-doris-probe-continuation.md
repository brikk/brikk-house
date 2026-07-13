# HANDOFF — Doris semantic-probe worklist continuation

Self-contained handoff for a fresh agent. Goal: finish the live differential
probing in `doris-probe-worklist.md` and populate the doris hazard registries.
Batches 1–2 are done (21 pairs each); ~300 functions remain (mostly the
IDENTICAL/`none` scalar tail + the array / aggregate / datetime families).

## Repos & paths
- **doris-focus worktree** (harness, live cluster, gradle):
  `/home/jayson/.local/share/opencode/worktree/2f9e0e4dd25286cf67e662e47fa960cf686b7c3e/doris-focus`
  — git branch `doris-focus`; gradle root is `jvm/`. Read its `AGENTS.md`.
- **brikk-house** (deliverable + worklist + report): `/home/jayson/DEV/brikk/brikk-house`
  - worklist: `docs/research/doris-probe-worklist.md` (181 duckdb→doris + 158 trino→doris rows, prior verdict + suggested areas per fn)
  - report (append your findings here, with `#anchor`s for provenance): `docs/research/REPORT-doris-differential-probe-2026-07-13.md`
  - hazard files to fill: `brikk-sql/testResources/semantics/{duckdb-doris,trino-doris}-hazards.json`
  - prior trino↔duckdb evidence (Trino-side reference, hints only): `brikk-sql/testResources/semantics/trino-duckdb-hazards.json`
  - registry generator (run after each batch): `python3 tools/generate_hazards_registry.py`

## Environment
- JAVA_HOME for gradle: `/home/jayson/.local/share/mise/installs/java/25.0.2`
- Bring the live cluster up: `cd jvm/doris-ducklake/compose && JAVA_HOME=<jdk25> ./smoke.sh --up-only` ; down: `./smoke.sh --down`. AGENTS.md rule: always `--down` then a fresh `--up-only` (BDBJE re-election stall on recreate).
- JDBC: DuckDB `jdbc:duckdb:` (in-memory; `duckdb_jdbc` is already on the test classpath); Doris FE `jdbc:mysql://127.0.0.1:9030/?user=root`.

## The harness — recreate it, then DELETE it when done
Put this at `jvm/doris-ducklake/test/src/dev/brikk/ducklake/doris/corpus/DifferentialProbe.kt`.
It is file-driven: reads `fn<TAB>expr` lines from `/tmp/opencode/probe-batch.txt`,
evaluates each on both engines, writes `fn\texpr\tSAME|DIFF\tduckdb\tdoris`
(codepoint-rendered) to `/tmp/opencode/probe-results.tsv`.

```kotlin
package dev.brikk.ducklake.doris.corpus
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.Test
internal class DifferentialProbe {
    private val inPath = System.getProperty("probe.in", "/tmp/opencode/probe-batch.txt")
    private val outPath = System.getProperty("probe.out", "/tmp/opencode/probe-results.tsv")
    @Test fun probe() {
        val batch = File(inPath).readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('\t') }
            .map { val i = it.indexOf('\t'); it.substring(0, i) to it.substring(i + 1) }
        val out = StringBuilder()
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            DriverManager.getConnection("jdbc:mysql://127.0.0.1:9030/?user=root").use { doris ->
                for ((fn, expr) in batch) {
                    val d = eval(duck, "SELECT $expr"); val o = eval(doris, "SELECT $expr")
                    out.append(fn).append('\t').append(expr).append('\t')
                       .append(if (d == o) "SAME" else "DIFF").append('\t')
                       .append(render(d)).append('\t').append(render(o)).append('\n')
                }
            }
        }
        File(outPath).writeText(out.toString()); println("wrote ${batch.size} rows")
    }
    private fun eval(c: Connection, sql: String): String? = try {
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else "<no row>" } }
    } catch (e: Exception) { "<ERR:${e.message?.lineSequence()?.firstOrNull()?.take(60)}>" }
    private fun render(s: String?): String {
        if (s == null) return "NULL"; if (s.startsWith("<")) return s
        return "\"$s\"[" + s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) } + "]"
    }
}
```
Run: `JAVA_HOME=<jdk25> ./gradlew :doris-ducklake:test --tests "*DifferentialProbe*" --rerun-tasks -q` (from `jvm/`).

**CRITICAL:** it is a `@Test` that is NOT excluded from `:doris-ducklake:test` and
needs the live cluster + batch file, so it will break the headless suite. **Delete
it before you finish / before any commit or push to doris-focus.** It is throwaway.

**Batch-file gotchas:** no newline/LF *inside* an expr (one line = one expr — an
embedded LF splits the row). Use IDENTICAL syntax on both engines. To inject exact
unicode/whitespace bytes, generate `probe-batch.txt` with a python heredoc (see how
batch 2 did EM SPACE U+2003 / NBSP U+00A0 / tab).

## Method (per function)
1. From the worklist row, take the "suggested probe areas" + prior trino↔duckdb verdict; craft 1–3 divergence-pressure exprs (NULL args, unicode edge chars, out-of-bounds, negative numbers, tz-bearing timestamps).
2. Run the batch; read the TSV.
3. Adjudicate:
   - **duckdb→doris**: live compare (DuckDB value vs Doris value). same→`identical`; differ→`divergent` (describe precisely); ambiguous→`unclear`.
   - **trino→doris**: compare the Doris live value to the probe-verified Trino behavior in `trino-duckdb-hazards.json`. Provenance MUST say "Doris live; Trino from prior evidence" (the trino agent can re-confirm live in its own project later).
4. Append rows to the two JSON files (schema below) + a findings section to the REPORT with `#anchor`s.
5. `python3 tools/generate_hazards_registry.py`.

## Heuristic (speeds it up — but VERIFY, don't assume)
Doris is Java/MySQL-family: it tends to **match Trino and diverge from DuckDB**
(NULL propagation, full case-folding, code-point—not grapheme—string ops,
replace-all regex, out-of-bounds→NULL). So duckdb→doris skews `divergent`,
trino→doris skews `identical`. Outliers found: `ascii` (byte, not codepoint) and
`length` (BYTE count, not char) — Doris diverges from BOTH engines there.

## JSON schema (append to `pairs`)
```json
{ "duckdb": "<name>", "doris": "<name>", "verdict": "identical|divergent|conditionally-equivalent|no-equivalent|unclear",
  "hazard": "<finding or null>", "areas": ["string","unicode",...], "provenance": "REPORT-...md#anchor" }
```
(trino file uses `"trino"` instead of `"duckdb"`.) Methodology discipline: when in
doubt mark `unclear` — never claim equivalence you didn't verify.

## Family gotchas for the remaining tail
- **strings tail** (mostly scalar, identical-syntax): easiest; batch big.
- **arrays** (`array_join`, `array_max`, `array_min`, `split`): array literal is `[1,2,3]` on DuckDB, `array(1,2,3)`/`[1,2,3]` on Doris; array-returning fns render differently via `getString` (batch-1 saw `regexp_extract_all` render empty on Doris — investigate its signature/return). Compare element-wise.
- **aggregates** (`max_by`, `min_by`, `any_value`, `approx_count_distinct`, `arg_*`): can't be bare scalar — build a tiny inline table (DuckDB `FROM (VALUES (1,'a'),(2,'b')) t(x,y)`; Doris via `FROM (SELECT 1 x,'a' y UNION ALL SELECT 2,'b') t`). Order-sensitive ones (`any_value`, `arg_*`) are nondeterministic → `conditionally-equivalent`.
- **datetime/timezone** (`date_add`, `date_format`, `date_trunc`, `hour`, `current_date`, …): set BOTH session zones to a matched value first (tz trap: DuckDB `SET TimeZone=...` vs Doris `SET time_zone=...`); probe a tz-bearing timestamp. `date_format` format-spec dialects differ (strftime vs Java-ish) → likely `divergent`. `date_add` arg-order/units differ.
- **`format`**: printf/fmt (DuckDB) vs Doris's — spec dialects differ → likely `divergent`.
- **`round`**: prior UNCLEAR — probe `round(x, d)` with d>0 across `.5` boundaries; half-up vs half-away-from-zero (tested identical for a few in batch 2, not exhaustive).

## Discipline
- Work in batches; write results to `/tmp` files, adjudicate from files — do NOT let per-function probing climb the chat context.
- Commit brikk-house incrementally (no other agent on its main; diff is 0→N so clear).
- Delete the harness from doris-focus before finishing; keep doris-focus green (`:doris-ducklake:test :detekt`) and untouched otherwise.

## Reusable findings so far (VERIFY per fn, but these patterns hold)
- **Domain-error contract diverges 3 ways:** live DuckDB 1.5.4 THROWS on out-of-domain
  (`acos(2)`, `ln(0)`, `sqrt(-1)`, `factorial(-1)`, `cot(0)`, …); Doris returns NULL
  (`cot(0)`→Infinity); prior-Trino evidence = NaN. (This live DuckDB throwing
  contradicts the older "NaN in both" prior-corpus notes — DuckDB version drift.)
- **Transcendental last-ULP:** irrational-returning fns agree to ~15 sig digits but the
  final rendered digit often differs (`exp cbrt asinh ln log10 log(2-arg)`) →
  `conditionally-equivalent` where a probed point differed, `identical` where all matched.
- **`log` single-arg trap:** DuckDB `log(x)`=log10; Doris `log(x)`=natural log. 2-arg agrees.
- **Boolean rendering:** Doris renders BOOLEAN as TINYINT `0/1` over MySQL wire vs
  DuckDB/Trino `true/false` → mark `conditionally-equivalent` (type mapping), NOT divergent.
- **Doris hex traps:** Doris `to_hex()`→NULL, `from_hex()` doesn't decode; use Doris
  `hex()` / `unhex()`. **Doris lacks:** `format_number`, `levenshtein_distance` (use
  `levenshtein`). **DuckDB lacks:** `hamming_distance`, `soundex`, `levenshtein_distance`,
  `format_number`; `octet_length` needs a BLOB arg (no bare-VARCHAR overload).
- Trino side is adjudicated via the **live-DuckDB bridge**: prior corpus says Trino==DuckDB
  for most, so where Doris==live-DuckDB → identical; ULP diff → conditionally-equivalent;
  Doris-NULL vs Trino-NaN (domain) → divergent. Provenance still says "Doris live; Trino
  from prior evidence".

## Tooling added this session
- `/tmp/opencode/add_pairs.py <duckdb|trino> <spec.json>` — appends a JSON array of pair
  objects to the right hazard file, dedup-by-side-name, writes indent=2. Use it instead of
  hand-editing the JSON.
- `/tmp/opencode/WORKER-BRIEF.md` — self-contained brief for delegating one family to a
  worker subagent (probe→adjudicate→append→report→regen→commit). Dispatch workers ONE AT A
  TIME (the Doris FE + file-driven harness is a shared singleton; probing cannot parallelize).
- A python `duckdb` is available in `/tmp/opencode/venv` (activate it) to get DuckDB's REAL
  error message when the JDBC wrapper says the unhelpful "Attempting to execute an
  unsuccessful operation" (= a bind/catalog failure).

## State at handoff (updated 2026-07-13) — WORKLIST COMPLETE
- brikk-house committed through `474cd50` (batches 1–10). **duckdb→doris 186 pairs**
  (80 identical / 55 divergent / 44 conditionally-equivalent / 6 unclear / 1 no-equivalent);
  **trino→doris 168 pairs** (83 identical / 35 divergent / 44 conditionally-equivalent /
  3 unclear / 3 no-equivalent). Report has `#batch3-numeric` … `#batch10-round-substring`
  sections. Registry regenerated (`tools/generate_hazards_registry.py`); tree committed, NOT
  pushed. Every bucket-A same-name function in `doris-probe-worklist.md` now has a verdict on
  both sides.
- **doris-focus: harness deleted; cluster DOWN; tree clean at original commit `4574124`;
  `:doris-ducklake:test :doris-ducklake:detekt` = BUILD SUCCESSFUL.** Nothing to restore.
- **Residual follow-ups (not blockers):** the 6/3 `unclear` verdicts are DuckDB table
  functions (`json_each`, `query`, `parquet_*`, `unnest`) needing a table-function harness,
  plus a few trino-side edges (`log` single-arg, `url_encode` space, `round` negative `.5`,
  `substring` start=0) whose provenance says "Trino from prior evidence" — a **live-Trino
  re-probe** in the trino project should confirm them. See the REPORT's "Worklist status /
  continuation — COMPLETE" section.

---

# ADDENDUM — Buckets B + C-real + unclears (next scope, ~118 pairs)

Bucket A (same-name, 186 duckdb + 168 trino) is COMPLETE. This addendum scopes
the meaningful remainder. Same repos/paths/harness/method/schema as above.

## Scope
- **Bucket B (~92): cross-name mappings** — functions certify *actively translates*
  to a DIFFERENT Doris name (e.g. duckdb `list_extract` → doris `element_at`).
- **Bucket C-real (~16):** the genuinely probe-worthy conditional cases (whatever
  batch-A classified as C-real, not the D noise).
- **~10 residual `unclear` verdicts** from batch A (6 duckdb-side + 3 trino-side):
  find every row with `"verdict":"unclear"` in the two hazard JSONs and resolve it.
- **OUT of scope:** Bucket D (~615, no-equivalent / no mapping) and the reverse
  directions (doris→duckdb, doris→trino) — not transpile paths we use.

## Why B matters MORE than it looks (the framing)
For same-name (A), certify passthrough-flags an unverified node. For **cross-name
(B), certify has a *gate-verified translation* — it emits the rename CONFIDENTLY
and does NOT raise a hazard.** So a semantic divergence in a B mapping is a
**confident-but-wrong translation = a brikk-sql generator BUG**, not just a
missing registry row. When you find one: (a) add the hazard row AND (b) **file it
to brikk-sql as a generator mapping bug** (note it in the REPORT + flag for the
maintainer) — that's the higher-value output here.

## How to enumerate B (certify reveals the mapping — no separate worklist needed)
For each candidate DuckDB scalar function `f` (from `gap-report.json`, or the
duckdb function catalog in `brikk-sql-metadata`):
1. `val r = SqlFragment("SELECT f(<args>)","duckdb").certify("doris", desugarPipes=true)`
2. Inspect `r.result.sql`. If it rewrote `f(...)` to a DIFFERENT doris function
   name `g(...)` (and `r.ok`, no existing hazard), that's a **bucket-B pair
   (f→g)** to verify. (Same name → already covered by A; unmappable/refused →
   not B.)
3. Differential-probe the ACTUAL mapping: run the DuckDB `f(...)` and the Doris
   `g(...)` (from `r.result.sql`) via the harness, compare.
4. Row schema is unchanged — the names just differ:
   `{"duckdb":"f","doris":"g","verdict":...,"hazard":...,"areas":[...],"provenance":...}`.
   (Trino side: same, comparing Doris `g` to the documented Trino behavior.)

Tip: you can batch step 1 for many functions in one certify loop (offline, no
cluster), collect the (f→g) rename pairs, then differential-probe them (cluster)
in one harness run.

## Method / discipline (unchanged)
Same harness (recreate, DELETE before finishing), same divergence-pressure inputs
per the function's areas, same live DuckDB↔Doris + Doris-vs-prior-Trino
adjudication, same `python3 tools/generate_hazards_registry.py` + `git diff
--exit-code` CI check, same commit-incrementally-in-brikk-house rule. Heuristic
still holds (Doris≈Trino, diverges from DuckDB) but VERIFY — a B rename is exactly
where the generator author's assumption might be wrong.

## Definition of done
Every B mapping and C-real pair has a verdict; zero `unclear` remain in either
hazard JSON (or each surviving `unclear` has a one-line reason why it can't be
resolved live); registry regenerated; any B-mapping divergence filed to brikk-sql
as a generator bug. Bucket D + reverse remain untouched (documented as out of scope).

---

# DATA CAPTURE (MANDATORY) — the raw matrix is the reuse asset, do not lose it

Raw probe data written only to `/tmp/opencode/probe-*.txt|tsv` is **overwritten
every batch and lost on reboot** — yet the per-engine input→output matrix is
exactly what makes a FUTURE engine O(N) instead of a full re-probe (see the
behavior-matrix discussion). So:

1. **Persist every batch durably, before the next batch.** Write into the repo,
   not just /tmp:
   - `docs/research/probe-runs/<UTC-ts>-<label>.batch`  — the `fn<TAB>expr` inputs
   - `docs/research/probe-runs/<UTC-ts>-<label>.tsv`     — the FULL results
     (`fn\texpr\tSAME|DIFF\tduckdb\tdoris`, codepoint-rendered — never truncate)
   Commit them alongside the verdict rows. Unique-name per batch — NEVER overwrite.
2. **Stamp engine versions** once per run into the run dir + REPORT:
   Doris `SELECT version()`, DuckDB `PRAGMA version` / `pragma_version()`, Trino
   version. Behavior is version-specific; unstamped verdicts go stale silently.
3. **Pin + record session state** for datetime/timezone probes: `SET time_zone`
   (Doris) / `SET TimeZone` (DuckDB) to UTC on BOTH before probing, and record the
   pin. Otherwise those results are non-reproducible / not apples-to-apples.
4. **FINAL HARVEST (end of run):** sweep anything left in `/tmp/opencode/probe-*`
   into `docs/research/probe-runs/`, confirm nothing precious remains only in /tmp,
   commit. Then (optional, recommended) consolidate all run TSVs into the
   structured `brikk-sql/testResources/semantics/results/{engine}.json` behavior
   matrix so pairwise hazards become a derived artifact and the Nth engine is cheap.

Rule of thumb: if a probe result exists only in /tmp, it is NOT captured yet.
