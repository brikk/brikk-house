package dev.brikk.house.intellij

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * BrikkSQL (.bsql) — the plugin-owned polyglot file type: language registration,
 * lenient host classification, and the marker gutter being active by default.
 */
class BrikkSqlFileTypePlatformTest : BasePlatformTestCase() {

    fun testBsqlFileGetsBrikkSqlLanguage() {
        myFixture.configureByText("scratch.bsql", "select 1;")
        assertEquals("BrikkSQL", myFixture.file.language.id)
    }

    fun testBrikkSqlHostIsLenient() {
        myFixture.configureByText("scratch.bsql", "select 1;")
        assertTrue(SqlHosts.isLenient(myFixture.file))
    }

    fun testMarkerGutterActiveInBsql() {
        myFixture.configureByText(
            "scratch.bsql",
            "-- dialect: clickhouse\nselect toString(now());\n\n-- dialect: doris\nFROM t |> WHERE a > 1 |> SELECT a;\n",
        )
        myFixture.doHighlighting()
        val markers = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.lineMarkerTooltip?.contains("Brikk SQL") == true }
        assertEquals(2, markers.size)
    }

    fun testMixedDialectContentParsesWithoutExceptions() {
        // The point of the lenient host: none of this should explode the parser.
        myFixture.configureByText(
            "scratch.bsql",
            """
            -- dialect: mysql
            select date_format(created_at, '%Y-%m-%d') from t;

            -- dialect: clickhouse
            select toString(number) from numbers(10);

            -- dialect: duckdb
            select * from read_parquet('f.parquet');
            """.trimIndent(),
        )
        myFixture.doHighlighting() // would throw on parser/lexer crashes
    }
}
