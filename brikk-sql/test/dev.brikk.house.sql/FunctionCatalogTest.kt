package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.DUCKDB_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.TRINO_FUNCTION_CATALOG
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * brikk-sql's integration with the brikk-sql-metadata catalogs: dialect wiring and
 * engine-exact slot detection. The catalogs' own surface (sizes, overloads, kinds,
 * serde) is covered in brikk-sql-metadata's FunctionCatalogTest.
 */
class FunctionCatalogTest {

    @Test
    fun dialectExposesCatalog() {
        assertEquals(DORIS_FUNCTION_CATALOG, Dialects.forName("doris").functionCatalog)
        assertEquals(DUCKDB_FUNCTION_CATALOG, Dialects.forName("duckdb").functionCatalog)
        assertEquals(TRINO_FUNCTION_CATALOG, Dialects.forName("trino").functionCatalog)
        assertEquals(null, Dialects.forName("mysql").functionCatalog)
        // Presto does NOT inherit Trino's catalog: the surfaces differ per engine.
        assertEquals(null, Dialects.forName("presto").functionCatalog)
    }

    @Test
    fun slotDetectionUsesCatalog() {
        // numbers(...) is a registered Doris TVF -> not a slot; my_source(...) is unknown -> slot.
        val frag = SqlFragment(
            "SELECT * FROM numbers(\"number\" = \"10\") JOIN my_source(1) AS s ON TRUE",
            dialect = "doris",
        )
        assertEquals(listOf("my_source"), frag.tableSlots)
    }

    @Test
    fun slotDetectionUsesDuckdbCatalog() {
        // glob(...) is a registered DuckDB TVF (lowercase in the catalog; matching is
        // case-insensitive) -> not a slot; my_source(...) is unknown -> slot.
        val frag = SqlFragment(
            "SELECT * FROM glob('*.csv') JOIN my_source(1) AS s ON TRUE",
            dialect = "duckdb",
        )
        assertEquals(listOf("my_source"), frag.tableSlots)
    }

    @Test
    fun slotDetectionUsesTrinoCatalog() {
        // sequence(...) is a registered Trino table function -> not a slot.
        val frag = SqlFragment(
            "SELECT * FROM sequence(1, 10, 1) JOIN my_source(1) AS s ON TRUE",
            dialect = "trino",
        )
        assertEquals(listOf("my_source"), frag.tableSlots)
    }
}
