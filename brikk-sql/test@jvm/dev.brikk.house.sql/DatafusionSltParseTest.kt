package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Oracle-less parse-acceptance gate for the brikk-native `datafusion` dialect.
 *
 * DataFusion has no sqlglot dialect. As a real-engine backstop this gate replays a
 * curated subset of DataFusion's own sqllogictest suite (dialect-corpus/
 * datafusion-slt-parse.json, produced by tools/extract_datafusion_slt_corpus.py from
 * reference/datafusion — Apache-2.0, see ATTRIBUTIONS.md): SQL the real DataFusion
 * engine accepts. Each case must PARSE under "datafusion" without a ParseError.
 *
 * This is deliberately weaker than a round-trip gate — it only proves the BASE grammar
 * (which the thin datafusion dialect reuses unchanged) accepts DataFusion's SELECT
 * surface. Result equality / semantic round-trip is phase 2's engine verifier.
 *
 * HONEST CAVEAT: some real-engine SQL uses constructs brikk's BASE parser does not yet
 * model (Arrow-native types, `~` regex operators, engine-specific grammar). Those are
 * recorded in datafusion-slt-parse-known-failures.json with a reason — they are honest
 * parser gaps, not silent passes. The full actual failure set is written to build/.
 */
class DatafusionSltParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: fail("resource $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }

    private fun loadLedger(): Map<String, String> {
        val text = runCatching {
            resource("dialect-corpus/datafusion-slt-parse-known-failures.json")
        }.getOrNull() ?: return emptyMap()
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("case").jsonPrimitive.content to
                obj.getValue("reason").jsonPrimitive.content
        }
    }

    @Test
    fun sltParseAcceptanceModuloLedger() {
        val root = json.parseToJsonElement(resource("dialect-corpus/datafusion-slt-parse.json")).jsonObject
        val cases = root.getValue("cases").jsonArray
        check(cases.isNotEmpty()) { "empty SLT corpus" }
        val ledger = loadLedger()

        var ran = 0
        var passed = 0
        val failures = LinkedHashMap<String, String>() // "source" -> reason

        val df = Dialects.forName("datafusion")

        for (elem in cases) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val source = case.getValue("source").jsonPrimitive.content
            ran += 1
            val result = runCatching { df.parseOne(sql) }
            if (result.isSuccess && result.getOrNull() != null) {
                passed += 1
            } else {
                val e = result.exceptionOrNull()
                failures[source] = "${e?.let { it::class.simpleName } ?: "null-parse"}: " +
                    (e?.message?.take(140) ?: "parseOne returned null") +
                    "  |sql=${sql.replace("\n", " ").take(90)}"
            }
        }

        val actualLedger = buildJsonObject {
            put("cases", buildJsonArray {
                for ((key, reason) in failures) {
                    add(buildJsonObject {
                        put("case", key)
                        put("reason", reason)
                    })
                }
            })
        }
        val outDir = java.io.File("build").takeIf { it.isDirectory } ?: java.io.File(".")
        java.io.File(outDir, "datafusion-slt-parse-ledger-actual.json")
            .writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledger.keys
        val stale = ledger.keys - failures.keys
        val pct = if (ran == 0) 0.0 else passed * 100.0 / ran

        println(
            "DatafusionSltParseTest: $passed/$ran parsed (%.1f%%)".format(pct) +
                ", ${failures.size} ledgered"
        )

        val problems = mutableListOf<String>()
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED parse failures (showing up to 30):\n" +
                    unledgered.take(30).joinToString("\n") { "  $it\n    ${failures[it]}" }
            )
        }
        if (stale.isNotEmpty()) {
            problems.add(
                "${stale.size} STALE ledger entries now parse (showing up to 30):\n" +
                    stale.take(30).joinToString("\n") { "  $it" }
            )
        }
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n\n") +
                    "\n\nActual ledger written to ${java.io.File(outDir, "datafusion-slt-parse-ledger-actual.json").absolutePath}"
            )
        }
    }
}
