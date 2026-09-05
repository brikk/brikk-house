package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.analysis.SqlLiteralText
import dev.brikk.house.sql.compiler.analysis.SqlPiece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The textual fallback used when the IDE hands the plugin a lazy (unbuilt) function body. */
class SqlLiteralTextTest {
    private val q = "\"\"\""

    /** Classifier standing in for the enclosing function: `src`/`dim` are Rel params, `LIMIT_N` a const, the rest binds. */
    private fun classify(name: String): SqlPiece = when (name) {
        "src", "dim" -> SqlPiece.Slot(name)
        "LIMIT_N" -> SqlPiece.Const("200")
        else -> SqlPiece.Bind(name)
    }

    private fun parse(text: String): Pair<String, String>? =
        SqlLiteralText.parse(text, ::classify)?.let { (d, t) -> d to t.sql }

    @Test
    fun expressionBodyWithEscapedString() {
        val text = """@BrikkSql fun recent(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= :start")"""
        assertEquals("postgres" to "FROM public.events |> WHERE event_at >= :start", parse(text))
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
        assertEquals("clickhouse" to "|> SELECT payload", parse(text))
    }

    @Test
    fun trimMarginAndEscapes() {
        val text = "fun f() = Sql.mysql(\"SELECT \\\"a\\\"\\n|> WHERE x = 1\")"
        assertEquals("mysql" to "SELECT \"a\"\n|> WHERE x = 1", parse(text))
        val margin = "fun g() = Sql.duckdb($q\n   |SELECT 1\n   |FROM t\n$q.trimMargin())"
        assertEquals("duckdb" to "SELECT 1\nFROM t", parse(margin))
    }

    @Test
    fun rawStringKeepsTrailingQuotesAndLiteralDollar() {
        assertEquals("pg" to "say \"hi\"", parse("fun f() = Sql.pg(${q}say \"hi\"$q)"))
        assertEquals("pg" to "cost $ 5", parse("fun f() = Sql.pg(${q}cost $ 5$q)"))
        // `$` before a non-identifier char is literal in Kotlin too: JSON paths, $$ quoting, $1.
        assertEquals("doris" to "json_extract(p, '$.user_id') $$ $1", parse("fun f() = Sql.doris(${q}json_extract(p, '$.user_id') $$ $1$q)"))
    }

    @Test
    fun templateEntriesAreClassified() {
        val text = "fun f(src: Rel<T>, start: Instant) = Sql.pg(${q}FROM \$src() |> WHERE event_at >= \$start LIMIT \${LIMIT_N}$q)"
        val (dialect, template) = SqlLiteralText.parse(text, ::classify)!!
        assertEquals("pg", dialect)
        assertEquals("FROM src() |> WHERE event_at >= :start LIMIT 200", template.sql)
        assertEquals(listOf("start"), template.binds)
        assertEquals(listOf("src"), template.slots)
        assertNull(template.malformedSlot())
    }

    @Test
    fun relReferencedAsValueIsMalformed() {
        val (_, template) = SqlLiteralText.parse("fun f(src: Rel<T>) = Sql.pg(\"FROM \$src |> SELECT 1\")", ::classify)!!
        assertEquals(SqlPiece.Slot("src"), template.malformedSlot())
    }

    @Test
    fun interpolatedExpressionIsNotReadable() {
        assertNull(parse("fun f(t: String) = Sql.pg(\"FROM \${t.uppercase()}\")"))
        assertNull(parse("fun f(t: String) = Sql.pg(${q}FROM \${a + b}$q)"))
    }

    @Test
    fun notTheSupportedShape() {
        assertNull(parse("fun f() = Sql.pg(build())"))
        assertNull(parse("fun f() = Sql.pg(\"a\".uppercase())"))
        assertNull(parse("fun f() = other.pg(\"a\")"))
        assertNull(parse("fun f() = 42"))
    }
}
