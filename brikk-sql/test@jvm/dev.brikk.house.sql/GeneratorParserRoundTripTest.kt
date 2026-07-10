package dev.brikk.house.sql

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Gate A: for every parser-corpus case, parseOne(sql) -> Generator().sql(ast) must equal
 * the Python oracle's parse_one(sql).sql() output (the corpus "generated" field) exactly.
 */
class GeneratorParserRoundTripTest {

    @Serializable
    private data class OracleCase(val sql: String, val generated: String)

    @Serializable
    private data class Corpus(val sqlglot_version: String, val cases: List<OracleCase>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCorpus(name: String): Corpus {
        val stream = javaClass.classLoader.getResourceAsStream("parser-corpus/$name")
            ?: java.io.File("brikk-sql/testResources/parser-corpus/$name")
                .takeIf { it.exists() }
                ?.inputStream()
            ?: fail("corpus parser-corpus/$name not found on classpath or filesystem")
        return stream.use { json.decodeFromString(Corpus.serializer(), it.readBytes().decodeToString()) }
    }

    @Test
    fun baseCorpusRoundTripsThroughGenerator() {
        val corpus = loadCorpus("base.json")
        check(corpus.cases.isNotEmpty()) { "empty parser corpus" }

        val mismatches = mutableListOf<String>()

        for (case in corpus.cases) {
            val actual = try {
                Generator().generate(parseOne(case.sql))
            } catch (e: Exception) {
                mismatches.add("SQL: ${case.sql}\n  threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            if (actual != case.generated) {
                mismatches.add("SQL: ${case.sql}\n  expected: ${case.generated}\n  actual:   $actual")
            }
        }

        if (mismatches.isNotEmpty()) {
            fail(
                "${mismatches.size}/${corpus.cases.size} generator mismatches (showing up to 20):\n\n" +
                    mismatches.take(20).joinToString("\n\n")
            )
        }
    }
}
