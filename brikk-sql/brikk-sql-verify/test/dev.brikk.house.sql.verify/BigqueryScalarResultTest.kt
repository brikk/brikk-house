package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryScalarResultTest {
    @Test
    fun safeDividePreservesAlgebraAndHandlesZeroAndNullDenominators() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE inputs (id INTEGER, x BIGINT, y BIGINT)")
                statement.execute("INSERT INTO inputs VALUES " +
                    "(1, 2, 2), (2, 2, 0), (3, 2, NULL), (4, NULL, 2), " +
                    "(5, NULL, 0), (6, NULL, NULL), (7, 2, -2), (8, -1, 2)")
                check(
                    statement,
                    "SELECT SAFE_DIVIDE(x + 1, 2 * y) AS quotient FROM inputs ORDER BY id",
                    listOf(
                        listOf(0.75), listOf(null), listOf(null), listOf(null),
                        listOf(null), listOf(null), listOf(-0.75), listOf(0.0),
                    ),
                )
                check(
                    statement,
                    "SELECT SAFE_DIVIDE(x, y) AS quotient FROM inputs ORDER BY id",
                    listOf(
                        listOf(1.0), listOf(null), listOf(null), listOf(null),
                        listOf(null), listOf(null), listOf(-1.0), listOf(-0.5),
                    ),
                )
            }
        }
    }

    @Test
    fun greatestAndLeastPropagateEveryNullableArgument() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE inputs (id INTEGER, a BIGINT, b BIGINT, c BIGINT)")
                statement.execute("INSERT INTO inputs VALUES " +
                    "(1, 1, 2, 3), (2, NULL, 2, 3), (3, 1, NULL, 3), (4, 1, 2, NULL), " +
                    "(5, NULL, NULL, NULL), (6, -4, -2, -3), (7, 2, 2, 2)")
                val source = "SELECT GREATEST(a, b, c) AS maximum, LEAST(a, b, c) AS minimum FROM inputs ORDER BY id"
                check(
                    statement, source,
                    listOf(
                        listOf(3.0, 1.0), listOf(null, null), listOf(null, null), listOf(null, null),
                        listOf(null, null), listOf(-2.0, -4.0), listOf(2.0, 2.0),
                    ),
                )
                // The same rows distinguish BigQuery's NULL propagation from DuckDB's native behavior.
                for (read in listOf("duckdb", "postgres")) {
                    check(
                        statement, source,
                        listOf(
                            listOf(3.0, 1.0), listOf(3.0, 2.0), listOf(3.0, 1.0), listOf(2.0, 1.0),
                            listOf(null, null), listOf(-2.0, -4.0), listOf(2.0, 2.0),
                        ),
                        read = read,
                    )
                }
            }
        }
    }

    private fun check(statement: Statement, source: String, expected: List<List<Double?>>, read: String = "bigquery") {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = read)
        val generated = generator.generate(tree)
        assertEquals(emptyList(), generator.unsupportedMessages, generated)
        assertEquals(before, tree, "Source AST changed: $source")
        statement.executeQuery(generated).use { result ->
            val actual = buildList {
                while (result.next()) {
                    add((1..result.metaData.columnCount).map { (result.getObject(it) as Number?)?.toDouble() })
                }
            }
            assertEquals(expected, actual, "$read -> duckdb: $generated")
        }
    }
}
