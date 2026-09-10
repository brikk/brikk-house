package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryArrayToStringResultTest {
    @Test
    fun bq14AndLiteralEdgeCasesExecuteGeneratedSql() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((arguments, expected) in listOf(
                    "['cake', 'pie', NULL], '--', 'MISSING'" to "cake--pie--MISSING",
                    "[NULL, 'a', NULL, 'b', NULL], '-', '?'" to "?-a-?-b-?",
                    "['a', NULL, 'b'], '', '?'" to "a?b",
                    "['a', NULL, 'b'], '-', ''" to "a--b",
                    "[NULL, NULL], '-', '?'" to "?-?",
                    "[], '-', '?'" to "",
                    "CAST(NULL AS ARRAY<STRING>), '-', '?'" to null,
                    "NULL, '-', '?'" to null,
                    // The pin lowers explicit NULL through COALESCE; null elements remain omitted.
                    "['a', NULL, 'b'], '-', NULL" to "a-b",
                    "[NULL, NULL], '-', NULL" to null,
                    "['a', NULL], NULL, '?'" to null,
                    "['a', NULL], \"'\", \"it's missing\"" to "a'it's missing",
                    """[r'a\b', NULL], '\\', r'c\d'""" to "a\\b\\c\\d",
                )) {
                    check(statement, "SELECT ARRAY_TO_STRING($arguments)", listOf(listOf(expected)))
                }
            }
        }
    }

    @Test
    fun runtimeFieldsNullsAndLambdaNameCollisions() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE inputs (id INTEGER, a VARCHAR[], d VARCHAR, x VARCHAR, x_1 VARCHAR)")
                statement.execute("INSERT INTO inputs VALUES " +
                    "(1, ['a', NULL, 'b'], '-', '?', '!'), " +
                    "(2, ['a', NULL, 'b'], '-', NULL, '!'), " +
                    "(3, [], '-', '?', '!'), (4, NULL, '-', '?', '!'), " +
                    "(5, ['a', NULL], NULL, '?', '!'), (6, [NULL, NULL], '-', NULL, '!'), " +
                    "(7, ['a', NULL, 'b'], '', '?', '!')")
                val expected = listOf("a-?-b", "a-b", "", null, "a-?", null, "a-?-b").map { listOf(it) }
                check(statement,
                    "SELECT ARRAY_TO_STRING(a, '-', X) AS x_1 FROM inputs ORDER BY id", expected)
                check(statement,
                    "SELECT ARRAY_TO_STRING(x.a, '-', x.x) FROM inputs AS x ORDER BY id", expected)
                check(statement,
                    "SELECT ARRAY_TO_STRING(a, '-', `null text`) FROM " +
                        "(SELECT a, d, x AS `null text`, id FROM inputs) AS q ORDER BY id", expected)
            }
        }
    }

    @Test
    fun nativeTwoArgumentCallsAndArrayJoinStillExecute() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (read in listOf("duckdb", "bigquery")) {
                    check(statement,
                        "SELECT ARRAY_TO_STRING(['a', NULL, 'b'], '-'), ARRAY_TO_STRING([], '-'), " +
                            "ARRAY_TO_STRING(NULL, '-'), ARRAY_TO_STRING(['a', NULL, 'b'], '')",
                        listOf(listOf("a-b", "", null, "ab")), read)
                }
                check(statement, "SELECT ARRAY_JOIN(['a', NULL, 'b'], '-')", listOf(listOf("a-b")), "duckdb")
            }
        }
    }

    private fun check(statement: Statement, source: String, expected: List<List<String?>>, read: String = "bigquery") {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = read)
        val generated = generator.generate(tree)
        assertEquals(emptyList(), generator.unsupportedMessages, generated)
        assertEquals(before, tree, source)
        statement.executeQuery(generated).use { result ->
            for (i in 1..result.metaData.columnCount) assertEquals(Types.VARCHAR, result.metaData.getColumnType(i), generated)
            val actual = buildList {
                while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
            }
            assertEquals(expected, actual, "$read -> duckdb: $generated")
        }
    }
}
