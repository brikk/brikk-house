package dev.brikk.house.sql

import java.io.File
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Base for corpus gates that enforce a two-directional known-failures ledger:
 *
 *  - every genuine failure must appear in the ledger (no UNLEDGERED failures);
 *  - every ledger entry must still fail (no STALE entries).
 *
 * Subclasses run their case loop, collect `failures` (case key -> reason), and call
 * [enforceLedger], which also writes `build/<name>-ledger-actual.json` in ledger format
 * so a ledger can be regenerated from an actual run.
 *
 * TODO(test-dedup): the one-off ledger-style gates (ParserIdentityCorpusTest,
 * GeneratorIdentityCorpusTest, DatafusionSltParseTest, DatafusionFixtureTest,
 * LineageCorpusTest, QualifyCorpusTest, ScopeCorpusTest) still hand-roll their own
 * unledgered/stale diff with per-file ledger formats and summary shapes. Folding them
 * onto this base needs case-by-case verification (differing ledger keys, extra checks,
 * no sqlglot_version in some) — not a mechanical transform like the per-dialect gates.
 */
abstract class LedgerGate {

    protected val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads a known-failures ledger whose cases are keyed by [caseKey] ("sql" or "case").
     *
     * A missing ledger file is treated as an empty ledger: every failure then reports as
     * UNLEDGERED, so absence still fails loudly rather than being silently tolerated.
     * (Historically the parser gates tolerated a missing ledger and the transpile gates
     * did not; this is the single, documented semantics for all gates.)
     */
    protected fun loadLedger(path: String, caseKey: String): Map<String, String> {
        val text = testResourceOrNull(path) ?: return emptyMap()
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue(caseKey).jsonPrimitive.content to
                obj.getValue("reason").jsonPrimitive.content
        }
    }

    /**
     * Writes `build/`[actualLedgerName] in ledger format (cases keyed by [caseKey],
     * prefixed with `sqlglot_version` when [sqlglotVersion] is given), prints [summary],
     * and fails on any unledgered failure or stale ledger entry.
     *
     * [mismatchDetails] entries must start with `"SQL: <case>\n"`; details belonging to
     * unledgered failures are appended to the failure message (up to 10).
     */
    protected fun enforceLedger(
        ledger: Map<String, String>,
        failures: Map<String, String>,
        summary: String,
        actualLedgerName: String,
        caseKey: String,
        sqlglotVersion: String? = null,
        mismatchDetails: List<String> = emptyList(),
    ) {
        // Always write the actual failure set in ledger format for easy regeneration.
        val actualLedger = buildJsonObject {
            if (sqlglotVersion != null) put("sqlglot_version", sqlglotVersion)
            put("cases", buildJsonArray {
                for ((key, reason) in failures) {
                    add(buildJsonObject {
                        put(caseKey, key)
                        put("reason", reason)
                    })
                }
            })
        }
        val outDir = File("build").takeIf { it.isDirectory } ?: File(".")
        val actualFile = File(outDir, actualLedgerName)
        actualFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledger.keys
        val stale = ledger.keys - failures.keys

        println(summary)

        val problems = mutableListOf<String>()
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED failures (showing up to 20):\n" +
                    unledgered.take(20).joinToString("\n") { "  $it\n    reason: ${failures[it]}" }
            )
            val shown = mismatchDetails.filter { d -> unledgered.any { d.startsWith("SQL: $it\n") } }
            if (shown.isNotEmpty()) {
                problems.add("mismatch details (up to 10):\n" + shown.take(10).joinToString("\n\n"))
            }
        }
        if (stale.isNotEmpty()) {
            problems.add(
                "${stale.size} STALE ledger entries now pass (showing up to 20):\n" +
                    stale.take(20).joinToString("\n") { "  $it" }
            )
        }
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n\n") +
                    "\n\nActual ledger written to ${actualFile.absolutePath}"
            )
        }
    }
}
