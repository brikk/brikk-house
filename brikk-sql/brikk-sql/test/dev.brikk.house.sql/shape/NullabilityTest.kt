package dev.brikk.house.sql.shape

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateNullability
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.qualify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BRIKK-NATIVE nullability pass ([annotateNullability]) surfaced through
 * [SqlFragment.outputShape] and [Shape.compare]. Tri-state: true / false / null(unknown).
 *
 * Function evidence is cited from brikk-sql-metadata's Doris catalog
 * (GeneratedDorisFunctionCatalog.kt): ABS = SemanticProfile(STRICT), ACOS =
 * SemanticProfile(ALWAYS_NULLABLE).
 */
class NullabilityTest {

    private val json = Json

    /** nullable() verdict of the single-projection output of [sql] under [catalog]. */
    private fun singleNullable(
        sql: String,
        catalog: ShapeCatalog = ShapeCatalog.EMPTY,
        dialect: String = "doris",
    ): Boolean? {
        val shape = SqlFragment(sql, dialect).outputShape(catalog)
        assertEquals(1, shape.columns.size, "expected one projection for: $sql")
        return shape.columns[0].nullable
    }

    // ------------------------------------------------------------------ literals

    @Test
    fun nullLiteralIsNullable() {
        assertEquals(true, singleNullable("SELECT NULL AS c"))
    }

    @Test
    fun nonNullLiteralsAreNotNull() {
        assertEquals(false, singleNullable("SELECT 1 AS c"))
        assertEquals(false, singleNullable("SELECT 'x' AS c"))
    }

    // ------------------------------------------------------------------ columns

    @Test
    fun declaredNullableColumnFlowsThrough() {
        val nullableCol = ShapeCatalog(
            tables = mapOf("t" to Shape(listOf(ColumnShape("a", "INT", nullable = true)))),
        )
        assertEquals(true, singleNullable("SELECT a AS c FROM t", nullableCol))

        val notNullCol = ShapeCatalog(
            tables = mapOf("t" to Shape(listOf(ColumnShape("a", "INT", nullable = false)))),
        )
        assertEquals(false, singleNullable("SELECT a AS c FROM t", notNullCol))
    }

    @Test
    fun undeclaredColumnIsUnknown() {
        // Column type declared, nullability NOT declared -> unknown (never guessed).
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT")))
        assertNull(singleNullable("SELECT a AS c FROM t", catalog))
    }

    // ------------------------------------------------------------------ COALESCE

    @Test
    fun coalesceWithNotNullArgIsNotNull() {
        // COALESCE(col, 0): 0 is a not-null literal -> whole expr not-null even though
        // col's nullability is unknown.
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT")))
        assertEquals(false, singleNullable("SELECT COALESCE(a, 0) AS c FROM t", catalog))
    }

    @Test
    fun coalesceAllNullableIsNullable() {
        val catalog = ShapeCatalog(
            tables = mapOf("t" to Shape(listOf(ColumnShape("a", "INT", nullable = true)))),
        )
        assertEquals(true, singleNullable("SELECT COALESCE(a, NULL) AS c FROM t", catalog))
    }

    // ---------------------------------------------------------------- functions

    @Test
    fun strictFunctionOverArgs() {
        // Doris ABS = STRICT: nullable iff its argument is. NULL arg -> nullable.
        assertEquals(true, singleNullable("SELECT ABS(NULL) AS c"))
        // not-null arg -> not-null.
        assertEquals(false, singleNullable("SELECT ABS(1) AS c"))
        // unknown arg -> unknown.
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT")))
        assertNull(singleNullable("SELECT ABS(a) AS c FROM t", catalog))
    }

    @Test
    fun alwaysNullableFunctionIsNullable() {
        // Doris ACOS = ALWAYS_NULLABLE: nullable regardless of its (not-null) argument.
        assertEquals(true, singleNullable("SELECT ACOS(1) AS c"))
    }

    // --------------------------------------------------------------- aggregates

    @Test
    fun aggregateIsNullableExceptCount() {
        val catalog = ShapeCatalog(
            tables = mapOf("t" to Shape(listOf(ColumnShape("a", "INT", nullable = false)))),
        )
        // SUM over an empty group is NULL even with a not-null column -> nullable.
        assertEquals(true, singleNullable("SELECT SUM(a) AS c FROM t", catalog))
        // COUNT returns 0 over an empty group -> not-null.
        assertEquals(false, singleNullable("SELECT COUNT(*) AS c FROM t", catalog))
        assertEquals(false, singleNullable("SELECT COUNT(a) AS c FROM t", catalog))
    }

    // -------------------------------------------------------------------- CASE

    @Test
    fun caseWithoutElseIsNullable() {
        // Missing ELSE -> implicit NULL branch -> nullable, even with not-null results.
        assertEquals(true, singleNullable("SELECT CASE WHEN 1 = 1 THEN 1 END AS c"))
        // With a not-null ELSE and not-null result -> not-null.
        assertEquals(false, singleNullable("SELECT CASE WHEN 1 = 1 THEN 1 ELSE 2 END AS c"))
    }

    // ------------------------------------------------------- pipe end-to-end mix

    @Test
    fun pipeFragmentOutputShapeTrueFalseUnknownMix() {
        // a: declared not-null; b: declared nullable; c: declared type only (unknown).
        val catalog = ShapeCatalog(
            tables = mapOf(
                "produce" to Shape(listOf(
                    ColumnShape("a", "INT", nullable = false),
                    ColumnShape("b", "INT", nullable = true),
                    ColumnShape("c", "INT"),
                )),
            ),
        )
        val fragment = SqlFragment(
            "FROM produce |> SELECT a AS not_null, b AS is_null, c AS unknown, NULL AS lit",
            "doris",
        )
        assertTrue(fragment.isPipe)
        val shape = fragment.outputShape(catalog)
        assertEquals(listOf("not_null", "is_null", "unknown", "lit"), shape.names())
        assertEquals(false, shape.columns[0].nullable)
        assertEquals(true, shape.columns[1].nullable)
        assertNull(shape.columns[2].nullable)
        assertEquals(true, shape.columns[3].nullable)
    }

    // ------------------------------------------------------- compare() matrix

    @Test
    fun compareNullabilityParticipationMatrix() {
        fun cmp(expected: Boolean?, actual: Boolean?): ShapeComparison =
            Shape(listOf(ColumnShape("c", "INT", nullable = expected)))
                .compare(Shape(listOf(ColumnShape("c", "INT", nullable = actual))))

        // expected not-null (false) + actual nullable (true) -> mismatch.
        cmp(false, true).let {
            assertEquals(1, it.nullabilityMismatches.size)
            assertEquals(ShapeVerdict.HAS_LESS, it.verdict)
        }
        // expected not-null + actual not-null -> ok.
        cmp(false, false).let {
            assertTrue(it.nullabilityMismatches.isEmpty())
            assertEquals(ShapeVerdict.SATISFIES, it.verdict)
        }
        // expected nullable (true) accepts anything.
        cmp(true, true).let { assertTrue(it.nullabilityMismatches.isEmpty()) }
        cmp(true, false).let { assertTrue(it.nullabilityMismatches.isEmpty()) }
        // unknown on either side -> not compared.
        cmp(null, true).let { assertTrue(it.nullabilityMismatches.isEmpty()) }
        cmp(false, null).let { assertTrue(it.nullabilityMismatches.isEmpty()) }
        cmp(null, null).let { assertTrue(it.nullabilityMismatches.isEmpty()) }
    }

    // ------------------------------------------------- serialization round-trip

    @Test
    fun columnShapeAndComparisonSerializeRoundTrip() {
        val shape = Shape(listOf(
            ColumnShape("a", "INT", nullable = true),
            ColumnShape("b", "TEXT", nullable = false),
            ColumnShape("c", "INT", nullable = null),
        ))
        val encoded = json.encodeToString(shape)
        assertEquals(shape, json.decodeFromString<Shape>(encoded))

        val comparison = Shape(listOf(ColumnShape("c", "INT", nullable = false)))
            .compare(Shape(listOf(ColumnShape("c", "INT", nullable = true))))
        val encodedCmp = json.encodeToString(comparison)
        assertEquals(comparison, json.decodeFromString<ShapeComparison>(encodedCmp))
        assertEquals(1, comparison.nullabilityMismatches.size)
    }

    // ---------------------------------------------- direct pass (cast/try_cast)

    @Test
    fun castPropagatesTryCastIsNullable() {
        val d = Dialects.forName("doris")
        // CAST propagates its operand; TRY_CAST can fail -> nullable.
        val castTree = annotateTypes(
            qualify(d.parseOne("SELECT CAST(1 AS INT) AS c"), dialect = d),
            dialect = d,
        )
        val castRes = annotateNullability(castTree, dialect = d)
        val castProj = firstProjection(castTree)
        assertEquals(false, castRes.nullableOf(castProj))

        val tryTree = annotateTypes(
            qualify(d.parseOne("SELECT TRY_CAST('x' AS INT) AS c"), dialect = d),
            dialect = d,
        )
        val tryRes = annotateNullability(tryTree, dialect = d)
        val tryProj = firstProjection(tryTree)
        assertEquals(true, tryRes.nullableOf(tryProj))
    }

    private fun firstProjection(tree: dev.brikk.house.sql.ast.Expression): dev.brikk.house.sql.ast.Expression =
        (tree as dev.brikk.house.sql.ast.Select).selects
            .filterIsInstance<dev.brikk.house.sql.ast.Expression>()
            .first()
}
