package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.analysis.SqlLiteralText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The textual fallback used when the IDE hands the plugin a lazy (unbuilt) function body. */
class SqlLiteralTextTest {
    private val q = "\"\"\""

    @Test
    fun expressionBodyWithEscapedString() {
        val text = """@BrikkSql fun recent(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= :start")"""
        assertEquals("postgres" to "FROM public.events |> WHERE event_at >= :start", SqlLiteralText.parse(text))
    }

    @Test
    fun blockBodyWithRawStringAndTrimIndent() {
        val text = """
            @BrikkSql
            fun f(rel: Rel<HasPayload>) {
                return Sql.clickhouse($q
                    |> SELECT payload
                $q.trimIndent())
            }
        """.trimIndent()
        assertEquals("clickhouse" to "|> SELECT payload", SqlLiteralText.parse(text))
    }

    @Test
    fun trimMarginAndEscapes() {
        val text = "fun f() = Sql.mysql(\"SELECT \\\"a\\\"\\n|> WHERE x = 1\")"
        assertEquals("mysql" to "SELECT \"a\"\n|> WHERE x = 1", SqlLiteralText.parse(text))
        val margin = "fun g() = Sql.duckdb($q\n   |SELECT 1\n   |FROM t\n$q.trimMargin())"
        assertEquals("duckdb" to "SELECT 1\nFROM t", SqlLiteralText.parse(margin))
    }

    @Test
    fun rawStringKeepsTrailingQuotesAndLiteralDollar() {
        assertEquals("pg" to "say \"hi\"", SqlLiteralText.parse("fun f() = Sql.pg(${q}say \"hi\"$q)"))
        assertEquals("pg" to "cost $ 5", SqlLiteralText.parse("fun f() = Sql.pg(${q}cost $ 5$q)"))
    }

    @Test
    fun interpolationIsNotConstant() {
        assertNull(SqlLiteralText.parse("fun f(t: String) = Sql.pg(\"FROM \$t\")"))
        assertNull(SqlLiteralText.parse("fun f(t: String) = Sql.pg(${q}FROM \${t}$q)"))
    }

    @Test
    fun notTheSupportedShape() {
        assertNull(SqlLiteralText.parse("fun f() = Sql.pg(build())"))
        assertNull(SqlLiteralText.parse("fun f() = Sql.pg(\"a\".uppercase())"))
        assertNull(SqlLiteralText.parse("fun f() = other.pg(\"a\")"))
        assertNull(SqlLiteralText.parse("fun f() = 42"))
    }
}
