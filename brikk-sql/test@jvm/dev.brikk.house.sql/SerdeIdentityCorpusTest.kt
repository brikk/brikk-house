package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

/**
 * Gate: load/dump round-trip parity over Python dumps of the full identity.sql
 * fixture (ast-corpus/identity-serde.json, tools/gen_serde_corpus.py). For every
 * case, Serde.load(dump) -> Serde.dump(...) must reproduce the Python payloads
 * structurally UNSTRIPPED — meta and comments included — mirroring sqlglot's own
 * tests/test_serde.py round-trip.
 */
class SerdeIdentityCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadCorpus(): List<Pair<String, JsonArray>> {
        val stream = javaClass.classLoader.getResourceAsStream("ast-corpus/identity-serde.json")
            ?: java.io.File("brikk-sql/testResources/ast-corpus/identity-serde.json")
                .takeIf { it.exists() }
                ?.inputStream()
            ?: fail("corpus ast-corpus/identity-serde.json not found on classpath or filesystem")
        val root = stream.use { json.parseToJsonElement(it.readBytes().decodeToString()) }.jsonObject
        check(root.getValue("skipped").jsonArray.isEmpty()) { "corpus has python-side skips" }
        return root.getValue("cases").jsonArray.map { case ->
            val obj = case.jsonObject
            obj.getValue("sql").jsonPrimitive.content to obj.getValue("dump").jsonArray
        }
    }

    @Test
    fun loadDumpRoundTripsPythonDumps() {
        val corpus = loadCorpus()
        check(corpus.isNotEmpty()) { "empty corpus" }

        val failures = mutableListOf<String>()
        for ((sql, oracle) in corpus) {
            val roundTripped = try {
                Serde.dump(Serde.loadExpression(oracle))
            } catch (e: Throwable) {
                failures.add("$sql\n  threw: $e")
                continue
            }
            if (roundTripped != oracle) {
                val firstDiff = oracle.indices.firstOrNull { i ->
                    roundTripped.getOrNull(i) != oracle[i]
                }
                failures.add(
                    "$sql\n  first differing payload #$firstDiff" +
                        "\n  oracle: ${firstDiff?.let { oracle.getOrNull(it) }}" +
                        "\n  ours:   ${firstDiff?.let { roundTripped.getOrNull(it) }}"
                )
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size}/${corpus.size} round-trip failures (showing up to 15):\n\n" +
                    failures.take(15).joinToString("\n\n")
            )
        }
    }
}
