package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the ClickHouse dialect wiring, each verified against the Python
 * oracle (reference/sqlglot v30.12.0-44-g93d16591): Nullable type wrapping, {name: Type}
 * query parameters, aggregate-function combinators with parameters, the
 * FINAL/PREWHERE/SETTINGS/FORMAT clauses, the `?:` ternary, TRY_CAST-to-Nullable
 * transpilation and ARRAY JOIN.
 */
class ClickhouseDialectTest {

    private fun roundTrip(sqlText: String): String =
        parseOne(sqlText, "clickhouse").sql("clickhouse")

    @Test
    fun textBecomesNullableString() {
        // datatype_sql wraps unmarked types in Nullable(...) and maps TEXT -> String
        assertEquals(
            "SELECT CAST(x AS Nullable(String))",
            transpile("SELECT CAST(x AS TEXT)", read = "", write = "clickhouse"),
        )
    }

    @Test
    fun queryParametersRoundTrip() {
        // PLACEHOLDER_PARSERS[L_BRACE] -> _parse_query_parameter / placeholder_sql
        assertEquals(
            "SELECT {abc: UInt32}, {b: String}",
            roundTrip("SELECT {abc: UInt32}, {b: String}"),
        )
    }

    @Test
    fun aggregateCombinatorsWithParams() {
        // _resolve_clickhouse_agg + _parse_func_params -> CombinedParameterizedAgg
        assertEquals(
            "SELECT quantileIf(0.5)(a, b > 0) FROM t",
            roundTrip("SELECT quantileIf(0.5)(a, b > 0) FROM t"),
        )
    }

    @Test
    fun finalPrewhereSettingsFormatRoundTrip() {
        assertEquals(
            "SELECT * FROM t FINAL PREWHERE x > 1 WHERE y = 2 SETTINGS max_threads = 1 FORMAT JSON",
            roundTrip(
                "SELECT * FROM t FINAL PREWHERE x > 1 WHERE y = 2 SETTINGS max_threads = 1 FORMAT JSON"
            ),
        )
    }

    @Test
    fun ternaryBecomesCase() {
        // _parse_assignment: `cond ? a : b` -> exp.If -> CASE WHEN
        assertEquals(
            "SELECT CASE WHEN cond THEN a ELSE b END FROM t",
            roundTrip("SELECT cond ? a : b FROM t"),
        )
    }

    @Test
    fun tryCastBecomesNullableCast() {
        // trycast_sql: casting into Nullable(T) behaves like TRY_CAST(x AS T)
        assertEquals(
            "SELECT CAST(x AS Nullable(Int32))",
            transpile("SELECT TRY_CAST(x AS Int32)", read = "duckdb", write = "clickhouse"),
        )
    }

    @Test
    fun arrayJoinRoundTrips() {
        // _parse_join: ARRAY JOIN operands become Column references, not Tables
        assertEquals(
            "SELECT x FROM t ARRAY JOIN arr AS a",
            roundTrip("SELECT x FROM t ARRAY JOIN arr AS a"),
        )
    }

    @Test
    fun refreshableMaterializedViews() {
        // sqlglot #7990: AutoRefreshProperty (REFRESH EVERY/AFTER/OFFSET/RANDOMIZE/DEPENDS/
        // SETTINGS/APPEND) with bare (keyword-less) schedule intervals. Verified against the
        // Python oracle — each round-trips identically.
        for (sql in listOf(
            "CREATE MATERIALIZED VIEW v REFRESH EVERY 30 SECOND AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH AFTER 5 MINUTE AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH DEPENDS ON db.x, db.y APPEND TO db.t AS SELECT 1",
            "CREATE MATERIALIZED VIEW v ON CLUSTER '{cluster}' REFRESH EVERY 1 MONTH OFFSET 5 DAY 2 HOUR " +
                "RANDOMIZE FOR 1 HOUR DEPENDS ON db.x, db.y SETTINGS refresh_retries = 5 APPEND TO db.t AS SELECT 1",
            "CREATE MATERIALIZED VIEW v REFRESH EVERY 1 HOUR (id UInt64) AS SELECT 1 AS id",
            "CREATE MATERIALIZED VIEW v REFRESH DEPENDS ON dep (id UInt64) AS SELECT 1",
        )) {
            assertEquals(sql, roundTrip(sql))
        }
    }
}
