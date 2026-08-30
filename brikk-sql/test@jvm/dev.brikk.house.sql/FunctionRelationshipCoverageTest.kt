package dev.brikk.house.sql

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the split gap-report layout: a small manifest at
 * `semantics/gap-report.json` indexing one per-pair detail file per ordered pair under
 * `semantics/function-gaps/`. Verifies StarRocks direction coverage, manifest↔file
 * integrity (every referenced file exists, no orphan/stale files remain), and that
 * every classified pair retains analysisStatus / evidence / per-function classifications.
 */
class FunctionRelationshipCoverageTest {

    private val manifest = Json.parseToJsonElement(
        testResource("semantics/gap-report.json")
    ).jsonObject

    private val pairs = manifest.getValue("pairs").jsonObject

    private val others = listOf(
        "sqlglot", "mysql", "doris", "presto", "trino", "duckdb", "postgres",
        "clickhouse", "hive", "spark2", "spark", "bigquery", "datafusion",
    )

    /** Loads a pair's full detail record by following the manifest's `file` pointer. */
    private fun pairDetail(key: String) =
        Json.parseToJsonElement(
            testResource("semantics/" + pairs.getValue(key).jsonObject.getValue("file").jsonPrimitive.content)
        ).jsonObject

    private fun functionGapsDir(): File =
        File("testResources/semantics/function-gaps").takeIf { it.isDirectory }
            ?: File("brikk-sql/testResources/semantics/function-gaps")

    @Test
    fun everyStarrocksDirectionHasAnIntentionalRecord() {
        for (other in others) {
            for (key in listOf("starrocks->$other", "$other->starrocks")) {
                val record = pairs[key]?.jsonObject
                assertNotNull(record, "missing relationship record $key")
                assertTrue(record.containsKey("analysisStatus"), "$key lacks analysisStatus")
                assertTrue(record.containsKey("sourceEvidence"), "$key lacks sourceEvidence")
                assertTrue(record.containsKey("targetEvidence"), "$key lacks targetEvidence")
                assertTrue(record.containsKey("file"), "$key lacks detail-file pointer")
            }
        }
    }

    @Test
    fun sqlglotBackedDirectionsCarryPerFunctionClassifications() {
        for (other in others - "datafusion") {
            for (key in listOf("starrocks->$other", "$other->starrocks")) {
                val idx = pairs.getValue(key).jsonObject
                assertEquals("classified", idx.getValue("analysisStatus").jsonPrimitive.content)
                val detail = pairDetail(key)
                // Metadata is preserved in the detail file too.
                assertTrue(detail.containsKey("analysisStatus"), "$key detail lacks analysisStatus")
                assertTrue(detail.containsKey("sourceEvidence"), "$key detail lacks sourceEvidence")
                assertTrue(detail.containsKey("targetEvidence"), "$key detail lacks targetEvidence")
                val entries = detail.getValue("entries").jsonArray
                assertTrue(entries.isNotEmpty(), "$key has no per-function classifications")
                assertTrue(
                    entries.all { it.jsonObject.containsKey("classification") },
                    "$key has unclassified entries",
                )
                // Manifest entryCount must match the actual entries in the detail file.
                assertEquals(
                    idx.getValue("entryCount").jsonPrimitive.int,
                    entries.size,
                    "$key entryCount mismatch",
                )
            }
        }
    }

    @Test
    fun clickhouseUsesEngineCatalogAndDatafusionIsExplicitlyUnavailable() {
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

    @Test
    fun everyManifestPairHasAnExistingDetailFile() {
        val dir = functionGapsDir()
        for ((key, idx) in pairs) {
            val rel = idx.jsonObject.getValue("file").jsonPrimitive.content
            val fname = rel.substringAfterLast('/')
            assertTrue(
                File(dir, fname).isFile,
                "manifest pair $key references missing detail file $rel",
            )
        }
    }

    @Test
    fun noOrphanOrStalePairFilesRemain() {
        val dir = functionGapsDir()
        val onDisk = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.map { it.name }?.toSet() ?: emptySet()
        val referenced = pairs.values
            .map { it.jsonObject.getValue("file").jsonPrimitive.content.substringAfterLast('/') }
            .toSet()
        assertEquals(
            referenced, onDisk,
            "function-gaps/ files must match manifest exactly (orphans/stale forbidden)",
        )
    }

    @Test
    fun classifiedPairsRetainStatusEvidenceAndClassifications() {
        for ((key, idxElem) in pairs) {
            val idx = idxElem.jsonObject
            if (idx.getValue("analysisStatus").jsonPrimitive.content != "classified") continue
            val detail = pairDetail(key)
            val entries = detail.getValue("entries").jsonArray
            assertTrue(entries.isNotEmpty(), "$key classified but has no entries")
            for (e in entries) {
                val obj = e.jsonObject
                assertTrue(obj.containsKey("classification"), "$key entry missing classification")
                assertTrue(obj.containsKey("bucket"), "$key entry missing bucket")
                assertTrue(obj.containsKey("sourceEvidence"), "$key entry missing sourceEvidence")
            }
        }
    }

    @Test
    fun everyExpectedStarrocksDirectionExists() {
        // Both directions between StarRocks and every registered dialect must be present.
        val expected = others.flatMap { listOf("starrocks->$it", "$it->starrocks") }.toSet()
        assertTrue(pairs.keys.containsAll(expected), "missing StarRocks directions: ${expected - pairs.keys}")
    }
}
