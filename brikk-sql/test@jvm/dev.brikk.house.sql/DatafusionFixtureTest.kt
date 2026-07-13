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
 * Oracle-less gate for the brikk-native `datafusion` dialect.
 *
 * DataFusion has NO sqlglot dialect, so there is no Python oracle to diff against.
 * This gate instead runs the hand-authored polyglot fixture corpus
 * (dialect-corpus/datafusion-fixtures.json, imported by
 * tools/import_polyglot_datafusion_fixtures.py — MIT, see ATTRIBUTIONS.md):
 *
 *  - identity: parse `sql` under "datafusion", regenerate under "datafusion"; the
 *    round-trip must reproduce `sql` exactly (both directions matter — a parse that
 *    silently drops structure would still be a failure here).
 *  - transpile: parse `read_sql` under `read`, generate under "datafusion"; must equal
 *    the datafusion-canonical `sql`. Directions whose read dialect is unregistered in
 *    brikk are skipped (counted, not failed).
 *
 * HONEST CAVEAT: polyglot's DataFusion dialect semantics do not perfectly coincide
 * with brikk's sqlglot-derived AST/generator (e.g. type spelling, quoting, star-modifier
 * rendering). Genuine, understood mismatches are recorded in
 * datafusion-fixtures-known-failures.json with a reason; the target is >=80% identity
 * pass. The full actual failure set is always written to build/ for easy regeneration.
 */
class DatafusionFixtureTest {

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
            resource("dialect-corpus/datafusion-fixtures-known-failures.json")
        }.getOrNull() ?: return emptyMap()
        val root = json.parseToJsonElement(text).jsonObject
        return root.getValue("cases").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("case").jsonPrimitive.content to
                obj.getValue("reason").jsonPrimitive.content
        }
    }

    @Test
    fun fixtureCorpusModuloLedger() {
        val root = json.parseToJsonElement(resource("dialect-corpus/datafusion-fixtures.json")).jsonObject
        val identity = root.getValue("identity").jsonArray
        val transpile = root.getValue("transpile").jsonArray
        check(identity.isNotEmpty()) { "empty identity corpus" }
        val ledger = loadLedger()

        var identityRan = 0
        var identityPass = 0
        var transpileRan = 0
        var transpilePass = 0
        var skippedUnavailable = 0
        val failures = LinkedHashMap<String, String>()

        val df = Dialects.forName("datafusion")

        // identity: parse under datafusion, regenerate under datafusion == sql
        for (elem in identity) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val key = "identity|$sql"
            identityRan += 1
            val result = runCatching { df.generate(df.parseOne(sql)) }
            val actual = result.getOrNull()
            if (actual == sql) {
                identityPass += 1
            } else {
                failures[key] = result.exceptionOrNull()?.let { e ->
                    "${e::class.simpleName}: ${e.message?.take(140)}"
                } ?: "expected `$sql` actual `$actual`"
            }
        }

        // transpile: parse read_sql under read, generate under datafusion == sql
        for (elem in transpile) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val readDialect = case.getValue("read").jsonPrimitive.content
            val readSql = case.getValue("read_sql").jsonPrimitive.content
            if (Dialects.forNameOrNull(readDialect) == null) {
                skippedUnavailable += 1
                continue
            }
            val key = "transpile|$readDialect|$readSql"
            transpileRan += 1
            val result = runCatching { df.generate(Dialects.forName(readDialect).parseOne(readSql)) }
            val actual = result.getOrNull()
            if (actual == sql) {
                transpilePass += 1
            } else {
                failures[key] = result.exceptionOrNull()?.let { e ->
                    "${e::class.simpleName}: ${e.message?.take(140)}"
                } ?: "expected `$sql` actual `$actual`"
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
        java.io.File(outDir, "datafusion-fixtures-ledger-actual.json")
            .writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledger.keys
        val stale = ledger.keys - failures.keys

        val identityPct = if (identityRan == 0) 0.0 else identityPass * 100.0 / identityRan
        println(
            "DatafusionFixtureTest: identity $identityPass/$identityRan " +
                "(%.1f%%)".format(identityPct) +
                ", transpile $transpilePass/$transpileRan, " +
                "${failures.size} ledgered, $skippedUnavailable transpile dirs skipped"
        )

        val problems = mutableListOf<String>()
        if (identityPct < 80.0) {
            problems.add("identity pass rate ${"%.1f".format(identityPct)}% is below the 80% target")
        }
        if (unledgered.isNotEmpty()) {
            problems.add(
                "${unledgered.size} UNLEDGERED failures (showing up to 25):\n" +
                    unledgered.take(25).joinToString("\n") { "  $it\n    reason: ${failures[it]}" }
            )
        }
        if (stale.isNotEmpty()) {
            problems.add(
                "${stale.size} STALE ledger entries now pass (showing up to 25):\n" +
                    stale.take(25).joinToString("\n") { "  $it" }
            )
        }
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n\n") +
                    "\n\nActual ledger written to ${java.io.File(outDir, "datafusion-fixtures-ledger-actual.json").absolutePath}"
            )
        }
    }
}
