package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Row and output-column checks in embedded DuckDB, plus the native Doris grammar. */
class DorisDistinctSemanticsTest {
    private fun assertResult(
        source: String,
        columns: List<String>,
        expected: List<List<String?>>,
        values: String? = "(1, 'A'), (2, 'A'), (3, 'A'), (4, 'B'), (5, 'C')",
        extraColumns: String = "",
        setup: List<String> = emptyList(),
    ) {
        val fragment = SqlFragment(source, "doris")
        val original = fragment.ast.copy()
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, category VARCHAR$extraColumns)")
                if (values != null) statement.execute("INSERT INTO t VALUES $values")
                for (sql in setup) statement.execute(sql)
                for (pretty in listOf(false, true)) {
                    val result = fragment.toExecutable("doris", pretty = pretty, trackSourceMap = true)
                    assertEquals(emptyList(), result.unsupportedMessages, source)
                    assertSame(result.sql, assertNotNull(result.sourceMap).output)
                    assertTrue(verifier.verify(result.sql).accepted, result.sql)
                    statement.executeQuery(result.sql).use { rows ->
                        assertEquals(columns, (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }, result.sql)
                        val actual = buildList {
                            while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                        }
                        assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), result.sql)
                    }
                    assertEquals(original, fragment.ast, "Generation must not mutate its input")
                }
            }
        }
    }

    @Test
    fun distinctConsumesRowsSelectedByTheEarlierLimit() {
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT category |> LIMIT 2",
            listOf("category"), listOf(listOf("A")),
        )
    }

    @Test
    fun distinctConsumesRowsSelectedByTheEarlierOffset() {
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 3 OFFSET 1 |> SELECT DISTINCT category |> LIMIT 2",
            listOf("category"), listOf(listOf("A"), listOf("B")),
        )
    }

    @Test
    fun laterDistinctPreservesTheHeadsDistinctOnSelection() {
        for (stage in listOf("SELECT DISTINCT id, category", "SELECT id, category", "DISTINCT")) {
            assertResult(
                "SELECT DISTINCT ON (category) id, category FROM t ORDER BY category, id |> $stage",
                listOf("id", "category"), listOf(listOf("1", "A"), listOf("3", "B")),
                values = "(1, 'A'), (2, 'A'), (3, 'B')",
            )
        }
    }

    @Test
    fun distinctOnStarsDoNotExposeRankingHelpers() {
        for (source in listOf(
            "FROM t |> SELECT DISTINCT ON (category) *",
            "FROM t AS x |> SELECT DISTINCT ON (x.category) x.*",
            "FROM t |> SELECT DISTINCT ON (category) * |> SELECT *",
            "SELECT DISTINCT ON (category) * FROM t",
        )) {
            assertResult(
                source, listOf("id", "category"), listOf(listOf("1", "A"), listOf("2", "B")),
                values = "(1, 'A'), (2, 'B')",
            )
        }
    }

    @Test
    fun distinctOnPreservesUserColumnsNamedLikeRankingHelpers() {
        assertResult(
            "FROM t |> ORDER BY category, id |> SELECT DISTINCT ON (category) * |> SELECT *",
            listOf("id", "category", "_row_number", "_row_number_2"),
            listOf(listOf("1", "A", "99", "98"), listOf("3", "B", "95", "94")),
            values = "(1, 'A', 99, 98), (2, 'A', 97, 96), (3, 'B', 95, 94)",
            extraColumns = ", _row_number INTEGER, _row_number_2 INTEGER",
        )
    }

    @Test
    fun projectionAfterWholeRowDistinctCanStillContainDuplicates() {
        for (select in listOf("SELECT category", "SELECT ALL category")) {
            assertResult(
                "FROM t |> DISTINCT |> $select", listOf("category"),
                listOf(listOf("A"), listOf("A"), listOf("A"), listOf("B"), listOf("C")),
            )
        }
    }

    @Test
    fun restrictedInputKeepsQualifiedColumnsAndAliases() {
        for (source in listOf(
            "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT t.category",
            "FROM t AS x |> ORDER BY x.id |> LIMIT 3 |> SELECT DISTINCT x.category",
            "FROM t |> AS x |> ORDER BY x.id |> LIMIT 3 |> SELECT DISTINCT x.category",
            "FROM t AS x |> ORDER BY x.id |> LIMIT 3 |> DISTINCT |> SELECT DISTINCT x.category",
        )) {
            assertResult(source, listOf("category"), listOf(listOf("A")))
        }
        assertResult(
            "SELECT category AS bucket FROM t ORDER BY id LIMIT 3 |> SELECT DISTINCT bucket",
            listOf("bucket"), listOf(listOf("A")),
        )
        assertResult(
            "SELECT category AS bucket FROM t |> SELECT DISTINCT bucket",
            listOf("bucket"), listOf(listOf("A"), listOf("B"), listOf("C")),
        )
    }

    @Test
    fun standaloneDistinctAlsoRespectsTheInputRestriction() {
        assertResult(
            "SELECT category FROM t ORDER BY id LIMIT 3 |> DISTINCT",
            listOf("category"), listOf(listOf("A")),
        )
        assertResult(
            "SELECT category FROM t ORDER BY id LIMIT 3 OFFSET 1 |> DISTINCT",
            listOf("category"), listOf(listOf("A"), listOf("B")),
        )
    }

    @Test
    fun zeroLimitsAndLaterOrderingDoNotChangeEarlierRowSelection() {
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 0 |> SELECT DISTINCT category |> LIMIT 2",
            listOf("category"), emptyList(),
        )
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 4 |> SELECT DISTINCT category |> ORDER BY category DESC |> LIMIT 1",
            listOf("category"), listOf(listOf("B")),
        )
        assertResult(
            "SELECT category, id FROM t ORDER BY 2 DESC LIMIT 2 |> SELECT DISTINCT category",
            listOf("category"), listOf(listOf("B"), listOf("C")),
        )
    }

    @Test
    fun introducedCtesDoNotCaptureUserCteNames() {
        for (source in listOf(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * FROM __tmp1 ORDER BY id LIMIT 3 |> SELECT DISTINCT category",
            "FROM t |> AS __tmp1 |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT category",
        )) {
            assertResult(source, listOf("category"), listOf(listOf("A")))
        }
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT (SELECT MAX(y.id) FROM __tmp1 AS y) AS max_id",
            listOf("max_id"), listOf(listOf("100")),
            setup = listOf("CREATE TABLE __tmp1 (id INTEGER)", "INSERT INTO __tmp1 VALUES (100)"),
        )
    }

    @Test
    fun restrictedProjectionKeepsCorrelatedAndShadowedNamespaces() {
        assertResult(
            "FROM t AS x |> ORDER BY x.id |> LIMIT 3 " +
                "|> SELECT DISTINCT (SELECT MAX(y.category) FROM t AS y WHERE y.id = x.id) AS bucket",
            listOf("bucket"), listOf(listOf("A")),
        )
        assertResult(
            "FROM t AS x |> ORDER BY x.id |> LIMIT 3 " +
                "|> SELECT DISTINCT (SELECT category FROM t AS x WHERE x.id = 4) AS bucket",
            listOf("bucket"), listOf(listOf("B")),
        )
    }

    @Test
    fun nativeDistinctOnStarAppliesFinalLimitAfterRanking() {
        assertResult(
            "SELECT DISTINCT ON (category) * FROM t ORDER BY category, id LIMIT 2 OFFSET 1",
            listOf("id", "category"), listOf(listOf("4", "B"), listOf("5", "C")),
        )
        assertResult(
            "FROM t AS x |> ORDER BY x.category, x.id |> SELECT DISTINCT ON (x.category) x.*, x.id + 10 AS extra",
            listOf("id", "category", "extra"),
            listOf(listOf("1", "A", "11"), listOf("4", "B", "14"), listOf("5", "C", "15")),
        )
    }

    @Test
    fun restrictedDistinctOnHeadAlsoRanksBeforeItsFinalLimit() {
        assertResult(
            "SELECT DISTINCT ON (category) id, category FROM t ORDER BY category, id LIMIT 2 OFFSET 1 " +
                "|> SELECT DISTINCT id, category",
            listOf("id", "category"), listOf(listOf("4", "B"), listOf("5", "C")),
        )
    }

    @Test
    fun distinctOnKeepsItsRankingOrderAcrossInputBoundaries() {
        assertResult(
            "FROM t |> ORDER BY category, id DESC |> LIMIT 3 |> SELECT DISTINCT ON (category) *",
            listOf("id", "category"), listOf(listOf("3", "A")),
        )
        assertResult(
            "SELECT category, t.id AS original_id, -t.id AS id FROM t ORDER BY category, t.id DESC LIMIT 3 " +
                "|> SELECT DISTINCT ON (category) *",
            listOf("category", "original_id", "id"), listOf(listOf("A", "3", "-3")),
        )
        assertResult(
            "SELECT category, COUNT(*) AS n FROM t GROUP BY category ORDER BY COUNT(*) DESC LIMIT 3 " +
                "|> SELECT DISTINCT ON (category) *",
            listOf("category", "n"), listOf(listOf("A", "3"), listOf("B", "1"), listOf("C", "1")),
        )
        assertResult(
            "SELECT t.id, u.category FROM t JOIN t AS u ON t.id = u.id ORDER BY u.category, t.id DESC LIMIT 3 " +
                "|> SELECT DISTINCT ON (category) *",
            listOf("id", "category"), listOf(listOf("3", "A")),
        )
        assertResult(
            "SELECT x.* FROM t AS x ORDER BY x.category, x.id DESC |> SELECT DISTINCT ON (x.category) x.*",
            listOf("id", "category"), listOf(listOf("3", "A"), listOf("4", "B"), listOf("5", "C")),
        )
        assertResult(
            "SELECT category AS bucket, id FROM t ORDER BY category, id DESC LIMIT 3 |> SELECT DISTINCT ON (bucket) *",
            listOf("bucket", "id"), listOf(listOf("A", "3")),
        )
    }

    @Test
    fun aStarInsideAScalarSubqueryIsNotAnOutputStar() {
        assertResult(
            "FROM t |> SELECT DISTINCT ON (category) (SELECT * FROM (SELECT 42 AS n) AS one_column)",
            listOf("_col"), listOf(listOf("42"), listOf("42"), listOf("42")),
        )
    }

    @Test
    fun distinctHandlesNullGroupsAndEmptyInputs() {
        assertResult(
            "SELECT DISTINCT ON (category) * FROM t ORDER BY category, id",
            listOf("id", "category"), listOf(listOf("1", null), listOf("3", "A")),
            values = "(1, NULL), (2, NULL), (3, 'A')",
        )
        assertResult(
            "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT category",
            listOf("category"), listOf(listOf(null), listOf("A")),
            values = "(1, NULL), (2, NULL), (3, 'A'), (4, 'B')",
        )
        for (source in listOf(
            "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT category",
            "FROM t |> SELECT DISTINCT ON (category) category",
        )) {
            assertResult(source, listOf("category"), emptyList(), values = null)
        }
    }

    @Test
    fun localMultipartReferencesAreNotMistakenForCorrelations() {
        assertResult(
            "FROM t AS x |> ORDER BY x.id |> LIMIT 3 |> SELECT DISTINCT " +
                "(SELECT MAX(db2.t.id) FROM db2.t WHERE db2.t.id = x.id) AS matched_id",
            listOf("matched_id"), listOf(listOf("1"), listOf("2"), listOf("3")),
            setup = listOf("CREATE SCHEMA db2", "CREATE TABLE db2.t AS SELECT * FROM t"),
        )
    }
}
