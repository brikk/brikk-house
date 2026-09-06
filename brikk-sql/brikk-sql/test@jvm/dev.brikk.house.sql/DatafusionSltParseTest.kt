package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
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
class DatafusionSltParseTest : LedgerGate() {

    @Test
    fun sltParseAcceptanceModuloLedger() {
        val root = json.parseToJsonElement(testResource("dialect-corpus/datafusion-slt-parse.json")).jsonObject
        val cases = root.getValue("cases").jsonArray
        check(cases.isNotEmpty()) { "empty SLT corpus" }
        val ledger = loadLedger("dialect-corpus/datafusion-slt-parse-known-failures.json", "case")
        val assertionIds = CorpusAssertionIds("datafusion-slt-parse")

        var ran = 0
        var passed = 0
        val failures = LinkedHashMap<String, CorpusFailure>()

        val df = Dialects.forName("datafusion")

        for (elem in cases) {
            val case = elem.jsonObject
            val sql = case.getValue("sql").jsonPrimitive.content
            val source = case.getValue("source").jsonPrimitive.content
            val assertionId = assertionIds.next(buildJsonObject {
                put("operation", "parseOne")
                put("dialect", "datafusion")
                put("case", JsonObject(case.filterKeys { it != "source" }))
                put("expected", "non-null parse without exception")
            })
            ran += 1
            val failure = try {
                df.parseOne(sql)
                null
            } catch (e: Exception) {
                CorpusFailure.exception(source, "parseOne", e)
            }
            if (failure == null) {
                passed += 1
            } else {
                failures[assertionId] = failure
            }
        }

        val pct = if (ran == 0) 0.0 else passed * 100.0 / ran

        enforceLedger(
            ledger = ledger,
            failures = failures,
            summary = "DatafusionSltParseTest: $passed/$ran parsed (%.1f%%)".format(pct) +
                ", ${failures.size} ledgered",
            actualLedgerName = "datafusion-slt-parse-ledger-actual.json",
            caseKey = "case",
        )
    }
}
