package dev.brikk.house.intellij

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-level wiring test: the line-marker provider registered on the base SQL
 * language must produce a gutter marker for `-- dialect: xyz` comments in a plain
 * .sql file (any SQL dialect language inherits the registration).
 *
 * Requires the database plugin in the test runtime (idea.load.plugins.id is set in
 * build.gradle.kts) — without it there is no SQL language and no markers.
 */
class DialectMarkerGutterPlatformTest : BasePlatformTestCase() {

    fun testMarkerCommentGetsGutterIcon() {
        myFixture.configureByText(
            "markers.sql",
            """
            -- dialect: clickhouse
            select 1;

            -- just a comment, not a marker
            select 2;
            """.trimIndent(),
        )
        myFixture.doHighlighting()
        val markers = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
        val brikkMarkers = markers.filter {
            it.lineMarkerTooltip?.contains("Brikk SQL") == true
        }
        assertEquals(
            "expected exactly one Brikk marker, got: ${markers.map { it.lineMarkerTooltip }}",
            1,
            brikkMarkers.size,
        )
        assertTrue(brikkMarkers.single().lineMarkerTooltip!!.contains("ClickHouse"))
    }

    fun testUnknownDialectMarkerIsFlaggedNotActionable() {
        myFixture.configureByText("unknown.sql", "-- dialect: exotic\nselect 1;")
        myFixture.doHighlighting()
        val markers = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.lineMarkerTooltip?.contains("Brikk SQL") == true }
        assertEquals(1, markers.size)
        assertTrue(markers.single().lineMarkerTooltip!!.contains("unknown dialect 'exotic'"))
    }
}
