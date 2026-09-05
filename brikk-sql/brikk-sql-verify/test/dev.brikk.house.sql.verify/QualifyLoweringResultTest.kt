package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ASTRA-001: execute the generated SQL, not just its upstream string oracle. */
class QualifyLoweringResultTest {
    private fun assertEquivalent(sql: String, expected: List<List<Int>>, target: String = "presto") {
        val lowered = Dialects.forName(target).generate(Dialects.forName("duckdb").parseOne(sql))
        assertFalse(lowered.contains("QUALIFY"), lowered)
        assertFalse(lowered.contains("DISTINCT ON"), lowered)
        if (target == "trino") assertTrue(SqlVerifiers.forEngine("trino")!!.verify(lowered).accepted, lowered)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            fun execute(query: String): Pair<List<String>, List<List<Int>>> =
                connection.createStatement().use { statement ->
                    statement.executeQuery(query).use { result ->
                        val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) }
                        val rows = buildList {
                            while (result.next()) add(names.indices.map { result.getInt(it + 1) })
                        }
                        names to rows
                    }
                }
            val source = execute(sql)
            assertEquals(expected, source.second, "source: $sql")
            assertEquals(source, execute(lowered), "lowered: $lowered")
        }
    }

    @Test
    fun qualifyBeforeLimit() {
        val sql = "SELECT x FROM (VALUES (1), (2)) AS t(x) " +
            "QUALIFY ROW_NUMBER() OVER (ORDER BY x DESC) = 1 ORDER BY x LIMIT 1"
        for (target in listOf("presto", "trino", "postgres")) {
            assertEquivalent(sql, listOf(listOf(2)), target)
        }
    }

    @Test
    fun qualifyBeforeOffsetAndLimit() {
        assertEquivalent(
            "SELECT x FROM (VALUES (1), (2), (3), (4)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x DESC) <= 3 ORDER BY x LIMIT 1 OFFSET 1",
            listOf(listOf(3)),
        )
    }

    @Test
    fun distinctAfterQualifyExcludesWindowHelper() {
        assertEquivalent(
            "SELECT DISTINCT x FROM (VALUES (1), (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 ORDER BY x LIMIT 1 OFFSET 1",
            emptyList(),
        )
        assertEquivalent(
            "SELECT DISTINCT x FROM (VALUES (1), (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 ORDER BY x",
            listOf(listOf(1)),
        )
    }

    @Test
    fun finalOrderResolvesQualifiedColumnsAndAliases() {
        assertEquivalent(
            "SELECT t.x AS y FROM (VALUES (1), (2), (3)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x DESC) <= 2 ORDER BY t.x DESC LIMIT 1",
            listOf(listOf(3)),
        )
        assertEquivalent(
            "SELECT x + 1 AS y FROM (VALUES (1), (2), (3)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x DESC) <= 2 ORDER BY y DESC LIMIT 1",
            listOf(listOf(4)),
        )
        assertEquivalent(
            "SELECT -x AS x FROM (VALUES (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY x + 0 LIMIT 1",
            listOf(listOf(-1)),
        )
        assertEquivalent(
            "SELECT x AS y FROM (VALUES (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY Y DESC LIMIT 1",
            listOf(listOf(2)), "postgres",
        )
    }

    @Test
    fun finalOrderCanUseHiddenColumnsAndWindows() {
        assertEquivalent(
            "SELECT x FROM (VALUES (1, 30), (2, 20), (3, 10)) AS t(x, y) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 ORDER BY t.y LIMIT 1",
            listOf(listOf(2)),
        )
        assertEquivalent(
            "SELECT x FROM (VALUES (1), (2), (3)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 " +
                "ORDER BY ROW_NUMBER() OVER (ORDER BY x DESC) LIMIT 1",
            listOf(listOf(2)),
        )
    }

    @Test
    fun distinctOnBeforeOffsetAndLimitKeepsFinalOrdering() {
        assertEquivalent(
            "SELECT DISTINCT ON (x) x, y FROM (VALUES (1, 10), (1, 20), (2, 30)) AS t(x, y) " +
                "ORDER BY x, y DESC LIMIT 1 OFFSET 1",
            listOf(listOf(2, 30)),
        )
        assertEquivalent(
            "SELECT DISTINCT ON (x) x FROM (VALUES (1, 10), (1, 20), (2, 30)) AS t(x, y) " +
                "ORDER BY x DESC, y DESC",
            listOf(listOf(2), listOf(1)),
        )
    }

    @Test
    fun qualifyBeforeDistinctOn() {
        for (target in listOf("presto", "mysql")) {
            assertEquivalent(
                "SELECT DISTINCT ON (x) x, y FROM (VALUES (1, 10), (1, 20), (2, 30)) AS t(x, y) " +
                    "QUALIFY ROW_NUMBER() OVER (PARTITION BY x ORDER BY y DESC) = 1 " +
                    "ORDER BY x, y LIMIT 1",
                listOf(listOf(1, 20)), target,
            )
        }
    }

    @Test
    fun orderExpressionsKeepAggregateWrappersAndSubqueryScope() {
        assertEquivalent(
            "SELECT x FROM (VALUES (1), (2)) AS t(x) GROUP BY x " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 " +
                "ORDER BY SUM(x) FILTER (WHERE x > 1) LIMIT 1",
            listOf(listOf(2)),
        )
        assertEquivalent(
            "SELECT x FROM (VALUES (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) <= 2 " +
                "ORDER BY EXISTS(SELECT 1 FROM (VALUES (2)) AS u(y) WHERE u.y = t.x) DESC LIMIT 1",
            listOf(listOf(2)),
        )
        assertEquivalent(
            "WITH c AS (SELECT 1 AS n) SELECT x FROM (VALUES (1), (2)) AS t(x) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY x LIMIT (SELECT n FROM c)",
            listOf(listOf(1)), "postgres",
        )
    }

    @Test
    fun distinctOnRankingResolvesOrderOrdinalsAndAliases() {
        assertEquivalent(
            "SELECT DISTINCT ON (x) x, y FROM (VALUES (1, 10), (1, 20)) AS t(x, y) ORDER BY 1, 2 DESC",
            listOf(listOf(1, 20)),
        )
        assertEquivalent(
            "SELECT DISTINCT ON (x) x, y + 1 AS z FROM (VALUES (1, 10), (1, 20)) AS t(x, y) " +
                "ORDER BY x, z DESC",
            listOf(listOf(1, 21)),
        )
    }

    @Test
    fun generatedAliasesCannotCaptureSortInputs() {
        assertEquivalent(
            "SELECT x FROM (VALUES (1, 0, 20), (2, 0, 10)) AS t(x, y, _o) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY y, _o, x LIMIT 1",
            listOf(listOf(2)),
        )
        assertEquivalent(
            "SELECT x FROM (VALUES (1, 20), (2, 10)) AS t(x, _w) " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY _w LIMIT 1",
            listOf(listOf(2)),
        )
    }
}
