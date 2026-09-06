package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executes the portable SQL subset in memory, not against a live Doris server. */
class DorisFullOuterJoinTest {
    private fun assertRows(
        stages: String,
        expected: List<List<String?>>,
        left: String? = "(1, 'g'), (2, 'g'), (4, 'left')",
        right: String? = "(1, 'g'), (3, 'g'), (5, 'right')",
        ordered: Boolean = false,
    ) {
        val fragment = SqlFragment("FROM a |> FULL OUTER JOIN b ON a.id = b.id $stages", "doris")
        assertTrue(fragment.isPipe)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE a (id INTEGER, category VARCHAR)")
                statement.execute("CREATE TABLE b (id INTEGER, category VARCHAR)")
                if (left != null) statement.execute("INSERT INTO a VALUES $left")
                if (right != null) statement.execute("INSERT INTO b VALUES $right")
                for (pretty in listOf(false, true)) {
                    val result = fragment.toExecutable("doris", pretty = pretty, trackSourceMap = true)
                    assertEquals(emptyList(), result.unsupportedMessages)
                    val verified = SqlVerifiers.forEngine("doris")!!.verify(result.sql)
                    assertTrue(verified.accepted, "Doris parser rejected ${result.sql}: ${verified.error}")
                    // Execute the generated SQL unchanged. Grammar acceptance alone missed this bug.
                    val actual = statement.executeQuery(result.sql).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add((1..rows.metaData.columnCount).map { rows.getString(it) })
                            }
                        }
                    }
                    if (ordered) {
                        assertEquals(expected, actual, result.sql)
                    } else {
                        assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), result.sql)
                    }
                }
            }
        }
    }

    @Test
    fun totalCountCoversMatchesUnmatchedDuplicatesNullsAndEmptyInputs() {
        val stages = "|> AGGREGATE COUNT(*) AS n"
        assertRows(stages, listOf(listOf("1")), left = "(1, 'g')", right = "(1, 'g')")
        assertRows(stages, listOf(listOf("5")))
        assertRows(stages, listOf(listOf("0")), left = null, right = null)
        assertRows(stages, listOf(listOf("3")), left = null)
        assertRows(stages, listOf(listOf("3")), right = null)
        assertRows(
            stages, listOf(listOf("6")),
            left = "(1, 'g'), (1, 'g'), (NULL, 'g')",
            right = "(1, 'g'), (1, 'g'), (NULL, 'g')",
        )
    }

    @Test
    fun groupedCountCombinesMatchedAndUnmatchedGroups() {
        assertRows(
            "|> AGGREGATE COUNT(*) AS n GROUP BY COALESCE(a.category, b.category) AS bucket",
            listOf(listOf("g", "3"), listOf("left", "1"), listOf("right", "1")),
        )
    }

    @Test
    fun distinctDeduplicatesAcrossTheWholeJoin() {
        assertRows(
            "|> SELECT DISTINCT COALESCE(a.category, b.category) AS category",
            listOf(listOf("g"), listOf("left"), listOf("right")),
        )
    }

    @Test
    fun orFilterDoesNotDuplicateMatchesBeforeLimit() {
        // Keep the reported query and also require LIMIT to exclude a third qualifying row.
        for (predicate in listOf("a.id = 1 OR b.id = 3", "a.id = 1 OR b.id IN (3, 5)")) {
            assertRows(
                "|> WHERE $predicate |> ORDER BY 1 DESC, 3 |> LIMIT 2",
                listOf(listOf("1", "g", "1", "g"), listOf(null, null, "3", "g")),
                ordered = true,
            )
        }
    }
}
