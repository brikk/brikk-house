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

## State at handoff
- brikk-house committed `738159b` (batches 1–2: 21+21 pairs, report, regenerated registry).
- doris-focus: 0.3.0 migration + inert DorisVerifier pushed to `origin/doris-focus`; tree clean; cluster **down**.
