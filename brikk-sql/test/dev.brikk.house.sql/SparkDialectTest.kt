package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the Spark dialect port (reference/sqlglot/sqlglot/dialects/spark.py).
 * Every expected value is pinned to the Python oracle (parse_one(sql, read="spark")
 * .sql("spark")) at sqlglot v30.12.0-44-g93d16591.
 */
class SparkDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "spark").sql("spark")

    // sqlglot: SparkParser.FUNCTIONS["TRY_ELEMENT_AT"] + SparkGenerator.bracket_sql (safe)
    @Test
    fun tryElementAtRoundTrips() {
        assertEquals("SELECT TRY_ELEMENT_AT(arr, 1)", roundTrip("SELECT TRY_ELEMENT_AT(arr, 1)"))
    }

    // sqlglot: SparkParser._build_datediff (unit-based) + SparkGenerator.datediff_sql —
    // the unit is uppercased to a Var.
    @Test
    fun datediffUnitUppercased() {
        assertEquals("SELECT DATEDIFF(WEEK, a, b)", roundTrip("SELECT DATEDIFF(week, a, b)"))
    }

    // sqlglot: SparkParser.FUNCTIONS["LIKE"] = build_like(exp.Like) — this=arg0, expr=arg1.
    @Test
    fun likeFunctionBecomesBinary() {
        assertEquals("foo LIKE 'p'", roundTrip("LIKE(foo, 'p')"))
    }

    // sqlglot: Spark2Generator TRANSFORMS[exp.ArraySort]=None restores base ARRAY_SORT
    // (HiveGenerator would emit SORT_ARRAY).
    @Test
    fun arraySortStaysArraySort() {
        assertEquals("SELECT ARRAY_SORT(x)", roundTrip("SELECT ARRAY_SORT(x)"))
    }

    // sqlglot: SparkGenerator TRANSFORMS[exp.TryCast] — Spark3 supports TRY_CAST.
    @Test
    fun tryCastSupported() {
        assertEquals("SELECT TRY_CAST(x AS INT)", roundTrip("SELECT TRY_CAST(x AS INT)"))
    }
}
