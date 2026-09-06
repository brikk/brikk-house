package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.DorisGenerator
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Client upgrade cases through the public API and native grammar, without an IDE or server. */
class DorisPipeGenerationTest {
    private val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))

    private fun assertExecutable(source: String, expected: String) {
        val fragment = SqlFragment(source, "doris")
        assertTrue(fragment.isPipe)
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            assertEquals(emptyList(), result.unsupportedMessages, source)
            assertFalse(result.isRawPassthroughStatement)
            assertSame(result.sql, assertNotNull(result.sourceMap).output)
            if (!pretty) assertEquals(expected, result.sql, source)
            assertEquals(parseOne(expected, "doris"), parseOne(result.sql, "doris"), result.sql)
            val verified = verifier.verify(result.sql)
            assertTrue(verified.verified && !verified.advisory, "Native parser required: $verified")
            assertTrue(verified.accepted, "Doris rejected ${result.sql}: ${verified.error}")
        }
    }

    private fun assertProjection(input: String, expected: String = input) {
        assertExecutable(
            "FROM t |> WHERE tenant_id = 7 |> SELECT $input |> LIMIT 5",
            "WITH __tmp1 AS (SELECT $expected FROM t WHERE tenant_id = 7) SELECT * FROM __tmp1 LIMIT 5",
        )
    }

    @Test
    fun escapedLiteralsKeepTheirValuesAndDoNotCreateStages() {
        for ((input, expected) in listOf(
            "'O''Reilly'" to "'O''Reilly'",
            "'O\\'Reilly'" to "'O''Reilly'",
            "\"double\\\"quoted\"" to "'double\"quoted'",
            "'C:\\\\tmp\\\\file'" to "'C:\\\\tmp\\\\file'",
            "'a\\nb\\tc'" to "'a\\nb\\tc'",
            "'a\\%b'" to "'a\\\\%b'",
            "'a\\qb'" to "'aqb'",
            "'a; |> LIMIT 999'" to "'a; |> LIMIT 999'",
        )) {
            assertProjection("$input AS payload", "$expected AS payload")
        }
    }

    @Test
    fun adjacentStringsRetainBothArguments() {
        assertProjection("'ab' 'cd' AS joined", "CONCAT('ab', 'cd') AS joined")
    }

    @Test
    fun qualifiedReservedAndEscapedIdentifiersStayQuoted() {
        assertExecutable(
            "FROM `internal`.`sales db`.`order` |> WHERE `tenant id` = 7 " +
                "|> SELECT `string`, `co``lumn` AS `display name` |> LIMIT 3",
            "WITH __tmp1 AS (SELECT `string`, `co``lumn` AS `display name` " +
                "FROM `internal`.`sales db`.`order` WHERE `tenant id` = 7) SELECT * FROM __tmp1 LIMIT 3",
        )
    }

    @Test
    fun temporalArgumentsKeepTheirOrderAndUnits() {
        assertProjection(
            "DATE_TRUNC(event_at, 'HOUR') AS bucket, TIMESTAMPDIFF(HOUR, started_at, ended_at) AS elapsed, " +
                "DATE_ADD(event_at, 7) AS next_week, DATE_SUB(event_at, INTERVAL 3 MONTH) AS prior_quarter",
            "DATE_TRUNC(event_at, 'HOUR') AS bucket, TIMESTAMPDIFF(HOUR, started_at, ended_at) AS elapsed, " +
                "DATE_ADD(event_at, INTERVAL 7 DAY) AS next_week, DATE_SUB(event_at, INTERVAL '3' MONTH) AS prior_quarter",
        )
    }

    @Test
    fun dateFormatDoesNotLeakInternalCoercionFunctions() {
        assertProjection(
            "DATE_FORMAT(event_at, '%Y-%m-%d %H:%i:%s') AS formatted",
            "DATE_FORMAT(event_at, '%Y-%m-%d %T') AS formatted",
        )
    }

    @Test
    fun lagAndLeadKeepExplicitArgumentsAndSupplyMissingDefaults() {
        assertProjection(
            "LAG(amount) OVER (ORDER BY event_at) AS previous_amount, " +
                "LEAD(amount, 2, -1) OVER (ORDER BY event_at) AS next_amount",
            "LAG(amount, 1, NULL) OVER (ORDER BY event_at) AS previous_amount, " +
                "LEAD(amount, 2, -1) OVER (ORDER BY event_at) AS next_amount",
        )
    }

    @Test
    fun groupedConcatenationUsesDorisSeparatorArguments() {
        assertExecutable(
            "FROM t |> WHERE tenant_id = 7 |> AGGREGATE GROUP_CONCAT(label, ';') AS labels GROUP BY category |> LIMIT 8",
            "WITH __tmp1 AS (SELECT category, GROUP_CONCAT(`label`, ';') AS labels FROM t " +
                "WHERE tenant_id = 7 GROUP BY category) SELECT * FROM __tmp1 LIMIT 8",
        )
    }

    @Test
    fun arrayAndStorageTypeCastsSurvivePipeDesugaring() {
        assertProjection("arr[1] AS first_item, CAST(arr AS ARRAY<INT>) AS ints, ARRAY(1, 2, 3) AS constants")
        assertProjection(
            "CAST(n AS LARGEINT) AS wide, CAST(d AS DATEV2) AS day_value, " +
                "CAST(ts AS DATETIMEV2(3)) AS millis, CAST(n AS DECIMALV3(18, 2)) AS money",
            "CAST(n AS LARGEINT) AS wide, CAST(d AS DATE) AS day_value, " +
                "CAST(ts AS DATETIME(3)) AS millis, CAST(n AS DECIMAL(18, 2)) AS money",
        )
    }

    @Test
    fun lateralViewExplodeStaysInTablePosition() {
        assertExecutable(
            "FROM t LATERAL VIEW EXPLODE(arr) exploded AS item |> WHERE tenant_id = 7 |> SELECT item |> LIMIT 4",
            "WITH __tmp1 AS (SELECT item FROM t LATERAL VIEW EXPLODE(arr) exploded AS item " +
                "WHERE tenant_id = 7) SELECT * FROM __tmp1 LIMIT 4",
        )
    }

    @Test
    fun escapedDelimitersAndCommentsCannotDropTrailingFilters() {
        assertExecutable(
            "FROM t |> WHERE payload = 'a\\'; |> LIMIT 999' " +
                "|> SELECT * -- ; |> LIMIT 999\n|> WHERE tenant_id = 7 |> LIMIT 5;",
            "WITH __tmp1 AS (SELECT * /* ; |> LIMIT 999 */ FROM t WHERE payload = 'a''; |> LIMIT 999') " +
                "SELECT * FROM __tmp1 WHERE tenant_id = 7 LIMIT 5",
        )
    }

    @Test
    fun lastDayReportsUnsupportedPartsWithoutFlaggingMonth() {
        for (call in listOf("LAST_DAY(d)", "LAST_DAY(d, MONTH)")) {
            assertProjection("$call AS month_end", "LAST_DAY(d) AS month_end")
        }
        for (unit in listOf("YEAR", "QUARTER", "WEEK")) {
            val result = SqlFragment("FROM t |> SELECT LAST_DAY(d, $unit) AS period_end |> LIMIT 1", "doris")
                .toExecutable("doris")
            assertEquals(listOf("Date parts are not supported in LAST_DAY."), result.unsupportedMessages)
            // Syntax acceptance must not erase a lossy-translation diagnostic.
            assertTrue(verifier.verify(result.sql).accepted)
        }
    }

    @Test
    fun accuracyWarningsDoNotLeakAcrossCallsOrReusedGenerators() {
        val lossy = SqlFragment("FROM t |> AGGREGATE APPROX_COUNT_DISTINCT(user_id, 0.01) AS users", "doris")
        val supported = SqlFragment("FROM t |> AGGREGATE APPROX_COUNT_DISTINCT(user_id) AS users", "doris")
        val warning = "Argument 'accuracy' is not supported for expression 'ApproxDistinct' when targeting Doris."
        val result = lossy.toExecutable("doris")
        assertEquals(listOf(warning), result.unsupportedMessages)
        assertTrue(verifier.verify(result.sql).accepted)
        assertExecutable(
            supported.sql,
            "WITH __tmp1 AS (SELECT APPROX_COUNT_DISTINCT(user_id) AS users FROM t) SELECT * FROM __tmp1",
        )
        assertEquals(listOf(warning), result.unsupportedMessages, "Earlier result diagnostics must remain intact")

        val generator = DorisGenerator()
        generator.generate(desugarPipes(lossy.ast))
        assertEquals(listOf(warning), generator.unsupportedMessages)
        generator.generate(desugarPipes(supported.ast))
        assertEquals(emptyList(), generator.unsupportedMessages)
    }
}
