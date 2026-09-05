package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the DuckDB dialect wiring, each verified against the Python
 * oracle (reference/sqlglot v30.12.0-44-g93d16591): struct literals, 1-based index
 * offset, ~ regex operator, sample-method defaulting, simplified PIVOT statements
 * and duckdb->presto transpilation through the registry.
 */
class DuckdbDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "duckdb").sql("duckdb")

    @Test
    fun structLiteralRoundTrips() {
        // MAP_KEYS_ARE_ARBITRARY_EXPRESSIONS + duckdb _struct_sql `{key: value}` form
        assertEquals("SELECT {'a': 1, 'b': [1, 2]}", roundTrip("SELECT {'a': 1, 'b': [1, 2]}"))
    }

    @Test
    fun indexOffsetIsAppliedTowardsBase() {
        // DuckDB INDEX_OFFSET=1: the bracket index is shifted for the base dialect
        assertEquals(
            "SELECT ARRAY(1, 2)[0]",
            transpile("SELECT LIST_VALUE(1, 2)[1]", read = "duckdb", write = ""),
        )
    }

    @Test
    fun tildeParsesToRegexpFullMatch() {
        // RANGE_PARSERS[TILDE] -> exp.RegexpFullMatch; regexplike_sql renders it back
        assertEquals("SELECT REGEXP_FULL_MATCH(a, b)", roundTrip("SELECT a ~ b"))
    }

    @Test
    fun tableSampleDefaultsToReservoir() {
        // DuckDBParser._parse_table_sample defaults size samples to RESERVOIR
        assertEquals(
            "SELECT x FROM t USING SAMPLE RESERVOIR (5 ROWS)",
            roundTrip("SELECT x FROM t USING SAMPLE 5"),
        )
    }

    @Test
    fun simplifiedPivotStatementRoundTrips() {
        // Parser._parse_simplified_pivot (https://duckdb.org/docs/sql/statements/pivot)
        assertEquals(
            "PIVOT Cities ON Year USING SUM(Population)",
            roundTrip("PIVOT Cities ON Year USING SUM(Population)"),
        )
    }

    @Test
    fun strSplitBracketToPresto() {
        // STR_SPLIT -> exp.Split -> Presto SPLIT; both dialects share INDEX_OFFSET=1
        assertEquals(
            "SELECT SPLIT('a,b', ',')[1]",
            transpile("SELECT STR_SPLIT('a,b', ',')[1]", read = "duckdb", write = "presto"),
        )
    }
}
