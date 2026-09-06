package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeSelect
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fixed outcomes for the client 0.10.3 boundary regressions, not success-or-refusal checks. */
class PipeBoundaryContractTest {
    private enum class Outcome { MUST_SUCCEED, MUST_REFUSE }

    private enum class Boundary(val sql: String, val stage: String) {
        LIMIT("LIMIT 2", "PipeLimit"),
        OFFSET("OFFSET 1", "PipeOffset"),
        LIMIT_OFFSET("LIMIT 2 OFFSET 1", "PipeLimit"),
        ZERO_LIMIT("LIMIT 0", "PipeLimit"),
        ZERO_OFFSET("OFFSET 0", "PipeOffset"),
        PRIOR_DISTINCT("DISTINCT", "PipeDistinct"),
    }

    private enum class Projection(val sql: String) {
        SELECT("SELECT"), ALL("SELECT ALL"), DISTINCT("SELECT DISTINCT"),
    }

    private data class Case(
        val name: String,
        val sql: String,
        val projection: String,
        val outcome: Outcome,
        val columns: List<String> = emptyList(),
        val dialect: String = "doris",
        val stages: List<String> = emptyList(),
        val lateralAliases: List<String> = emptyList(),
        val outerLaterals: Int = 0,
        val joins: Int = 0,
        val unnests: Int = 0,
        val fromKind: String = "Table",
        val distinct: Boolean = false,
    )

    // These are catalog facts, not output names inferred from the pipe being tested.
    private val schema = mapOf(
        "t" to linkedMapOf("id" to "INT", "category" to "VARCHAR", "arr" to "ARRAY<INT>"),
        "u" to linkedMapOf("other_id" to "INT", "category2" to "VARCHAR"),
    )
    private val lateral = "t LATERAL VIEW EXPLODE(t.arr) e AS item"
    private val aliasedLateral = "t AS a LATERAL VIEW EXPLODE(a.arr) e AS item"
    private val chainedLateral = "$lateral LATERAL VIEW EXPLODE(ARRAY(e.item)) f AS next_item"
    private val joined = "t AS a JOIN u AS b ON a.id = b.other_id"

    private fun boundaryCases(): List<Case> {
        val inputs = listOf(
            Case("lateral/base-star", "FROM $lateral", "t.*", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e")),
            Case("lateral/element-column", "FROM $lateral", "e.item", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e")),
            Case("lateral/element-star", "FROM $lateral", "e.*", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e")),
            Case("aliased-lateral/base-star", "FROM $aliasedLateral", "a.*", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e")),
            Case("chained-laterals/last-column", "FROM $chainedLateral", "f.next_item", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e", "f")),
            Case("outer-lateral/element-star", "FROM t LATERAL VIEW OUTER EXPLODE(t.arr) e AS item",
                "e.*", Outcome.MUST_REFUSE, lateralAliases = listOf("e"), outerLaterals = 1),
            Case("pipe-full-join/left-column", "FROM t AS a |> FULL OUTER JOIN u AS b ON a.id = b.other_id",
                "a.id", Outcome.MUST_REFUSE, stages = listOf("PipeJoin"), joins = 1),
            Case("join/right-star", "FROM $joined", "b.*", Outcome.MUST_REFUSE, joins = 1),
            Case("lateral/unqualified-unresolved-input", "FROM $lateral", "item", Outcome.MUST_REFUSE,
                lateralAliases = listOf("e")),
            Case("explicit-lateral/source-qualified", "SELECT t.id AS base_id, e.item AS item_value FROM $lateral",
                "e.item", Outcome.MUST_REFUSE, lateralAliases = listOf("e")),
            Case("explicit-join/source-qualified", "SELECT a.id AS left_id, b.other_id AS right_id FROM $joined",
                "a.id", Outcome.MUST_REFUSE, joins = 1),
            Case("partial-table/source-qualified", "SELECT t.id FROM t", "t.id", Outcome.MUST_REFUSE),
            Case("extended-table/source-star", "SELECT t.*, 1 AS extra FROM t", "t.*", Outcome.MUST_REFUSE),
            Case("renamed-table/source-qualified-output", "SELECT t.id AS base_id FROM t",
                "t.base_id", Outcome.MUST_REFUSE),
            Case("cross-unnest/source-qualified", "FROM t CROSS JOIN UNNEST(t.arr) AS e(item)",
                "e.item", Outcome.MUST_REFUSE, dialect = "duckdb", joins = 1, unnests = 1),

            Case("table/whole-rowset-star", "FROM t", "t.*", Outcome.MUST_SUCCEED,
                listOf("id", "category", "arr")),
            Case("aliased-table/reordered-columns", "FROM t AS a", "a.category, a.id", Outcome.MUST_SUCCEED,
                listOf("category", "id")),
            Case("pipe-as-table/whole-rowset-star", "FROM t |> AS s", "s.*", Outcome.MUST_SUCCEED,
                listOf("id", "category", "arr"), stages = listOf("PipeAs")),
            Case("cte/whole-rowset-star", "WITH s AS (SELECT category, id FROM t) SELECT * FROM s",
                "s.*", Outcome.MUST_SUCCEED, listOf("category", "id")),
            Case("derived-table/whole-rowset-star", "FROM (SELECT category, id FROM t) AS s",
                "s.*", Outcome.MUST_SUCCEED, listOf("category", "id"), fromKind = "Subquery"),
            Case("pipe-as-lateral/whole-rowset-star", "FROM $lateral |> AS s", "s.*", Outcome.MUST_SUCCEED,
                listOf("id", "category", "arr", "item"), stages = listOf("PipeAs"), lateralAliases = listOf("e")),
            Case("derived-lateral/reordered-columns", "FROM (SELECT t.id, e.item FROM $lateral) AS s",
                "s.item, s.id", Outcome.MUST_SUCCEED, listOf("item", "id"),
                lateralAliases = listOf("e"), fromKind = "Subquery"),
            Case("derived-join/whole-rowset-star",
                "FROM (SELECT a.id AS left_id, b.other_id AS right_id FROM $joined) AS s",
                "s.*", Outcome.MUST_SUCCEED, listOf("left_id", "right_id"), joins = 1, fromKind = "Subquery"),
            Case("explicit-lateral/output-names", "SELECT t.id AS base_id, e.item AS item_value FROM $lateral",
                "item_value, base_id", Outcome.MUST_SUCCEED, listOf("item_value", "base_id"), lateralAliases = listOf("e")),
            Case("explicit-join/output-names", "SELECT a.id AS left_id, b.other_id AS right_id FROM $joined",
                "right_id, left_id", Outcome.MUST_SUCCEED, listOf("right_id", "left_id"), joins = 1),
            Case("partial-table/local-scalar-shadowing", "SELECT t.id AS base_id FROM t",
                "(SELECT MAX(t.id) FROM t) AS local_id, base_id", Outcome.MUST_SUCCEED, listOf("local_id", "base_id")),
            Case("explicit-lateral/local-scalar-qualified-star",
                "SELECT t.id AS base_id, e.item AS item_value FROM $lateral",
                "(SELECT e.* FROM (SELECT 42 AS answer) AS e) AS local_item, item_value",
                Outcome.MUST_SUCCEED, listOf("local_item", "item_value"), lateralAliases = listOf("e")),
            Case("unnest/whole-rowset-star", "FROM UNNEST([10, 20, 30]) AS e(item)", "e.*",
                Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb", unnests = 1, fromKind = "Unnest"),
            Case("table-function/whole-rowset-star", "FROM RANGE(1, 4) AS s(item)", "s.*",
                Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb", fromKind = "TableFunction"),
        )
        // Every input meets all six boundaries and each SELECT mode twice. Rotating the
        // mode avoids a 522-case product while retaining both outcomes at every boundary.
        return inputs.flatMapIndexed { inputIndex, input ->
            Boundary.entries.mapIndexed { boundaryIndex, boundary ->
                val projection = Projection.entries[(inputIndex + boundaryIndex) % Projection.entries.size]
                input.copy(
                    name = "${input.name}/${boundary.name}/${projection.name}/${input.outcome}",
                    sql = "${input.sql} |> ${boundary.sql} |> ${projection.sql} ${input.projection}",
                    stages = input.stages + listOf(boundary.stage, "PipeSelect"),
                    distinct = projection == Projection.DISTINCT,
                )
            }
        }
    }

    @Test
    fun matrixHasFixedOutcomesAndPositiveControlsAtEveryBoundary() {
        val cases = boundaryCases()
        assertEquals(174, cases.size)
        assertEquals(cases.size, cases.map { it.name }.toSet().size)
        assertEquals(90, cases.count { it.outcome == Outcome.MUST_REFUSE })
        assertEquals(84, cases.count { it.outcome == Outcome.MUST_SUCCEED })
        for (boundary in Boundary.entries) {
            for (projection in Projection.entries) {
                val cells = cases.filter { "/${boundary.name}/${projection.name}/" in it.name }
                assertTrue(cells.any { it.outcome == Outcome.MUST_SUCCEED }, "$boundary/$projection needs a positive control")
                assertTrue(cells.any { it.outcome == Outcome.MUST_REFUSE }, "$boundary/$projection needs a refusal control")
            }
        }
    }

    @Test
    fun unsafeInputNamespacesMustRefuse() {
        checkCases(boundaryCases().filter { it.outcome == Outcome.MUST_REFUSE })
    }

    @Test
    fun wholeRowsetsExplicitOutputsAndLocalScalarBindingsMustSucceed() {
        checkCases(boundaryCases().filter { it.outcome == Outcome.MUST_SUCCEED })
    }

    @Test
    fun lateralProjectionBeforeLimitDoesNotNeedAnInputBoundary() {
        val inputs = listOf(
            Case("lateral/base-star", "FROM $lateral", "t.*", Outcome.MUST_SUCCEED,
                listOf("id", "category", "arr"), lateralAliases = listOf("e")),
            Case("lateral/element-column", "FROM $lateral", "e.item", Outcome.MUST_SUCCEED,
                listOf("item"), lateralAliases = listOf("e")),
            Case("lateral/element-star", "FROM $lateral", "e.*", Outcome.MUST_SUCCEED,
                listOf("item"), lateralAliases = listOf("e")),
            Case("aliased-lateral/base-star", "FROM $aliasedLateral", "a.*", Outcome.MUST_SUCCEED,
                listOf("id", "category", "arr"), lateralAliases = listOf("e")),
            Case("chained-laterals/last-star", "FROM $chainedLateral", "f.*", Outcome.MUST_SUCCEED,
                listOf("next_item"), lateralAliases = listOf("e", "f")),
        )
        val cases = inputs.flatMap { input ->
            Projection.entries.map { projection ->
                input.copy(
                    name = "${input.name}/NO_BOUNDARY/${projection.name}/MUST_SUCCEED",
                    sql = "${input.sql} |> ${projection.sql} ${input.projection} |> LIMIT 2",
                    stages = listOf("PipeSelect", "PipeLimit"),
                    distinct = projection == Projection.DISTINCT,
                )
            }
        }
        assertEquals(15, cases.size)
        checkCases(cases)
    }

    private fun checkCases(cases: List<Case>, execute: ((Case, String) -> Unit)? = null) {
        val verifiers = mutableMapOf<String, SqlVerifier>()
        try {
            for (case in cases) {
                val label = "${case.name}\n${case.sql}"
                val dialect = Dialects.forName(case.dialect)
                val fragment = SqlFragment(case.sql, case.dialect)
                // Parsing is outside assertFailsWith: a ParseError is never a safety refusal.
                // FROM (subquery) can produce a synthetic outer SELECT; the pipe
                // remains first-class inside it and must still lower at the public API.
                val pipe = fragment.ast.findAll<PipeQuery>().single()
                val head = pipe.thisArg
                val from = if (head is Select) assertIs<From>(head.args["from_"], label).thisArg else head
                if (case.fromKind == "TableFunction") {
                    assertIs<Func>(assertIs<Table>(from, label).thisArg, label)
                } else {
                    assertEquals(case.fromKind, assertIs<Expression>(from, label)::class.simpleName, label)
                }
                val stages = pipe.expressionsArg.filterIsInstance<Expression>()
                assertEquals(case.stages, stages.map { it::class.simpleName }, label)
                assertEquals(case.lateralAliases.sorted(), pipe.findAll<Lateral>().map { it.alias }.sorted().toList(), label)
                assertEquals(case.outerLaterals, pipe.findAll<Lateral>().count { it.args["outer"] == true }, label)
                assertEquals(case.joins, pipe.findAll<Join>().count(), label)
                assertEquals(case.unnests, pipe.findAll<Unnest>().count(), label)
                val consumer = stages.filterIsInstance<PipeSelect>().last()
                assertEquals(dialect.parseOne("SELECT ${case.projection}").expressionsArg, consumer.expressionsArg, label)
                assertEquals(case.distinct, consumer.args["distinct"] != null, label)
                val original = fragment.ast.copy()
                val originalNodes = fragment.ast.walk().map { Triple(it, it.parent, it.meta.toMap()) }.toList()
                val referencePositions = consumer.findAll<Column>().mapNotNull { it.thisArg as? Identifier }
                    .map { assertNotNull(SourceMap.sourcePosOf(it), label) }.toList()
                for (pretty in listOf(false, true)) {
                    val context = "$label\npretty=$pretty"
                    try {
                        when (case.outcome) {
                            Outcome.MUST_REFUSE -> {
                                val error = assertFailsWith<UnsupportedError>(context) {
                                    fragment.toExecutable(case.dialect, pretty = pretty)
                                }
                                val message = assertNotNull(error.message, context)
                                assertTrue(message.isNotBlank() && message.length > 20, "$context\n$message")
                                assertTrue(listOf("column", "projection", "star", "reference", "rowset", "namespace")
                                    .any { message.contains(it, ignoreCase = true) }, "$context\n$message")
                            }
                            Outcome.MUST_SUCCEED -> {
                                val result = fragment.toExecutable(case.dialect, pretty = pretty)
                                assertEquals(emptyList(), result.unsupportedMessages, context)
                                assertFalse(result.isRawPassthroughStatement, context)
                                val sourceMap = assertNotNull(result.sourceMap, context)
                                assertSame(result.sql, sourceMap.output, context)
                                for (position in referencePositions) {
                                    val entry = sourceMap.entries.firstOrNull {
                                        it.node is Identifier && SourceMap.sourcePosOf(it.node) == position
                                    }
                                    assertNotNull(entry, "$context\nMissing mapped column at ${position.start}\n${result.sql}")
                                    assertEquals(position, sourceMap.sourcePosition(entry.start, exact = true), context)
                                }
                                val verifier = verifiers.getOrPut(case.dialect) {
                                    assertNotNull(SqlVerifiers.forEngine(case.dialect), context)
                                }
                                val verified = verifier.verify(result.sql)
                                assertTrue(verified.verified && !verified.advisory, "$context\nNative parser required: $verified")
                                assertTrue(verified.accepted, "$context\n${result.sql}\n$verified")
                                val generated = dialect.parseOne(result.sql)
                                assertIs<Select>(generated, context)
                                assertFalse(generated.findAll<PipeQuery>().any(), context)
                                val qualified = qualify(
                                    generated.copy(), dialect = dialect, schema = schema,
                                    inferSchema = false, validateQualifyColumns = true,
                                    allowPartialQualification = false, expandStars = true,
                                )
                                assertFalse(qualified.selects.any { it.isStar }, "$context\n${result.sql}")
                                assertEquals(case.columns, qualified.selects.map { it.outputName }, "$context\n${result.sql}")
                                execute?.invoke(case, result.sql)
                            }
                        }
                    } finally {
                        assertEquals(original, fragment.ast, "$context\nGeneration must not mutate the input AST")
                        for ((node, parent, metadata) in originalNodes) {
                            assertSame(parent, node.parent, context)
                            assertEquals(metadata, node.meta, context)
                        }
                    }
                }
            }
        } finally {
            verifiers.values.filterIsInstance<AutoCloseable>().forEach { it.close() }
        }
    }

    @Test
    fun nestedPipeAliasesAndDialectIdentifierCaseRemainLocal() {
        val cases = listOf(
            Case("nested-pipe/local-star", "SELECT t.id AS base_id FROM t LIMIT 2 " +
                "|> SELECT base_id, (SELECT other_id FROM u |> AS s |> SELECT s.* |> LIMIT 1) AS scalar_id",
                "", Outcome.MUST_SUCCEED, listOf("base_id", "scalar_id")),
            Case("duckdb/unquoted-case", "FROM t AS A |> LIMIT 1 |> SELECT a.id",
                "", Outcome.MUST_SUCCEED, listOf("id"), dialect = "duckdb"),
            Case("duckdb/quoted-case", "FROM t AS \"A\" |> LIMIT 1 |> SELECT \"a\".id",
                "", Outcome.MUST_SUCCEED, listOf("id"), dialect = "duckdb"),
            Case("duckdb/local-case", "SELECT t.id AS base_id FROM t LIMIT 2 " +
                "|> SELECT (SELECT a.* FROM (SELECT 42 AS answer) AS A) AS scalar_id",
                "", Outcome.MUST_SUCCEED, listOf("scalar_id"), dialect = "duckdb"),
        )
        for (case in cases) {
            val fragment = SqlFragment(case.sql, case.dialect)
            assertTrue(fragment.ast.findAll<PipeQuery>().any(), case.sql)
            for (pretty in listOf(false, true)) {
                val result = fragment.toExecutable(case.dialect, pretty = pretty)
                assertEquals(emptyList(), result.unsupportedMessages, case.sql)
                val verifier = assertNotNull(SqlVerifiers.forEngine(case.dialect))
                assertTrue(verifier.verify(result.sql).accepted, result.sql)
                val qualified = qualify(Dialects.forName(case.dialect).parseOne(result.sql),
                    dialect = Dialects.forName(case.dialect), schema = schema,
                    inferSchema = false, validateQualifyColumns = true)
                assertEquals(case.columns, qualified.selects.map { it.outputName }, result.sql)
            }
        }
    }

    @Test
    fun portableDuckdbControlsExecuteGeneratedSqlUnchanged() {
        val unnest = "UNNEST([30, 10, 20]) AS e(item)"
        val expanded = "t CROSS JOIN UNNEST(t.arr) AS e(item)"
        val cases = listOf(
            Case("execute/unnest-star-limit", "FROM $unnest |> ORDER BY e.item |> LIMIT 2 |> SELECT e.*",
                "e.*", Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb",
                stages = listOf("PipeOrderBy", "PipeLimit", "PipeSelect"), unnests = 1, fromKind = "Unnest"),
            Case("execute/unnest-column-offset", "FROM $unnest |> ORDER BY e.item |> OFFSET 1 |> SELECT ALL e.item",
                "e.item", Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb",
                stages = listOf("PipeOrderBy", "PipeOffset", "PipeSelect"), unnests = 1, fromKind = "Unnest"),
            Case("execute/unnest-star-zero", "FROM $unnest |> LIMIT 0 |> SELECT DISTINCT e.*",
                "e.*", Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb",
                stages = listOf("PipeLimit", "PipeSelect"), unnests = 1, fromKind = "Unnest", distinct = true),
            Case("execute/table-function-limit-offset",
                "FROM RANGE(1, 4) AS s(item) |> ORDER BY s.item |> LIMIT 2 OFFSET 1 |> SELECT s.item",
                "s.item", Outcome.MUST_SUCCEED, listOf("item"), dialect = "duckdb",
                stages = listOf("PipeOrderBy", "PipeLimit", "PipeSelect"), fromKind = "TableFunction"),
            Case("execute/unnest-projection-before-limit",
                "FROM $expanded |> SELECT t.id, e.item |> ORDER BY id, item |> LIMIT 3",
                "t.id, e.item", Outcome.MUST_SUCCEED, listOf("id", "item"), dialect = "duckdb",
                stages = listOf("PipeSelect", "PipeOrderBy", "PipeLimit"), joins = 1, unnests = 1),
            Case("execute/explicit-unnest-distinct-after-limit",
                "SELECT t.id AS base_id, e.item AS item_value FROM $expanded ORDER BY t.id, e.item LIMIT 3 " +
                    "|> SELECT DISTINCT item_value, base_id",
                "item_value, base_id", Outcome.MUST_SUCCEED, listOf("item_value", "base_id"), dialect = "duckdb",
                stages = listOf("PipeSelect"), joins = 1, unnests = 1, distinct = true),
            Case("execute/pipe-as-unnest-limit-offset",
                "FROM $expanded |> AS s |> ORDER BY s.id, s.item |> LIMIT 3 OFFSET 1 |> SELECT s.item, s.id",
                "s.item, s.id", Outcome.MUST_SUCCEED, listOf("item", "id"), dialect = "duckdb",
                stages = listOf("PipeAs", "PipeOrderBy", "PipeLimit", "PipeSelect"), joins = 1, unnests = 1),
            Case("execute/derived-outer-unnest-null-row",
                "FROM (SELECT t.id, e.item FROM t LEFT JOIN UNNEST(t.arr) AS e(item) ON TRUE) AS s " +
                    "|> ORDER BY s.id, s.item |> LIMIT 2 OFFSET 3 |> SELECT s.*",
                "s.*", Outcome.MUST_SUCCEED, listOf("id", "item"), dialect = "duckdb",
                stages = listOf("PipeOrderBy", "PipeLimit", "PipeSelect"), joins = 1, unnests = 1, fromKind = "Subquery"),
            Case("execute/derived-explicit-lateral",
                "FROM (SELECT t.id, e.item FROM t CROSS JOIN LATERAL (SELECT t.id + 100 AS item) AS e) AS s " +
                    "|> ORDER BY s.id |> LIMIT 2 |> SELECT s.item, s.id",
                "s.item, s.id", Outcome.MUST_SUCCEED, listOf("item", "id"), dialect = "duckdb",
                stages = listOf("PipeOrderBy", "PipeLimit", "PipeSelect"), lateralAliases = listOf("e"),
                joins = 1, fromKind = "Subquery"),
            Case("execute/local-scalar-qualified-star",
                "SELECT t.id AS base_id, e.item AS item_value FROM $expanded ORDER BY t.id, e.item LIMIT 2 " +
                    "|> SELECT (SELECT e.* FROM (SELECT 42 AS answer) AS e) AS local_item, item_value",
                "(SELECT e.* FROM (SELECT 42 AS answer) AS e) AS local_item, item_value",
                Outcome.MUST_SUCCEED, listOf("local_item", "item_value"), dialect = "duckdb",
                stages = listOf("PipeSelect"), joins = 1, unnests = 1),
        )
        val expected: Map<String, List<List<String?>>> = mapOf(
            "execute/unnest-star-limit" to listOf(listOf("10"), listOf("20")),
            "execute/unnest-column-offset" to listOf(listOf("20"), listOf("30")),
            "execute/unnest-star-zero" to emptyList(),
            "execute/table-function-limit-offset" to listOf(listOf("2"), listOf("3")),
            "execute/unnest-projection-before-limit" to listOf(listOf("1", "10"), listOf("1", "10"), listOf("1", "20")),
            "execute/explicit-unnest-distinct-after-limit" to listOf(listOf("10", "1"), listOf("20", "1")),
            "execute/pipe-as-unnest-limit-offset" to listOf(listOf("10", "1"), listOf("20", "1"), listOf("30", "2")),
            "execute/derived-outer-unnest-null-row" to listOf(listOf("2", "30"), listOf("3", null)),
            "execute/derived-explicit-lateral" to listOf(listOf("101", "1"), listOf("102", "2")),
            "execute/local-scalar-qualified-star" to listOf(listOf("42", "10"), listOf("42", "10")),
        )
        assertEquals(10, cases.size)
        assertEquals(cases.map { it.name }.toSet(), expected.keys)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (id INTEGER, category VARCHAR, arr INTEGER[])")
                statement.execute("INSERT INTO t VALUES (1, 'A', [10, 10, 20]), (2, 'B', [30]), (3, 'C', []), (4, 'D', NULL)")
                checkCases(cases) { case, generated ->
                    // Execute the exact DuckDB output. No Doris translation or SQL rewriting.
                    statement.executeQuery(generated).use { result ->
                        val count = result.metaData.columnCount
                        assertEquals(case.columns, (1..count).map { result.metaData.getColumnLabel(it) }, "${case.name}\n$generated")
                        val actual = buildList {
                            while (result.next()) add((1..count).map { result.getString(it) })
                        }
                        // Ordering inside an input boundary chooses rows, not final presentation.
                        assertEquals(expected.getValue(case.name).groupingBy { it }.eachCount(),
                            actual.groupingBy { it }.eachCount(), "${case.name}\n$generated")
                    }
                }
            }
        }
    }
}
