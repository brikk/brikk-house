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

    @Test
    fun dorisOverloadsMatchTheSourceSignatures() {
        // Pinned against scalar/Abs.java's SIGNATURES in the reference/doris checkout:
        // 9 overloads; widening integer returns (abs(BIGINT) -> LARGEINT, ...).
        val abs = DORIS_FUNCTION_CATALOG["abs"]!!
        assertEquals(9, abs.overloads.size)
        assertTrue(FunctionOverload(listOf("DOUBLE"), "DOUBLE") in abs.overloads)
        assertTrue(FunctionOverload(listOf("BIGINT"), "LARGEINT") in abs.overloads)
        assertTrue(FunctionOverload(listOf("TINYINT"), "SMALLINT") in abs.overloads)
        // agg/Count.java: count() and count(varargs ANY).
        val count = DORIS_FUNCTION_CATALOG["count"]!!
        assertEquals(
            listOf(
                FunctionOverload(emptyList(), "BIGINT"),
                FunctionOverload(listOf("ANY"), "BIGINT", variadic = true),
            ),
            count.overloads,
        )
    }

    @Test
    fun dorisVariadicsAreFlagged() {
        // scalar/Concat.java: ret(VARCHAR).varArgs(VARCHAR), ret(STRING).varArgs(STRING).
        val concat = DORIS_FUNCTION_CATALOG["concat"]!!
        assertEquals(
            listOf(
                FunctionOverload(listOf("VARCHAR"), "VARCHAR", variadic = true),
                FunctionOverload(listOf("STRING"), "STRING", variadic = true),
            ),
            concat.overloads,
        )
    }

    @Test
    fun dorisArrayTypesAndPlaceholdersAreRendered() {
        // scalar/ArraySort.java: retArgType(0).args(ArrayType.of(AnyDataType...)) and a
        // lambda overload — rendered with the extractor's ARG_/ANY placeholders.
        val arraySort = DORIS_FUNCTION_CATALOG["array_sort"]!!
        assertEquals(
            listOf(
                FunctionOverload(listOf("ARRAY<ANY>"), "ARG_0"),
                FunctionOverload(listOf("LAMBDA"), "ARRAY<ANY>"),
            ),
            arraySort.overloads,
        )
    }

    @Test
    fun dorisOverloadTotalsArePinned() {
        // Static extraction coverage at the pinned checkout: 630 defs carry 1434
        // overloads; the rest (dynamic getSignatures(): all table-valued functions,
        // rank-like window functions, ...) stay empty.
        assertEquals(1434, DORIS_FUNCTION_CATALOG.functions.sumOf { it.overloads.size })
        assertEquals(630, DORIS_FUNCTION_CATALOG.functions.count { it.overloads.isNotEmpty() })
        // Dynamic-signature examples remain overload-free (names still resolvable).
        assertTrue(DORIS_FUNCTION_CATALOG["rank"]!!.overloads.isEmpty())
        assertTrue(DORIS_FUNCTION_CATALOG["numbers"]!!.overloads.isEmpty())
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
        // Doris overloads survive the round trip.
        assertEquals(9, decoded.first { it.name == "ABS" }.overloads.size)
        assertTrue(decoded.first { it.name == "CONCAT" }.overloads.all { it.variadic })
    }

    @Test
    fun sinceVersionIsNullUntilADocsSourceIsVendored() {
        // The field is declared for serialization stability (doris-intellij's
        // version-gated completion) but no vendored source carries version metadata
        // yet — see vendor/README.md. Absent-as-null must round-trip.
        assertTrue(DORIS_FUNCTION_CATALOG.functions.all { it.sinceVersion == null })
        assertNull(DORIS_FUNCTION_CATALOG["abs"]!!.sinceVersion)
        val decoded = Json.decodeFromString(
            ListSerializer(FunctionDef.serializer()),
            DORIS_FUNCTION_CATALOG.toJson(),
        )
        assertNull(decoded.first { it.name == "ABS" }.sinceVersion)
        // And a populated value round-trips through the wire name "since_version".
        val def = FunctionDef("F", FunctionKind.SCALAR, sinceVersion = "2.1")
        val json = Json.encodeToString(FunctionDef.serializer(), def)
        assertTrue("\"since_version\":\"2.1\"" in json)
        assertEquals(def, Json.decodeFromString(FunctionDef.serializer(), json))
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
