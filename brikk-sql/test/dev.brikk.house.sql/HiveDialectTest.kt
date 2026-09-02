package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the Hive dialect port (reference/sqlglot/sqlglot/dialects/hive.py).
 * Every expected value below is pinned to the Python oracle (parse_one(sql, read="hive")
 * .sql("hive")) at sqlglot v30.12.0-44-g93d16591.
 */
class HiveDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "hive").sql("hive")

    // sqlglot: HiveParser.LOG_DEFAULTS_TO_LN — single-arg LOG(x) is LN(x).
    @Test
    fun logDefaultsToLn() {
        assertEquals("SELECT LN(x)", roundTrip("SELECT LOG(x)"))
    }

    // sqlglot: HiveParser._parse_types — casts to CHAR/VARCHAR(len) collapse to STRING
    // outside schema definitions.
    @Test
    fun varcharCastCollapsesToString() {
        assertEquals("SELECT CAST(x AS STRING)", roundTrip("SELECT CAST(x AS VARCHAR(10))"))
    }

    // sqlglot: Hive.ESCAPED_SEQUENCES + STRINGS_SUPPORT_ESCAPED_SEQUENCES — backslashes
    // are escaped in string literals.
    @Test
    fun backslashEscapedInStrings() {
        assertEquals("SELECT '\\n'", roundTrip("SELECT '\\n'"))
    }

    // sqlglot: HiveGenerator.struct_sql — NAMED_STRUCT keys are dropped (Hive has no
    // named structs); values are wrapped in a positional STRUCT.
    @Test
    fun namedStructBecomesPositionalStruct() {
        assertEquals("SELECT STRUCT(1)", roundTrip("SELECT NAMED_STRUCT('a', 1)"))
    }

    // sqlglot: HiveParser.FUNCTIONS["PERCENTILE"] round-trips via TRANSFORMS[Quantile].
    @Test
    fun percentileRoundTrips() {
        assertEquals("SELECT PERCENTILE(x, 0.5)", roundTrip("SELECT PERCENTILE(x, 0.5)"))
    }

    // sqlglot: Hive.NORMALIZATION_STRATEGY = CASE_INSENSITIVE (identifiers unquoted).
    @Test
    fun sizeAndSplitRoundTrip() {
        assertEquals("SELECT SIZE(x)", roundTrip("SELECT SIZE(x)"))
        assertEquals("SELECT SPLIT(x, y)", roundTrip("SELECT SPLIT(x, y)"))
    }
}
