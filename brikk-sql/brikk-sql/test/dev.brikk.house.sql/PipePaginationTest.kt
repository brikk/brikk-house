package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipePaginationTest {
    private val max = Long.MAX_VALUE

    private fun assertRefused(source: String) {
        val fragment = SqlFragment(source, "doris")
        val original = fragment.ast.copy()
        val nodes = fragment.ast.walk().map { Triple(it, it.parent, it.meta.toMap()) }.toList()
        assertFailsWith<UnsupportedError>(source) { desugarPipes(fragment.ast) }
        assertFailsWith<UnsupportedError>(source) { fragment.toStandardSql() }
        for (pretty in listOf(false, true)) {
            assertFailsWith<UnsupportedError>(source) { fragment.toExecutable("doris", pretty = pretty) }
        }
        assertEquals(original, fragment.ast, source)
        for ((node, parent, meta) in nodes) {
            assertSame(parent, node.parent, source)
            assertEquals(meta, node.meta, source)
        }
    }

    @Test
    fun invalidValuesAreTypedRefusalsInEveryLimitAndOffsetForm() {
        for (value in listOf("-1", "?", "1 + 1", "1.5", "9223372036854775808", "'1'")) {
            for (source in listOf(
                "FROM t |> OFFSET $value",
                "FROM t |> LIMIT $value",
                "FROM t |> LIMIT 1 OFFSET $value",
                "FROM t |> LIMIT $value OFFSET 1",
                "FROM t |> LIMIT $value, 1",
                "FROM t |> LIMIT 1, $value",
                "FROM t |> LIMIT 0 |> LIMIT $value",
                "FROM t |> LIMIT 0 |> OFFSET $value",
                "FROM t |> OFFSET 0 |> OFFSET $value",
                "FROM t |> LIMIT $value |> LIMIT $value",
                "SELECT id FROM t LIMIT $value |> LIMIT 1",
                "SELECT id FROM t LIMIT $value |> OFFSET 1",
                "SELECT id FROM t OFFSET $value |> LIMIT 1",
                "SELECT id FROM t OFFSET $value |> OFFSET 1",
            )) assertRefused(source)
        }
    }

    @Test
    fun literalOnlyPolicyDoesNotEvaluateCoerceOrDropArguments() {
        for (value in listOf("-0", "1.0", "1e0", "'0'", "NULL", "id", ":n", "(1)", "CAST(1 AS BIGINT)")) {
            for (stage in listOf("LIMIT $value", "OFFSET $value", "LIMIT 0 OFFSET $value", "LIMIT $value OFFSET 0")) {
                assertRefused("FROM t |> $stage")
                assertRefused("FROM t |> LIMIT 0 |> $stage")
            }
        }
    }

    private fun assertSlice(source: String, limit: Long?, offset: Long?) {
        val fragment = SqlFragment(source, "doris")
        val original = fragment.ast.copy()
        val lowered = assertIs<Select>(desugarPipes(fragment.ast))
        assertEquals(limit?.toString(), (lowered.args["limit"] as? Limit)?.expressionArg?.let { (it as Literal).name }, source)
        assertEquals(offset?.toString(), (lowered.args["offset"] as? Offset)?.expressionArg?.let { (it as Literal).name }, source)
        assertEquals(original, fragment.ast, source)
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            assertEquals(emptyList(), result.unsupportedMessages, source)
            assertSame(result.sql, assertNotNull(result.sourceMap).output)
            val generated = parseOne(result.sql, "doris")
            val generatedLimit = (generated.args["limit"] as? Limit)?.expressionArg as? Literal
            assertEquals((limit ?: offset?.let { max - it })?.toString(), generatedLimit?.name, result.sql)
            assertEquals(original, fragment.ast, source)
        }
    }

    @Test
    fun limitsAndOffsetsComposeSlicesRatherThanIndependentMinimaAndSums() {
        for ((stages, limit, offset) in listOf(
            Triple("LIMIT 2 |> OFFSET 1", 1L, 1L),
            Triple("OFFSET 1 |> LIMIT 2", 2L, 1L),
            Triple("LIMIT 2 OFFSET 1", 2L, 1L),
            Triple("LIMIT 1, 2", 2L, 1L),
            Triple("LIMIT 4 OFFSET 2 |> OFFSET 3", 1L, 5L),
            Triple("LIMIT 2 OFFSET 2 |> LIMIT 4 OFFSET 3", 0L, 5L),
            Triple("LIMIT 5 |> LIMIT 2 OFFSET 1", 2L, 1L),
            Triple("LIMIT 5 |> LIMIT 8 OFFSET 1", 4L, 1L),
            Triple("LIMIT 5 |> LIMIT 1, 8", 4L, 1L),
            Triple("LIMIT 5 |> OFFSET 8", 0L, 8L),
            Triple("OFFSET 2 |> LIMIT 5 |> OFFSET 1 |> LIMIT 8 OFFSET 2", 2L, 5L),
            Triple("LIMIT 5 |> LIMIT 3 |> LIMIT 8", 3L, null),
            Triple("OFFSET 1 |> OFFSET 2 |> OFFSET 3", null, 6L),
        )) assertSlice("FROM t |> $stages", limit, offset)
        assertSlice("SELECT id FROM t LIMIT 5 OFFSET 2 |> LIMIT 8 OFFSET 3", 2L, 5L)
        assertSlice("SELECT id FROM t LIMIT 2, 5 |> OFFSET 3", 2L, 5L)
        assertSlice("SELECT id FROM t OFFSET 2 |> LIMIT 5", 5L, 2L)
    }

    @Test
    fun zeroAndSignedLongBoundariesRemainValid() {
        for ((stages, limit, offset) in listOf(
            Triple("LIMIT 0", 0L, null),
            Triple("OFFSET 0", null, 0L),
            Triple("LIMIT $max", max, null),
            Triple("OFFSET $max", null, max),
            Triple("LIMIT $max |> LIMIT $max |> LIMIT $max", max, null),
            Triple("OFFSET $max |> OFFSET 0", null, max),
            Triple("OFFSET ${max - 1} |> OFFSET 1", null, max),
            Triple("LIMIT 0 OFFSET $max", 0L, max),
            Triple("LIMIT $max, 0", 0L, max),
            Triple("LIMIT 0 |> OFFSET $max", 0L, max),
            Triple("OFFSET $max |> LIMIT 0", 0L, max),
            Triple("LIMIT $max |> OFFSET $max", 0L, max),
            Triple("LIMIT $max |> OFFSET 1", max - 1, 1L),
            Triple("LIMIT ${max - 1} OFFSET 1", max - 1, 1L),
            Triple("OFFSET ${max - 1} |> LIMIT 1", 1L, max - 1),
            Triple("LIMIT 1 OFFSET ${max - 1} |> OFFSET 1", 0L, max),
        )) assertSlice("FROM t |> $stages", limit, offset)
        assertSlice("FROM t |> LIMIT 0002 OFFSET 0001", 2L, 1L)
    }

    @Test
    fun overflowingArithmeticIsRefusedBeforeAdditionEvenForEmptySlices() {
        for (stages in listOf(
            "OFFSET $max |> OFFSET 1",
            "OFFSET $max |> OFFSET $max",
            "OFFSET ${max / 2 + 1} |> OFFSET ${max / 2 + 1}",
            "LIMIT 0 |> OFFSET $max |> OFFSET $max",
            "LIMIT 0 OFFSET $max |> LIMIT 0 OFFSET 1",
            "LIMIT $max OFFSET 1",
            "LIMIT 1, $max",
            "LIMIT 1 OFFSET $max",
            "OFFSET 1 |> LIMIT $max",
            "OFFSET ${max - 1} |> LIMIT 2",
            "OFFSET 1 |> LIMIT ${max - 1} OFFSET 1",
            "LIMIT 0 |> LIMIT $max OFFSET 1",
        )) assertRefused("FROM t |> $stages")
        assertRefused("SELECT id FROM t LIMIT $max OFFSET 1 |> LIMIT 0")
        assertRefused("SELECT id FROM t LIMIT $max OFFSET 1 |> OFFSET 0")
    }

    @Test
    fun modifiersThatAreNotPlainRowCountsAreNotSilentlyMerged() {
        for (clause in listOf("LIMIT 2 PERCENT", "LIMIT 2 WITH TIES", "LIMIT 2 BY id")) {
            assertRefused("FROM t |> $clause")
            assertRefused("FROM t |> LIMIT 0 |> $clause")
            assertRefused("SELECT id FROM t $clause |> LIMIT 1")
            assertRefused("SELECT id FROM t $clause |> OFFSET 1")
        }
        assertRefused("FROM t |> OFFSET 1 BY id")
        assertRefused("FROM t |> LIMIT 1, 2 OFFSET 3")
    }

    @Test
    fun headOffsetGuardStillRejectsInvalidLiteralsThroughThePublicEntryPoint() {
        for (value in listOf("-1", "?", "1 + 1", "1.5", "9223372036854775808", "'1'")) {
            val fragment = SqlFragment("SELECT id FROM t OFFSET $value |> ORDER BY id", "doris")
            for (pretty in listOf(false, true)) {
                val error = assertFailsWith<UnsupportedError> { fragment.toExecutable("doris", pretty = pretty) }
                assertEquals("Doris OFFSET without LIMIT requires a non-negative signed 64-bit integer literal", error.message)
            }
        }
        assertSlice("SELECT id FROM t OFFSET 1 |> ORDER BY id", null, 1L)
    }

    @Test
    fun arithmeticKeepsLiteralMetadataAndCopyFalseKeepsTheHeadIdentity() {
        val source = "FROM t |> LIMIT 8 OFFSET 2 |> OFFSET 1"
        val ast = assertIs<PipeQuery>(parseOne(source, "doris"))
        val original = ast.copy()
        val literals = ast.findAll<Literal>().toList()
        for (literal in literals) literal.comments = mutableListOf("source ${literal.name}")
        val lowered = desugarPipes(ast)
        val limit = assertIs<Literal>((lowered.args["limit"] as Limit).expressionArg)
        val offset = assertIs<Literal>((lowered.args["offset"] as Offset).expressionArg)
        for ((before, after) in listOf(literals[0] to limit, literals.last() to offset)) {
            assertNotSame(before, after)
            assertEquals(before.meta, after.meta)
            assertEquals(before.comments, after.comments)
        }
        assertEquals(original, ast)
        val mutable = ast.copy()
        val head = mutable.thisArg as Expression
        assertSame(head, desugarPipes(mutable, copy = false))
        assertTrue("LIMIT 7" in head.sql())
        assertEquals("3", ((head.args["offset"] as Offset).expressionArg as Literal).name)
    }

    @Test
    fun publicSourceMapsKeepExactRepeatedNameAndArithmeticSpans() {
        val source = "SELECT id, id AS repeated FROM t |> ORDER BY id |> LIMIT 8 OFFSET 2 |> OFFSET 1 |> LIMIT 4"
        val fragment = SqlFragment(source, "doris")
        val positions = fragment.ast.findAll<Identifier>().filter { it.name == "id" }
            .map { assertNotNull(SourceMap.sourcePosOf(it)) }.toList()
        assertEquals(3, positions.size)
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            val map = assertNotNull(result.sourceMap)
            assertSame(result.sql, map.output)
            for (position in positions) {
                val entry = assertNotNull(map.entries.firstOrNull { it.node is Identifier && SourceMap.sourcePosOf(it.node) == position })
                assertEquals("id", result.sql.substring(entry.start, entry.end))
                assertEquals(position, map.sourcePosition(entry.start, exact = true))
            }
            for ((clause, expectedStart) in listOf("LIMIT 4" to source.lastIndexOf('4'), "OFFSET 3" to source.lastIndexOf('1'))) {
                val outputStart = result.sql.indexOf(clause) + clause.length - 1
                val position = assertNotNull(map.sourcePosition(outputStart, exact = true), result.sql)
                assertEquals(expectedStart, position.start, result.sql)
                assertEquals(expectedStart, position.end, result.sql)
            }
        }
    }
}
