package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.SourcePos
import dev.brikk.house.sql.parser.PipeStageSplitter
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun positionsCarryStartAndEndAnchors() {
        // brikk-native: SourcePos exposes both anchors, all 1-based. `col`/`colEnd`
        // are the token END; `colStart` is where it BEGINS (no line-counting needed).
        val ast = parseOne(
            "FROM t\n" +
                "    |> WHERE event_axt >= 1",
        )
        val col = ast.findAll(Identifier::class).first { it.name == "event_axt" }
        val p = assertNotNull(SourceMap.sourcePosOf(col))
        // `    |> WHERE event_axt` — begins at col 14, ends at col 22, on line 2.
        assertEquals(2, p.lineStart)
        assertEquals(14, p.colStart)
        assertEquals(2, p.lineEnd)
        assertEquals(22, p.colEnd)
        assertEquals(p.col, p.colEnd)
        assertEquals(p.line, p.lineEnd)
    }

    @Test
    fun multiLineTokenAnchorsSpanTheLineBreak() {
        // A token that straddles a newline: the single-line derivation would be wrong,
        // so the start anchor comes from the tokenizer, not from `col - (end - start)`.
        val ast = parseOne("SELECT 'ab\ncd' AS x")
        val lit = ast.findAll(dev.brikk.house.sql.ast.Literal::class).first()
        val p = assertNotNull(SourceMap.sourcePosOf(lit))
        assertEquals(1, p.lineStart)
        assertEquals(8, p.colStart) // the opening quote on line 1
        assertEquals(2, p.lineEnd)
        assertEquals(3, p.colEnd) // the closing quote on line 2
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

    @Test
    fun exactModeReturnsNullWhenNoPositionedNodeCovers() {
        val (out, map) = mapped("SELECT alpha FROM tbl")
        // Same FROM-keyword offset, but exact: refuse to guess (no covering token).
        assertNull(map.sourcePosition(out.indexOf("FROM"), exact = true))
        // Directly on the token, exact still resolves.
        val onAlpha = assertNotNull(map.sourcePosition(out.indexOf("alpha"), exact = true))
        assertEquals(7, onAlpha.start)
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

    @Test
    fun toExecutableDesugarsPipesAndKeepsSqlAndMapInLockstep() {
        val source =
            "FROM events\n" +
                "    |> WHERE event_axt >= '2026-05-01'\n" +
                "    |> AGGREGATE count(*) AS c GROUP BY g"
        val result = SqlFragment(source, "bigquery").toExecutable("doris", pretty = true)

        // Pipes are flattened to the executable CTE-chain form.
        assertTrue(result.sql.contains("WITH __tmp1"), "expected desugared output: ${result.sql}")
        assertFalse(result.sql.contains("|>"), "no pipe operator survives")

        // The invariant the plugin relies on: the SQL and the map are the SAME string
        // (identity, not just equals) — they came from one generator pass, so positions
        // can never be measured against a different rendering.
        val map = assertNotNull(result.sourceMap)
        assertTrue(result.sql === map.output, "sql and sourceMap.output must be the same instance")

        // And a mapped position still points at the original pipe-syntax source.
        val axtOut = result.sql.indexOf("event_axt")
        val pos = assertNotNull(map.sourcePosition(axtOut, exact = true))
        assertEquals(2, pos.lineStart)
        assertEquals(source.indexOf("event_axt"), pos.start)
    }

    @Test
    fun stageShapesGivesOnePerStageWithScopeEvolution() {
        val source =
            "FROM events\n" +
                "    |> WHERE flagged\n" +
                "    |> AGGREGATE count(*) AS c GROUP BY g\n" +
                "    |> ORDER BY c DESC"
        val fragment = SqlFragment(source, "bigquery")
        val catalog = ShapeCatalog(
            tables = mapOf(
                "events" to Shape.of("g" to "INT", "flagged" to "BOOLEAN"),
            ),
        )
        val shapes = fragment.stageShapes(catalog)

        // 1:1 with the user-visible stage model: FROM, WHERE, AGGREGATE, ORDER BY.
        val splitterStages = PipeStageSplitter.split(source, "bigquery").stages
        assertEquals(splitterStages.size, shapes.size)
        assertEquals(4, shapes.size)
        assertEquals("FROM", splitterStages[0].operator) // element 0 is the FROM head
        assertEquals(fragment.stages.size + 1, shapes.size) // stages[] excludes the head

        // Stage 0 = after FROM: the base relation columns.
        assertEquals(listOf("g", "flagged"), shapes[0].columns.map { it.name })
        // Stage 1 = after WHERE (shape-preserving): still the source columns.
        assertEquals(listOf("g", "flagged"), shapes[1].columns.map { it.name })
        // Stage 2 = after AGGREGATE: the alias `c` enters scope HERE, not before (the
        // over-offer fix) — it is absent from every earlier stage's shape.
        assertEquals(listOf("g", "c"), shapes[2].columns.map { it.name })
        assertFalse(shapes[0].columns.any { it.name == "c" }, "c not in scope after FROM")
        assertFalse(shapes[1].columns.any { it.name == "c" }, "c not in scope after WHERE")
        // Stage 3 = after ORDER BY (shape-preserving): carries the aggregate shape forward.
        assertEquals(listOf("g", "c"), shapes[3].columns.map { it.name })

        // Last element == outputShape() (full pipeline).
        assertEquals(
            fragment.outputShape(catalog).columns.map { it.name },
            shapes.last().columns.map { it.name },
        )
    }

    @Test
    fun stageShapesEmptyForNonPipe() {
        assertTrue(SqlFragment("SELECT 1 AS a", "duckdb").stageShapes().isEmpty())
    }
}
