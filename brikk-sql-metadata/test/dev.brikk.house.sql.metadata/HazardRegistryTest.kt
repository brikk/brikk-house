package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HazardRegistryTest {

    // ------------------------------------------------------------ lookups

    @Test
    fun lookupWorksInBothDirections() {
        // 'lower' | 'lower' divergent (Turkish İ case-folding probe).
        val fwd = HazardRegistry.lookup("trino", "duckdb", "lower")
        assertNotNull(fwd)
        assertEquals(HazardVerdict.DIVERGENT, fwd.verdict)
        assertTrue("unicode" in fwd.areas)
        assertTrue(fwd.provenance.isNotEmpty())

        val rev = HazardRegistry.lookup("duckdb", "trino", "lower")
        assertNotNull(rev)
        assertEquals(HazardVerdict.DIVERGENT, rev.verdict)
    }

    @Test
    fun directionalKeysUseTheSourceSideName() {
        // 'day_of_week / dow' (trino) | 'isodow' (duckdb): each direction keys by its
        // own side's names.
        val byTrinoName = HazardRegistry.lookup("trino", "duckdb", "day_of_week")
        assertNotNull(byTrinoName)
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, byTrinoName.verdict)
        // 'dow' alternative from the ' / ' list is keyed too.
        assertEquals(byTrinoName, HazardRegistry.lookup("trino", "duckdb", "dow"))

        val byDuckdbName = HazardRegistry.lookup("duckdb", "trino", "isodow")
        assertNotNull(byDuckdbName)
        assertEquals(HazardVerdict.CONDITIONALLY_EQUIVALENT, byDuckdbName.verdict)
        // The trino-side name is NOT a duckdb->trino key (different entry may claim it,
        // but this entry's duckdb side is only 'isodow').
        assertNull(HazardRegistry.lookup("duckdb", "trino", "day_of_week"))
    }

    @Test
    fun lookupIsCaseInsensitiveOnDialectsAndNames() {
        assertEquals(
            HazardRegistry.lookup("trino", "duckdb", "lower"),
            HazardRegistry.lookup("Trino", "DUCKDB", "LOWER"),
        )
    }

    @Test
    fun unknownPairsAndNamesReturnNull() {
        // mysql<->doris has no hazards file; same-dialect pairs never do.
        assertNull(HazardRegistry.lookup("mysql", "doris", "lower"))
        assertNull(HazardRegistry.lookup("duckdb", "duckdb", "lower"))
        assertNull(HazardRegistry.lookup("trino", "duckdb", "definitely_not_a_function"))
    }

    @Test
    fun constructEntriesStayRetrievableByRawString() {
        // Non-function constructs never match parsed function names but stay
        // addressable verbatim.
        val cast = HazardRegistry.lookup("trino", "duckdb", "CAST (primitive)")
        assertNotNull(cast)
        assertEquals(HazardVerdict.IDENTICAL, cast.verdict)
        val distinctFrom = HazardRegistry.lookup("trino", "duckdb", "IS [NOT] DISTINCT FROM")
        assertNotNull(distinctFrom)
    }

    @Test
    fun collisionsResolveToTheWorstVerdict() {
        // Trino 'concat' has both a divergent argument-coercion entry and an identical
        // '||'-mapping entry — the registry must keep the conservative one.
        assertEquals(
            HazardVerdict.DIVERGENT,
            HazardRegistry.lookup("trino", "duckdb", "concat")?.verdict,
        )
        // 'hour': identical extract entry vs conditionally-equivalent WTZ-session-zone
        // entry — conditional wins over identical.
        assertEquals(
            HazardVerdict.CONDITIONALLY_EQUIVALENT,
            HazardRegistry.lookup("trino", "duckdb", "hour")?.verdict,
        )
    }

    // ------------------------------------------------------- generated data pins

    @Test
    fun entryAndVerdictCountsArePinned() {
        // Pinned to trino-duckdb-hazards.json (242 probe-verified pairs); the sync test
        // in brikk-sql test@jvm cross-checks content against the JSON itself.
        assertEquals(246, TRINO_DUCKDB_HAZARD_ENTRIES.size)
        val counts = TRINO_DUCKDB_HAZARD_ENTRIES.groupingBy { it.verdict }.eachCount()
        assertEquals(106, counts[HazardVerdict.IDENTICAL])
        assertEquals(38, counts[HazardVerdict.DIVERGENT])
        assertEquals(52, counts[HazardVerdict.CONDITIONALLY_EQUIVALENT])
        assertEquals(44, counts[HazardVerdict.NO_EQUIVALENT])
        assertEquals(6, counts[HazardVerdict.UNCLEAR])
    }

    @Test
    fun keyCountsPerDirectionArePinned() {
        assertEquals(386, TRINO_TO_DUCKDB_HAZARDS.size)
        assertEquals(309, DUCKDB_TO_TRINO_HAZARDS.size)
        // Every keyed value is one of the shared entries (no copies).
        assertTrue(TRINO_TO_DUCKDB_HAZARDS.values.all { it in TRINO_DUCKDB_HAZARD_ENTRIES })
        assertTrue(DUCKDB_TO_TRINO_HAZARDS.values.all { it in TRINO_DUCKDB_HAZARD_ENTRIES })
    }

    @Test
    fun dorisPairsArePopulatedByLiveProbes() {
        // Populated by the doris live-probe program (REPORT-doris-differential-probe-
        // 2026-07-13); counts pinned to the ingested registries.
        assertEquals(258, DUCKDB_DORIS_HAZARD_ENTRIES.size)
        assertEquals(216, TRINO_DORIS_HAZARD_ENTRIES.size)
        assertTrue(DUCKDB_TO_DORIS_HAZARDS.isNotEmpty())
        assertTrue(DORIS_TO_DUCKDB_HAZARDS.isNotEmpty())
        assertTrue(TRINO_TO_DORIS_HAZARDS.isNotEmpty())
        assertTrue(DORIS_TO_TRINO_HAZARDS.isNotEmpty())
        // Live-probed verdict spot checks: unicode case folding diverges.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "doris", "lower")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("trino", "doris", "trim")?.verdict)
    }

    @Test
    fun clickhousePairsArePopulatedByLiveProbes() {
        // Populated by the clickhouse differential-probe program (REPORT-clickhouse-
        // differential-probe-2026-07-13; ClickHouse 26.5.1.1 via chdb vs DuckDB 1.5.4);
        // counts pinned to the ingested registries.
        assertEquals(213, DUCKDB_CLICKHOUSE_HAZARD_ENTRIES.size)
        assertEquals(134, TRINO_CLICKHOUSE_HAZARD_ENTRIES.size)
        assertTrue(DUCKDB_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_DUCKDB_HAZARDS.isNotEmpty())
        assertTrue(TRINO_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_TRINO_HAZARDS.isNotEmpty())
        // Live-probed verdict spot checks:
        // ClickHouse length() counts bytes; lower() is ASCII-only.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "length")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "round")?.verdict)
        // ClickHouse regexp_replace = replace-all, matching Trino (differs from DuckDB).
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "regexp_replace")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("trino", "clickhouse", "date_format")?.verdict)
    }

    @Test
    fun dorisClickhousePairIsPopulatedByLiveProbes() {
        // Populated by the doris<->clickhouse live differential probe (Doris FE pr62767-local
        // / BE 4.1.2 vs ClickHouse 26.5.1.1 via chdb; docs/research/probe-runs/doris-clickhouse.*).
        assertEquals(177, DORIS_CLICKHOUSE_HAZARD_ENTRIES.size)
        assertTrue(DORIS_TO_CLICKHOUSE_HAZARDS.isNotEmpty())
        assertTrue(CLICKHOUSE_TO_DORIS_HAZARDS.isNotEmpty())
        // Live-probed spot checks: both round half-away vs banker's; both single-arg log is
        // NATURAL (identical — resolves what the conservative composition marked divergent).
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "round")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "dayofweek")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "log")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("clickhouse", "doris", "length")?.verdict)
    }

}
