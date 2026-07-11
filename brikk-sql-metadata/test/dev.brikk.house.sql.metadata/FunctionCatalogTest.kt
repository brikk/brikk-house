package dev.brikk.house.sql.metadata

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunctionCatalogTest {

    // ---------------------------------------------------------------- doris

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

    // ---------------------------------------------------------------- duckdb

    @Test
    fun duckdbCatalogLoadsWithExpectedSurface() {
        // Pinned to DuckDB v1.5.4 (the python module the generator embeds).
        assertEquals(881, DUCKDB_FUNCTION_CATALOG.size)
        assertTrue("list_transform" in DUCKDB_FUNCTION_CATALOG)
        // Engine-verified overload: list_transform(ANY[], LAMBDA) -> ANY[].
        val listTransform = DUCKDB_FUNCTION_CATALOG["list_transform"]!!
        assertEquals(
            listOf(FunctionOverload(listOf("ANY[]", "LAMBDA"), "ANY[]")),
            listTransform.overloads,
        )
        assertNull(listTransform.nativeKind)
    }

    @Test
    fun duckdbVariadicsAreFlagged() {
        // duckdb_functions() reports concat as (value ANY, varargs ANY).
        val concat = DUCKDB_FUNCTION_CATALOG["concat"]!!
        assertEquals(
            listOf(FunctionOverload(listOf("ANY"), "ANY", variadic = true)),
            concat.overloads,
        )
    }

    @Test
    fun duckdbMacrosKeepNativeKind() {
        // pg_has_role is a macro: normalized to SCALAR, native kind preserved.
        val pgHasRole = DUCKDB_FUNCTION_CATALOG["pg_has_role"]!!
        assertEquals(FunctionKind.SCALAR, pgHasRole.kind)
        assertEquals("macro", pgHasRole.nativeKind)
        // histogram_values is a table_macro: normalized to TABLE_VALUED.
        val histogramValues = DUCKDB_FUNCTION_CATALOG["histogram_values"]!!
        assertEquals(FunctionKind.TABLE_VALUED, histogramValues.kind)
        assertEquals("table_macro", histogramValues.nativeKind)
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("histogram_values"))
        // current_database is registered as BOTH scalar and macro -> plain scalar def.
        assertNull(DUCKDB_FUNCTION_CATALOG["current_database"]!!.nativeKind)
    }

    @Test
    fun duckdbTableFunctionsAreClassified() {
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("glob"))
        assertTrue(DUCKDB_FUNCTION_CATALOG.isTableFunction("read_csv"))
        assertTrue(!DUCKDB_FUNCTION_CATALOG.isTableFunction("abs"))
    }

    // ---------------------------------------------------------------- trino

    @Test
    fun trinoCatalogLoadsWithExpectedSurface() {
        // Pinned to vendor/data/trino-functions-481.tsv (Trino 481 SHOW FUNCTIONS:
        // 746 rows -> 654 distinct signatures over 320 (name, kind) defs).
        assertEquals(320, TRINO_FUNCTION_CATALOG.size)
        assertTrue("approx_percentile" in TRINO_FUNCTION_CATALOG)
        // TSV-verified overload count for approx_percentile.
        assertEquals(15, TRINO_FUNCTION_CATALOG["approx_percentile"]!!.overloads.size)
        assertEquals(FunctionKind.AGGREGATE, TRINO_FUNCTION_CATALOG["approx_percentile"]!!.kind)
    }

    @Test
    fun trinoWindowAndTableKinds() {
        val rowNumber = TRINO_FUNCTION_CATALOG["row_number"]!!
        assertEquals(FunctionKind.WINDOW, rowNumber.kind)
        assertEquals(listOf(FunctionOverload(emptyList(), "bigint")), rowNumber.overloads)
        // sequence is registered both as scalar and as a table function; the table def
        // wins name lookup (emitted later in kind order).
        assertTrue(TRINO_FUNCTION_CATALOG.isTableFunction("sequence"))
    }

    @Test
    fun trinoVariadicIsNeverFlagged() {
        // SHOW FUNCTIONS does not flag variadics — arity is a lower bound (see the
        // generated header). concat accepts N args but the catalog must not claim so.
        assertTrue(TRINO_FUNCTION_CATALOG["concat"]!!.overloads.all { !it.variadic })
    }

    // ---------------------------------------------------------------- serde

    @Test
    fun jsonRoundTrip() {
        val json = DORIS_FUNCTION_CATALOG.toJson()
        val decoded = Json.decodeFromString(ListSerializer(FunctionDef.serializer()), json)
        assertEquals(DORIS_FUNCTION_CATALOG.functions, decoded)
        assertEquals(FunctionKind.SCALAR, decoded.first { it.name == "ABS" }.kind)
    }

    @Test
    fun jsonRoundTripPreservesOverloadsAndNativeKind() {
        val json = DUCKDB_FUNCTION_CATALOG.toJson()
        val decoded = Json.decodeFromString(ListSerializer(FunctionDef.serializer()), json)
        assertEquals(DUCKDB_FUNCTION_CATALOG.functions, decoded)
        assertEquals("macro", decoded.first { it.name == "pg_has_role" }.nativeKind)
        assertTrue(decoded.first { it.name == "concat" }.overloads.single().variadic)
    }
}
