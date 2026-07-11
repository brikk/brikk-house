package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.PostgresGenerator
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gate: for every ast-corpus/postgres-serde.json case, Serde.load(dump) ->
 * PostgresGenerator().generate(ast) is compared against Python's `.sql(dialect="postgres")`
 * output ("generated"). Failures must exactly match
 * generator-corpus/postgres-generator-known-failures.json (both directions).
 */
class PostgresGeneratorCorpusTest {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String, val dump: JsonArray)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    @Serializable
    private data class LedgerCase(val sql: String, val reason: String)

    @Serializable
    private data class Ledger(val sqlglot_version: String, val cases: List<LedgerCase>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: fail("resource $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }

    @Test
    fun postgresCorpusMatchesPythonGeneratorModuloLedger() {
        val corpus = json.decodeFromString(Corpus.serializer(), resource("ast-corpus/postgres-serde.json"))
        val ledger = json.decodeFromString(
            Ledger.serializer(),
            resource("generator-corpus/postgres-generator-known-failures.json"),
        )
        check(corpus.cases.isNotEmpty()) { "empty postgres corpus" }

        val ledgered = ledger.cases.associateBy { it.sql }
        val failures = LinkedHashMap<String, String>()
        var passed = 0

        for (case in corpus.cases) {
            val result = runCatching { PostgresGenerator().generate(Serde.loadExpression(case.dump)) }
            val actual = result.getOrNull()

            if (actual == case.generated) {
                passed += 1
            } else {
                failures[case.sql] = result.exceptionOrNull()?.let { e ->
                    "${e::class.simpleName}: ${e.message?.take(140)}"
                } ?: "output mismatch: expected `${case.generated.take(120)}` actual `${actual?.take(120)}`"
            }
        }

        // Always write the actual failure set in ledger format for easy regeneration.
        val actualLedger = buildJsonObject {
            put("sqlglot_version", corpus.sqlglot_version)
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
        java.io.File(outDir, "postgres-generator-ledger-actual.json")
            .writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledgered.keys
        val stale = ledgered.keys - failures.keys

        println("PostgresGeneratorCorpusTest: $passed pass / ${failures.size} ledgered (of ${corpus.cases.size})")

        val problems = mutableListOf<String>()
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED failures (showing up to 20):\n" +
                    unledgered.take(20).joinToString("\n") { "  $it\n    reason: ${failures[it]}" }
            )
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
                    "\n\nActual ledger written to ${java.io.File(outDir, "postgres-generator-ledger-actual.json").absolutePath}"
            )
        }
    }
}
