package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.AggregateTypeColumnConstraint
import dev.brikk.house.sql.ast.ColumnConstraint
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.CommentColumnConstraint
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DefaultColumnConstraint
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.NotNullColumnConstraint
import dev.brikk.house.sql.ast.PartitionRange
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DorisDdlRoundTripTest {

    // Complements DorisDialectTest and SqlVerifierTest's literal SQL checks with the public API path.
    private fun assertDdlRoundTrip(input: String, expected: String = input): Expression {
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"), "Doris native parser must be available")
        val source = SqlFragment(input, "doris")
        for (fragment in listOf(source, SqlFragment(expected, "doris"))) {
            assertIs<Create>(fragment.ast, "Expected structured CREATE TABLE or CREATE INDEX: ${fragment.sql}")
            assertFalse(fragment.isRawPassthroughStatement, fragment.sql)
            val result = fragment.transpileTo("doris")
            assertEquals(emptyList(), result.unsupportedMessages, fragment.sql)
            assertFalse(result.isRawPassthroughStatement, fragment.sql)
            assertEquals(expected, result.sql, "DDL must survive generation and re-parsing: ${fragment.sql}")

            // Grammar acceptance only, not server-side schema or semantic analysis.
            for (sql in listOf(fragment.sql, result.sql)) {
                val verified = verifier.verify(sql)
                assertTrue(verified.verified, "Doris parser unavailable: ${verified.warning}")
                assertFalse(verified.advisory, "Expected Doris's native grammar")
                assertNull(verified.warning, sql)
                assertTrue(verified.accepted, "Doris FE parser rejected `$sql`: ${verified.error}")
            }
        }
        return source.ast
    }

    @Test
    fun aggregateKeyRetainsAggregatorsAndAdjacentConstraints() {
        val ast = assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v BIGINT SUM NULL DEFAULT '0' COMMENT 'total', " +
                "b BITMAP BITMAP_UNION, h HLL HLL_UNION, m INT MAX, " +
                "r INT REPLACE_IF_NOT_NULL, q QUANTILE_STATE QUANTILE_UNION) AGGREGATE KEY (k)",
        )
        val column = ast.findAll(ColumnDef::class).single { it.name == "v" }
        val constraints = (column.args["constraints"] as List<*>).map {
            assertIs<ColumnConstraint>(it).args["kind"]
        }
        assertEquals(4, constraints.size)
        assertEquals("SUM", assertIs<AggregateTypeColumnConstraint>(constraints[0]).name)
        assertEquals(true, assertIs<NotNullColumnConstraint>(constraints[1]).args["allow_null"])
        assertEquals("0", assertIs<DefaultColumnConstraint>(constraints[2]).name)
        assertEquals("total", assertIs<CommentColumnConstraint>(constraints[3]).name)
    }

    @Test
    fun mixedPartitionBoundsRetainPropertiesAndNormalizeBareMaxvalue() {
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) " +
                "(PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01') ('replication_num'='1'), " +
                "PARTITION p3 VALUES LESS THAN MAXVALUE)",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) " +
                "(PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01') ('replication_num'='1'), " +
                "PARTITION p3 VALUES LESS THAN (MAXVALUE))",
        )
    }

    @Test
    fun multiColumnLessThanBoundsStayFlat() {
        val ast = assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d, k) " +
                "(PARTITION p1 VALUES LESS THAN ('2020-01-01', 100), " +
                "PARTITION p2 VALUES LESS THAN ('2020-02-01', MAXVALUE))",
        )
        val bounds = ast.findAll(PartitionRange::class).toList()
        assertEquals(2, bounds.size)
        for (bound in bounds) {
            assertEquals(2, bound.expressionsArg.size)
            assertTrue(bound.expressionsArg.all { it is Expression }, "LESS THAN bounds must remain a flat tuple")
        }
    }

    @Test
    fun createIndexRetainsOrderedColumnsUsingPropertiesAndComment() {
        assertDdlRoundTrip(
            "CREATE INDEX IF NOT EXISTS idx ON db.t (s, k) USING INVERTED " +
                "PROPERTIES ('parser'='english', 'support_phrase'='true') COMMENT 'search'",
        )
    }

    @Test
    fun typedVariantRetainsFieldTypesNestedArrayAndProperties() {
        assertDdlRoundTrip(
            "CREATE TABLE t (v VARIANT<'x':LARGEINT, 'ip4':IPV4, 'arr':ARRAY<STRING>>, " +
                "w VARIANT<'a':INT, properties('variant_max_subcolumns_count'='10')>)",
        )
    }

    @Test
    fun aggregateStateRetainsArgumentOrderTypesAndNullability() {
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v AGG_STATE<sum(INT)> GENERIC, " +
                "w AGG_STATE<max_by(INT NOT NULL, VARCHAR(10) NULL)> GENERIC) AGGREGATE KEY (k)",
        )
    }
}
