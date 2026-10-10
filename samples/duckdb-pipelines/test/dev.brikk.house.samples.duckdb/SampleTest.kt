package dev.brikk.house.samples.duckdb

import dev.brikk.house.sql.runtime.Partial
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SampleTest {
    private fun day(value: String) = LocalDate.parse(value)
    private fun money(value: String) = BigDecimal(value)

    @Test
    fun allFourReportsExecuteWithExpectedRowsAndNoUnsupportedPreservation() {
        sampleDatabase().use { connection ->
            val results = reports("acme", SAMPLE_START, SAMPLE_END).associate { report ->
                val execution = execute(connection, report.relation)
                assertTrue(execution.rendered.stages.all { it.unsupportedMessages.isEmpty() })
                report.name to execution.result
            }
            assertEquals(QueryRows(listOf("tenant", "day", "valid_orders", "revenue"), listOf(
                listOf("acme", day("2026-01-01"), 4L, money("177.50")),
                listOf("acme", day("2026-01-02"), 2L, money("87.00")),
            )), results.getValue("daily-revenue"))
            assertEquals(QueryRows(listOf("tenant", "day", "refunds", "refunded_amount"), listOf(
                listOf("acme", day("2026-01-01"), 1L, money("10.00")),
                listOf("acme", day("2026-01-02"), 2L, money("7.50")),
            )), results.getValue("daily-refunds"))
            assertEquals(QueryRows(listOf("tenant", "segment", "valid_orders", "revenue"), listOf(
                listOf("acme", "gold", 3L, money("205.50")),
                listOf("acme", "unclassified", 1L, money("40.00")),
                listOf("acme", "unmatched", 2L, money("19.00")),
            )), results.getValue("customer-revenue"))
            assertEquals(QueryRows(listOf("tenant", "events", "missing_customer", "missing_kind", "invalid_amount", "null_payloads", "unknown_kind"),
                listOf(listOf("acme", 15L, 5L, 3L, 2L, 1L, 1L))), results.getValue("data-quality"))
        }
    }

    @Test
    fun sharedJsonExtractionAndCleaningKeepBadRowsInsteadOfHidingThem() {
        sampleDatabase().use { connection ->
            val result = execute(connection, cleanedEvents("acme", SAMPLE_START, SAMPLE_END)).result
            val rows = result.rows.associate { row ->
                val record = result.columns.zip(row).toMap()
                (record.getValue("event_id") as Number).toLong() to record
            }
            assertEquals((1L..12L).toSet() + setOf(16L, 17L, 18L), rows.keys)
            assertEquals(" C-001 ", rows.getValue(1)["customer_raw"])
            assertEquals("c-001", rows.getValue(1)["customer_key"])
            assertEquals("order", rows.getValue(1)["event_kind"])
            assertEquals("web", rows.getValue(1)["channel"])
            assertEquals(money("100.00"), rows.getValue(1)["amount"])
            assertEquals(money("25.50"), rows.getValue(2)["amount"]) // Numeric and string JSON amounts agree.
            assertNull(rows.getValue(8)["amount"])
            assertNull(rows.getValue(10)["payload"])
            assertNull(rows.getValue(17)["customer_key"])
            assertEquals("unknown", rows.getValue(17)["channel"])
            assertNull(rows.getValue(18)["customer_key"])
            assertNull(rows.getValue(18)["event_kind"])
            assertEquals("unknown", rows.getValue(18)["channel"])
        }
    }

    @Test
    fun tenantIsolationAndHalfOpenBoundariesPreventMoneyFromLeakingIntoReports() {
        sampleDatabase().use { connection ->
            val beta = reports("beta", SAMPLE_START, SAMPLE_END).associate { it.name to execute(connection, it.relation).result }
            assertEquals(listOf(listOf("beta", day("2026-01-01"), 1L, money("500.00"))), beta.getValue("daily-revenue").rows)
            assertEquals(emptyList(), beta.getValue("daily-refunds").rows)
            assertEquals(listOf(listOf("beta", "platinum", 1L, money("500.00"))), beta.getValue("customer-revenue").rows)
            assertEquals(listOf(listOf("beta", 1L, 0L, 0L, 0L, 0L, 0L)), beta.getValue("data-quality").rows)
            val empty = reports("absent", SAMPLE_START, SAMPLE_END)
            assertTrue(empty.all { execute(connection, it.relation).result.rows.isEmpty() })
        }
    }

    @Test
    fun ordinaryCallsReuseOneGraphPrefixAndTwoInputBindingsRemainNamespaced() {
        val views = reports("acme", SAMPLE_START, SAMPLE_END)
        val prefix = views.first().relation.inputs.getValue("events")
        views.forEach { assertSame(prefix, it.relation.inputs.getValue("events")) }
        val join = views.single { it.name == "customer-revenue" }.relation
        assertNotNull(join.inputs["customers"])
        assertEquals(2, join.bindings().values.count { it == "acme" })
        assertTrue("tenant" !in join.bindings().keys, "Colliding tenant binds must be renamed")
        val rendered = join.renderWithDiagnostics()
        assertEquals(5, rendered.stages.size)
        assertContains(rendered.sql, "/* sample: event source */")
        val source = rendered.stages.first()
        assertTrue(source.diagnostics.isEmpty(), "Native source was unnecessarily regenerated")
        assertContains(jdbcQuery(rendered.sql, join.bindings()).sql, "/* sample: event source */")
    }

    @Test
    fun jdbcUsesExactRangesForRepeatedParametersWithoutTouchingLiteralsCommentsOrCasts() {
        val sql = "SELECT ':n 😀' AS note, :n::INTEGER + :n AS value /* :unused */"
        val adapted = jdbcQuery(sql, mapOf("n" to 3))
        assertEquals("SELECT ':n 😀' AS note, ?::INTEGER + ? AS value /* :unused */", adapted.sql)
        assertEquals(listOf(3, 3), adapted.values)
        sampleDatabase().use { connection ->
            assertEquals(listOf(listOf(":n 😀", 6)), execute(connection, Rel<Partial>(sql, "duckdb").bind("n", 3)).result.rows)
        }
    }

    @Test
    fun jdbcRefusesMissingExtraAndPositionalBindingsButAcceptsAnExplicitNull() {
        val sql = "SELECT CAST(:n AS INTEGER) AS n"
        assertFailsWith<IllegalArgumentException> { jdbcQuery(sql, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { jdbcQuery(sql, mapOf("n" to 1, "extra" to 2)) }
        assertFailsWith<IllegalArgumentException> { jdbcQuery("SELECT ? AS n", emptyMap()) }
        assertEquals(listOf(null), jdbcQuery(sql, mapOf("n" to null)).values)
        sampleDatabase().use { connection ->
            assertEquals(listOf(listOf(null)), execute(connection, Rel<Partial>(sql, "duckdb").bind("n", null)).result.rows)
        }
    }

    @Test
    fun bareColonProjectionsArePreservedAndExecuteWithoutAWorkaround() {
        for (sql in listOf("SELECT :n AS n", "SELECT :value AS result", "SELECT :n::INTEGER AS n")) {
            assertEquals(sql, SqlFragment(sql, "duckdb").toSourcePreservingExecutable().sql)
            assertEquals(sql, SqlFragment(sql, "postgres").toSourcePreservingExecutable().sql)
            val name = if (sql.contains(":value")) "value" else "n"
            sampleDatabase().use { connection ->
                assertEquals(listOf(listOf(7)), execute(connection, Rel<Partial>(sql, "duckdb").bind(name, 7)).result.rows)
            }
        }
        val native = "SELECT ${'$'}n AS n"
        assertEquals("SELECT ? AS n", jdbcQuery(native, mapOf("n" to 3)).sql)
        sampleDatabase().use { connection ->
            assertEquals(listOf(listOf(3)), execute(connection, Rel<Partial>(native, "duckdb").bind("n", 3)).result.rows)
        }
    }

    @Test
    fun theCompilerSchemaIsTheSameDdlThatCreatesTheRealDatabase() {
        sampleDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("DESCRIBE sample.raw_events").use { result ->
                    val columns = buildList { while (result.next()) add(Triple(result.getString("column_name"), result.getString("column_type"), result.getString("null"))) }
                    assertEquals(listOf(
                        Triple("event_id", "BIGINT", "NO"), Triple("tenant", "VARCHAR", "NO"),
                        Triple("event_at", "TIMESTAMP WITH TIME ZONE", "NO"), Triple("payload", "JSON", "YES"),
                    ), columns)
                }
                statement.executeQuery("SELECT COUNT(*) FROM sample.raw_events").use { result ->
                    assertTrue(result.next())
                    assertEquals(18, result.getInt(1))
                }
            }
        }
    }
}
