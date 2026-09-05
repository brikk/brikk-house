package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Schema-aware annotate_types assertions. Every expectation below was verified
 * against Python first:
 *
 *   annotate_types(parse_one(sql), schema={"t": {"a": "INT", "b": "VARCHAR"}})
 *
 * Columns must be table-qualified: annotate_types only resolves Columns that carry a
 * table reference (Python runs `qualify` first in the optimizer pipeline).
 */
class AnnotateTypesTest {

    private fun firstSelectType(sql: String): String {
        val schema = MappingSchema(mapOf("t" to mapOf("a" to "INT", "b" to "VARCHAR")))
        val expression = annotateTypes(Dialects.BASE.parseOne(sql), schema = schema)
        val type = expression.selects[0].type as DataType
        return Dialects.BASE.generate(type)
    }

    // SELECT t.a + 1 -> INT (schema column + integer literal coerce to INT)
    @Test
    fun columnPlusIntLiteral() {
        assertEquals("INT", firstSelectType("SELECT t.a + 1 FROM t"))
    }

    // SELECT t.a + 1.5 -> DOUBLE (INT coerces into the DOUBLE literal)
    @Test
    fun columnPlusDoubleLiteral() {
        assertEquals("DOUBLE", firstSelectType("SELECT t.a + 1.5 FROM t"))
    }

    // CONCAT returns VARCHAR
    @Test
    fun concatIsVarchar() {
        assertEquals("VARCHAR", firstSelectType("SELECT CONCAT(t.b, 'x') FROM t"))
    }

    // CASE branches coerce: INT branch + 1.5 default -> DOUBLE
    @Test
    fun caseBranchesCoerce() {
        assertEquals(
            "DOUBLE",
            firstSelectType("SELECT CASE WHEN t.a > 0 THEN t.a ELSE 1.5 END FROM t"),
        )
    }

    // ARRAY(t.a) -> ARRAY<INT>
    @Test
    fun arrayOfColumn() {
        assertEquals("ARRAY<INT>", firstSelectType("SELECT ARRAY(t.a) FROM t"))
    }

    // Subquery projection propagation: x.a2 resolves through the derived table to INT
    @Test
    fun subqueryProjectionPropagation() {
        assertEquals(
            "INT",
            firstSelectType("SELECT x.a2 FROM (SELECT t.a AS a2 FROM t) AS x"),
        )
    }

    // Set-op coercion: INT union'ed with DOUBLE literal -> DOUBLE
    @Test
    fun setOperationCoercion() {
        assertEquals(
            "DOUBLE",
            firstSelectType(
                "SELECT u.c FROM (SELECT t.a AS c FROM t UNION ALL SELECT 1.5 AS c FROM t) AS u"
            ),
        )
    }

    // NULL literal -> rewritten to the dialect's DEFAULT_NULL_TYPE (base: UNKNOWN)
    @Test
    fun nullLiteralGetsDefaultNullType() {
        assertEquals("UNKNOWN", firstSelectType("SELECT NULL"))
    }

    // COALESCE(INT, NULL) -> NULL coerces away, leaving INT
    @Test
    fun coalesceNullCoercesAway() {
        assertEquals("INT", firstSelectType("SELECT COALESCE(t.a, NULL) FROM t"))
    }

    // Scalar subquery propagates its single projection's type
    @Test
    fun scalarSubqueryType() {
        assertEquals("VARCHAR", firstSelectType("SELECT (SELECT t.b FROM t) FROM t"))
    }

    // Unqualified columns stay UNKNOWN (annotate_types expects qualified input)
    @Test
    fun unqualifiedColumnIsUnknown() {
        assertEquals("UNKNOWN", firstSelectType("SELECT a + 1 FROM t"))
    }
}
