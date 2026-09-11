package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * build/ledger-actual/<name>-annotate-ledger-actual.json for review.
 */
class AnnotateTypesCorpusTest : LedgerGate() {

    private fun loadCorpus(name: String): List<Pair<String, JsonArray>> {
        val text = testResourceOrNull("ast-corpus/$name.json")
            ?: fail("corpus ast-corpus/$name.json not found on classpath or filesystem")
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.map { case ->
            val obj = case.jsonObject
            obj.getValue("sql").jsonPrimitive.content to obj.getValue("dump").jsonArray
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
        val ledger = loadLedger("annotate-corpus/known-failures-$corpusName.json", "sql")
        val ids = CorpusAssertionIds("AnnotateTypesCorpusTest:$corpusName")

        val failures = LinkedHashMap<String, CorpusFailure>()
        val details = mutableListOf<String>()

        for ((sql, expectedDump) in cases) {
            val id = ids.next(buildJsonObject {
                put("sql", sql)
                put("expected", expectedDump)
                put("dialect", dialect)
                put("schema", JsonNull)
                put("expressionMetadata", JsonNull)
                put("coercesTo", JsonNull)
                put("overwriteTypes", true)
                put("comparison", "strip-comments-and-positions")
            })
            val expected = stripCommentsAndPositions(expectedDump)
            var phase = "parse"
            val actual = try {
                val expression = Dialects.forName(dialect).parseOne(sql)
                phase = "annotate"
                annotateTypes(expression, dialect = Dialects.forName(dialect))
                phase = "dump"
                stripCommentsAndPositions(Serde.dump(expression))
            } catch (e: Exception) {
                failures[id] = CorpusFailure.exception(sql, phase, e)
                continue
            }
            if (expected != actual) {
                failures[id] = CorpusFailure.mismatch(
                    sql, expected, actual, kind = "type-mismatch", reason = mismatchReason(expected, actual),
                )
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
            summary = "AnnotateTypesCorpusTest[$corpusName]: ${cases.size - failures.size} pass / " +
                "${failures.size} ledgered (of ${cases.size})",
            actualLedgerName = "$corpusName-annotate-ledger-actual.json",
            caseKey = "sql",
            mismatchDetails = details,
        )
    }

    @Test fun identityAnnotatedCorpus() = runCorpus("identity-annotated-serde", "")
    @Test fun mysqlAnnotatedCorpus() = runCorpus("mysql-annotated-serde", "mysql")
    @Test fun duckdbAnnotatedCorpus() = runCorpus("duckdb-annotated-serde", "duckdb")
    @Test fun postgresAnnotatedCorpus() = runCorpus("postgres-annotated-serde", "postgres")
    @Test fun prestoAnnotatedCorpus() = runCorpus("presto-annotated-serde", "presto")
    @Test fun trinoAnnotatedCorpus() = runCorpus("trino-annotated-serde", "trino")
    @Test fun dorisAnnotatedCorpus() = runCorpus("doris-annotated-serde", "doris")
    @Test fun starrocksAnnotatedCorpus() = runCorpus("starrocks-annotated-serde", "starrocks")
    @Test fun clickhouseAnnotatedCorpus() = runCorpus("clickhouse-annotated-serde", "clickhouse")
    @Test fun hiveAnnotatedCorpus() = runCorpus("hive-annotated-serde", "hive")

    // spark2 has no dialect-corpus fixture in sqlglot; the Spark2 annotator is gated
    // transitively through the hive and spark annotated corpora (Hive -> Spark2 -> Spark).
    @Test fun sparkAnnotatedCorpus() = runCorpus("spark-annotated-serde", "spark")

    @Test fun bigqueryAnnotatedCorpus() = runCorpus("bigquery-annotated-serde", "bigquery")
}
