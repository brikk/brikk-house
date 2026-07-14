package dev.brikk.house.sql

import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the shipped hazard registry (brikk-sql-metadata Generated…Hazards.kt) against
 * its sources of truth under testResources/semantics/ (the pair hazards JSON files):
 * tools/generate_hazards_registry.py is byte-deterministic, and this test fails when
 * the JSON changes without a regeneration (or vice versa).
 *
 * Collision policy under test (see the tool's docstring): several JSON entries can
 * claim the same lookup key (e.g. Trino 'concat'); the registry keeps the WORST
 * verdict — so a raw-name lookup must return a verdict at least as severe as the
 * entry's own.
 *
 * Empty skeleton pairs (duckdb↔doris, trino↔doris) are wired through HazardRegistry
 * but contribute no keys until probes land.
 */
class HazardsRegistrySyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: java.io.File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: java.io.File("testResources/$path").takeIf { it.exists() }?.inputStream()
            ?: fail("resource $path not found on classpath or filesystem")
        return stream.use { it.readBytes().decodeToString() }
    }

    private data class JsonPair(
        val trino: String,
        val duckdb: String,
        val verdict: HazardVerdict,
        val hazard: String?,
        val provenance: String,
    )

    private fun loadTrinoDuckdbPairs(): List<JsonPair> {
        val root = json.parseToJsonElement(resource("semantics/trino-duckdb-hazards.json")).jsonObject
        return root.getValue("pairs").jsonArray.map { entry ->
            val obj = entry.jsonObject
            JsonPair(
                trino = obj.getValue("trino").jsonPrimitive.content,
                duckdb = obj.getValue("duckdb").jsonPrimitive.content,
                verdict = HazardVerdict.valueOf(
                    obj.getValue("verdict").jsonPrimitive.content.uppercase().replace('-', '_')
                ),
                hazard = obj["hazard"]?.jsonPrimitive?.contentOrNull,
                provenance = obj.getValue("provenance").jsonPrimitive.content,
            )
        }
    }

    private fun loadPairCount(path: String): Int {
        val root = json.parseToJsonElement(resource(path)).jsonObject
        return root.getValue("pairs").jsonArray.size
    }

    /** Worst-first severity rank (must match tools/generate_hazards_registry.py). */
    private val rank = mapOf(
        HazardVerdict.DIVERGENT to 0,
        HazardVerdict.UNCLEAR to 1,
        HazardVerdict.CONDITIONALLY_EQUIVALENT to 2,
        HazardVerdict.NO_EQUIVALENT to 3,
        HazardVerdict.IDENTICAL to 4,
    )

    @Test
    fun everyJsonPairIsRetrievableInBothDirections() {
        val pairs = loadTrinoDuckdbPairs()
        assertEquals(246, pairs.size, "hazards JSON pair count changed — regenerate the registry")
        for (pair in pairs) {
            val fwd = HazardRegistry.lookup("trino", "duckdb", pair.trino)
            assertNotNull(fwd, "trino->duckdb lookup missing for ${pair.trino}")
            assertTrue(
                rank.getValue(fwd.verdict) <= rank.getValue(pair.verdict),
                "trino->duckdb '${pair.trino}': registry verdict ${fwd.verdict} is less " +
                    "severe than the JSON entry's ${pair.verdict}",
            )
            val rev = HazardRegistry.lookup("duckdb", "trino", pair.duckdb)
            assertNotNull(rev, "duckdb->trino lookup missing for ${pair.duckdb}")
            assertTrue(
                rank.getValue(rev.verdict) <= rank.getValue(pair.verdict),
                "duckdb->trino '${pair.duckdb}': registry verdict ${rev.verdict} is less " +
                    "severe than the JSON entry's ${pair.verdict}",
            )
        }
    }

    @Test
    fun collisionFreeKeysMatchTheirJsonEntryExactly() {
        val pairs = loadTrinoDuckdbPairs()
        // Raw side-names claimed by exactly one entry must round-trip all fields.
        for (side in listOf("trino", "duckdb")) {
            val byName = pairs.groupBy {
                (if (side == "trino") it.trino else it.duckdb).trim().uppercase()
            }
            val (source, target) = if (side == "trino") "trino" to "duckdb" else "duckdb" to "trino"
            for ((name, claimants) in byName) {
                if (claimants.size != 1) continue
                val expected = claimants.single()
                val got = HazardRegistry.lookup(source, target, name)
                // A '/'-alternative of ANOTHER entry may still out-sever a unique raw
                // name (e.g. 'lower' also keyed from 'lower/upper case folding...') —
                // only assert exact equality when the registry returned this entry.
                assertNotNull(got, "$source->$target lookup missing for $name")
                if (got.provenance == expected.provenance && got.verdict == expected.verdict) {
                    assertEquals(expected.hazard, got.hazard, "hazard text drift for $name")
                }
                assertTrue(
                    rank.getValue(got.verdict) <= rank.getValue(expected.verdict),
                    "$source->$target '$name': ${got.verdict} less severe than ${expected.verdict}",
                )
            }
        }
    }

    @Test
    fun dorisJsonsCarryLiveProbePairs() {
        // Populated by the doris live-probe program; counts must match the generated
        // registries (see HazardRegistryTest pins).
        assertEquals(258, loadPairCount("semantics/duckdb-doris-hazards.json"))
        assertEquals(216, loadPairCount("semantics/trino-doris-hazards.json"))
        assertNotNull(HazardRegistry.lookup("duckdb", "doris", "lower"))
        assertNotNull(HazardRegistry.lookup("trino", "doris", "ascii"))
    }

    @Test
    fun clickhouseJsonsCarryLiveProbePairs() {
        // Populated by the clickhouse differential-probe program; counts must match the
        // generated registries (see HazardRegistryTest pins).
        assertEquals(168, loadPairCount("semantics/duckdb-clickhouse-hazards.json"))
        assertEquals(110, loadPairCount("semantics/trino-clickhouse-hazards.json"))
        assertNotNull(HazardRegistry.lookup("duckdb", "clickhouse", "length"))
        assertNotNull(HazardRegistry.lookup("trino", "clickhouse", "date_format"))
    }

    @Test
    fun dorisClickhouseJsonCarriesLiveProbePairs() {
        // The last core pair, filled by the doris<->clickhouse live differential probe.
        assertEquals(94, loadPairCount("semantics/doris-clickhouse-hazards.json"))
        assertNotNull(HazardRegistry.lookup("doris", "clickhouse", "round"))
        assertNotNull(HazardRegistry.lookup("clickhouse", "doris", "length"))
    }

}
