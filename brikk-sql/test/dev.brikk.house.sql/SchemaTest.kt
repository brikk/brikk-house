package dev.brikk.house.sql

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.SchemaError
import dev.brikk.house.sql.optimizer.ensureSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hand assertions against Python sqlglot.schema.MappingSchema oracle values. */
class SchemaTest {

    @Test
    fun nestedTableLookupViaTrie() {
        // Depth-2 schema: unqualified "t" resolves through the trie (single possibility).
        val schema = MappingSchema(mapOf("db" to mapOf("t" to mapOf("a" to "int"))))
        assertEquals(listOf("this", "db"), schema.supportedTableArgs)
        assertEquals(listOf("a"), schema.columnNames("db.t"))
        assertEquals(listOf("a"), schema.columnNames("t"))

        // Depth-3 with catalog.
        val deep = MappingSchema(
            mapOf("cat" to mapOf("db" to mapOf("t" to mapOf("a" to "int"))))
        )
        assertEquals(3, deep.depth())
        assertEquals(listOf("this", "db", "catalog"), deep.supportedTableArgs)
        assertEquals(listOf("a"), deep.columnNames("cat.db.t"))
        assertEquals(listOf("a"), deep.columnNames("t"))
    }

    @Test
    fun ambiguousMappingRaises() {
        val schema = MappingSchema(
            mapOf(
                "db1" to mapOf("t" to mapOf("a" to "int")),
                "db2" to mapOf("t" to mapOf("b" to "int")),
            )
        )
        val error = assertFailsWith<SchemaError> { schema.columnNames("t") }
        // Python: "Ambiguous mapping for t: db1, db2."
        assertEquals("Ambiguous mapping for t: db1, db2.", error.message)
        // Qualified lookups still resolve.
        assertEquals(listOf("a"), schema.columnNames("db1.t"))
        assertEquals(listOf("b"), schema.columnNames("db2.t"))
    }

    @Test
    fun identifierNormalization() {
        // The base dialect lowercases unquoted identifiers.
        val schema = MappingSchema(mapOf("TBL" to mapOf("COL" to "varchar(10)")))
        assertEquals<Map<String, Any?>>(
            mapOf("tbl" to mapOf("col" to "varchar(10)")),
            schema.mapping,
        )
        assertEquals(listOf("col"), schema.columnNames("tbl"))
        assertEquals(listOf("col"), schema.columnNames("TBL"))
        val colType = schema.getColumnType("tbl", "CoL")
        assertEquals(DType.VARCHAR, colType.thisArg)
        assertEquals(
            "10",
            ((colType.expressionsArg[0] as Expression).thisArg as Literal).name,
        )

        // normalize=false keeps the given casing.
        val raw = MappingSchema(
            mapOf("TBL" to mapOf("COL" to "int")),
            normalize = false,
            dialect = Dialects.BASE,
        )
        assertEquals(listOf("COL"), raw.columnNames("TBL", normalize = false))
    }

    @Test
    fun columnTypesAndUnknownMisses() {
        val schema = MappingSchema()
        assertTrue(schema.empty)
        schema.addTable("x", mapOf("a" to "int", "b" to "text"))
        assertFalse(schema.empty)
        assertEquals(listOf("a", "b"), schema.columnNames("x"))
        assertEquals(DType.INT, schema.getColumnType("x", "a").thisArg)
        assertEquals(DType.TEXT, schema.getColumnType("x", "b").thisArg)
        assertTrue(schema.hasColumn("x", "a"))
        assertFalse(schema.hasColumn("x", "z"))
        // Misses resolve to UNKNOWN, mirroring exp.DType.UNKNOWN.into_expr().
        assertEquals(DType.UNKNOWN, schema.getColumnType("x", "zzz").thisArg)
        assertEquals(DType.UNKNOWN, schema.getColumnType("nope", "a").thisArg)
    }

    @Test
    fun addTableDepthMismatchAndEnsureSchema() {
        val schema = MappingSchema(mapOf("db" to mapOf("t" to mapOf("a" to "int"))))
        // Python: "Table x must match the schema's nesting level: 2."
        val error = assertFailsWith<SchemaError> {
            schema.addTable("x", mapOf("a" to "int"))
        }
        assertTrue(error.message!!.contains("must match the schema's nesting level: 2"))
        schema.addTable("db.u", mapOf("z" to "bigint"))
        assertEquals(listOf("z"), schema.columnNames("db.u"))

        val ensured = ensureSchema(mapOf("t" to mapOf("a" to "int")))
        assertEquals(listOf("a"), ensured.columnNames("t"))
        assertTrue(ensureSchema(ensured) === ensured)
    }
}
