package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryRoundingResultTest {
    @Test
    fun explicitModesExecuteBothBq30Examples() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                check(
                    statement,
                    "SELECT ROUND(NUMERIC '2.25', 1, 'ROUND_HALF_AWAY_FROM_ZERO'), " +
                        "ROUND(NUMERIC '2.25', 1, 'ROUND_HALF_EVEN')",
                    listOf(listOf(2.3, 2.2)),
                )
            }
        }
    }

    @Test
    fun positiveAndNegativeTiesAndNonTiesAtPositiveZeroAndNegativeScales() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                // Both parities of the retained digit distinguish ties-to-even from ties-away.
                for ((scale, cases) in listOf(
                    0 to listOf(
                        "2.5" to listOf(3.0, 2.0), "3.5" to listOf(4.0, 4.0),
                        "2.4" to listOf(2.0, 2.0), "2.6" to listOf(3.0, 3.0),
                    ),
                    1 to listOf(
                        "2.25" to listOf(2.3, 2.2), "2.35" to listOf(2.4, 2.4),
                        "2.24" to listOf(2.2, 2.2), "2.26" to listOf(2.3, 2.3),
                    ),
                    2 to listOf(
                        "2.125" to listOf(2.13, 2.12), "2.135" to listOf(2.14, 2.14),
                        "2.124" to listOf(2.12, 2.12), "2.126" to listOf(2.13, 2.13),
                    ),
                    -1 to listOf(
                        "25" to listOf(30.0, 20.0), "35" to listOf(40.0, 40.0),
                        "24" to listOf(20.0, 20.0), "26" to listOf(30.0, 30.0),
                    ),
                    -2 to listOf(
                        "250" to listOf(300.0, 200.0), "350" to listOf(400.0, 400.0),
                        "249" to listOf(200.0, 200.0), "251" to listOf(300.0, 300.0),
                    ),
                )) {
                    for ((value, expected) in cases) {
                        for (sign in listOf(1, -1)) {
                            val operand = "CAST('${if (sign < 0) "-" else ""}$value' AS NUMERIC(18, 4))"
                            check(
                                statement,
                                "SELECT ROUND($operand, $scale, 'ROUND_HALF_AWAY_FROM_ZERO'), " +
                                    "ROUND($operand, $scale, 'ROUND_HALF_EVEN')",
                                listOf(expected.map { it * sign }),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun groupedDecimalOperandsAndNullsExecuteAtRuntime() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE inputs (id INTEGER, x DECIMAL(18, 4), y DECIMAL(18, 4))")
                statement.execute("INSERT INTO inputs VALUES " +
                    "(1, 0.125, 4), (2, 0.125, 0), (3, NULL, 4), (4, 0.125, NULL), (5, -1, 4)")
                check(
                    statement,
                    "SELECT ROUND((x + 1) * (y - 2), (2 - 1), 'ROUND_HALF_AWAY_FROM_ZERO'), " +
                        "ROUND((x + 1) * (y - 2), (2 - 1), 'ROUND_HALF_EVEN') FROM inputs ORDER BY id",
                    listOf(
                        listOf(2.3, 2.2), listOf(-2.3, -2.2), listOf(null, null),
                        listOf(null, null), listOf(0.0, 0.0),
                    ),
                )
                check(
                    statement,
                    "SELECT ROUND(x, NULL, 'ROUND_HALF_AWAY_FROM_ZERO'), " +
                        "ROUND(x, NULL, 'ROUND_HALF_EVEN') FROM inputs ORDER BY id",
                    List(5) { listOf(null, null) },
                )
            }
        }
    }

    @Test
    fun modeLessRoundStillUsesDuckdbTiesAwayFromZero() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                val source = "SELECT ROUND(2.5), ROUND(-2.5), ROUND(2.25, 1), ROUND(-2.25, 1), " +
                    "ROUND(25, -1), ROUND(-25, -1), ROUND(NULL), ROUND(2.25, NULL)"
                for (read in listOf("duckdb", "bigquery")) {
                    check(
                        statement, source,
                        listOf(listOf(3.0, -3.0, 2.3, -2.3, 30.0, -30.0, null, null)),
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
