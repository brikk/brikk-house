package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the Spark2 dialect port (reference/sqlglot/sqlglot/dialects/spark2.py),
 * focused on behaviors that differ from Spark (the Spark3 leaf). Expected values are pinned
 * to the Python oracle (parse_one(sql, read="spark2").sql("spark2")) at sqlglot
 * v30.12.0-44-g93d16591.
 */
class Spark2DialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "spark2").sql("spark2")

    // sqlglot: Spark2 has no TRY_CAST in TRANSFORMS (SparkGenerator adds it) — Spark2 lowers
    // TRY_CAST to a plain CAST, unlike Spark.
    @Test
    fun tryCastLowersToCast() {
        assertEquals("SELECT CAST(x AS INT)", roundTrip("SELECT TRY_CAST(x AS INT)"))
    }

    // sqlglot: Spark2Parser inherits Hive TRUNC -> DateTrunc; unit stays a string Literal.
    @Test
    fun truncRoundTrips() {
        assertEquals("SELECT TRUNC(date_col, 'MM')", roundTrip("SELECT TRUNC(date_col, 'MM')"))
    }
}
