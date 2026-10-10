package dev.brikk.house.intellij

import org.junit.Test
import org.junit.Assert.*

class ContractsTest {
    @Test fun dialectIdentitiesAreExplicit() {
        assertEquals("DuckDBSQL", BrikkDialect.DUCKDB.languageId)
        assertEquals("doris", BrikkDialect.DORIS.sqlName)
    }

    @Test fun editingFactsNeverClaimExecutionAndDistinguishEntryRoles() {
        val context = BrikkContext(BrikkDialect.DUCKDB, emptyList(), listOf(
            TemplateEntry(HostRange(0, 2), EntryRole.BIND, "n", ":n"),
            TemplateEntry(HostRange(3, 5), EntryRole.BIND, "n", ":n"),
            TemplateEntry(HostRange(6, 10), EntryRole.RELATION_SLOT, "src", "src"),
            TemplateEntry(HostRange(11, 15), EntryRole.SQL_CONSTANT, "COLS", "id"),
        ), emptyMap())
        assertEquals(listOf("n"), context.binds)
        assertEquals(listOf("src"), context.slots)
        assertFalse(context.uncertain)
        assertFalse(context.executable)
        assertTrue(context.pipeEditingEnabled)
        assertTrue(context.copy(entries = context.entries + TemplateEntry(HostRange(16, 17), EntryRole.PENDING)).uncertain)
    }

    @Test fun scalarFamiliesDoNotInventNativeSqlTypes() {
        assertEquals(SqlFamily.TIMESTAMP, BrikkTypeFamilies.forClassId("java.time.Instant"))
        assertEquals(SqlFamily.UNKNOWN, BrikkTypeFamilies.forClassId("my.Instant"))
        assertEquals(SqlFamily.DECIMAL, BrikkTypeFamilies.forClassId("java.math.BigDecimal"))
        val column = ColumnContract("amount", "java.math.BigDecimal", ColumnNullability.NULLABLE, SqlFamily.DECIMAL)
        assertNull(column.exactSqlType)
        assertNull(column.quotedSqlIdentity)
    }

    @Test fun partialEmptyIsNotUnavailableOrClosedEmpty() {
        val partial = ShapeResult.Available(RelationShape(ShapeKnowledge.PARTIAL, emptyList(), ShapeOrigin.TYPE_BOUND))
        assertFalse(partial == ShapeResult.Unavailable("pending"))
        assertEquals(ShapeKnowledge.PARTIAL, partial.shape.knowledge)
    }

    @Test fun sourceRangesCannotBeInvalid() {
        for ((start, end) in listOf(-1 to 1, 3 to 2)) {
            try { HostRange(start, end); fail("Expected invalid range refusal") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
