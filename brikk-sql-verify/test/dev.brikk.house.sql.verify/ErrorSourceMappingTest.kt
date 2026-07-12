package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * brikk-native end-to-end proof of the source-mapping pipeline:
 *
 *   parse (position meta on nodes) -> transpile with trackSourceMap
 *   -> engine verifier REJECTS the output with a (line, col) in the OUTPUT
 *   -> TranspileResult.mapErrorToSource maps it back to the ORIGINAL source.
 */
class ErrorSourceMappingTest {

    @Test
    fun dorisRejectPositionMapsBackToOriginalSource() {
        // The known remaining unverifiable case (doris-verify-known-failures.json):
        // brikk (like Python sqlglot) renders the MV column list without types, which
        // Doris FE rejects at the end of the statement.
        val source = "CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1)"
        val fragment = SqlFragment(source, "doris")
        val result = fragment.transpileTo("doris", trackSourceMap = true)
        assertEquals("CREATE MATERIALIZED VIEW test_table (c1, c2) KEY (c1)", result.sql)

        val verifier = SqlVerifiers.forEngine("doris")!!
        val vr = verifier.verify(result.sql)
        assertFalse(vr.accepted, "expected Doris FE to reject: ${result.sql}")
        val line = assertNotNull(vr.line, "Doris FE reports an error line")
        val col = assertNotNull(vr.col, "Doris FE reports an error column")

        // The verifier position points into the OUTPUT sql; map it back to the source.
        val pos = assertNotNull(
            result.mapErrorToSource(line, col),
            "error position ($line, $col) in '${result.sql}' should map back to source",
        )
        assertEquals(1, pos.line)
        // The error is at the end of the statement; the nearest positioned token is
        // the KEY-clause column ref `c1`, whose SOURCE offset (59) differs from its
        // output offset (50) because the dropped `INT` types shifted everything left.
        assertEquals(source.lastIndexOf("c1"), pos.start)
        assertTrue(result.sql.lastIndexOf("c1") != source.lastIndexOf("c1"))
    }

    @Test
    fun validOutputPositionsMapExactly() {
        // Valid duckdb -> doris transpilation: a specific column ref in the OUTPUT
        // maps back to its exact original line/col even though the output shape
        // (quoting/keywords) differs from the source.
        val source = "SELECT foo,\n       bar\nFROM tbl\nWHERE baz >= 42"
        val fragment = SqlFragment(source, "duckdb")
        val result = fragment.transpileTo("doris", trackSourceMap = true)
        val map = assertNotNull(result.sourceMap)

        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verify(result.sql).accepted, "sanity: output is valid doris")

        val barOut = result.sql.indexOf("bar")
        val pos = assertNotNull(map.sourcePosition(barOut))
        assertEquals(2, pos.line, "bar sits on source line 2")
        assertEquals(source.indexOf("bar"), pos.start)
    }
}
