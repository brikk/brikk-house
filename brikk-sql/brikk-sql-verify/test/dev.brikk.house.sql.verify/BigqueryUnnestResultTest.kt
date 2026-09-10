package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BigqueryUnnestResultTest {
    @Test
    fun loweredValuesAndCteColumnsKeepResults() {
        val cases = listOf(
            Triple("SELECT t.a, t.b FROM (VALUES (1, 'x'), (2, NULL), (1, 'x')) AS t(a, b)",
                listOf("a", "b"), listOf("{\"a\":1,\"b\":\"x\"}", "{\"a\":2,\"b\":null}", "{\"a\":1,\"b\":\"x\"}")),
            Triple("SELECT t.a, u.b FROM (VALUES (1), (2)) AS t(a) CROSS JOIN (VALUES (3), (4)) AS u(b)",
                listOf("a", "b"), listOf("{\"a\":1,\"b\":3}", "{\"a\":1,\"b\":4}", "{\"a\":2,\"b\":3}", "{\"a\":2,\"b\":4}")),
            Triple("WITH cte(foo) AS (SELECT 1 AS bar UNION ALL SELECT 2) SELECT foo FROM cte",
                listOf("foo"), listOf("{\"foo\":1}", "{\"foo\":2}")),
        )
        for ((source, columns, expected) in cases) {
            val generator = Dialects.BIGQUERY.generator(sourceDialect = "postgres")
            val sql = generator.generate(Dialects.POSTGRES.parseOne(source))
            assertEquals(emptyList(), generator.unsupportedMessages, source)
            check(sql, columns, expected, struct = source.contains("VALUES"))
        }
    }

    @Test
    fun untypedNullFieldsReconcileAcrossRows() {
        for (array in listOf(
            "[STRUCT('x' AS b), STRUCT(NULL AS b)]",
            "[STRUCT(NULL AS b), STRUCT('x' AS b)]",
        )) check("SELECT t.b FROM UNNEST($array) AS t", listOf("b"),
            listOf("{\"b\":\"x\"}", "{\"b\":null}"), struct = true)
        check("SELECT t.s.b AS b FROM UNNEST([STRUCT(STRUCT(NULL AS b) AS s), STRUCT(STRUCT('x' AS b) AS s)]) AS t",
            listOf("b"), listOf("{\"b\":null}", "{\"b\":\"x\"}"), struct = true)
        // Trino CLI JSON output cannot serialize collection columns; inspect their contents as scalars.
        check("SELECT ARRAY_LENGTH(t.a) AS n, t.a[SAFE_OFFSET(0)] AS a " +
            "FROM UNNEST([STRUCT(NULL AS a), STRUCT(['x'] AS a)]) AS t",
            listOf("n", "a"), listOf("{\"n\":null,\"a\":null}", "{\"n\":1,\"a\":\"x\"}"), struct = true)
        check("SELECT ARRAY_LENGTH(t.a) AS n, t.a[SAFE_OFFSET(0)] AS a " +
            "FROM UNNEST([STRUCT([NULL] AS a), STRUCT(['x'] AS a)]) AS t",
            listOf("n", "a"), listOf("{\"n\":1,\"a\":null}", "{\"n\":1,\"a\":\"x\"}"), struct = true)
        check("SELECT t.b FROM UNNEST([STRUCT(NULL AS b), STRUCT(NULL AS b)]) AS t",
            listOf("b"), listOf("{\"b\":null}", "{\"b\":null}"), struct = true)
    }

    @Test
    fun nativeAliasesAndImplicitReferencesStayScopeCorrect() {
        for ((source, expected) in listOf(
            "SELECT x.a FROM UNNEST([STRUCT(1 AS a)]) AS x" to "SELECT x.a FROM UNNEST([STRUCT(1 AS a)]) AS x",
            "SELECT results FROM Coordinates, Coordinates.position AS results" to
                "SELECT results FROM Coordinates CROSS JOIN UNNEST(Coordinates.position) AS results",
        )) {
            assertEquals(expected, Dialects.BIGQUERY.generate(Dialects.BIGQUERY.parseOne(source)), source)
        }
        val quoted = Dialects.BIGQUERY.parseOne("SELECT results FROM Coordinates, `Coordinates.position` AS results")
        assertTrue(quoted.findAll<Unnest>().none())
        val aliased = Dialects.BIGQUERY.parseOne("SELECT * FROM UNNEST([1, 2]) AS x WITH OFFSET")
        val alias = aliased.find<Unnest>()!!.args["alias"] as TableAlias
        assertEquals(null, alias.thisArg)
        assertEquals(listOf("x"), alias.columns.filterIsInstance<Expression>().map { it.name })
        val qualified = qualify(aliased, dialect = Dialects.BIGQUERY, identify = false)
        assertEquals("SELECT x AS x, offset AS offset FROM UNNEST([1, 2]) AS x WITH OFFSET AS offset", Dialects.BIGQUERY.generate(qualified))
        val shadowed = qualify(Dialects.BIGQUERY.parseOne("SELECT (SELECT _0.a FROM (SELECT 1 AS a) AS _0 " +
            "CROSS JOIN (SELECT 2 AS a) AS b) FROM UNNEST([1]) AS x"), dialect = Dialects.BIGQUERY, identify = false)
        assertTrue(Dialects.BIGQUERY.generate(shadowed).contains("SELECT _0.a AS a FROM"))
    }

    @Test
    fun prestoRelationColumnsBecomeNamedBigqueryStructFields() {
        for (source in listOf(
            "SELECT u.x, u.p FROM UNNEST(ARRAY[2, 3]) WITH ORDINALITY AS u(x, p)",
            "SELECT u.a, u.b FROM UNNEST(ARRAY[CAST(ROW(1, 2) AS ROW(f INTEGER, g INTEGER))]) AS u(a, b)",
        )) {
            val generator = Dialects.BIGQUERY.generator(sourceDialect = "trino")
            val sql = generator.generate(Dialects.TRINO.parseOne(source))
            assertTrue(generator.unsupportedMessages.isEmpty(), generator.unsupportedMessages.toString())
            assertTrue(sql.contains("SELECT AS STRUCT"), sql)
            assertTrue(sql.contains(" AS u"), sql)
            assertTrue(sql.startsWith("SELECT u."), sql)
            val parsed = Dialects.BIGQUERY.parseOne(sql)
            assertEquals(Dialects.TRINO.parseOne(source).selects.map { it.aliasOrName }, parsed.selects.map { it.aliasOrName })
            if (source.contains("ORDINALITY")) assertTrue(sql.contains(" + 1 AS p"), sql)
            else assertTrue(sql.contains("STRUCT<a INT64, b INT64>"), sql)
        }
        val schema = mapOf("t" to mapOf("items" to "ARRAY<INT>"))
        val typed = annotateTypes(qualify(Dialects.TRINO.parseOne("SELECT u.x FROM t CROSS JOIN UNNEST(t.items) AS u(x)"),
            schema = schema, dialect = Dialects.TRINO, identify = false), schema = schema, dialect = Dialects.TRINO)
        val generator = Dialects.BIGQUERY.generator(sourceDialect = "trino")
        val sql = generator.generate(typed)
        assertTrue(generator.unsupportedMessages.isEmpty(), generator.unsupportedMessages.toString())
        assertTrue(sql.contains("SELECT AS STRUCT"), sql)
    }

    private fun check(source: String, columns: List<String>, expected: List<String>, struct: Boolean = false, offset: Boolean = false) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        for (target in listOf("presto", "trino")) {
            val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
            val generated = generator.generate(tree)
            assertTrue(generator.unsupportedMessages.isEmpty(), generator.unsupportedMessages.toString())
            assertTrue(SqlVerifiers.forEngine("trino")!!.verify(generated).accepted, generated)
            assertEquals(columns, Dialects.forName(target).parseOne(generated).selects.map(Expression::aliasOrName), generated)
            assertEquals(struct, generated.contains("TRANSFORM("), generated)
            assertEquals(offset, generated.contains("WITH ORDINALITY"), generated)
            if (offset) {
                assertTrue(generated.contains("LATERAL (SELECT"), generated)
                assertTrue(generated.contains(" - 1 AS "), generated)
            }

            val container = System.getenv("BRIKK_TRINO_CONTAINER") ?: continue
            val process = ProcessBuilder("docker", "exec", container, "trino", "--execute", generated,
                "--output-format", "JSON").redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Trino query timed out: $generated")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), "$generated\n$output")
                val actual = output.lineSequence().filter { it.startsWith("{") }
                    .map { Json.parseToJsonElement(it).jsonObject }.toList()
                val wanted = expected.map { Json.parseToJsonElement(it).jsonObject }
                assertEquals(wanted.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), generated)
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun namedScalarArraysAndOffsets() {
        check("SELECT x FROM UNNEST([2, 3]) AS x", listOf("x"), listOf("{\"x\":2}", "{\"x\":3}"))
        check("SELECT x, pos FROM UNNEST([2, 3]) AS x WITH OFFSET AS pos ORDER BY pos",
            listOf("x", "pos"), listOf("{\"x\":2,\"pos\":0}", "{\"x\":3,\"pos\":1}"), offset = true)
        check("SELECT x, offset FROM UNNEST([2, 3]) AS x WITH OFFSET WHERE offset = 0",
            listOf("x", "offset"), listOf("{\"x\":2,\"offset\":0}"), offset = true)
        check("SELECT x, p FROM UNNEST(CAST([] AS ARRAY<INT64>)) AS x WITH OFFSET AS p",
            listOf("x", "p"), emptyList(), offset = true)
        check("SELECT x, p FROM UNNEST(CAST(NULL AS ARRAY<INT64>)) AS x WITH OFFSET AS p",
            listOf("x", "p"), emptyList(), offset = true)
    }

    @Test
    fun namedStructFieldsAndWholeValues() {
        check("SELECT x.a FROM UNNEST([STRUCT(1 AS a), STRUCT(2 AS a)]) AS x",
            listOf("a"), listOf("{\"a\":1}", "{\"a\":2}"), struct = true)
        check("SELECT x = STRUCT(1 AS a) AS same FROM UNNEST([STRUCT(1 AS a)]) AS x",
            listOf("same"), listOf("{\"same\":true}"), struct = true)
        val whole = Dialects.TRINO.generate(Dialects.BIGQUERY.parseOne(
            "SELECT x FROM UNNEST([STRUCT(1 AS a)]) AS x"), sourceDialect = "bigquery")
        assertTrue(whole.startsWith("SELECT x FROM UNNEST(TRANSFORM("), whole)
        assertTrue(SqlVerifiers.forEngine("trino")!!.verify(whole).accepted, whole)
        check("SELECT x.a, x.b, p FROM UNNEST([STRUCT(1 AS a, 'z' AS b), STRUCT(2 AS a, 'y' AS b)]) AS x WITH OFFSET AS p",
            listOf("a", "b", "p"), listOf("{\"a\":1,\"b\":\"z\",\"p\":0}", "{\"a\":2,\"b\":\"y\",\"p\":1}"),
            struct = true, offset = true)
    }

    @Test
    fun offsetsKeepNestedScopesAndCorrelatedInputs() {
        check("SELECT p FROM UNNEST([2, 3]) AS x WITH OFFSET AS p " +
            "WHERE p IN (SELECT p FROM UNNEST([7]) AS y WITH OFFSET AS p)",
            listOf("p"), listOf("{\"p\":0}"), offset = true)
        check("SELECT t.id, x, p FROM (SELECT 7 AS id) AS t " +
            "CROSS JOIN UNNEST(CAST([t.id] AS ARRAY<INT64>)) AS x WITH OFFSET AS p",
            listOf("id", "x", "p"), listOf("{\"id\":7,\"x\":7,\"p\":0}"), offset = true)
        check("SELECT _bq_ordinal_0, p FROM UNNEST([2]) AS _bq_ordinal_0 WITH OFFSET AS p",
            listOf("_bq_ordinal_0", "p"), listOf("{\"_bq_ordinal_0\":2,\"p\":0}"), offset = true)
    }

    @Test
    fun unnamedStructStillFlattens() {
        check("SELECT a FROM UNNEST([STRUCT(1 AS a)])", listOf("a"), listOf("{\"a\":1}"))
    }

    @Test
    fun unsafeShapesHaveDiagnostics() {
        for (source in listOf(
            "SELECT x FROM UNNEST(mystery()) AS x",
            "SELECT x FROM t CROSS JOIN UNNEST(t.items) AS x",
            "SELECT a FROM UNNEST([STRUCT(1 AS a)]) AS x",
            "SELECT * FROM UNNEST([STRUCT(1 AS a)]) AS x",
            "SELECT x.* FROM UNNEST([STRUCT(1 AS a)]) AS x",
            "SELECT a, p FROM UNNEST([STRUCT(1 AS a)]) WITH OFFSET AS p",
            "SELECT t.id, x, p FROM (SELECT 1 AS id) AS t LEFT JOIN " +
                "UNNEST(CAST([t.id] AS ARRAY<INT64>)) AS x WITH OFFSET AS p ON x > 1",
        )) {
            val generator = Dialects.TRINO.generator(sourceDialect = "bigquery")
            generator.generate(Dialects.BIGQUERY.parseOne(source))
            assertTrue(generator.unsupportedMessages.any { it.contains("BigQuery") }, source)
        }
    }

    @Test
    fun qualifiedSourceAliasAndSourceLessStructEvidence() {
        val tree = Dialects.BIGQUERY.parseOne("SELECT x.a FROM UNNEST([STRUCT(1 AS a)]) AS x")
        val inferred = Dialects.TRINO.generate(tree)
        assertTrue(inferred.contains("TRANSFORM("), inferred)
        val unnest = tree.findAll<Unnest>().single()
        (unnest.args["alias"] as TableAlias).set("this", Identifier(args("this" to "u")))
        tree.findAll<Column>().first().set("db", Identifier(args("this" to "u")))
        val generated = Dialects.TRINO.generate(tree, sourceDialect = "bigquery")
        assertTrue(generated.startsWith("SELECT u.x.a FROM"), generated)
        assertTrue(generated.contains(" AS u(x)"), generated)
        assertTrue(SqlVerifiers.forEngine("trino")!!.verify(generated).accepted, generated)
    }

    @Test
    fun nativePrestoOrdinalityAndStructFlatteningStayNative() {
        val source = "SELECT a, p FROM UNNEST(ARRAY[CAST(ROW(1) AS ROW(a INTEGER))]) WITH ORDINALITY AS u(a, p)"
        val generator = Dialects.PRESTO.generator(sourceDialect = "presto")
        val generated = generator.generate(Dialects.PRESTO.parseOne(source))
        assertEquals(source, generated)
        assertFalse(generated.contains("TRANSFORM("), generated)
        assertFalse(generated.contains(" - 1"), generated)
        assertTrue(generator.unsupportedMessages.isEmpty())
        assertTrue(SqlVerifiers.forEngine("trino")!!.verify(generated).accepted, generated)
    }
}
