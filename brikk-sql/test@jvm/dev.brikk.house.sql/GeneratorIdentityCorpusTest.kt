package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.generator.Generator
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Gate B: for every ast-corpus case, Serde.load(dump) -> Generator().generate(ast) is
 * compared against the Python oracle output ("generated"). Failures must be ledgered in
 * testResources/generator-corpus/known-failures.json; ledgered cases that actually pass
 * are stale and also fail the test.
 */
class GeneratorIdentityCorpusTest {

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
            ?: java.io.File("brikk-sql/testResources/$path")
                .takeIf { it.exists() }
                ?.inputStream()
            ?: fail("resource $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }

    @Test
    fun identityCorpusMatchesPythonGeneratorModuloLedger() {
        val corpus = json.decodeFromString(Corpus.serializer(), resource("ast-corpus/identity-serde.json"))
        val ledger = json.decodeFromString(Ledger.serializer(), resource("generator-corpus/known-failures.json"))
        check(corpus.cases.isNotEmpty()) { "empty identity corpus" }

        val ledgered = ledger.cases.associateBy { it.sql }
        val unledgeredFailures = mutableListOf<String>()
        val staleLedgerEntries = mutableListOf<String>()
        var passed = 0
        val failedSqls = mutableSetOf<String>()

        for (case in corpus.cases) {
            val result = runCatching { Generator().generate(Serde.loadExpression(case.dump)) }
            val actual = result.getOrNull()

            if (actual == case.generated) {
                passed += 1
            } else {
                failedSqls.add(case.sql)
                if (case.sql !in ledgered) {
                    val reason = result.exceptionOrNull()
                        ?.let { "${it::class.simpleName}: ${it.message}" }
                        ?: "mismatch"
                    unledgeredFailures.add(
                        "SQL: ${case.sql}\n  reason: $reason\n  expected: ${case.generated}\n  actual:   $actual"
                    )
                }
            }
        }

        for (entry in ledger.cases) {
            if (entry.sql !in failedSqls) {
                staleLedgerEntries.add("stale ledger entry (now passes): ${entry.sql} [${entry.reason}]")
            }
        }

        println("GeneratorIdentityCorpus: $passed pass / ${ledger.cases.size} ledgered (of ${corpus.cases.size})")

        val problems = mutableListOf<String>()
        if (unledgeredFailures.isNotEmpty()) {
            problems.add(
                "${unledgeredFailures.size} unledgered failures (showing up to 40):\n" +
                    unledgeredFailures.take(40).joinToString("\n\n")
            )
        }
        if (staleLedgerEntries.isNotEmpty()) {
            problems.add(staleLedgerEntries.joinToString("\n"))
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
    }
}
