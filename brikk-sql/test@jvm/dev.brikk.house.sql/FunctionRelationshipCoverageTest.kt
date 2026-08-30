package dev.brikk.house.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pins gap-report coverage for StarRocks against every registered canonical dialect. */
class FunctionRelationshipCoverageTest {

    private val root = Json.parseToJsonElement(
        testResource("semantics/gap-report.json")
    ).jsonObject

    private val others = listOf(
        "sqlglot", "mysql", "doris", "presto", "trino", "duckdb", "postgres",
        "clickhouse", "hive", "spark2", "spark", "bigquery", "datafusion",
    )

    @Test
    fun everyStarrocksDirectionHasAnIntentionalRecord() {
        val pairs = root.getValue("pairs").jsonObject
        for (other in others) {
            for (key in listOf("starrocks->$other", "$other->starrocks")) {
                val record = pairs[key]?.jsonObject
                assertNotNull(record, "missing relationship record $key")
                assertTrue(record.containsKey("analysisStatus"), "$key lacks analysisStatus")
                assertTrue(record.containsKey("sourceEvidence"), "$key lacks sourceEvidence")
                assertTrue(record.containsKey("targetEvidence"), "$key lacks targetEvidence")
            }
        }
    }

    @Test
    fun sqlglotBackedDirectionsCarryPerFunctionClassifications() {
        val pairs = root.getValue("pairs").jsonObject
        for (other in others - "datafusion") {
            for (key in listOf("starrocks->$other", "$other->starrocks")) {
                val record = pairs.getValue(key).jsonObject
                assertEquals("classified", record.getValue("analysisStatus").jsonPrimitive.content)
                val entries = record.getValue("entries").jsonArray
                assertTrue(entries.isNotEmpty(), "$key has no per-function classifications")
                assertTrue(entries.all { it.jsonObject.containsKey("classification") }, "$key has unclassified entries")
            }
        }
    }

    @Test
    fun clickhouseUsesEngineCatalogAndDatafusionIsExplicitlyUnavailable() {
        val pairs = root.getValue("pairs").jsonObject
        val clickhouse = pairs.getValue("starrocks->clickhouse").jsonObject
        assertTrue(
            "system.functions" in clickhouse.getValue("targetEvidence").jsonPrimitive.content,
            clickhouse.toString(),
        )

        for (key in listOf("starrocks->datafusion", "datafusion->starrocks")) {
            val record = pairs.getValue(key).jsonObject
            assertEquals("unavailable", record.getValue("analysisStatus").jsonPrimitive.content)
            assertTrue(record.getValue("reason").jsonPrimitive.content.isNotEmpty())
        }
    }
}
