package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared harness for the per-dialect transpile gates.
 *
 * Gate: dialect-corpus/<dialect>.json transpile section, run for every direction where both
 * dialects are registered in [Dialects]:
 *
 *  - read direction (sqlglot Validator.validate_all `read`): parse read_sql under the
 *    read dialect, generate under <dialect>, expect the case's canonical `sql`;
 *  - write direction (`write`): parse `sql` under <dialect>, generate under the write
 *    dialect, expect the recorded output — or an UnsupportedError marker.
 *
 * Directions whose dialect is not registered are counted and printed as skipped (not
 * failures). Genuine failures must match dialect-corpus/<dialect>-transpile-known-failures.json
 * — no unledgered failure, no stale ledger entry (see [LedgerGate]).
 *
 * Each dialect gets a concrete subclass (one line) so gates stay individually addressable
 * via `--include-classes` and report per-dialect in test output.
 */
abstract class TranspileCorpusGate(private val dialect: String) : LedgerGate() {

    @Test
    fun transpileCorpusModuloLedger() {
        val root = json.parseToJsonElement(testResource("dialect-corpus/$dialect.json")).jsonObject
        val transpile = root.getValue("transpile").jsonArray
        check(transpile.isNotEmpty()) { "empty transpile corpus" }
        val ledger = loadLedger("dialect-corpus/$dialect-transpile-known-failures.json", caseKey = "case")

        var ran = 0
        var passedCount = 0
        var skippedUnavailable = 0
        val failures = LinkedHashMap<String, String>() // "dir|dialect|sql" -> reason

        for (caseElem in transpile) {
            val case = caseElem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val pretty = (case["pretty"] as? JsonPrimitive)?.content == "true"

            // read direction: parseOne(read_sql, read_dialect).sql(dialect) == sql
            for ((readDialect, readValue) in (case["read"] as? JsonObject ?: emptyMap<String, JsonElement>())) {
                if (Dialects.forNameOrNull(readDialect) == null) {
                    skippedUnavailable += 1
                    continue
                }
                val readSql = (readValue as? JsonPrimitive)?.content ?: continue
                val key = "read|$readDialect|$sql"
                ran += 1
                val result = runCatching {
                    Dialects.forName(dialect).generate(Dialects.forName(readDialect).parseOne(readSql))
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

            // write direction: parseOne(sql, dialect) generated under write dialect
            for ((writeDialect, writeValue) in (case["write"] as? JsonObject ?: emptyMap<String, JsonElement>())) {
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
                    generator.generate(Dialects.forName(dialect).parseOne(sql))
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

        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "${javaClass.simpleName}: $passedCount pass / ${failures.size} ledgered (of $ran run), " +
                "$skippedUnavailable directions skipped (unavailable dialect)",
            actualLedgerName = "$dialect-transpile-ledger-actual.json",
            caseKey = "case",
        )
    }
}
