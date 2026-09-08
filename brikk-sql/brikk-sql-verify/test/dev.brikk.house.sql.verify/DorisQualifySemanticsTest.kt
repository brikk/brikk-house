package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Execute unchanged Doris output in DuckDB against independent, stage-separated references. */
class DorisQualifySemanticsTest {
    private fun assertResult(
        source: String,
        reference: String,
        columns: List<String>,
        expected: List<List<String?>>,
        values: String? = "(1, 'A'), (2, 'A'), (3, 'B')",
        extraColumns: Map<String, String> = emptyMap(),
        ordered: Boolean = false,
        sourceDialect: String = "doris",
    ) {
        val fragment = SqlFragment(source, sourceDialect)
        val before = Serde.dump(fragment.ast)
        val tableColumns = linkedMapOf("id" to "INT", "category" to "VARCHAR") + extraColumns
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (${tableColumns.entries.joinToString(", ") { "${it.key} ${it.value}" }})")
                if (values != null) statement.execute("INSERT INTO t VALUES $values")
                fun execute(query: String): Pair<List<String>, List<List<String?>>> =
                    statement.executeQuery(query).use { result ->
                        val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) }
                        val rows = buildList {
                            while (result.next()) add(names.indices.map { result.getString(it + 1) })
                        }
                        names to rows
                    }

                val independent = execute(reference)
                assertEquals(columns, independent.first, reference)
                assertEquals(expected.groupingBy { it }.eachCount(), independent.second.groupingBy { it }.eachCount(), reference)
                if (ordered) assertEquals(expected, independent.second, reference)
                if (sourceDialect == "duckdb" && fragment.ast.findAll<PipeQuery>().none()) {
                    val input = execute(source)
                    assertEquals(columns, input.first, source)
                    assertEquals(independent.second.groupingBy { it }.eachCount(), input.second.groupingBy { it }.eachCount(), source)
                }
                for (pretty in listOf(false, true)) {
                    val result = fragment.toExecutable("doris", pretty = pretty, trackSourceMap = true)
                    assertEquals(emptyList(), result.unsupportedMessages, source)
                    assertSame(result.sql, assertNotNull(result.sourceMap).output)
                    val verified = verifier.verify(result.sql)
                    assertTrue(verified.verified && !verified.advisory, "Native grammar required: $verified")
                    assertTrue(verified.accepted, "${result.sql}: ${verified.error}")
                    val resolved = qualify(
                        parseOne(result.sql, "doris"), dialect = Dialects.DORIS,
                        schema = mapOf("t" to tableColumns), inferSchema = false, validateQualifyColumns = true,
                    )
                    assertEquals(columns, resolved.namedSelects, result.sql)
                    // Qualification is a binding check only. Do not rewrite the SQL we execute.
                    val actual = execute(result.sql)
                    assertEquals(independent.first, actual.first, result.sql)
                    assertEquals(independent.second.groupingBy { it }.eachCount(), actual.second.groupingBy { it }.eachCount(), result.sql)
                    if (ordered) assertEquals(independent.second, actual.second, result.sql)
                    assertEquals(before, Serde.dump(fragment.ast), "Generation must not mutate its input")
                }
            }
        }
    }

    @Test
    fun ordinaryQualifyDoesNotExportAHelperThroughLaterStars() {
        val reference = "SELECT id, category FROM " +
            "(SELECT id, category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked WHERE ref_rank < 3"
        for (projection in listOf("*", "x.*")) {
            for (stage in listOf("SELECT *", "SELECT DISTINCT *", "DISTINCT", "SELECT DISTINCT x.*")) {
                assertResult(
                    "SELECT $projection FROM t AS x QUALIFY ROW_NUMBER() OVER (ORDER BY x.id) < 3 |> $stage",
                    reference, listOf("id", "category"), listOf(listOf("1", "A"), listOf("2", "A")),
                )
            }
        }
        assertResult(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) < 3 |> SELECT DISTINCT *",
            reference, listOf("id", "category"), listOf(listOf("1", "A"), listOf("2", "A")),
        )
    }

    @Test
    fun mixedStarsAndRealHelperLookingNamesKeepTheirExactOutput() {
        for (projection in listOf("*", "x.*", "id, category, _w, _row_number")) {
            assertResult(
                "SELECT $projection, x.id + 10 AS extra FROM t AS x " +
                    "QUALIFY ROW_NUMBER() OVER (ORDER BY x.id) < 3 |> SELECT DISTINCT *",
                "SELECT id, category, _w, _row_number, id + 10 AS extra FROM " +
                    "(SELECT id, category, _w, _row_number, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked " +
                    "WHERE ref_rank < 3",
                listOf("id", "category", "_w", "_row_number", "extra"),
                listOf(listOf("1", "A", "90", "80", "11"), listOf("2", "A", null, "70", "12")),
                values = "(1, 'A', 90, 80), (2, 'A', NULL, 70), (3, 'B', 60, 50)",
                extraColumns = mapOf("_w" to "INT", "_row_number" to "INT"),
            )
        }
    }

    @Test
    fun selectedWindowAliasesRemainVisibleAndKeepTheirOriginalRows() {
        for (projection in listOf("*", "x.*", "id, category")) {
            assertResult(
                "SELECT $projection, ROW_NUMBER() OVER (ORDER BY x.id) AS rn FROM t AS x " +
                    "QUALIFY ROW_NUMBER() OVER (ORDER BY x.id DESC) <= 2 |> SELECT DISTINCT *",
                "SELECT id, category, rn FROM (SELECT id, category, " +
                    "ROW_NUMBER() OVER (ORDER BY id) AS rn, ROW_NUMBER() OVER (ORDER BY id DESC) AS ref_rank FROM t) AS ranked " +
                    "WHERE ref_rank <= 2",
                listOf("id", "category", "rn"), listOf(listOf("2", "A", "2"), listOf("3", "B", "3")),
            )
        }
        for (alias in listOf("rn", "_w", "_row_number")) {
            assertResult(
                "SELECT *, ROW_NUMBER() OVER (ORDER BY id) AS $alias FROM t QUALIFY $alias < 3 " +
                    "|> SELECT $alias |> ORDER BY $alias DESC",
                "SELECT rn AS $alias FROM (SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS ranked " +
                    "WHERE rn < 3 ORDER BY rn DESC",
                listOf(alias), listOf(listOf("2"), listOf("1")), ordered = true,
            )
        }
    }

    @Test
    fun wholeRowDistinctThenProjectionStillPreservesDuplicateProjectedValues() {
        val ranked = "SELECT id, category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t"
        for (projection in listOf("SELECT category", "SELECT ALL category")) {
            assertResult(
                "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 |> DISTINCT |> $projection",
                "SELECT category FROM (SELECT DISTINCT id, category FROM ($ranked) AS ranked WHERE ref_rank <= 3) AS deduped",
                listOf("category"), listOf(listOf("A"), listOf("A"), listOf("B")),
            )
        }
        assertResult(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 |> SELECT DISTINCT category",
            "SELECT DISTINCT category FROM ($ranked) AS ranked WHERE ref_rank <= 3",
            listOf("category"), listOf(listOf("A"), listOf("B")),
        )
    }

    @Test
    fun duplicateRowsAreDeduplicatedWithoutAnInternalRankChangingTheirIdentity() {
        assertResult(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 |> SELECT DISTINCT *",
            "SELECT DISTINCT id, category FROM " +
                "(SELECT id, category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked WHERE ref_rank <= 3",
            listOf("id", "category"), listOf(listOf("1", "A"), listOf("2", "B")),
            // Both tied copies are selected, so their internal ordering cannot affect the expected rows.
            values = "(1, 'A'), (1, 'A'), (2, 'B'), (3, NULL)",
        )
    }

    @Test
    fun nativeFilterPrecedesFinalOrderingLimitAndOffset() {
        val reference = "SELECT id, category FROM " +
            "(SELECT id, category, ROW_NUMBER() OVER (ORDER BY id DESC) AS ref_rank FROM t) AS ranked " +
            "WHERE ref_rank <= 2 ORDER BY id LIMIT 1 OFFSET 1"
        for (suffix in listOf(
            "ORDER BY id LIMIT 1 OFFSET 1",
            "|> ORDER BY id |> LIMIT 1 OFFSET 1",
            "|> SELECT DISTINCT * |> ORDER BY id |> LIMIT 1 OFFSET 1",
            "|> SELECT * |> ORDER BY id |> OFFSET 1 |> LIMIT 1",
            "ORDER BY id OFFSET 1",
        )) {
            assertResult(
                "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) <= 2 $suffix",
                reference, listOf("id", "category"), listOf(listOf("3", "B")), ordered = true,
            )
        }
        assertResult(
            "SELECT DISTINCT category FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 ORDER BY category LIMIT 1 OFFSET 1",
            "SELECT DISTINCT category FROM (SELECT category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank <= 3 ORDER BY category LIMIT 1 OFFSET 1",
            listOf("category"), listOf(listOf("B")), ordered = true,
        )
        assertResult(
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 3 " +
                "|> SELECT DISTINCT category |> ORDER BY category |> LIMIT 1 OFFSET 1",
            "SELECT DISTINCT category FROM (SELECT category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank <= 3 ORDER BY category LIMIT 1 OFFSET 1",
            listOf("category"), listOf(listOf("B")), ordered = true,
        )
    }

    @Test
    fun nullGroupsEmptyInputAndZeroLimitDoNotChangeTheOutputSchema() {
        val source = "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) = 1 |> SELECT DISTINCT *"
        val reference = "SELECT id, category FROM (SELECT id, category, " +
            "ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS ref_rank FROM t) AS ranked WHERE ref_rank = 1"
        assertResult(
            source, reference, listOf("id", "category"), listOf(listOf("1", null), listOf("3", "A")),
            values = "(1, NULL), (2, NULL), (3, 'A'), (4, 'A')",
        )
        assertResult(source, reference, listOf("id", "category"), emptyList(), values = null)
        assertResult("$source |> LIMIT 0", "$reference LIMIT 0", listOf("id", "category"), emptyList())
        assertResult(
            "SELECT category FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) <= 2 AND category = 'A' |> SELECT DISTINCT *",
            "SELECT DISTINCT category FROM (SELECT category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank <= 2 AND category = 'A'",
            listOf("category"), listOf(listOf("A")), values = "(1, NULL), (2, 'A'), (3, 'A')",
        )
    }

    @Test
    fun explicitDistinctOnWithQualifyRetainsItsSeparateFilteringStage() {
        assertResult(
            "SELECT DISTINCT ON (category) id, category FROM t " +
                "QUALIFY ROW_NUMBER() OVER (PARTITION BY category ORDER BY id DESC) = 1 ORDER BY id",
            "SELECT id, category FROM (SELECT id, category, ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS chosen " +
                "FROM (SELECT id, category, ROW_NUMBER() OVER (PARTITION BY category ORDER BY id DESC) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank = 1) AS picked WHERE chosen = 1 ORDER BY id",
            listOf("id", "category"), listOf(listOf("2", "A"), listOf("3", "B")), ordered = true,
        )
        assertResult(
            "SELECT DISTINCT ON (category) id, category, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY rn > 1 ORDER BY id",
            "SELECT id, category, rn FROM (SELECT id, category, rn, " +
                "ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS chosen FROM " +
                "(SELECT id, category, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS ranked WHERE rn > 1) AS picked " +
                "WHERE chosen = 1 ORDER BY id",
            listOf("id", "category", "rn"), listOf(listOf("2", "A", "2"), listOf("3", "B", "3")), ordered = true,
        )
    }

    @Test
    fun nonstarDistinctOnCannotRelowerItsNativeQualifyChildInTheWrongOrder() {
        assertResult(
            "SELECT DISTINCT ON (category) id, category FROM " +
                "(SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id DESC) < 3) AS filtered ORDER BY id",
            "SELECT id, category FROM (SELECT id, category, ROW_NUMBER() OVER (PARTITION BY category ORDER BY id) AS chosen " +
                "FROM (SELECT id, category, ROW_NUMBER() OVER (ORDER BY id DESC) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank < 3) AS picked WHERE chosen = 1 ORDER BY id",
            listOf("id", "category"), listOf(listOf("2", "A"), listOf("3", "B")), ordered = true,
        )
    }

    @Test
    fun nativeQualifyStillRunsAfterSemiJoinPreprocessing() {
        assertResult(
            "SELECT t.* FROM t SEMI JOIN t AS u ON t.id = u.id " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY t.id) < 3 ORDER BY t.id",
            "SELECT id, category FROM (SELECT id, category, ROW_NUMBER() OVER (ORDER BY id) AS ref_rank FROM t) AS ranked " +
                "WHERE ref_rank < 3 ORDER BY id",
            listOf("id", "category"), listOf(listOf("1", "A"), listOf("2", "A")), ordered = true,
        )
    }

    @Test
    fun scalarQualifyPredicatesUseASeparateWhereWithoutExportingMissingInputs() {
        for (predicate in listOf("id > 1", "t.id > 1")) {
            assertResult(
                "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY $predicate |> SELECT *",
                "SELECT id, rn FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS projected WHERE id > 1",
                listOf("id", "rn"), listOf(listOf("2", "2"), listOf("3", "3")), sourceDialect = "duckdb",
            )
        }
        assertResult(
            "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t QUALIFY t.id > 1 |> SELECT *",
            "SELECT rn FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS projected WHERE id > 1",
            listOf("rn"), listOf(listOf("2"), listOf("3")), sourceDialect = "duckdb",
        )
        assertResult(
            "SELECT t.id AS original_id, -t.id AS id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn FROM t QUALIFY t.id > 1",
            "SELECT original_id, id, rn FROM (SELECT t.id AS original_id, -t.id AS id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn FROM t) AS ranked " +
                "WHERE original_id > 1",
            listOf("original_id", "id", "rn"), listOf(listOf("2", "-2", "2"), listOf("3", "-3", "3")),
            sourceDialect = "duckdb",
        )
        assertResult(
            "SELECT t.id AS original_id, -t.id AS id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn FROM t QUALIFY id > 1",
            "SELECT original_id, id, rn FROM (SELECT t.id AS original_id, -t.id AS id, ROW_NUMBER() OVER (ORDER BY t.id) AS rn FROM t) AS ranked " +
                "WHERE original_id > 1",
            listOf("original_id", "id", "rn"), listOf(listOf("2", "-2", "2"), listOf("3", "-3", "3")),
            sourceDialect = "duckdb",
        )
        assertResult(
            "SELECT category, COUNT(*) AS n, ROW_NUMBER() OVER (ORDER BY category) AS rn FROM t GROUP BY category QUALIFY COUNT(*) > 1",
            "SELECT category, n, rn FROM (SELECT category, COUNT(*) AS n, ROW_NUMBER() OVER (ORDER BY category) AS rn " +
                "FROM t GROUP BY category) AS grouped WHERE n > 1",
            listOf("category", "n", "rn"), listOf(listOf("A", "2", "1")), sourceDialect = "duckdb",
        )
        assertResult(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM (SELECT id FROM t) AS input_rows QUALIFY rn < 3",
            "SELECT id, rn FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t) AS ranked WHERE rn < 3",
            listOf("id", "rn"), listOf(listOf("1", "1"), listOf("2", "2")), sourceDialect = "duckdb",
        )
        assertResult(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn, ROW_NUMBER() OVER (ORDER BY id) AS actual_rank " +
                "FROM (SELECT 1 AS id, 0 AS rn) AS t QUALIFY rn = 0 AND actual_rank = 1",
            "SELECT id, selected_rn AS rn, actual_rank FROM " +
                "(SELECT id, rn AS input_rn, ROW_NUMBER() OVER (ORDER BY id) AS selected_rn, " +
                "ROW_NUMBER() OVER (ORDER BY id) AS actual_rank FROM (SELECT 1 AS id, 0 AS rn) AS t) AS ranked " +
                "WHERE input_rn = 0 AND actual_rank = 1",
            listOf("id", "rn", "actual_rank"), listOf(listOf("1", "1", "1")), sourceDialect = "duckdb",
        )
    }
}
