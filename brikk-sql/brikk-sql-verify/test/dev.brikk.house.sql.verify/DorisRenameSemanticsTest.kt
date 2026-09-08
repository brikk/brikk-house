package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.expandStarModifiers
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DorisRenameSemanticsTest {
    private val shape = Shape.of("id" to "INT", "category" to "VARCHAR")
    private val catalog = ShapeCatalog(tables = mapOf("t" to shape))
    private val schema = mapOf("t" to shape.toSchemaMapping())

    private fun result(statement: Statement, sql: String): Pair<List<String>, Map<List<String?>, Int>> =
        statement.executeQuery(sql).use { rows ->
            val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }
            val values = buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
            }
            columns to values.groupingBy { it }.eachCount()
        }

    private fun assertEquivalent(
        source: String,
        reference: String,
        columns: List<String>,
        values: String? = "(1, 'A'), (2, 'A'), (2, 'A'), (3, 'B'), (NULL, NULL)",
        qualifiedInput: Boolean = false,
    ) {
        val dialect = Dialects.DORIS
        val inputs = if (qualifiedInput) ShapeCatalog(tables = mapOf("db1.t" to shape)) else catalog
        val mapping = if (qualifiedInput) mapOf("db1" to schema) else schema
        val fragment = SqlFragment(source, "doris")
        val original = fragment.ast.copy()
        // Track the original AST through lowering and expansion, never a reparse of emitted SQL.
        val tree = expandStarModifiers(desugarPipes(fragment.ast, dialect, copy = true), mapping, dialect)
        val prepared = tree.copy()
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                if (qualifiedInput) statement.execute("CREATE SCHEMA db1")
                val table = if (qualifiedInput) "db1.t" else "t"
                statement.execute("CREATE TABLE $table (id INTEGER, category VARCHAR)")
                if (values != null) statement.execute("INSERT INTO $table VALUES $values")
                val expected = result(statement, reference)
                assertEquals(columns, expected.first, reference)
                for (pretty in listOf(false, true)) {
                    val generator = dialect.generator(pretty = pretty, sourceDialect = "doris")
                    generator.trackSpans = true
                    val sql = generator.generate(tree, copy = true)
                    assertEquals(emptyList(), generator.unsupportedMessages, source)
                    assertFalse("RENAME" in sql, sql)
                    assertSame(sql, assertNotNull(generator.lastSourceMap).output)
                    assertTrue(verifier.verify(sql).accepted, sql)
                    val bound = qualify(dialect.parseOne(sql), dialect = dialect, schema = mapping, inferSchema = false)
                    assertEquals(columns, bound.selects.map { it.aliasOrName }, sql)
                    assertEquals(expected, result(statement, sql), "$source\n$sql")
                    if (!pretty) assertEquals(sql, fragment.toStandardSql("doris", inputs, expandStars = true))
                    assertEquals(prepared, tree, "Generation must not mutate the expanded AST")
                    assertEquals(original, fragment.ast, "Lowering must not mutate the parsed AST")
                }
            }
        }
    }

    @Test
    fun renamesAndSwapsPreserveValuesWithoutFollowupStages() {
        assertEquivalent(
            "FROM t |> RENAME id AS renamed_id",
            "SELECT id AS renamed_id, category FROM t", listOf("renamed_id", "category"),
        )
        assertEquivalent(
            "FROM t |> RENAME id AS category, category AS id",
            "SELECT id AS category, category AS id FROM t", listOf("category", "id"),
        )
        assertEquivalent(
            "FROM t |> RENAME id AS id",
            "SELECT id, category FROM t", listOf("id", "category"),
        )
    }

    @Test
    fun handoffRenameWhereLimitConsumesRenamedOutput() {
        assertEquivalent(
            "FROM t |> RENAME id AS renamed_id |> WHERE renamed_id > 1 |> LIMIT 2",
            "SELECT renamed_id, category FROM (SELECT id AS renamed_id, category FROM t) AS renamed WHERE renamed_id > 1 LIMIT 2",
            listOf("renamed_id", "category"), values = "(1, 'A'), (2, 'A'), (3, 'B')",
        )
    }

    @Test
    fun subsequentSelectAndDistinctKeepTheRenameBindingAndDuplicateRows() {
        for (select in listOf("SELECT", "SELECT ALL", "SELECT DISTINCT")) {
            assertEquivalent(
                "FROM t |> RENAME id AS renamed_id |> WHERE renamed_id > 1 |> $select renamed_id, category",
                "$select renamed_id, category FROM (SELECT id AS renamed_id, category FROM t) AS renamed WHERE renamed_id > 1",
                listOf("renamed_id", "category"),
            )
        }
        assertEquivalent(
            "FROM t |> RENAME id AS renamed_id |> DISTINCT |> SELECT category",
            "SELECT category FROM (SELECT DISTINCT renamed_id, category FROM (SELECT id AS renamed_id, category FROM t) AS renamed) AS unique_rows",
            listOf("category"),
        )
    }

    @Test
    fun chainedRenamesAndSimultaneousSwapsKeepValuesAttachedToTheirSource() {
        assertEquivalent(
            "FROM t |> RENAME id AS first_id |> RENAME first_id AS last_id |> WHERE last_id > 1 |> SELECT last_id",
            "SELECT last_id FROM (SELECT first_id AS last_id, category FROM (SELECT id AS first_id, category FROM t) AS first_rename) AS last_rename WHERE last_id > 1",
            listOf("last_id"),
        )
        for (rename in listOf("id AS category, category AS id", "category AS id, id AS category")) {
            assertEquivalent(
                "FROM t |> RENAME $rename |> WHERE category > 1 |> SELECT category, id",
                "SELECT category, id FROM (SELECT id AS category, category AS id FROM t) AS swapped WHERE category > 1",
                listOf("category", "id"),
            )
        }
        assertEquivalent(
            "FROM t |> RENAME id AS category, category AS id |> RENAME category AS id, id AS category |> SELECT id, category",
            "SELECT id, category FROM t", listOf("id", "category"),
        )
    }

    @Test
    fun filteringOrderingAndLimitsAfterRenamePreserveNullAndEmptyControls() {
        for (values in listOf("(1, 'A'), (2, 'A'), (2, 'A'), (3, 'B'), (NULL, NULL)", null)) {
            assertEquivalent(
                "FROM t |> RENAME id AS renamed_id |> WHERE renamed_id > 1 |> ORDER BY renamed_id |> LIMIT 2 |> SELECT renamed_id, category",
                "SELECT renamed_id, category FROM (SELECT renamed_id, category FROM (SELECT id AS renamed_id, category FROM t) AS renamed WHERE renamed_id > 1 ORDER BY renamed_id LIMIT 2) AS limited",
                listOf("renamed_id", "category"), values,
            )
            assertEquivalent(
                "FROM t |> RENAME id AS renamed_id |> WHERE renamed_id IS NULL |> SELECT DISTINCT *",
                "SELECT DISTINCT renamed_id, category FROM (SELECT id AS renamed_id, category FROM t) AS renamed WHERE renamed_id IS NULL",
                listOf("renamed_id", "category"), values,
            )
        }
    }

    @Test
    fun qualifiedPhysicalInputsAndTableAliasesSurviveRename() {
        for (head in listOf("FROM db1.t", "FROM db1.t AS x", "SELECT x.* FROM db1.t AS x")) {
            assertEquivalent(
                "$head |> RENAME id AS renamed_id |> WHERE renamed_id > 1 |> SELECT DISTINCT renamed_id, category",
                "SELECT DISTINCT renamed_id, category FROM (SELECT id AS renamed_id, category FROM db1.t) AS renamed WHERE renamed_id > 1",
                listOf("renamed_id", "category"), qualifiedInput = true,
            )
        }
    }

    @Test
    fun quotedDorisIdentifiersPassNativeGrammarAndStrictQualification() {
        // Backtick syntax is native Doris, not portable DuckDB SQL. Do not rewrite it for execution.
        val dialect = Dialects.DORIS
        val mapping = mapOf("t" to linkedMapOf("`old name`" to "INT", "`odd``field`" to "VARCHAR"))
        val source = "FROM t |> RENAME `old name` AS `new``name` |> WHERE `new``name` > 1 |> SELECT `new``name`, `odd``field`"
        val fragment = SqlFragment(source, "doris")
        val tree = expandStarModifiers(desugarPipes(fragment.ast, dialect, copy = true), mapping, dialect)
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        for (pretty in listOf(false, true)) {
            val generator = dialect.generator(pretty = pretty, sourceDialect = "doris")
            generator.trackSpans = true
            val sql = generator.generate(tree, copy = true)
            assertEquals(emptyList(), generator.unsupportedMessages)
            assertSame(sql, assertNotNull(generator.lastSourceMap).output)
            assertTrue(verifier.verify(sql).accepted, sql)
            val bound = qualify(dialect.parseOne(sql), dialect = dialect, schema = mapping, inferSchema = false)
            assertEquals(listOf("new`name", "odd`field"), bound.selects.map { it.aliasOrName })
        }
    }

    @Test
    fun missingOrPartialCatalogsCannotProduceExecutableDorisRename() {
        for (source in listOf(
            "FROM missing |> RENAME id AS renamed_id",
            "FROM t CROSS JOIN missing |> RENAME id AS renamed_id",
            "SELECT *, 1 AS known FROM missing |> RENAME known AS renamed_known",
        )) {
            val fragment = SqlFragment(source, "doris")
            for (inputs in listOf(ShapeCatalog.EMPTY, catalog)) {
                assertFailsWith<UnsupportedError>(source) {
                    fragment.toStandardSql("doris", inputs, expandStars = true)
                }
            }
            assertFailsWith<UnsupportedError>(source) { fragment.toExecutable("doris") }
        }
    }

    @Test
    fun invalidSchemaAwareRenamesHaveShapeErrorsNotGeneratorWarnings() {
        for (source in listOf(
            "FROM t |> RENAME missing AS category",
            "WITH unrelated AS (SELECT 1 AS renamed_id) SELECT * FROM t |> RENAME missing AS renamed_id",
            "FROM t |> RENAME id AS category",
            "FROM t |> RENAME id AS renamed_id, id AS renamed_id",
            "FROM t |> RENAME id AS renamed_id, category AS renamed_id",
            "FROM t |> RENAME id AS CATEGORY",
            "FROM t |> RENAME id AS `CATEGORY`",
            "FROM t |> RENAME id AS `Mixed`, category AS `mixed`",
        )) {
            assertFailsWith<ShapeError>(source) {
                SqlFragment(source, "doris").toStandardSql("doris", catalog, expandStars = true)
            }
        }
    }
}
