package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.RowNumber
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.DorisGenerator
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DorisQualifyTest {
    private fun assertSql(source: String, expected: String = source) {
        val fragment = SqlFragment(source, "doris")
        val before = Serde.dump(fragment.ast)
        val parents = fragment.ast.walk().map { it to it.parent }.toList()
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            if (!pretty) assertEquals(expected, result.sql, source)
            assertEquals(parseOne(expected, "doris"), parseOne(result.sql, "doris"), result.sql)
            assertEquals(emptyList(), result.unsupportedMessages, result.sql)
            assertSame(result.sql, assertNotNull(result.sourceMap).output)
            assertEquals(before, Serde.dump(fragment.ast), "Generation must not mutate the source AST")
            for ((node, parent) in parents) assertSame(parent, node.parent)
        }
    }

    @Test
    fun qualifyOnlyWindowDoesNotWidenTheFollowingDistinctStar() {
        assertSql(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) < 3 |> SELECT DISTINCT *",
            "WITH __tmp1 AS (SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) < 3), " +
                "__tmp2 AS (SELECT DISTINCT * FROM __tmp1 AS t) SELECT * FROM __tmp2",
        )
    }

    @Test
    fun bareQualifiedAndMixedStarsKeepNativeQualify() {
        for (projection in listOf("*", "x.*", "x.*, x.id + 10 AS extra", "*, 7 AS extra")) {
            assertSql(
                "SELECT $projection FROM t AS x QUALIFY ROW_NUMBER() OVER (ORDER BY x.id) < 3 " +
                    "ORDER BY x.id DESC LIMIT 1 OFFSET 1",
            )
        }
        assertSql(
            "SELECT t.*, u.category AS other_category FROM t JOIN u ON t.id = u.id " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY t.id) < 3 ORDER BY t.id",
        )
    }

    @Test
    fun selectedWindowsAndRealHelperLookingColumnsAreNotRemoved() {
        for (projection in listOf(
            "id, _w, _row_number",
            "*, ROW_NUMBER() OVER (ORDER BY id) AS rn",
            "x.*, ROW_NUMBER() OVER (ORDER BY x.id) AS rn",
            "id, ROW_NUMBER() OVER (ORDER BY id) AS _w, _row_number",
        )) {
            assertSql(
                "SELECT $projection FROM t AS x QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) <= 3 " +
                    "ORDER BY id LIMIT 1 OFFSET 1",
            )
        }
        assertSql(
            "SELECT *, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY rn < 3 ORDER BY rn",
        )
        assertSql(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) + 1 AS rn FROM t QUALIFY rn < 3",
        )
        assertSql(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY rn < 3 |> SELECT rn",
            "WITH __tmp1 AS (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY rn < 3), " +
                "__tmp2 AS (SELECT rn FROM __tmp1) SELECT * FROM __tmp2",
        )
    }

    @Test
    fun finalClausesStayAfterTheNativeFilterInTheirOriginalScope() {
        assertSql(
            "SELECT DISTINCT category FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 " +
                "ORDER BY category LIMIT 1 OFFSET 1",
        )
        assertSql(
            "SELECT id AS chosen_id FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) <= 3 " +
                "ORDER BY t.category, chosen_id LIMIT 1",
        )
        assertSql(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 ORDER BY id OFFSET 1",
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 " +
                "ORDER BY id LIMIT 9223372036854775806 OFFSET 1",
        )
        assertSql(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 " +
                "|> SELECT DISTINCT * |> ORDER BY id |> LIMIT 1 OFFSET 1",
            "WITH __tmp1 AS (SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3), " +
                "__tmp2 AS (SELECT DISTINCT * FROM __tmp1 AS t) " +
                "SELECT * FROM __tmp2 ORDER BY id LIMIT 1 OFFSET 1",
        )
    }

    @Test
    fun nonstarDistinctOnStillFiltersQualifyBeforeRankingAgain() {
        assertSql(
            "SELECT DISTINCT ON (category) id, category FROM t " +
                "QUALIFY ROW_NUMBER() OVER (PARTITION BY category ORDER BY id DESC) = 1 ORDER BY category, id",
            "SELECT id, category FROM (SELECT id AS id, category AS category, " +
                "ROW_NUMBER() OVER (PARTITION BY category ORDER BY category, id) AS _row_number " +
                "FROM (SELECT id, category, ROW_NUMBER() OVER (PARTITION BY category ORDER BY id DESC) AS _w " +
                "FROM t) AS _t WHERE _w = 1) AS _t WHERE _row_number = 1 ORDER BY category, id",
        )
        // Re-entering SELECT dispatch for this child must not lower its native filter
        // into a helper-bearing star or move it after the outer DISTINCT ON ranking.
        assertSql(
            "SELECT DISTINCT ON (category) id, category FROM " +
                "(SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) < 3) AS filtered ORDER BY id",
            "SELECT id, category FROM (SELECT id AS id, category AS category, " +
                "ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS _row_number FROM " +
                "(SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) < 3) AS filtered) AS _t " +
                "WHERE _row_number = 1 ORDER BY id",
        )
    }

    @Test
    fun mixedDistinctOnNativeCasesAndNumericRefusalsStayExplicit() {
        for ((source, reason) in listOf(
            "SELECT DISTINCT ON (category) * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) > 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (category) t.* FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) > 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (category) *, id + 1 AS extra FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) > 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (category) id, category FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) > 1 LIMIT 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (category) id, category FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) > 1 OFFSET 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (1) * FROM t" to "positional",
            "SELECT DISTINCT ON ((1)) * FROM t" to "positional",
            "SELECT DISTINCT ON (category) * FROM t ORDER BY 1" to "positional",
            "SELECT DISTINCT ON (category) * FROM t ORDER BY (1) DESC" to "positional",
            "SELECT DISTINCT ON (category) id, category FROM t ORDER BY 1 LIMIT 1" to "positional",
        )) {
            val fragment = SqlFragment(source, "doris")
            val before = Serde.dump(fragment.ast)
            for (pretty in listOf(false, true)) {
                val error = assertFailsWith<UnsupportedError>(source) { fragment.toExecutable("doris", pretty = pretty) }
                assertTrue(error.message.orEmpty().contains("Doris DISTINCT ON"), error.message)
                assertTrue(error.message.orEmpty().contains(reason), error.message)
                assertEquals(before, Serde.dump(fragment.ast))
            }
        }
    }

    @Test
    fun aSelectedButUnreferencedWindowUsesTheExplicitProjectionFallback() {
        for (pretty in listOf(false, true)) {
            val result = SqlFragment(
                "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY id > 1 |> SELECT *",
                "duckdb",
            ).toExecutable("doris", pretty = pretty)
            assertEquals(emptyList(), result.unsupportedMessages)
            assertNull(parseOne(result.sql, "doris").find<Qualify>(), result.sql)
            assertEquals(1, parseOne(result.sql, "doris").findAll<RowNumber>().count())
        }
        for (source in listOf(
            "SELECT *, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY id > 1",
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY id > (SELECT MAX(id) FROM u)",
        )) {
            assertFailsWith<UnsupportedError>(source) { SqlFragment(source, "duckdb").toExecutable("doris") }
        }
    }

    @Test
    fun windowAliasNamesDoNotOverrideInputColumnBindings() {
        for (source in listOf(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM (SELECT 1 AS id, 0 AS rn) AS t QUALIFY rn = 0",
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY rn = 1",
        )) {
            assertFailsWith<UnsupportedError>(source) { SqlFragment(source, "duckdb").toExecutable("doris") }
        }
        assertFailsWith<UnsupportedError> {
            SqlFragment("SELECT id AS n, COUNT(*) AS n, ROW_NUMBER() OVER (ORDER BY id) AS rn " +
                "FROM t GROUP BY id QUALIFY COUNT(*) > 1", "duckdb").toExecutable("doris")
        }
    }

    @Test
    fun nativeQualifyPreservesMetadataParentLinksAndRepeatedSourceSpans() {
        val source = "SELECT x.*, '\uD83D\uDE00' AS marker FROM t AS x\n" +
            "QUALIFY ROW_NUMBER() OVER (ORDER BY x.id) < 3\nORDER BY x.id\n|> SELECT DISTINCT *"
        val fragment = SqlFragment(source, "doris")
        val qualify = assertNotNull(fragment.ast.find<Qualify>())
        qualify.meta["origin"] = "native-filter"
        val before = Serde.dump(fragment.ast)
        val parents = fragment.ast.walk().map { it to it.parent }.toList()
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            val map = assertNotNull(result.sourceMap)
            assertSame(result.sql, map.output)
            val sourceIds = Regex("\\bid\\b").findAll(source).map { it.range.first }.toList()
            val outputIds = Regex("\\bid\\b").findAll(result.sql).map { it.range.first }.toList()
            assertEquals(2, outputIds.size, result.sql)
            for ((outputOffset, sourceOffset) in outputIds.zip(sourceIds)) {
                val position = assertNotNull(map.sourcePosition(outputOffset, exact = true), result.sql)
                assertEquals(sourceOffset, position.start)
                assertEquals(sourceOffset + 1, position.end)
                assertEquals(source.take(sourceOffset).count { it == '\n' } + 1, position.lineStart)
            }
            val emittedQualify = assertIs<Qualify>(map.entries.first { it.node is Qualify }.node)
            assertEquals("native-filter", emittedQualify.meta["origin"])
            val generated = parseOne(result.sql, "doris")
            val rank = generated.findAll<RowNumber>().single()
            assertIs<Qualify>(rank.findAncestor(Qualify::class))
            assertNull(rank.findAncestor(Alias::class))
            val filtered = generated.findAll<Select>().single { it.args["qualify"] != null }
            assertEquals(2, filtered.selects.size)
            assertEquals(before, Serde.dump(fragment.ast))
            for ((node, parent) in parents) assertSame(parent, node.parent)
        }
    }

    @Test
    fun prettyOrderMappingDoesNotSearchInsideLiteralSqlText() {
        val source = "SELECT x.*, 'ORDER BY\\n  x.id' AS marker FROM t AS x\n" +
            "QUALIFY ROW_NUMBER() OVER (ORDER BY x.id) < 3\nORDER BY x.id\n|> SELECT DISTINCT *"
        val result = SqlFragment(source, "doris").toExecutable("doris", pretty = true)
        val map = assertNotNull(result.sourceMap)
        val sourceIds = Regex("\\bid\\b").findAll(source).map { it.range.first }.toList()
        val outputIds = Regex("\\bid\\b").findAll(result.sql).map { it.range.first }.toList()
        assertEquals(3, outputIds.size, result.sql)
        assertEquals(source.indexOf('\''), assertNotNull(map.sourcePosition(outputIds.first(), exact = true)).start)
        for ((output, input) in outputIds.drop(1).zip(sourceIds.drop(1))) {
            assertEquals(input, assertNotNull(map.sourcePosition(output, exact = true), result.sql).start)
        }
    }

    @Test
    fun survivingStarRenameFailsAtTheGeneratorRegardlessOfOtherModifiers() {
        for (source in listOf(
            "SELECT * RENAME (id AS renamed_id) FROM t",
            "SELECT t.* RENAME (id AS renamed_id) FROM t",
            "SELECT * EXCEPT (category) RENAME (id AS renamed_id) FROM t",
            "SELECT * REPLACE (id + 1 AS id) RENAME (id AS renamed_id) FROM t",
        )) {
            val ast = parseOne(source, "doris")
            val before = Serde.dump(ast)
            for (pretty in listOf(false, true)) {
                val error = assertFailsWith<UnsupportedError>(source) { DorisGenerator(pretty = pretty).generate(ast) }
                assertTrue(error.message.orEmpty().contains("Doris star RENAME"), error.message)
                assertEquals(before, Serde.dump(ast))
            }
        }
    }

    @Test
    fun emptyRenameAndOtherStarModifiersKeepTheirExistingRendering() {
        assertEquals("*", DorisGenerator().generate(Star(args("rename" to emptyList<Expression>()))))
        for (source in listOf(
            "SELECT * FROM t",
            "SELECT t.* FROM t",
            "SELECT COUNT(*) FROM t",
            "SELECT * EXCEPT (category) FROM t",
            "SELECT * REPLACE (id + 1 AS id) FROM t",
        )) assertSql(source)
    }

    @Test
    fun ddlRenameDoesNotUseTheStarRenameBackstop() {
        for (source in listOf(
            "ALTER TABLE t RENAME t2",
            "ALTER TABLE t RENAME COLUMN a b",
            "ALTER TABLE t RENAME PARTITION p1 p2",
            "ALTER TABLE t RENAME ROLLUP r1 r2",
        )) assertSql(source)
    }
}
