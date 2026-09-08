package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** References keep row-selection operations in separate relations, independent of the lowerer. */
class DorisPipeStageOrderTest {
    private fun check(
        source: String,
        reference: String,
        expected: List<List<String?>>,
        columns: List<String> = listOf("id", "category"),
        values: String? = "(1, 'A'), (2, 'A'), (3, 'B')",
        setup: List<String> = emptyList(),
    ) {
        val fragment = SqlFragment(source, "doris")
        val before = Serde.dump(fragment.ast)
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        val schema = mapOf("t" to mapOf("id" to "INT", "category" to "VARCHAR"), "u" to mapOf("id" to "INT"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, category VARCHAR)")
                if (values != null) statement.execute("INSERT INTO t VALUES $values")
                for (sql in setup) statement.execute(sql)
                for (sql in listOf(reference) + listOf(false, true).map { pretty ->
                    val result = fragment.toExecutable("doris", pretty = pretty)
                    assertEquals(emptyList(), result.unsupportedMessages, source)
                    assertSame(result.sql, assertNotNull(result.sourceMap).output)
                    assertTrue(verifier.verify(result.sql).accepted, result.sql)
                    val resolved = qualify(Dialects.DORIS.parseOne(result.sql), dialect = Dialects.DORIS,
                        schema = schema, inferSchema = false, validateQualifyColumns = true)
                    assertEquals(columns, resolved.namedSelects, result.sql)
                    assertEquals(before, Serde.dump(fragment.ast), "Generation mutated $source")
                    result.sql
                }) {
                    statement.executeQuery(sql).use { rows ->
                        assertEquals(columns, (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }, sql)
                        val actual = buildList {
                            while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                        }
                        assertEquals(expected.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), "$source\n$sql")
                    }
                }
            }
        }
    }

    @Test
    fun whereConsumesThePreviouslyLimitedRows() {
        check("FROM t |> ORDER BY id |> LIMIT 2 |> WHERE id > 1",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 2) AS limited WHERE id > 1",
            listOf(listOf("2", "A")))
        check("FROM t AS x |> ORDER BY x.id |> LIMIT 2 |> WHERE x.id > 1",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 2) AS limited WHERE id > 1",
            listOf(listOf("2", "A")))
    }

    @Test
    fun whereConsumesThePreviouslyOffsetRows() {
        for (restriction in listOf("OFFSET 1", "LIMIT 2 OFFSET 1")) {
            check("FROM t |> ORDER BY id |> $restriction |> WHERE id > 1",
                "SELECT * FROM (SELECT * FROM t ORDER BY id $restriction) AS sliced WHERE id > 1",
                listOf(listOf("2", "A"), listOf("3", "B")))
        }
    }

    @Test
    fun whereDoesNotChooseAReplacementDistinctOnRow() {
        check("SELECT DISTINCT ON (category) id, category FROM t ORDER BY id |> WHERE id > 1",
            "SELECT id, category FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS rn " +
                "FROM t) AS ranked WHERE rn = 1 AND id > 1",
            listOf(listOf("3", "B")))
    }

    @Test
    fun whereRemainsAfterQualifyAndGroupedHeads() {
        check("SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) < 3 |> WHERE id > 1",
            "SELECT id, category FROM (SELECT *, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS ranked " +
                "WHERE rn < 3 AND id > 1", listOf(listOf("2", "A")))
        check("SELECT category, COUNT(*) AS n FROM t GROUP BY category HAVING COUNT(*) >= 1 |> WHERE n > 1",
            "SELECT * FROM (SELECT category, COUNT(*) AS n FROM t GROUP BY category HAVING COUNT(*) >= 1) AS grouped WHERE n > 1",
            listOf(listOf("A", "2")), columns = listOf("category", "n"))
    }

    @Test
    fun laterWhereOrderAndLimitCannotChangeTheFirstSlice() {
        check("FROM t |> ORDER BY id |> LIMIT 2 |> WHERE id > 1 OR category = 'B' |> WHERE category = 'A' " +
            "|> ORDER BY id DESC |> LIMIT 1",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 2) AS limited " +
                "WHERE (id > 1 OR category = 'B') AND category = 'A' ORDER BY id DESC LIMIT 1",
            listOf(listOf("2", "A")))
        check("FROM t |> WHERE id > 1 |> ORDER BY id |> LIMIT 2",
            "SELECT * FROM (SELECT * FROM t WHERE id > 1) AS filtered ORDER BY id LIMIT 2",
            listOf(listOf("2", "A"), listOf("3", "B")))
    }

    @Test
    fun whereUsesTheProjectedAliasRatherThanTheOriginalFromScope() {
        check("SELECT id, category AS bucket FROM t ORDER BY id LIMIT 2 |> WHERE bucket = 'A' AND id > 1",
            "SELECT * FROM (SELECT id, category AS bucket FROM t ORDER BY id LIMIT 2) AS projected WHERE bucket = 'A' AND id > 1",
            listOf(listOf("2", "A")), columns = listOf("id", "bucket"))
        check("SELECT id + 1 AS next_id FROM t |> WHERE next_id > 2",
            "SELECT * FROM (SELECT id + 1 AS next_id FROM t) AS projected WHERE next_id > 2",
            listOf(listOf("3"), listOf("4")), columns = listOf("next_id"))
    }

    @Test
    fun emptyAndNullInputsStayInsideTheRequestedSlice() {
        check("FROM t |> ORDER BY id |> LIMIT 2 |> WHERE id > 1",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 2) AS limited WHERE id > 1", emptyList(), values = null)
        check("FROM t |> ORDER BY id |> LIMIT 0 |> WHERE id > 1",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 0) AS limited WHERE id > 1", emptyList())
        check("FROM t |> ORDER BY id |> LIMIT 2 |> WHERE category IS NULL",
            "SELECT * FROM (SELECT * FROM t ORDER BY id LIMIT 2) AS limited WHERE category IS NULL",
            listOf(listOf("1", null)), values = "(1, NULL), (2, 'A'), (3, NULL)")
    }

    @Test
    fun fullJoinOutputIsFilteredAfterItsSlice() {
        check("FROM t |> FULL OUTER JOIN u ON t.id = u.id |> SELECT t.id AS left_id, u.id AS right_id " +
            "|> ORDER BY COALESCE(left_id, right_id) |> LIMIT 3 |> WHERE right_id IS NOT NULL",
            "SELECT left_id, right_id FROM (SELECT t.id AS left_id, u.id AS right_id " +
                "FROM t FULL OUTER JOIN u ON t.id = u.id ORDER BY COALESCE(t.id, u.id) LIMIT 3) AS limited " +
                "WHERE right_id IS NOT NULL", listOf(listOf("1", "1")), columns = listOf("left_id", "right_id"),
            setup = listOf("CREATE TABLE u (id INTEGER)", "INSERT INTO u VALUES (1), (4)"))
    }

    @Test
    fun filteringBoundariesKeepTheExistingNamespaceSafetyContract() {
        val lateral = "t LATERAL VIEW EXPLODE(t.arr) e AS item"
        val inputs = mapOf("t" to mapOf("id" to "INT", "category" to "VARCHAR", "arr" to "ARRAY<INT>"))
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        for (boundary in listOf("LIMIT 2", "OFFSET 1", "LIMIT 2 OFFSET 1", "LIMIT 0", "DISTINCT")) {
            for (predicate in listOf("t.id > 1", "e.item > 1")) {
                val fragment = SqlFragment("FROM $lateral |> $boundary |> WHERE $predicate", "doris")
                assertTrue(fragment.ast is PipeQuery)
                assertFailsWith<UnsupportedError> { fragment.toExecutable("doris") }
            }
            for ((source, columns) in listOf(
                "FROM t AS a |> $boundary |> WHERE a.id > 1" to listOf("id", "category", "arr"),
                "SELECT t.id AS base_id, e.item AS item_value FROM $lateral |> $boundary |> WHERE item_value > 1" to
                    listOf("base_id", "item_value"),
                "FROM $lateral |> AS r |> $boundary |> WHERE r.item > 1" to listOf("id", "category", "arr", "item"),
                "FROM t AS a |> $boundary |> WHERE EXISTS(SELECT 1 FROM t AS b WHERE b.id = a.id)" to
                    listOf("id", "category", "arr"),
            )) {
                val fragment = SqlFragment(source, "doris")
                val original = Serde.dump(fragment.ast)
                for (pretty in listOf(false, true)) {
                    val result = fragment.toExecutable("doris", pretty = pretty)
                    assertEquals(emptyList(), result.unsupportedMessages, source)
                    assertSame(result.sql, assertNotNull(result.sourceMap).output)
                    assertTrue(verifier.verify(result.sql).accepted, result.sql)
                    val resolved = qualify(Dialects.DORIS.parseOne(result.sql), dialect = Dialects.DORIS,
                        schema = inputs, inferSchema = false, validateQualifyColumns = true)
                    assertEquals(columns, resolved.namedSelects, result.sql)
                    assertEquals(original, Serde.dump(fragment.ast))
                }
            }
        }
        // Doris LATERAL VIEW output is native-only here; it is not rewritten for DuckDB.
    }
}
