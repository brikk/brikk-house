package dev.brikk.house.intellij

import org.junit.Test
import org.junit.Assert.*

class ContractsTest {
    @Test fun dialectIdentitiesAreExplicit() {
        assertEquals("DuckDBSQL", BrikkDialect.DUCKDB.languageId)
        assertEquals("doris", BrikkDialect.DORIS.sqlName)
        assertEquals("trino", BrikkDialect.TRINO.sqlName)
        assertEquals("TrinoSQL", BrikkDialect.TRINO.languageId)
        assertEquals("clickhouse", BrikkDialect.CLICKHOUSE.sqlName)
        assertEquals("ClickHouseSQL", BrikkDialect.CLICKHOUSE.languageId)
    }

    @Test fun dialectsAreRegistryEntriesNotAClosedSet() {
        assertTrue(BrikkDialect.CLICKHOUSE in BrikkDialect.entries)
        assertSame(BrikkDialect.DUCKDB, BrikkDialect.of("duckdb"))
        // A registered dialect keeps its plugin language whatever the runtime's parameter declares.
        assertEquals("ClickHouseSQL", BrikkDialect.of("clickhouse", "BrikkSQL").languageId)
        val postgres = BrikkDialect.of("postgres", "BrikkSQL")
        assertEquals("postgres", postgres.sqlName)
        assertEquals("BrikkSQL", postgres.languageId)
        assertFalse(postgres.registered)
        assertTrue(BrikkDialect.TRINO.registered)
        assertEquals(postgres, BrikkDialect.of("postgres"))
        assertEquals(BrikkDialect.GENERIC_LANGUAGE_ID, BrikkDialect.of("postgres").languageId)
        assertNotEquals(BrikkDialect.DUCKDB, postgres)
        assertEquals("DUCKDB", BrikkDialect.DUCKDB.name)
    }

    @Test fun columnContractsAnswerNullabilityAndOriginOnce() {
        val property = object : NavigationTarget {
            override val kind = NavigationKind.PROPERTY
            override fun navigate(requestFocus: Boolean) = Unit
        }
        val fallback = object : NavigationTarget {
            override val kind = NavigationKind.RELATION_FALLBACK
            override fun navigate(requestFocus: Boolean) = Unit
        }
        val declared = ColumnContract("kind", "com.example.EventKind", ColumnNullability.NULLABLE, SqlFamily.UNKNOWN, property)
        assertEquals(true, declared.nullableOrNull())
        assertEquals("brikk column", declared.originLabel())
        assertEquals("EventKind", declared.kotlinTypeName)
        val generated = declared.copy(nullability = ColumnNullability.NON_NULL, navigation = fallback)
        assertEquals(false, generated.nullableOrNull())
        assertEquals("relation contract", generated.originLabel())
        assertNull(declared.copy(nullability = ColumnNullability.UNKNOWN).nullableOrNull())
        assertNull(declared.copy(kotlinClassId = null).kotlinTypeName)
        // No navigation is not evidence of a relation-level contract.
        assertEquals("brikk column", declared.copy(navigation = null).originLabel())
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

    @Test fun generatedColumnsAreRelationContractsNavigatingToTheirRelation() {
        val fallback = object : NavigationTarget {
            override val kind = NavigationKind.RELATION_FALLBACK
            override fun navigate(requestFocus: Boolean) = Unit
        }
        val shape = RelationShape(ShapeKnowledge.FULL, listOf(ColumnContract("code", "kotlin.String", ColumnNullability.UNKNOWN, SqlFamily.TEXT, fallback)),
            ShapeOrigin.GENERATED, fallback)
        val src = BrikkRelationFacts.factsOf(mapOf("src" to ShapeResult.Available(shape)))!!.slots.getValue("src")
        assertEquals(dev.brikk.house.sql.scope.Knowledge.FULL, src.knowledge)
        val code = src.columns.single()
        assertEquals(dev.brikk.house.sql.scope.ColumnOrigin.RELATION_CONTRACT, code.origin)
        assertEquals(dev.brikk.house.sql.scope.Nullability.UNKNOWN, code.nullability)
        assertEquals(src.provenance, code.provenance)
        assertEquals("String", code.displayType)
    }

    @Test fun aColumnWithoutNavigationIsDeclaredInFactsAndLabels() {
        val column = ColumnContract("id", "kotlin.Int", ColumnNullability.NON_NULL, SqlFamily.INTEGER, navigation = null)
        val shape = RelationShape(ShapeKnowledge.PARTIAL, listOf(column), ShapeOrigin.DECLARED)
        val fact = BrikkRelationFacts.factsOf(mapOf("src" to ShapeResult.Available(shape)))!!.slots.getValue("src").columns.single()
        assertEquals("brikk column", column.originLabel())
        assertEquals(dev.brikk.house.sql.scope.ColumnOrigin.DECLARED, fact.origin)
    }

    @Test fun anUnavailableContractMakesTheWholeHostPending() {
        val partial = ShapeResult.Available(RelationShape(ShapeKnowledge.PARTIAL, emptyList(), ShapeOrigin.UNKNOWN))
        assertNotNull(BrikkRelationFacts.factsOf(mapOf("a" to partial)))
        assertNull(BrikkRelationFacts.factsOf(mapOf("a" to partial, "b" to ShapeResult.Unavailable("Generated row members are not available yet"))))
        assertTrue(BrikkRelationFacts.factsOf(mapOf("c" to ShapeResult.NotRelation))!!.slots.isEmpty())
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
