package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gate: our parse -> annotateTypes -> dump must match Python's annotated dumps
 * (tools/gen_serde_corpus.py --annotate) over the base identity corpus and the
 * ported dialects' corpora, schema-less.
 *
 * Comparison strips comments ("o") and the parser POSITION meta keys
 * (line/col/start/end — our parser does not populate positions yet; see Serde.kt),
 * keeping every other meta key (e.g. the annotator's "nonnull") and, crucially, the
 * full "t" type payloads — those are the point of this gate.
 *
 * Failures must be exactly the ledgered ones (annotate-corpus/known-failures-<name>
 * .json); the actual failure set is always written to
 * build/<name>-annotate-ledger-actual.json for regeneration.
 */
class AnnotateTypesCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String? {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: return null
        return stream.use { it.readBytes().decodeToString() }
    }

    private fun loadCorpus(name: String): List<Pair<String, JsonArray>> {
        val text = resource("ast-corpus/$name.json")
            ?: fail("corpus ast-corpus/$name.json not found on classpath or filesystem")
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.map { case ->
            val obj = case.jsonObject
            obj.getValue("sql").jsonPrimitive.content to obj.getValue("dump").jsonArray
        }
    }

    private fun loadLedger(name: String): Map<String, String> {
        val text = resource("annotate-corpus/known-failures-$name.json") ?: return emptyMap()
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("sql").jsonPrimitive.content to
                obj.getValue("reason").jsonPrimitive.content
        }
    }

    /** Parser position meta keys — ours are not populated yet (see Serde.kt). */
    private val positionKeys = setOf("line", "col", "start", "end")

    /** Strips "o" everywhere and position keys inside "m" (dropping empty "m"). */
    private fun stripCommentsAndPositions(payloads: JsonArray): JsonArray = JsonArray(
        payloads.map { payload ->
            JsonObject(
                payload.jsonObject
                    .filterKeys { it != "o" }
                    .mapNotNull { (k, v) ->
                        when (k) {
                            "t" -> k to stripCommentsAndPositions(v.jsonArray)
                            "m" -> {
                                val kept = v.jsonObject.filterKeys { key -> key !in positionKeys }
                                if (kept.isEmpty()) null else k to JsonObject(kept)
                            }
                            else -> k to v
                        }
                    }
                    .toMap()
            )
        }
    )

    private fun mismatchReason(expected: JsonArray, actual: JsonArray): String {
        for (i in 0 until minOf(expected.size, actual.size)) {
            if (expected[i] != actual[i]) {
                val exp = expected[i].jsonObject
                val cls = (exp["c"] as? JsonPrimitive)?.content?.substringAfterLast(".")
                val key = (exp["k"] as? JsonPrimitive)?.content
                val expT = (expected[i].jsonObject["t"] as? JsonElement)?.toString()?.take(80)
                val actT = (actual[i].jsonObject["t"] as? JsonElement)?.toString()?.take(80)
                return "type-mismatch at #$i (${cls ?: "?"}${key?.let { " k=$it" } ?: ""}): " +
                    "expected t=$expT actual t=$actT"
            }
        }
        return "payload count expected=${expected.size} actual=${actual.size}"
    }

    private fun runCorpus(corpusName: String, dialect: String) {
        val cases = loadCorpus(corpusName)
        check(cases.isNotEmpty()) { "empty corpus $corpusName" }
        val ledger = loadLedger(corpusName)

        val failures = LinkedHashMap<String, String>()
        val details = mutableListOf<String>()

        for ((sql, expectedDump) in cases) {
            val expected = stripCommentsAndPositions(expectedDump)
            val actual = try {
                val expression = Dialects.forName(dialect).parseOne(sql)
                annotateTypes(expression, dialect = Dialects.forName(dialect))
                stripCommentsAndPositions(Serde.dump(expression))
            } catch (e: Exception) {
                failures[sql] = "${e::class.simpleName}: ${e.message?.take(140)}"
                continue
            }
            if (expected != actual) {
                failures[sql] = mismatchReason(expected, actual)
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

        val actualLedger = buildJsonObject {
            put("cases", buildJsonArray {
                for ((sql, reason) in failures) {
                    add(buildJsonObject {
                        put("sql", sql)
                        put("reason", reason)
                    })
                }
            })
        }
        val outDir = java.io.File("build").takeIf { it.isDirectory } ?: java.io.File(".")
        val actualFile = java.io.File(outDir, "$corpusName-annotate-ledger-actual.json")
        actualFile.writeText(
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger)
        )

        val unledgered = failures.keys - ledger.keys
        val stale = ledger.keys - failures.keys

        println(
            "AnnotateTypesCorpusTest[$corpusName]: ${cases.size - failures.size} pass / " +
                "${failures.size} ledgered (of ${cases.size})"
        )

        val problems = mutableListOf<String>()
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED failures (showing up to 20):\n" +
                    unledgered.take(20).joinToString("\n") { "  $it\n    reason: ${failures[it]}" }
            )
            val shown = details.filter { d -> unledgered.any { d.startsWith("SQL: $it\n") } }
            if (shown.isNotEmpty()) {
                problems.add("mismatch details (up to 8):\n" + shown.take(8).joinToString("\n\n"))
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

    @Test fun identityAnnotatedCorpus() = runCorpus("identity-annotated-serde", "")
    @Test fun mysqlAnnotatedCorpus() = runCorpus("mysql-annotated-serde", "mysql")
    @Test fun duckdbAnnotatedCorpus() = runCorpus("duckdb-annotated-serde", "duckdb")
    @Test fun postgresAnnotatedCorpus() = runCorpus("postgres-annotated-serde", "postgres")
    @Test fun prestoAnnotatedCorpus() = runCorpus("presto-annotated-serde", "presto")
    @Test fun trinoAnnotatedCorpus() = runCorpus("trino-annotated-serde", "trino")
    @Test fun dorisAnnotatedCorpus() = runCorpus("doris-annotated-serde", "doris")
}
