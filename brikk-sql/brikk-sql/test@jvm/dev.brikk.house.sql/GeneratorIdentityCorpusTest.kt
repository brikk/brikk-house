package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.generator.Generator
import kotlin.test.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gate B: for every ast-corpus case, Serde.load(dump) -> Generator().generate(ast) is
 * compared against the Python oracle output ("generated"). Failures must be ledgered in
 * testResources/generator-corpus/known-failures.json; ledgered cases that actually pass
 * are stale and also fail the test.
 */
class GeneratorIdentityCorpusTest : LedgerGate() {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String, val dump: JsonArray)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    @Test
    fun identityCorpusMatchesPythonGeneratorModuloLedger() {
        val corpus = json.decodeFromString(Corpus.serializer(), testResource("ast-corpus/identity-serde.json"))
        val ledger = loadLedger("generator-corpus/known-failures.json", "sql")
        check(corpus.cases.isNotEmpty()) { "empty identity corpus" }

        val ids = CorpusAssertionIds("GeneratorIdentityCorpusTest:identity-serde")
        val failures = LinkedHashMap<String, CorpusFailure>()
        val details = mutableListOf<String>()
        var passed = 0

        for (case in corpus.cases) {
            val id = ids.next(buildJsonObject {
                put("sql", case.sql)
                put("dump", case.dump)
                put("expected", case.generated)
                put("dialect", "")
                put("options", buildJsonObject {
                    put("generator", "default")
                    put("copy", true)
                })
            })
            var phase = "load"
            val actual = try {
                val expression = Serde.loadExpression(case.dump)
                phase = "generation"
                Generator().generate(expression)
            } catch (e: Exception) {
                failures[id] = CorpusFailure.exception(case.sql, phase, e)
                continue
            }

            if (actual == case.generated) {
                passed += 1
            } else {
                val failure = CorpusFailure.sqlMismatch(case.sql, case.generated, actual)
                failures[id] = failure
                details.add(
                    "SQL: ${case.sql}\n  reason: ${failure.reason}\n  expected: ${case.generated}\n  actual:   $actual"
                )
            }
        }

        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "GeneratorIdentityCorpus: $passed pass / ${ledger.size} ledgered (of ${corpus.cases.size})",
            actualLedgerName = "generator-identity-ledger-actual.json",
            caseKey = "sql",
            mismatchDetails = details,
        )
    }
}
