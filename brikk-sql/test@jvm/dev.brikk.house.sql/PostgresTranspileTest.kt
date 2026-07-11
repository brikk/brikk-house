package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gate: dialect-corpus/postgres.json transpile section, run for every direction where both
 * dialects are registered in [Dialects] (currently "" and "postgres"):
 *
 *  - read direction (sqlglot Validator.validate_all `read`): parse read_sql under the
 *    read dialect, generate under postgres, expect the case's canonical `sql`;
 *  - write direction (`write`): parse `sql` under postgres, generate under the write
 *    dialect, expect the recorded output — or an UnsupportedError marker.
 *
 * Directions whose dialect is not registered are counted and printed as skipped (not
 * failures). Genuine failures must match dialect-corpus/postgres-transpile-known-failures.json.
 */
class PostgresTranspileTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: fail("resource $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }

    private fun loadLedger(): Map<String, String> {
        val root = json.parseToJsonElement(resource("dialect-corpus/postgres-transpile-known-failures.json")).jsonObject
        return root.getValue("cases").jsonArray.associate { entry ->
            val obj = entry.jsonObject
            obj.getValue("case").jsonPrimitive.content to
                obj.getValue("reason").jsonPrimitive.content
        }
    }

    @Test
    fun transpileCorpusModuloLedger() {
        val root = json.parseToJsonElement(resource("dialect-corpus/postgres.json")).jsonObject
        val transpile = root.getValue("transpile").jsonArray
        check(transpile.isNotEmpty()) { "empty transpile corpus" }
        val ledger = loadLedger()

        var ran = 0
        var passedCount = 0
        var skippedUnavailable = 0
        val failures = LinkedHashMap<String, String>() // "dir|dialect|sql" -> reason

        for (caseElem in transpile) {
            val case = caseElem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val pretty = (case["pretty"] as? JsonPrimitive)?.content == "true"

            // read direction: parseOne(read_sql, read_dialect).sql("postgres") == sql
            for ((readDialect, readValue) in (case["read"] as? JsonObject).orEmpty()) {
                if (Dialects.forNameOrNull(readDialect) == null) {
                    skippedUnavailable += 1
                    continue
                }
                val readSql = (readValue as? JsonPrimitive)?.content ?: continue
                val key = "read|$readDialect|$sql"
                ran += 1
                val result = runCatching {
                    Dialects.forName("postgres").generate(Dialects.forName(readDialect).parseOne(readSql))
                }
                val actual = result.getOrNull()
                if (actual == sql) {
                    passedCount += 1
                } else {
                    failures[key] = result.exceptionOrNull()?.let { e ->
                        "${e::class.simpleName}: ${e.message?.take(120)}"
                    } ?: "expected `$sql` actual `$actual`"
                }
            }

            // write direction: parseOne(sql, "postgres") generated under write dialect
            for ((writeDialect, writeValue) in (case["write"] as? JsonObject).orEmpty()) {
                if (Dialects.forNameOrNull(writeDialect) == null) {
                    skippedUnavailable += 1
                    continue
                }
                val key = "write|$writeDialect|$sql"
                ran += 1

                val expectsError = writeValue is JsonObject &&
                    (writeValue["error"] as? JsonPrimitive)?.content == "UnsupportedError"

                val generator = Dialects.forName(writeDialect).generator(pretty = pretty)
                val result = runCatching {
                    generator.generate(Dialects.forName("postgres").parseOne(sql))
                }

                if (expectsError) {
                    // sqlglot: unsupported_level=RAISE — our generator collects warnings
                    val raised = result.exceptionOrNull() is UnsupportedError ||
                        generator.unsupportedMessages.isNotEmpty()
                    if (raised) passedCount += 1
                    else failures[key] = "expected UnsupportedError, got `${result.getOrNull()}`"
                } else {
                    val expected = (writeValue as? JsonPrimitive)?.content
                    val actual = result.getOrNull()
                    if (actual == expected) {
                        passedCount += 1
                    } else {
                        failures[key] = result.exceptionOrNull()?.let { e ->
                            "${e::class.simpleName}: ${e.message?.take(120)}"
                        } ?: "expected `$expected` actual `$actual`"
                    }
                }
            }
        }

        // Always write the actual failure set in ledger format for easy regeneration.
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
        java.io.File(outDir, "postgres-transpile-ledger-actual.json")
            .writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))

        val unledgered = failures.keys - ledger.keys
        val stale = ledger.keys - failures.keys

        println(
            "PostgresTranspileTest: $passedCount pass / ${failures.size} ledgered (of $ran run), " +
                "$skippedUnavailable directions skipped (unavailable dialect)"
        )

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
                    "\n\nActual ledger written to ${java.io.File(outDir, "postgres-transpile-ledger-actual.json").absolutePath}"
            )
        }
    }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> =
        this ?: emptyMap()
}
