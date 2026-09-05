package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.generator.Generator
import kotlin.test.Test
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * Shared harness for the per-dialect generator gates.
 *
 * Gate: for every ast-corpus/<dialect>-serde.json case, Serde.load(dump) ->
 * generator.generate(ast) is compared against Python's `.sql(dialect=<dialect>)` output
 * ("generated"). Failures must exactly match
 * generator-corpus/<dialect>-generator-known-failures.json — no unledgered failure,
 * no stale ledger entry (see [LedgerGate]).
 *
 * The generator is supplied as a factory so each case runs on a fresh instance of the
 * dialect's concrete generator class (matching how the original per-dialect gates
 * instantiated e.g. `TrinoGenerator()` directly, rather than going through [Dialects]).
 *
 * Each dialect gets a concrete subclass (one line) so gates stay individually addressable
 * via `--include-classes` and report per-dialect in test output.
 */
abstract class GeneratorCorpusGate(
    private val dialect: String,
    private val generatorFactory: () -> Generator,
) : LedgerGate() {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String, val dump: JsonArray)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    @Test
    fun corpusMatchesPythonGeneratorModuloLedger() {
        val corpus = json.decodeFromString(Corpus.serializer(), testResource("ast-corpus/$dialect-serde.json"))
        val ledger = loadLedger("generator-corpus/$dialect-generator-known-failures.json", caseKey = "sql")
        check(corpus.cases.isNotEmpty()) { "empty $dialect corpus" }

        val failures = LinkedHashMap<String, String>() // sql -> reason
        var passed = 0

        for (case in corpus.cases) {
            val result = runCatching { generatorFactory().generate(Serde.loadExpression(case.dump)) }
            val actual = result.getOrNull()

            if (actual == case.generated) {
                passed += 1
            } else {
                failures[case.sql] = result.exceptionOrNull()?.let { e ->
                    "${e::class.simpleName}: ${e.message?.take(140)}"
                } ?: "output mismatch: expected `${case.generated.take(120)}` actual `${actual?.take(120)}`"
            }
        }

        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "${javaClass.simpleName}: $passed pass / ${failures.size} ledgered (of ${corpus.cases.size})",
            actualLedgerName = "$dialect-generator-ledger-actual.json",
            caseKey = "sql",
            sqlglotVersion = corpus.sqlglot_version,
        )
    }
}
