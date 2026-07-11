package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.FunctionDef
import dev.brikk.house.sql.dialects.FunctionKind
import dev.brikk.house.sql.shape.SqlFragment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionCatalogTest {

    @Test
    fun dorisCatalogLoadsWithExpectedSurface() {
        // Pinned to the reference/doris checkout the generator ran against.
        assertEquals(728, DORIS_FUNCTION_CATALOG.size)
        assertTrue("ABS" in DORIS_FUNCTION_CATALOG)
        // alias lookup (SUBSTR/SUBSTRING/MID registered on one def)
        val substr = DORIS_FUNCTION_CATALOG["substr"]
        assertEquals(DORIS_FUNCTION_CATALOG["substring"], substr)
        // case-insensitive
        assertEquals(DORIS_FUNCTION_CATALOG["abs"], DORIS_FUNCTION_CATALOG["ABS"])
    }

    @Test
    fun tableFunctionsAreClassified() {
        assertTrue(DORIS_FUNCTION_CATALOG.isTableFunction("numbers"))
        assertTrue(DORIS_FUNCTION_CATALOG.isTableFunction("explode"))
        assertTrue(!DORIS_FUNCTION_CATALOG.isTableFunction("abs"))
    }

    @Test
    fun dialectExposesCatalog() {
        assertEquals(DORIS_FUNCTION_CATALOG, Dialects.forName("doris").functionCatalog)
        assertEquals(null, Dialects.forName("mysql").functionCatalog)
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
    fun jsonRoundTrip() {
        val json = DORIS_FUNCTION_CATALOG.toJson()
        val decoded = Json.decodeFromString(ListSerializer(FunctionDef.serializer()), json)
        assertEquals(DORIS_FUNCTION_CATALOG.functions, decoded)
        assertEquals(FunctionKind.SCALAR, decoded.first { it.name == "ABS" }.kind)
    }
}
