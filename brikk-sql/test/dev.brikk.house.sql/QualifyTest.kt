package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.OptimizeError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Full-pipeline qualify smoke test over a TPCH-ish schema. Every expected string was
 * produced by Python sqlglot's optimizer.qualify.qualify with default options
 * (reference/sqlglot @ v30.12.0-44-g93d16591).
 */
class QualifyTest {

    private val schema: Map<String, Any?> = mapOf(
        "lineitem" to mapOf(
            "l_orderkey" to "INT",
            "l_partkey" to "INT",
            "l_quantity" to "DOUBLE",
            "l_extendedprice" to "DOUBLE",
        ),
        "orders" to mapOf(
            "o_orderkey" to "INT",
            "o_custkey" to "INT",
            "o_totalprice" to "DOUBLE",
        ),
        "customer" to mapOf("c_custkey" to "INT", "c_name" to "VARCHAR"),
        "nation" to mapOf("c_custkey" to "INT", "n_name" to "VARCHAR"),
    )

    private fun qualified(sql: String): String =
        qualify(parseOne(sql), schema = schema).sql()

    @Test
    fun starExpansionWithSchema() {
        assertEquals(
            "SELECT \"lineitem\".\"l_orderkey\" AS \"l_orderkey\", " +
                "\"lineitem\".\"l_partkey\" AS \"l_partkey\", " +
                "\"lineitem\".\"l_quantity\" AS \"l_quantity\", " +
                "\"lineitem\".\"l_extendedprice\" AS \"l_extendedprice\" " +
                "FROM \"lineitem\" AS \"lineitem\"",
            qualified("SELECT * FROM lineitem"),
        )
    }

    @Test
    fun unqualifiedJoinColumnsAreDisambiguated() {
        assertEquals(
            "SELECT \"lineitem\".\"l_orderkey\" AS \"l_orderkey\", " +
                "\"orders\".\"o_totalprice\" AS \"o_totalprice\" " +
                "FROM \"lineitem\" AS \"lineitem\" JOIN \"orders\" AS \"orders\" " +
                "ON \"lineitem\".\"l_orderkey\" = \"orders\".\"o_orderkey\"",
            qualified(
                "SELECT l_orderkey, o_totalprice FROM lineitem " +
                    "JOIN orders ON l_orderkey = o_orderkey"
            ),
        )
    }

    @Test
    fun usingExpansion() {
        assertEquals(
            "SELECT \"nation\".\"n_name\" AS \"n_name\", " +
                "\"customer\".\"c_name\" AS \"c_name\" " +
                "FROM \"customer\" AS \"customer\" JOIN \"nation\" AS \"nation\" " +
                "ON \"customer\".\"c_custkey\" = \"nation\".\"c_custkey\"",
            qualified("SELECT n_name, c_name FROM customer JOIN nation USING (c_custkey)"),
        )
    }

    @Test
    fun usingExpansionCoalescesStarColumns() {
        assertEquals(
            "SELECT COALESCE(\"customer\".\"c_custkey\", \"nation\".\"c_custkey\") " +
                "AS \"c_custkey\", \"customer\".\"c_name\" AS \"c_name\", " +
                "\"nation\".\"n_name\" AS \"n_name\" " +
                "FROM \"customer\" AS \"customer\" JOIN \"nation\" AS \"nation\" " +
                "ON \"customer\".\"c_custkey\" = \"nation\".\"c_custkey\"",
            qualified("SELECT * FROM customer JOIN nation USING (c_custkey)"),
        )
    }

    @Test
    fun ordinalGroupByExpansion() {
        assertEquals(
            "SELECT \"orders\".\"o_custkey\" AS \"o_custkey\", " +
                "SUM(\"orders\".\"o_totalprice\") AS \"_col_1\" " +
                "FROM \"orders\" AS \"orders\" GROUP BY \"orders\".\"o_custkey\"",
            qualified("SELECT o_custkey, SUM(o_totalprice) FROM orders GROUP BY 1"),
        )
    }

    @Test
    fun aliasRefExpansionAndOrdinalOrderBy() {
        assertEquals(
            "SELECT \"orders\".\"o_custkey\" AS \"ck\", " +
                "\"orders\".\"o_totalprice\" * 2 AS \"twice\" " +
                "FROM \"orders\" AS \"orders\" WHERE \"orders\".\"o_custkey\" > 0 " +
                "ORDER BY \"twice\"",
            qualified(
                "SELECT o_custkey AS ck, o_totalprice * 2 AS twice FROM orders " +
                    "WHERE ck > 0 ORDER BY 2"
            ),
        )
    }

    @Test
    fun cteStarExpansion() {
        assertEquals(
            "WITH \"big\" AS (SELECT \"orders\".\"o_orderkey\" AS \"o_orderkey\" " +
                "FROM \"orders\" AS \"orders\" WHERE \"orders\".\"o_totalprice\" > 100) " +
                "SELECT \"big\".\"o_orderkey\" AS \"o_orderkey\" FROM \"big\" AS \"big\"",
            qualified(
                "WITH big AS (SELECT o_orderkey FROM orders WHERE o_totalprice > 100) " +
                    "SELECT * FROM big"
            ),
        )
    }

    @Test
    fun subqueryStarExpansion() {
        assertEquals(
            "SELECT \"sub\".\"l_partkey\" AS \"l_partkey\", " +
                "\"sub\".\"l_quantity\" AS \"l_quantity\" " +
                "FROM (SELECT \"lineitem\".\"l_partkey\" AS \"l_partkey\", " +
                "\"lineitem\".\"l_quantity\" AS \"l_quantity\" " +
                "FROM \"lineitem\" AS \"lineitem\") AS \"sub\"",
            qualified("SELECT * FROM (SELECT l_partkey, l_quantity FROM lineitem) AS sub"),
        )
    }

    @Test
    fun aliasedJoinWithGroupBy() {
        assertEquals(
            "SELECT \"c\".\"c_name\" AS \"c_name\", " +
                "SUM(\"o\".\"o_totalprice\") AS \"_col_1\" " +
                "FROM \"customer\" AS \"c\" JOIN \"orders\" AS \"o\" " +
                "ON \"c\".\"c_custkey\" = \"o\".\"o_custkey\" GROUP BY \"c\".\"c_name\"",
            qualified(
                "SELECT c.c_name, SUM(o.o_totalprice) FROM customer AS c " +
                    "JOIN orders AS o ON c.c_custkey = o.o_custkey GROUP BY c.c_name"
            ),
        )
    }

    @Test
    fun validationErrorOnUnknownColumn() {
        val error = assertFailsWith<OptimizeError> {
            qualified(
                "SELECT * FROM customer JOIN orders " +
                    "ON customer.c_custkey = orders.o_custkey WHERE unknown_col > 1"
            )
        }
        assertEquals(true, error.message!!.contains("Column 'unknown_col' could not be resolved"))
    }
}
