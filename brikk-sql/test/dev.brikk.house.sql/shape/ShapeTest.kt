package dev.brikk.house.sql.shape

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Hand assertions for the BRIKK-NATIVE shape model (Shapes.kt). There is no Python
 * oracle for this layer; the underlying primitives (parse/qualify/annotate) are
 * oracle-verified elsewhere.
 */
class ShapeTest {

    private val json = Json

    @Test
    fun namesAndByNameWithDialectNormalization() {
        val shape = Shape.of("item" to "TEXT", "total_sales" to "DOUBLE")
        assertEquals(listOf("item", "total_sales"), shape.names())

        // Unquoted identifiers are case-insensitive under the base dialect
        // (LOWERCASE normalization), so "ITEM" resolves to "item".
        assertEquals(ColumnShape("item", "TEXT"), shape.byName("ITEM"))
        assertNull(shape.byName("missing"))
    }

    @Test
    fun toSchemaMappingPreservesOrder() {
        val shape = Shape.of("b" to "INT", "a" to "TEXT")
        assertEquals(listOf("b", "a"), shape.toSchemaMapping().keys.toList())
        assertEquals("TEXT", shape.toSchemaMapping()["a"])
    }

    @Test
    fun compareSatisfiesWithSpellingDifferences() {
        // Type comparison is exact on the normalized DataType: spelling and
        // whitespace differences ("decimal(10,2)" vs "DECIMAL(10, 2)") don't matter.
        val expected = Shape.of("a" to "decimal(10,2)", "b" to "int")
        val actual = Shape.of("a" to "DECIMAL(10, 2)", "b" to "INT")
        val cmp = expected.compare(actual)
        assertEquals(ShapeVerdict.SATISFIES, cmp.verdict)
        assertTrue(cmp.missing.isEmpty())
        assertTrue(cmp.additional.isEmpty())
        assertTrue(cmp.typeMismatches.isEmpty())
    }

    @Test
    fun compareHasAdditional() {
        val expected = Shape.of("a" to "INT")
        val actual = Shape.of("a" to "INT", "extra" to "TEXT")
        val cmp = expected.compare(actual)
        assertEquals(ShapeVerdict.HAS_ADDITIONAL, cmp.verdict)
        assertEquals(listOf(ColumnShape("extra", "TEXT")), cmp.additional)
    }

    @Test
    fun compareHasLessForMissingColumn() {
        val expected = Shape.of("a" to "INT", "b" to "TEXT")
        val actual = Shape.of("a" to "INT")
        val cmp = expected.compare(actual)
        assertEquals(ShapeVerdict.HAS_LESS, cmp.verdict)
        assertEquals(listOf(ColumnShape("b", "TEXT")), cmp.missing)
    }

    @Test
    fun compareTypeMismatchIsHasLess() {
        // A wrong type cannot satisfy the contract: mismatch => HAS_LESS, even though
        // the column exists. ARRAY<INT> vs ARRAY<FLOAT> is structural, not name-level.
        val expected = Shape.of("a" to "ARRAY<INT>")
        val actual = Shape.of("a" to "ARRAY<FLOAT>")
        val cmp = expected.compare(actual)
        assertEquals(ShapeVerdict.HAS_LESS, cmp.verdict)
        assertEquals(
            listOf(ColumnShape("a", "ARRAY<INT>") to ColumnShape("a", "ARRAY<FLOAT>")),
            cmp.typeMismatches,
        )
        // The extra-column check still reports nothing: the column names matched.
        assertTrue(cmp.additional.isEmpty())
    }

    @Test
    fun compareUnknownAsymmetry() {
        // UNKNOWN expected = declared-any: matches a concrete actual type.
        val anyExpected = Shape.of("a" to "UNKNOWN")
        assertEquals(
            ShapeVerdict.SATISFIES,
            anyExpected.compare(Shape.of("a" to "INT")).verdict,
        )

        // UNKNOWN actual vs concrete expected = mismatch: the actual side cannot
        // prove it satisfies the contract.
        val concreteExpected = Shape.of("a" to "INT")
        assertEquals(
            ShapeVerdict.HAS_LESS,
            concreteExpected.compare(Shape.of("a" to "UNKNOWN")).verdict,
        )
    }

    @Test
    fun unparsableTypeRaisesShapeError() {
        val bad = Shape.of("a" to "NOT A REAL ((( TYPE")
        assertFailsWith<ShapeError> { bad.compare(Shape.of("a" to "INT")) }
    }

    @Test
    fun shapeSerializationRoundTrip() {
        val shape = Shape(
            listOf(
                ColumnShape("a", "DECIMAL(10, 2)"),
                ColumnShape("b", "ARRAY<INT>", nullable = true),
            )
        )
        val encoded = json.encodeToString(shape)
        assertEquals(shape, json.decodeFromString<Shape>(encoded))

        // ShapeComparison round-trips too (verdict is derived, not serialized state).
        val cmp = Shape.of("a" to "INT").compare(Shape.of("a" to "TEXT"))
        val decoded = json.decodeFromString<ShapeComparison>(json.encodeToString(cmp))
        assertEquals(cmp, decoded)
        assertEquals(ShapeVerdict.HAS_LESS, decoded.verdict)
    }
}
