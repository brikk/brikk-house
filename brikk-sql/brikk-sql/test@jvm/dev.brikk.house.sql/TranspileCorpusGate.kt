package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun transpileAssertionDescriptor(
    namespace: String,
    direction: String,
    rawDialect: String,
    inputSQL: String,
    expected: JsonElement,
    pretty: Boolean,
    identify: Boolean,
): JsonObject = buildJsonObject {
    put("namespace", namespace)
    put("direction", direction)
    put("dialect", rawDialect)
    put("inputSQL", inputSQL)
    put("expected", buildJsonObject {
        if (expected is JsonPrimitive && expected.isString) put("sql", expected)
        else put("error", expected)
    })
    put("pretty", pretty)
    put("identify", identify)
}

/** Parse errors cannot satisfy generation expectations, even if warnings already exist. */
internal fun <T> transpileAssertionFailure(
    case: String,
    expected: JsonElement,
    parse: () -> T,
    generate: (T) -> String,
    unsupportedMessages: () -> List<String> = { emptyList() },
): CorpusFailure? {
    val expectsUnsupported = expected is JsonObject &&
        (expected["error"] as? JsonPrimitive)?.content == "UnsupportedError"
    val expression = try {
        parse()
    } catch (e: Exception) {
        return CorpusFailure.exception(case, "parse", e)
    }
    val actual = try {
        generate(expression)
    } catch (e: Exception) {
        return if (expectsUnsupported && e is UnsupportedError) null
        else CorpusFailure.exception(case, "generation", e)
    }
    if (expectsUnsupported) {
        return if (unsupportedMessages().isNotEmpty()) null
        else CorpusFailure.missingError(case, "UnsupportedError", actual)
    }
    val expectedSQL = expected.jsonPrimitive.content
    return if (actual == expectedSQL) null else CorpusFailure.sqlMismatch(case, expectedSQL, actual)
}

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
 * Direction names resolve through [CorpusDialects]: out-of-scope dialects are counted and
 * printed as skipped; version-qualified in-scope names (`"postgres, version=16"`) run under
 * the base dialect; anything else unresolvable fails the gate. Genuine failures must match
 * dialect-corpus/<dialect>-transpile-known-failures.json — no unledgered failure, no stale
 * ledger entry (see [LedgerGate]).
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
        var unextractable = 0
        var oracleFailed = 0
        val failures = LinkedHashMap<String, CorpusFailure>()
        val namespace = "transpile:$dialect"
        val ids = CorpusAssertionIds(namespace)

        for (caseElem in transpile) {
            val case = caseElem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val pretty = (case["pretty"] as? JsonPrimitive)?.content == "true"
            val identify = (case["identify"] as? JsonPrimitive)?.content == "true"

            // read direction: parseOne(read_sql, read_dialect).sql(dialect) == sql
            for ((readDialect, readValue) in (case["read"] as? JsonObject ?: emptyMap<String, JsonElement>())) {
                val reader = CorpusDialects.resolveOrSkip(readDialect)
                if (reader == null) {
                    skippedUnavailable += 1
                    continue
                }
                if (readValue !is JsonPrimitive || !readValue.isString) {
                    if (readValue is JsonObject && "error" in readValue) oracleFailed++ else unextractable++
                    continue
                }
                val readSql = readValue.content
                val key = "read|$readDialect|$sql"
                val expected = JsonPrimitive(sql)
                val id = ids.next(transpileAssertionDescriptor(
                    namespace, "read", readDialect, readSql, expected, pretty, identify,
                ))
                ran += 1
                val failure = transpileAssertionFailure(
                    key, expected,
                    parse = { reader.parseOne(readSql) },
                    // Preserve existing execution semantics: read generation ignores pretty/identify.
                    generate = { Dialects.forName(dialect).generate(it) },
                )
                if (failure == null) {
                    passedCount += 1
                } else {
                    failures[id] = failure
                }
            }

            // write direction: parseOne(sql, dialect) generated under write dialect
            for ((writeDialect, writeValue) in (case["write"] as? JsonObject ?: emptyMap<String, JsonElement>())) {
                val writer = CorpusDialects.resolveOrSkip(writeDialect)
                if (writer == null) {
                    skippedUnavailable += 1
                    continue
                }
                val key = "write|$writeDialect|$sql"
                val expectsError = writeValue is JsonObject &&
                    (writeValue["error"] as? JsonPrimitive)?.content == "UnsupportedError"
                if (!expectsError && (writeValue !is JsonPrimitive || !writeValue.isString)) {
                    if (writeValue is JsonObject && "error" in writeValue) oracleFailed++ else unextractable++
                    continue
                }
                val id = ids.next(transpileAssertionDescriptor(
                    namespace, "write", writeDialect, sql, writeValue, pretty, identify,
                ))
                ran += 1

                var generator: Generator? = null
                val failure = transpileAssertionFailure(
                    key, writeValue,
                    parse = { Dialects.forName(dialect).parseOne(sql) },
                    generate = { expression ->
                        writer.generator(pretty = pretty).also { generator = it }.generate(expression)
                    },
                    unsupportedMessages = { generator?.unsupportedMessages.orEmpty() },
                )
                if (failure == null) {
                    passedCount += 1
                } else {
                    failures[id] = failure
                }
            }
        }

        val totalDirections = transpile.sumOf { case ->
            listOf("read", "write").sumOf { (case.jsonObject[it] as? JsonObject)?.size ?: 0 }
        }
        check(ran + skippedUnavailable + unextractable + oracleFailed == totalDirections) { "Unaccounted transpile directions" }
        check(ran == passedCount + failures.size) { "Unaccounted transpile assertions or duplicate IDs" }
        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "${javaClass.simpleName}: $passedCount pass / ${ran - passedCount} failing executions " +
                "(${failures.size} ledger keys, of $ran run), " +
                "excluded=$skippedUnavailable (out-of-scope dialect), unextractable=$unextractable, oracle-failed=$oracleFailed",
            actualLedgerName = "$dialect-transpile-ledger-actual.json",
            caseKey = "case",
            sqlglotVersion = (root["sqlglot_version"] as? JsonPrimitive)?.content,
        )
    }
}
