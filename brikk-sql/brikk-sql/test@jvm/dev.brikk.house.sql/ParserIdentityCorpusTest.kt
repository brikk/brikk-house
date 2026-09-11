package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gate: our parseOne(sql) over the full identity.sql fixture must match Python
 * sqlglot's ASTs (ast-corpus/identity-serde.json) UNSTRIPPED — including meta
 * (position keys line/col/start/end) and comments. Failures must be exactly the cases ledgered in
 * parser-corpus/known-failures.json — no unledgered failure, no stale ledger entry.
 *
 * The test always writes the *actual* current failure set (in ledger format) to
 * build/ledger-actual/parser-identity-ledger-actual.json for review.
 */
class ParserIdentityCorpusTest : LedgerGate() {

    private fun loadCorpus(): Pair<String, List<Pair<String, JsonArray>>> {
        val root = json.parseToJsonElement(testResource("ast-corpus/identity-serde.json")).jsonObject
        val version = root.getValue("sqlglot_version").jsonPrimitive.content
        val cases = root.getValue("cases").jsonArray.map { case ->
            val obj = case.jsonObject
            obj.getValue("sql").jsonPrimitive.content to obj.getValue("dump").jsonArray
        }
        return version to cases
    }

    /** Derives a cluster-friendly reason from a structural mismatch. */
    private fun mismatchReason(expected: JsonArray, actual: JsonArray): String {
        for (i in 0 until minOf(expected.size, actual.size)) {
            if (expected[i] != actual[i]) {
                val exp = expected[i].jsonObject
                val cls = (exp["c"] as? JsonPrimitive)?.content?.substringAfterLast(".")
                val key = (exp["k"] as? JsonPrimitive)?.content
                return "ast-mismatch at #$i: expected " +
                    (cls?.let { "$it${key?.let { k -> " (k=$k)" } ?: ""}" }
                        ?: exp.toString().take(120))
            }
        }
        return "ast-mismatch: payload count expected=${expected.size} actual=${actual.size}"
    }

    @Test
    fun identityCorpusMatchesPythonAstsModuloLedger() {
        val (version, cases) = loadCorpus()
        check(cases.isNotEmpty()) { "empty corpus" }
        val ledger = loadLedger("parser-corpus/known-failures.json", "sql")
        val ids = CorpusAssertionIds("ParserIdentityCorpusTest:identity-serde")

        val failures = LinkedHashMap<String, CorpusFailure>()
        val details = mutableListOf<String>()

        for ((sql, expectedDump) in cases) {
            val id = ids.next(buildJsonObject {
                put("sql", sql)
                put("expected", expectedDump)
                put("dialect", "")
                put("comparison", "unstripped")
            })
            // Fully unstripped: positions (meta) and comments must match Python's dumps.
            val expected = expectedDump
            var phase = "parse"
            val actual = try {
                val expression = parseOne(sql)
                phase = "dump"
                Serde.dump(expression)
            } catch (e: Exception) {
                failures[id] = CorpusFailure.exception(sql, phase, e)
                continue
            }
            if (expected != actual) {
                failures[id] = CorpusFailure.mismatch(sql, expected, actual, reason = mismatchReason(expected, actual))
                val firstDiff = (0 until minOf(expected.size, actual.size))
                    .firstOrNull { expected[it] != actual[it] }
                    ?: minOf(expected.size, actual.size)
                details.add(
                    "SQL: $sql\n  first diff #$firstDiff\n" +
                        "  expected: ${expected.getOrNull(firstDiff)}\n" +
                        "  actual:   ${actual.getOrNull(firstDiff)}"
                )
            }
        }

        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "ParserIdentityCorpusTest: ${cases.size - failures.size} pass / ${failures.size} ledgered (of ${cases.size})",
            actualLedgerName = "parser-identity-ledger-actual.json",
            caseKey = "sql",
            sqlglotVersion = version,
            mismatchDetails = details,
        )
    }
}
