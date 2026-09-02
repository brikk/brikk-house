package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * EVAL-01 (TODO-rectify-from-eval.md): the base generator must render every internal
 * `TsOrDsTo{Time,Timestamp,Datetime,Date}` coercion node, never leak its pseudo-function
 * name (`TS_OR_DS_TO_*`) into target SQL.
 *
 * MySQL `DATE_FORMAT(x, fmt)` and BigQuery `DATETIME(x)` / `TIME(x)` / `FORMAT_*` parse to
 * these nodes; the expected strings below are the pinned Python oracle's output for every
 * in-scope target (sqlglot: Generator.tsordsto{time,timestamp,datetime}_sql + exp.cast
 * idempotence against the target's TYPE_MAPPING).
 */
class TsOrDsCoercionTest {

    private fun check(read: String, sql: String, expected: Map<String, String>) {
        for ((write, exp) in expected) {
            val actual = transpile(sql, read = read, write = write)
            assertFalse(
                "TS_OR_DS_TO_" in actual,
                "[$read -> ${write.ifEmpty { "base" }}] leaked an internal coercion node: $actual",
            )
            assertEquals(exp, actual, "[$read -> ${write.ifEmpty { "base" }}] $sql")
        }
    }

    @Test
    fun mysqlDateFormatCoercesToTimestampEverywhere() = check(
        "mysql",
        "SELECT DATE_FORMAT(dt, '%Y-%m-%d') FROM t",
        mapOf(
            "trino" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "presto" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "duckdb" to "SELECT STRFTIME(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "postgres" to "SELECT TO_CHAR(CAST(dt AS TIMESTAMP), 'YYYY-MM-DD') FROM t",
            "spark" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "spark2" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "hive" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "clickhouse" to "SELECT formatDateTime(dt, '%Y-%m-%d') FROM t",
            "bigquery" to "SELECT FORMAT_TIMESTAMP('%F', dt) FROM t",
            "doris" to "SELECT DATE_FORMAT(dt, '%Y-%m-%d') FROM t",
            "starrocks" to "SELECT DATE_FORMAT(dt, '%Y-%m-%d') FROM t",
            "mysql" to "SELECT DATE_FORMAT(dt, '%Y-%m-%d') FROM t",
            "" to "SELECT TIME_TO_STR(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
        ),
    )

    /** exp.cast idempotence: an existing CAST that the target maps to TIMESTAMP is not re-cast. */
    @Test
    fun mysqlDateFormatOverExistingCastIsIdempotentPerTargetTypeMapping() = check(
        "mysql",
        "SELECT DATE_FORMAT(CAST(dt AS DATETIME), '%Y-%m-%d') FROM t",
        mapOf(
            "trino" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "presto" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "duckdb" to "SELECT STRFTIME(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "postgres" to "SELECT TO_CHAR(CAST(dt AS TIMESTAMP), 'YYYY-MM-DD') FROM t",
            "spark" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "spark2" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "hive" to "SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "clickhouse" to "SELECT formatDateTime(CAST(dt AS Nullable(DateTime)), '%Y-%m-%d') FROM t",
            "bigquery" to "SELECT FORMAT_TIMESTAMP('%F', CAST(dt AS DATETIME)) FROM t",
            "doris" to "SELECT DATE_FORMAT(CAST(dt AS DATETIME), '%Y-%m-%d') FROM t",
            "starrocks" to "SELECT DATE_FORMAT(CAST(dt AS DATETIME), '%Y-%m-%d') FROM t",
            "mysql" to "SELECT DATE_FORMAT(CAST(dt AS DATETIME), '%Y-%m-%d') FROM t",
            // base dialect has no DATETIME->TIMESTAMP mapping, so the oracle double-casts.
            "" to "SELECT TIME_TO_STR(CAST(CAST(dt AS DATETIME) AS TIMESTAMP), '%Y-%m-%d') FROM t",
        ),
    )

    @Test
    fun bigqueryDatetimeAndTimeCoerceEverywhere() = check(
        "bigquery",
        "SELECT DATETIME(x), TIME(x) FROM t",
        mapOf(
            "trino" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIME) FROM t",
            "presto" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIME) FROM t",
            "duckdb" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIME) FROM t",
            "postgres" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIME) FROM t",
            "spark" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIMESTAMP) FROM t",
            "spark2" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIMESTAMP) FROM t",
            "hive" to "SELECT CAST(x AS TIMESTAMP), CAST(x AS TIMESTAMP) FROM t",
            "clickhouse" to "SELECT CAST(x AS Nullable(DateTime)), CAST(x AS Nullable(TIME)) FROM t",
            "bigquery" to "SELECT DATETIME(x), TIME(x) FROM t",
            "doris" to "SELECT CAST(x AS DATETIME), CAST(x AS TIME) FROM t",
            "starrocks" to "SELECT CAST(x AS DATETIME), CAST(x AS TIME) FROM t",
            "mysql" to "SELECT CAST(x AS DATETIME), CAST(x AS TIME) FROM t",
            "" to "SELECT CAST(x AS DATETIME), CAST(x AS TIME) FROM t",
        ),
    )

    @Test
    fun bigqueryFormatTimestampCoercesEverywhere() = check(
        "bigquery",
        "SELECT FORMAT_TIMESTAMP('%F', ts) FROM t",
        mapOf(
            "trino" to "SELECT DATE_FORMAT(CAST(ts AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "presto" to "SELECT DATE_FORMAT(CAST(ts AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "duckdb" to "SELECT STRFTIME(CAST(ts AS TIMESTAMP), '%Y-%m-%d') FROM t",
            "postgres" to "SELECT TO_CHAR(CAST(ts AS TIMESTAMP), 'YYYY-MM-DD') FROM t",
            "spark" to "SELECT DATE_FORMAT(CAST(ts AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "spark2" to "SELECT DATE_FORMAT(CAST(ts AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "hive" to "SELECT DATE_FORMAT(CAST(ts AS TIMESTAMP), 'yyyy-MM-dd') FROM t",
            "clickhouse" to "SELECT formatDateTime(ts, '%Y-%m-%d') FROM t",
            "bigquery" to "SELECT FORMAT_TIMESTAMP('%F', ts) FROM t",
            "doris" to "SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
            "starrocks" to "SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
            "mysql" to "SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
            "" to "SELECT TIME_TO_STR(CAST(ts AS TIMESTAMP), '%Y-%m-%d') FROM t",
        ),
    )

    /** `this.is_type(DATETIME)` short-circuit (sqlglot: Cast.is_type delegates to `to`). */
    @Test
    fun bigqueryDatetimeOverExistingCastShortCircuits() = check(
        "bigquery",
        "SELECT DATETIME(CAST(x AS DATETIME)) FROM t",
        mapOf(
            "trino" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "presto" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "duckdb" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "postgres" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "spark" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "spark2" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "hive" to "SELECT CAST(x AS TIMESTAMP) FROM t",
            "clickhouse" to "SELECT CAST(x AS Nullable(DateTime)) FROM t",
            "bigquery" to "SELECT DATETIME(CAST(x AS DATETIME)) FROM t",
            "doris" to "SELECT CAST(x AS DATETIME) FROM t",
            "starrocks" to "SELECT CAST(x AS DATETIME) FROM t",
            "mysql" to "SELECT CAST(x AS DATETIME) FROM t",
            "" to "SELECT CAST(CAST(x AS TIMESTAMP) AS DATETIME) FROM t",
        ),
    )
}
