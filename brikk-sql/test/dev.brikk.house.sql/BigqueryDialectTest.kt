package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the BigQuery dialect port (reference/sqlglot/sqlglot/dialects/
 * bigquery.py). Expected values are pinned to the Python oracle
 * (parse_one(sql, read="bigquery").sql("bigquery")) at sqlglot v30.12.0-44-g93d16591,
 * except the pipe-syntax case which is a brikk-native behavior (pipes kept first-class
 * rather than sqlglot's CTE rewrite).
 */
class BigqueryDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "bigquery").sql("bigquery")

    // sqlglot: BigQueryGenerator.NAMED_PLACEHOLDER_TOKEN = "@" — named parameter @p.
    @Test
    fun namedParameterRoundTrips() {
        assertEquals("SELECT @param", roundTrip("SELECT @param"))
    }

    // brikk-native: pipe (|>) syntax is kept first-class in every dialect (BigQuery is
    // its origin). sqlglot rewrites pipes into CTEs; brikk preserves them.
    @Test
    fun pipeSyntaxKeptFirstClass() {
        assertEquals(
            "FROM t |> WHERE x > 1 |> SELECT x",
            roundTrip("FROM t |> WHERE x > 1 |> SELECT x"),
        )
    }

    // sqlglot: BigQuery.Tokenizer BYTE_STRINGS + BYTE_STRING_IS_BYTES_TYPE — b'..' literals.
    @Test
    fun byteStringRoundTrips() {
        assertEquals("SELECT b'abc'", roundTrip("SELECT b'abc'"))
    }

    // sqlglot: BigQueryGenerator TRANSFORMS[exp.SafeDivide] = rename_func("SAFE_DIVIDE").
    @Test
    fun safeDivideRoundTrips() {
        assertEquals("SELECT SAFE_DIVIDE(a, b)", roundTrip("SELECT SAFE_DIVIDE(a, b)"))
    }

    // sqlglot: BigQueryGenerator.TYPE_MAPPING[BIGINT] = "INT64".
    @Test
    fun int64TypeRoundTrips() {
        assertEquals("SELECT CAST(x AS INT64)", roundTrip("SELECT CAST(x AS INT64)"))
    }

    // sqlglot: BigQueryParser.FUNCTIONS["DIV"] = IntDiv + Generator rename_func("DIV").
    @Test
    fun intDivRoundTrips() {
        assertEquals("SELECT DIV(10, 3)", roundTrip("SELECT DIV(10, 3)"))
    }

    // sqlglot: TO_HEX -> exp.LowerHex, HEX_FUNC = "TO_HEX" round-trips.
    @Test
    fun toHexRoundTrips() {
        assertEquals("SELECT TO_HEX(x)", roundTrip("SELECT TO_HEX(x)"))
    }

    // sqlglot: BigQuery.UNNEST_COLUMN_ONLY + inline array literal.
    @Test
    fun unnestArrayRoundTrips() {
        assertEquals("SELECT * FROM UNNEST([1, 2, 3])", roundTrip("SELECT * FROM UNNEST([1, 2, 3])"))
    }
}
