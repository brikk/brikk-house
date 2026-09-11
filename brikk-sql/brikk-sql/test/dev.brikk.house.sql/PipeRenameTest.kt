package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.PipeRename
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.SourceMap
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.expandStarModifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipeRenameTest {
    private val shape = Shape.of("id" to "INT", "category" to "VARCHAR")
    private val catalog = ShapeCatalog(tables = mapOf("t" to shape))
    private val schema = mapOf("t" to shape.toSchemaMapping())

    @Test
    fun renameKeepsOrderUntouchedFieldsAndSimultaneousSwaps() {
        for ((rename, names) in listOf(
            "id AS renamed_id" to listOf("renamed_id", "category"),
            "id AS category, category AS id" to listOf("category", "id"),
            "category AS id, id AS category" to listOf("category", "id"),
            "id AS id" to listOf("id", "category"),
        )) {
            val fragment = SqlFragment("FROM t |> RENAME $rename", "doris")
            val sql = fragment.toStandardSql("doris", catalog, expandStars = true)
            assertFalse("RENAME" in sql, sql)
            assertEquals(names, fragment.outputShape(catalog).names(), sql)
        }
    }

    @Test
    fun unknownSourceCannotBeMaskedByAnExistingTargetOrUnrelatedAlias() {
        for (source in listOf(
            "FROM t |> RENAME missing AS category",
            "WITH unrelated AS (SELECT 1 AS renamed_id) SELECT * FROM t |> RENAME missing AS renamed_id",
            "WITH unrelated AS (SELECT * FROM t |> RENAME id AS renamed_id) SELECT * FROM t |> RENAME missing AS renamed_id",
        )) {
            val error = assertFailsWith<ShapeError>(source) {
                SqlFragment(source, "doris").toStandardSql("doris", catalog, expandStars = true)
            }
            assertTrue("missing" in assertNotNull(error.message))
        }
    }

    @Test
    fun duplicatesAndFinalOutputCollisionsAreShapeErrors() {
        for (rename in listOf(
            "id AS renamed_id, id AS renamed_id",
            "id AS renamed_id, id AS other_id",
            "id AS same_name, category AS same_name",
            "id AS category",
            "id AS category, category AS category",
            "id AS CATEGORY",
            "id AS `CATEGORY`",
            "id AS x, category AS X",
            "id AS `x`, category AS `X`",
            "id AS x, ID AS y",
        )) {
            assertFailsWith<ShapeError>(rename) {
                SqlFragment("FROM t |> RENAME $rename", "doris")
                    .toStandardSql("doris", catalog, expandStars = true)
            }
        }
        for (rename in listOf("id AS x, id AS x", "id AS x, category AS x")) {
            assertFailsWith<ShapeError>(rename) {
                SqlFragment("FROM t |> RENAME $rename", "duckdb")
                    .toStandardSql("duckdb", ShapeCatalog.EMPTY, expandStars = true)
            }
        }
    }

    @Test
    fun eachStarValidatesOnlyItsOwnSourcesAndDoesNotLeakRenames() {
        val dialect = Dialects.DORIS
        val twoTables = schema + ("u" to mapOf("renamed_id" to "INT"))
        val invalid = dialect.parseOne("SELECT t.* RENAME (missing AS renamed_id), u.* FROM t CROSS JOIN u")
        assertFailsWith<ShapeError> { expandStarModifiers(invalid, twoTables, dialect) }
        val valid = expandStarModifiers(
            dialect.parseOne("SELECT * RENAME (id AS renamed_id), * FROM t"), schema, dialect,
        )
        assertEquals(listOf("renamed_id", "category", "id", "category"), valid.selects.map { it.aliasOrName })
        val replaced = expandStarModifiers(
            dialect.parseOne("SELECT * REPLACE (1 AS id) RENAME (id AS renamed_id), * FROM t"), schema, dialect,
        )
        assertEquals(listOf("renamed_id", "category", "id", "category"), replaced.selects.map { it.aliasOrName })
        val qualified = expandStarModifiers(
            dialect.parseOne("SELECT t.* RENAME (id AS renamed_id) FROM t CROSS JOIN u"), twoTables, dialect,
        )
        assertEquals(listOf("renamed_id", "category"), qualified.selects.map { it.aliasOrName })
    }

    @Test
    fun ambiguousJoinedAndDerivedSourceNamesAreRefused() {
        for (source in listOf(
            "SELECT * RENAME (id AS renamed_id) FROM t CROSS JOIN t AS u",
            "SELECT * RENAME (id AS renamed_id) FROM (SELECT id, id FROM t) AS u",
            "SELECT * RENAME (category AS renamed_category) FROM (SELECT id, id, category FROM t) AS u",
        )) {
            assertFailsWith<ShapeError>(source) {
                expandStarModifiers(Dialects.DORIS.parseOne(source), schema, Dialects.DORIS)
            }
        }
    }

    @Test
    fun qualifiedPhysicalTablesAndAliasesAreSupported() {
        val qualified = ShapeCatalog(tables = mapOf("db1.t" to shape))
        for (source in listOf(
            "FROM db1.t |> RENAME id AS renamed_id",
            "FROM db1.t AS x |> RENAME id AS renamed_id",
            "SELECT x.* FROM db1.t AS x |> RENAME id AS renamed_id",
        )) {
            val fragment = SqlFragment(source, "doris")
            val sql = fragment.toStandardSql("doris", qualified, expandStars = true)
            assertFalse("RENAME" in sql, sql)
            assertEquals(listOf("renamed_id", "category"), fragment.outputShape(qualified).names())
        }
    }

    @Test
    fun rawQualifiedRenameSourceIsAnExplicitUnsupportedError() {
        val tree = Dialects.DORIS.parseOne("SELECT * RENAME (t.id AS renamed_id) FROM t")
        assertFailsWith<UnsupportedError> { expandStarModifiers(tree, schema, Dialects.DORIS) }
    }

    @Test
    fun dorisColumnBindingsIgnoreCaseWithoutLosingQuotedTargetSpelling() {
        val fragment = SqlFragment("FROM t |> RENAME `ID` AS `Mixed`", "doris")
        val sql = fragment.toStandardSql("doris", catalog, expandStars = true)
        assertTrue("AS `Mixed`" in sql, sql)
        assertEquals(listOf("Mixed", "category"), fragment.outputShape(catalog).names())
        assertEquals(listOf("Mixed", "category"), SqlFragment(sql, "doris").outputShape(catalog).names())
        val mixed = ShapeCatalog(tables = mapOf("t" to Shape.of("id" to "INT", "ID" to "INT")))
        assertFailsWith<ShapeError> {
            SqlFragment("FROM t |> RENAME id AS `Mixed`, `ID` AS `mixed`", "doris")
                .toStandardSql("doris", mixed, expandStars = true)
        }
    }

    @Test
    fun duckdbNormalizesEvenQuotedRenameSourcesAndTargets() {
        val fragment = SqlFragment("FROM t |> RENAME \"ID\" AS \"Renamed_ID\"", "duckdb")
        assertEquals(listOf("renamed_id", "category"), fragment.outputShape(catalog).names())
        assertFalse("RENAME" in fragment.toStandardSql("duckdb", catalog, expandStars = true))
        for (rename in listOf("id AS \"CATEGORY\"", "id AS x, \"ID\" AS y", "id AS x, category AS \"X\"")) {
            assertFailsWith<ShapeError>(rename) {
                SqlFragment("FROM t |> RENAME $rename", "duckdb")
                    .toStandardSql("duckdb", catalog, expandStars = true)
            }
        }
    }

    @Test
    fun dorisTargetsRefuseCaseDistinctIntermediateSlotsFromOtherDialects() {
        val source = "FROM (SELECT 1 AS \"id\", 2 AS \"ID\") AS q |> RENAME \"id\" AS first_id, \"ID\" AS second_id"
        assertFailsWith<UnsupportedError> {
            SqlFragment(source, "postgres").toStandardSql("doris", ShapeCatalog.EMPTY, expandStars = true)
        }
        val supported = SqlFragment("FROM t |> RENAME id AS \"Renamed_ID\"", "postgres")
            .toStandardSql("doris", catalog, expandStars = true)
        assertTrue("Renamed_ID" in supported, supported)
        assertFalse("RENAME" in supported, supported)
    }

    @Test
    fun renameRespectsCatalogQuotednessWithoutBypassingDialectCollisionChecks() {
        val mixed = Shape(listOf(ColumnShape("id", "INT"), ColumnShape("ID", "TEXT", quoted = true)))
        for ((source, inputs) in listOf(
            "t" to ShapeCatalog(tables = mapOf("t" to mixed)),
            "source()" to ShapeCatalog(tables = emptyMap(), slots = mapOf("source" to mixed)),
        )) {
            val fragment = SqlFragment("FROM $source |> RENAME \"ID\" AS renamed_id", "postgres")
            val sql = fragment.toStandardSql("postgres", inputs, expandStars = true)
            assertFalse("RENAME" in sql, sql)
            val output = fragment.outputShape(inputs)
            assertEquals(listOf("id", "renamed_id"), output.names())
            assertEquals(listOf("INT", "TEXT"), output.columns.map { it.type })
            val resolvedInputs = ShapeCatalog(tables = inputs.tables + inputs.slots)
            assertEquals(output, SqlFragment(sql, "postgres").outputShape(resolvedInputs))
            for (dialect in listOf("doris", "duckdb")) {
                assertFailsWith<ShapeError>(dialect) {
                    SqlFragment("FROM $source |> RENAME ID AS renamed_id", dialect)
                        .toStandardSql(dialect, inputs, expandStars = true)
                }
            }
        }
    }

    @Test
    fun quotedOutputNamesSurviveReuseAsRenameInputs() {
        val mixed = Shape(listOf(ColumnShape("id", "INT"), ColumnShape("ID", "TEXT", quoted = true)))
        val inputs = ShapeCatalog(tables = mapOf("t" to mixed))
        for (source in listOf(
            "FROM t |> RENAME id AS renamed_id",
            "SELECT id AS renamed_id, \"ID\" FROM t",
            "SELECT id AS renamed_id, \"ID\" FROM t UNION ALL SELECT id, \"ID\" FROM t",
        )) {
            val output = SqlFragment(source, "postgres").outputShape(inputs)
            assertEquals(listOf(false, true), output.columns.map { it.quoted }, source)
            val slots = ShapeCatalog(tables = emptyMap(), slots = mapOf("source" to output))
            val next = SqlFragment("FROM source() |> RENAME \"ID\" AS final_id", "postgres")
            val final = next.outputShape(slots)
            assertEquals(listOf("renamed_id", "final_id"), final.names(), source)
            assertEquals(listOf("INT", "TEXT"), final.columns.map { it.type }, source)
            assertFalse("RENAME" in next.toStandardSql("postgres", slots, expandStars = true), source)
        }
    }

    @Test
    fun sourceNamesWithSpacesAndBackticksStayQuotedThroughExpansion() {
        val quoted = ShapeCatalog(tables = mapOf("t" to Shape.of("old name" to "INT", "odd`field" to "VARCHAR")))
        val fragment = SqlFragment("FROM t |> RENAME `old name` AS `new``name`", "doris")
        val sql = fragment.toStandardSql("doris", quoted, expandStars = true)
        assertTrue(".`old name` AS `new``name`" in sql, sql)
        assertTrue(".`odd``field`" in sql, sql)
        assertEquals(listOf("new`name", "odd`field"), fragment.outputShape(quoted).names())
        val renamedBacktick = SqlFragment("FROM t |> RENAME `odd``field` AS renamed_field", "doris")
            .toStandardSql("doris", quoted, expandStars = true)
        assertTrue(".`odd``field` AS renamed_field" in renamedBacktick, renamedBacktick)
    }

    @Test
    fun schemaDuplicatesAreRejectedBeforeMapConversionForTablesAndSlots() {
        for (dialect in listOf("doris", "duckdb")) {
            val duplicate = Shape(listOf(ColumnShape("id", "INT"), ColumnShape("id", "VARCHAR")))
            assertFailsWith<ShapeError> {
                SqlFragment("FROM t |> RENAME id AS renamed_id", dialect).toStandardSql(
                    dialect, ShapeCatalog(tables = mapOf("t" to duplicate)), expandStars = true,
                )
            }
            assertFailsWith<ShapeError> {
                SqlFragment("FROM source() |> RENAME id AS renamed_id", dialect).toStandardSql(
                    dialect, ShapeCatalog(tables = emptyMap(), slots = mapOf("source" to duplicate)), expandStars = true,
                )
            }
        }
        for ((first, second) in listOf("id" to "ID", "odd name" to "ODD NAME")) {
            assertFailsWith<ShapeError> {
                SqlFragment("FROM t |> RENAME id AS renamed_id", "duckdb").toStandardSql(
                    "duckdb", ShapeCatalog(tables = mapOf("t" to Shape.of(first to "INT", second to "INT"))), true,
                )
            }
        }
    }

    @Test
    fun unrelatedDuplicateSchemaDoesNotBreakQueriesWithoutRename() {
        val duplicate = Shape(listOf(ColumnShape("id", "INT"), ColumnShape("ID", "VARCHAR")))
        val inputs = ShapeCatalog(tables = mapOf("t" to shape, "unused" to duplicate))
        assertEquals(listOf("id"), SqlFragment("SELECT id FROM t", "doris").outputShape(inputs).names())
        assertEquals(
            setOf("t"),
            SqlFragment("SELECT id FROM t", "doris").columnDependencies(inputs = inputs)["id"],
        )
    }

    @Test
    fun unresolvedAndPartialStarsRemainAvailableForNativeRenameDialects() {
        for (source in listOf(
            "FROM missing |> RENAME id AS renamed_id",
            "FROM t CROSS JOIN missing |> RENAME id AS renamed_id",
            "SELECT *, 1 AS known FROM missing |> RENAME known AS renamed_known",
        )) {
            val fragment = SqlFragment(source, "duckdb")
            val tree = expandStarModifiers(desugarPipes(fragment.ast, Dialects.DUCKDB, copy = true), schema, Dialects.DUCKDB)
            assertTrue(tree.findAll<Star>().any { !(it.args["rename"] as? List<*>).isNullOrEmpty() }, source)
        }
        val native = qualify(Dialects.DUCKDB.parseOne("SELECT * RENAME (id AS renamed_id) FROM missing"), dialect = Dialects.DUCKDB)
        assertTrue("RENAME" in Dialects.DUCKDB.generate(native))
    }

    @Test
    fun setUnknownColumnGuardStillRuns() {
        assertFailsWith<ShapeError> {
            SqlFragment("FROM t |> SET missing = 1", "doris")
                .toStandardSql("doris", catalog, expandStars = true)
        }
    }

    @Test
    fun trackedGenerationPreservesEveryRenameTokenIncludingRepeatedNames() {
        val source = "FROM t\n|> RENAME id AS `renamed id`\n|> RENAME `renamed id` AS id\n|> WHERE id > 1"
        val fragment = SqlFragment(source, "doris")
        val original = fragment.ast.copy()
        val tokens = fragment.ast.findAll<PipeRename>().flatMap { stage ->
            stage.expressionsArg.map { assertIs<Alias>(it) }.flatMap { alias ->
                listOf(assertIs<Identifier>(assertIs<Column>(alias.thisArg).thisArg), assertIs<Identifier>(alias.args["alias"]))
            }
        }.toList()
        val positions = tokens.map { assertNotNull(SourceMap.sourcePosOf(it)) }
        assertEquals(4, positions.toSet().size)
        val tree = expandStarModifiers(desugarPipes(fragment.ast, Dialects.DORIS, copy = true), schema, Dialects.DORIS)
        val prepared = tree.copy()
        for (pretty in listOf(false, true)) {
            val generator = Dialects.DORIS.generator(pretty = pretty, sourceDialect = "doris")
            generator.trackSpans = true
            val sql = generator.generate(tree, copy = true)
            assertEquals(emptyList(), generator.unsupportedMessages)
            val map = assertNotNull(generator.lastSourceMap)
            assertSame(sql, map.output)
            for (position in positions) {
                val entries = map.entries.filter { it.node is Identifier && SourceMap.sourcePosOf(it.node) == position }
                assertTrue(entries.isNotEmpty(), "Missing rename token ${source.substring(position.start, position.end + 1)} in $sql")
                for (entry in entries) {
                    assertEquals(source.substring(position.start, position.end + 1), sql.substring(entry.start, entry.end))
                    assertEquals(position, map.sourcePosition(entry.start, exact = true))
                }
            }
            assertEquals(prepared, tree)
            assertEquals(original, fragment.ast)
        }
    }
}
