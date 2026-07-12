package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.SourcePos
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * brikk-native: position meta on parsed nodes (Part A, Expression.updatePositions)
 * and Generator emit-span tracking / SourceMap queries (Part B).
 */
class SourceMapTest {

    // -- parser position meta ---------------------------------------------------------

    @Test
    fun parsedNodesCarryTokenPositions() {
        // Token semantics (oracle parity): line/col are 1-based and refer to the
        // token's END; start/end are absolute offsets, end inclusive.
        val ast = parseOne("SELECT alpha FROM tbl")
        val alpha = ast.findAll(Identifier::class).first { it.name == "alpha" }
        assertEquals(
            SourcePos(line = 1, col = 12, start = 7, end = 11),
            SourceMap.sourcePosOf(alpha),
        )
        val tbl = ast.findAll(Identifier::class).first { it.name == "tbl" }
        assertEquals(
            SourcePos(line = 1, col = 21, start = 18, end = 20),
            SourceMap.sourcePosOf(tbl),
        )
    }

    @Test
    fun copiedNodesKeepPositions() {
        val ast = parseOne("SELECT alpha FROM tbl")
        val copy = ast.copy()
        val alpha = copy.findAll(Identifier::class).first { it.name == "alpha" }
        assertEquals(7, SourceMap.sourcePosOf(alpha)?.start)
    }

    // -- span tracking ------------------------------------------------------------------

    private fun mapped(sql: String, pretty: Boolean = false): Pair<String, SourceMap> {
        val g = Generator(pretty = pretty)
        g.trackSpans = true
        val out = g.generate(parseOne(sql))
        return out to assertNotNull(g.lastSourceMap)
    }

    @Test
    fun trackingIsOffByDefault() {
        val g = Generator()
        g.generate(parseOne("SELECT a"))
        assertNull(g.lastSourceMap)
    }

    @Test
    fun rootSpansWholeOutputAndSpansAreSane() {
        val (out, map) = mapped("SELECT a + 1 AS x FROM t WHERE b = 2")
        assertTrue(map.entries.isNotEmpty())
        val root = map.entries.first()
        assertEquals(0, root.start)
        assertEquals(out.length, root.end)
        assertTrue(root.node is Select)
        for (e in map.entries) {
            assertTrue(e.start in 0 until e.end && e.end <= out.length, "bad span $e")
            // every span renders exactly the fragment the map claims
            assertTrue(out.substring(e.start, e.end).isNotEmpty())
        }
    }

    @Test
    fun nestedNodesHaveNestedSpans() {
        val (out, map) = mapped("SELECT a + 1 AS x FROM t")
        // "SELECT a + 1 AS x FROM t": Column a at offset 7, its span nested in the
        // Alias span, nested in the root span.
        val aOffset = out.indexOf("a + 1")
        val inner = assertNotNull(map.entryAt(aOffset))
        assertTrue(inner.node is Column || inner.node is Identifier)
        assertEquals("a", out.substring(inner.start, inner.end))
        val covering = map.entries.filter { it.start <= aOffset && aOffset < it.end }
        // chain: Select ⊇ Alias ⊇ Add ⊇ Column ⊇ Identifier — all nested
        assertTrue(covering.size >= 3)
        for (i in 1 until covering.size) {
            assertTrue(
                covering[i].start >= covering[i - 1].start && covering[i].end <= covering[i - 1].end,
                "spans not nested: ${covering[i - 1]} vs ${covering[i]}",
            )
        }
    }

    @Test
    fun outputOffsetsMapBackToSourcePositions() {
        val source = "SELECT alpha, beta FROM tbl WHERE gamma = 1"
        val g = Generator()
        g.trackSpans = true
        val out = g.generate(parseOne(source))
        val map = assertNotNull(g.lastSourceMap)
        // identity generation here: every projected column maps to its source offset
        for (name in listOf("alpha", "beta", "gamma")) {
            val pos = assertNotNull(map.sourcePosition(out.indexOf(name)), name)
            assertEquals(source.indexOf(name), pos.start, name)
            assertEquals(source.indexOf(name) + name.length - 1, pos.end, name)
            assertEquals(1, pos.line, name)
        }
    }

    @Test
    fun multiLinePrettyOutputMapsBackToSingleLineSource() {
        val source = "SELECT aaa, bbb FROM t WHERE ccc = 1 AND ddd = 2"
        val g = Generator(pretty = true)
        g.trackSpans = true
        val out = g.generate(parseOne(source))
        val map = assertNotNull(g.lastSourceMap)
        assertTrue(out.contains("\n"), "expected multi-line pretty output: $out")

        for (name in listOf("aaa", "bbb", "ccc", "ddd")) {
            val offset = out.indexOf(name)
            assertTrue(offset >= 0, name)
            // line/col query form: compute the 1-based output line/col of the name
            val line = out.substring(0, offset).count { it == '\n' } + 1
            val col = offset - (out.lastIndexOf('\n', offset - 1) + 1) + 1
            val pos = assertNotNull(map.sourcePosition(line, col), "$name @$line:$col in\n$out")
            assertEquals(source.indexOf(name), pos.start, name)
            assertEquals(1, pos.line, "source is single-line ($name)")
        }
    }

    @Test
    fun keywordOffsetsFallBackToNearestPositionedToken() {
        val (out, map) = mapped("SELECT alpha FROM tbl")
        // offset on the FROM keyword: no positioned covering node — falls back to the
        // nearest positioned span before it (the alpha projection)
        val pos = assertNotNull(map.sourcePosition(out.indexOf("FROM")))
        assertEquals(7, pos.start)
    }

    // -- shape API ------------------------------------------------------------------------

    @Test
    fun transpileToTracksSourceMapOnDemand() {
        val source = "SELECT foo FROM tbl WHERE bar > 5"
        val fragment = SqlFragment(source, "duckdb")

        val untracked = fragment.transpileTo("doris")
        assertNull(untracked.sourceMap)

        val tracked = fragment.transpileTo("doris", trackSourceMap = true)
        val map = assertNotNull(tracked.sourceMap)
        assertEquals(tracked.sql, map.output)
        val barPos = assertNotNull(map.sourcePosition(tracked.sql.indexOf("bar")))
        assertEquals(source.indexOf("bar"), barPos.start)

        // mapErrorToSource: 1-based output (line, col) form
        val errPos = assertNotNull(tracked.mapErrorToSource(1, tracked.sql.indexOf("bar") + 1))
        assertEquals(source.indexOf("bar"), errPos.start)
    }
}
