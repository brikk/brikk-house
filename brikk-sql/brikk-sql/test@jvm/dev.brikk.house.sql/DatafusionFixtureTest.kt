package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * The full actual failure set is always written to build/ for diagnosis. The committed
 * ledger is exact and currently empty: every imported identity and available transpile
 * case must pass.
 */
class DatafusionFixtureTest : LedgerGate() {

    @Test
    fun fixtureCorpusModuloLedger() {
        val root = json.parseToJsonElement(testResource("dialect-corpus/datafusion-fixtures.json")).jsonObject
        val identity = root.getValue("identity").jsonArray
        val transpile = root.getValue("transpile").jsonArray
        check(identity.isNotEmpty()) { "empty identity corpus" }
        val ledger = loadLedger("dialect-corpus/datafusion-fixtures-known-failures.json", "case")
        val assertionIds = CorpusAssertionIds("datafusion-fixtures")

        var identityRan = 0
        var identityPass = 0
        var transpileRan = 0
        var transpilePass = 0
        var skippedUnavailable = 0
        val failures = LinkedHashMap<String, CorpusFailure>()

        val df = Dialects.forName("datafusion")

        // identity: parse under datafusion, regenerate under datafusion == sql
        for (elem in identity) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val key = "identity|$sql"
            val assertionId = assertionIds.next(buildJsonObject {
                put("operation", "identity")
                put("read", "datafusion")
                put("write", "datafusion")
                put("case", JsonObject(case.filterKeys { it != "description" }))
                put("expected", sql)
            })
            identityRan += 1
            val failure = transpileAssertionFailure(
                key, JsonPrimitive(sql), parse = { df.parseOne(sql) }, generate = { df.generate(it) },
            )
            if (failure == null) {
                identityPass += 1
            } else {
                failures[assertionId] = failure
            }
        }

        // transpile: parse read_sql under read, generate under datafusion == sql
        for (elem in transpile) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val readDialect = case.getValue("read").jsonPrimitive.content
            val readSql = case.getValue("read_sql").jsonPrimitive.content
            val reader = CorpusDialects.resolveOrSkip(readDialect)
            if (reader == null) {
                skippedUnavailable += 1
                continue
            }
            val key = "transpile|$readDialect|$readSql"
            val assertionId = assertionIds.next(buildJsonObject {
                put("operation", "transpile")
                put("read", readDialect)
                put("write", "datafusion")
                put("case", JsonObject(case.filterKeys { it != "description" }))
                put("expected", sql)
            })
            transpileRan += 1
            val failure = transpileAssertionFailure(
                key, JsonPrimitive(sql), parse = { reader.parseOne(readSql) }, generate = { df.generate(it) },
            )
            if (failure == null) {
                transpilePass += 1
            } else {
                failures[assertionId] = failure
            }
        }

        val identityPct = if (identityRan == 0) 0.0 else identityPass * 100.0 / identityRan
        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "DatafusionFixtureTest: identity $identityPass/$identityRan " +
                "(%.1f%%)".format(identityPct) +
                ", transpile $transpilePass/$transpileRan, " +
                "${failures.size} ledgered, $skippedUnavailable transpile dirs skipped",
            actualLedgerName = "datafusion-fixtures-ledger-actual.json",
            caseKey = "case",
        )

        if (identityPct < 80.0) {
            fail("identity pass rate ${"%.1f".format(identityPct)}% is below the 80% target")
        }
    }
}
