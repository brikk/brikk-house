package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Gate: every SQL in brikk-sql/testResources/ast-corpus/<dialect>-serde.json is re-parsed
 * and re-generated through brikk's own dialect pipeline, and the *actual* output is fed to
 * the target engine's own parser via [SqlVerifiers]. A reject means the engine's native
 * grammar does not accept SQL we emit for it — a real dialect bug.
 *
 * (The corpora's `generated` strings are the Python oracle's outputs; ours equal them
 * byte-for-byte except at the registered brikk extensions — docs/brikk-extensions.md —
 * several of which exist precisely to make the emitted SQL grammar-legal, so the gate must
 * verify what brikk actually emits, not the oracle's string.)
 *
 * Rejects must exactly match testResources/<dialect>-verify-known-failures.json, in both
 * directions: unledgered rejects fail the gate, and ledger entries that now pass are stale
 * and also fail the gate. The actual reject set is always written to
 * build/<dialect>-verify-ledger-actual.json for easy ledger regeneration.
 */
class VerifyCorpusGateTest {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    @Serializable
    private data class LedgerCase(val sql: String, val generated: String, val error: String)

    @Serializable
    private data class Ledger(val engine: String, val cases: List<LedgerCase>)

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TranspileCase(val sql: String, val write: Map<String, String> = emptyMap())

    @Serializable
    private data class DialectCorpus(val transpile: List<TranspileCase>)

    @Test
    fun dorisGeneratedSqlIsAcceptedByDorisParserModuloLedger() = runGate("doris")

    /**
     * Transpile-direction gate: for every dialect-corpus/doris.json `transpile[]` case with
     * a `write.doris` entry, the case's SQL is transpiled doris->doris through brikk —
     * exactly what a "transpile AND verify" editor action would hand to the engine. Same
     * ledger contract as the serde gate, with its own ledger file.
     */
    @Test
    fun dorisTranspileOutputsAreAcceptedByDorisParserModuloLedger() {
        val engine = "doris"
        val corpus = json.decodeFromString(
            DialectCorpus.serializer(),
            corpusResource("dialect-corpus/doris.json"),
        )
        val sqls = corpus.transpile.mapNotNull { case -> case.sql.takeIf { case.write.containsKey(engine) } }
        check(sqls.isNotEmpty()) { "no doris transpile outputs in dialect corpus" }
        val ledger = json.decodeFromString(
            Ledger.serializer(),
            ledgerResource("$engine-transpile-verify-known-failures.json"),
        )
        runGateOver(engine, "doris-transpile", sqls, ledger)
    }

    private fun verifierFor(engine: String): SqlVerifier =
        SqlOracles.forEngine(engine) ?: fail("no verifier available for engine $engine")

    @Test
    fun trinoGeneratedSqlIsAcceptedByTrinoParserModuloLedger() = runGate("trino")

    @Test
    fun duckdbGeneratedSqlIsAcceptedByDuckdbParserModuloLedger() = runGate("duckdb")

    /**
     * Postgres gate: re-generates postgres-serde.json through brikk's postgres pipeline and
     * feeds each output to a REAL embedded PostgreSQL, which discriminates grammar rejection
     * from catalog/semantic failure by SQLSTATE (see [PostgresVerifier]'s KDoc). Rejects here
     * are genuine dialect bugs OR a SQLSTATE we mis-partitioned; both are ledgered with reasons.
     *
     * [SqlOracles.forEngine] routes `"postgres"` to the fidelity embedded PostgreSQL oracle
     * (whereas [SqlVerifiers.forEngine] would return the advisory ShardingSphere verifier), so
     * this gate needs no special-case verifier.
     */
    @Test
    fun postgresGeneratedSqlIsAcceptedByPostgresParserModuloLedger() = runGate("postgres")

    private fun runGate(engine: String) {
        val corpus = json.decodeFromString(
            Corpus.serializer(),
            corpusResource("ast-corpus/$engine-serde.json"),
        )
        val ledger = json.decodeFromString(
            Ledger.serializer(),
            ledgerResource("$engine-verify-known-failures.json"),
        )
        check(corpus.cases.isNotEmpty()) { "empty $engine corpus" }
        runGateOver(engine, engine, corpus.cases.map { it.sql }, ledger)
    }

    /**
     * Each of [sqls] is re-generated through brikk's own dialect pipeline
     * (parse under [engine], generate under [engine]) and the result is verified against
     * the engine's native parser; the ledger is keyed by source sql.
     */
    private fun runGateOver(
        engine: String,
        label: String,
        sqls: List<String>,
        ledger: Ledger,
    ) {
        // SqlOracles routes postgres -> embedded PG and clickhouse -> chDB; trino/doris/duckdb
        // reuse the lightweight JVM parsers. Close it afterwards to stop any embedded engine.
        val verifier = verifierFor(engine)
        try {
            runGateWith(engine, label, sqls, ledger, verifier)
        } finally {
            (verifier as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }

    private fun runGateWith(
        engine: String,
        label: String,
        sqls: List<String>,
        ledger: Ledger,
        verifier: SqlVerifier,
    ) {
        check(ledger.engine == engine) { "ledger engine mismatch: ${ledger.engine} != $engine" }

        val dialect = Dialects.forName(engine)
        val ledgered = ledger.cases.associateBy { it.sql }
        // sql -> (generated, engine error)
        val failures = LinkedHashMap<String, Pair<String, String>>()
        var accepted = 0

        for (sourceSql in sqls) {
            val generated = try {
                dialect.generate(dialect.parseOne(sourceSql))
            } catch (e: Exception) {
                failures[sourceSql] = "" to
                    "brikk parse/generate failed: ${e::class.simpleName}: ${e.message?.take(140)}"
                continue
            }
            // The corpora mix full statements with bare-expression fixtures (e.g.
            // `DAYNAME(x)`), so try the engine's statement grammar first and fall back to its
            // expression grammar. A reject means neither native entry point accepts the SQL.
            val asStatement = runCatching { verifier.verify(generated) }.getOrElse { e ->
                VerifyResult(false, "${e::class.simpleName}: ${e.message}")
            }
            val result = if (asStatement.accepted) {
                asStatement
            } else {
                runCatching { verifier.verifyExpression(generated) }.getOrElse { e ->
                    VerifyResult(false, "${e::class.simpleName}: ${e.message}")
                }
            }
            if (result.accepted) {
                accepted += 1
            } else {
                val reason = "statement: ${summarize(asStatement)} | expression: ${summarize(result)}"
                failures[sourceSql] = generated to reason
            }
        }

        // Always write the actual reject set in ledger format for easy regeneration.
        val actualLedger = buildJsonObject {
            put("engine", engine)
            put("cases", buildJsonArray {
                for ((sql, generatedAndError) in failures) {
                    add(buildJsonObject {
                        put("sql", sql)
                        put("generated", generatedAndError.first)
                        put("error", generatedAndError.second)
                    })
                }
            })
        }
        val outDir = java.io.File("build").takeIf { it.isDirectory } ?: java.io.File(".")
        val actualFile = java.io.File(outDir, "$label-verify-ledger-actual.json")
        actualFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledgered.keys
        val stale = ledgered.keys - failures.keys

        println("VerifyCorpusGateTest[$label]: $accepted accepted / ${failures.size} ledgered (of ${sqls.size})")

        val problems = mutableListOf<String>()
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED rejects (showing up to 20):\n" +
                    unledgered.take(20).joinToString("\n") {
                        "  $it\n    generated: ${failures[it]?.first}\n    error: ${failures[it]?.second}"
                    }
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
                    "\n\nActual ledger written to ${actualFile.absolutePath}"
            )
        }
    }

    /** Engine error message trimmed for the ledger (Doris "expecting {...}" lists run to kilobytes). */
    private fun summarize(result: VerifyResult): String {
        val message = (result.error ?: "rejected without message").replace("\n", " ")
        val cut = message.take(160).let { if (message.length > 160) "$it…" else it }
        val position = result.line?.let { " (line ${result.line}, col ${result.col})" } ?: ""
        return cut + position
    }

    /** Cross-module corpus files, read from the filesystem relative to repo root or module dir. */
    private fun corpusResource(path: String): String {
        val candidates = listOf(
            java.io.File("brikk-sql/testResources/$path"),
            java.io.File("../brikk-sql/testResources/$path"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: fail("corpus $path not found; tried ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun ledgerResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql-oracle/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: fail("ledger $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }
}
