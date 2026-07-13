package dev.brikk.house.sql.shape

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hand assertions for the BRIKK-NATIVE SqlFragment façade. No Python oracle exists for
 * this layer, but the type expectations marked "Python-verified" below were
 * cross-checked against sqlglot (reference/sqlglot @ v30.12.0-44-g93d16591):
 * qualify(validate_qualify_columns=False) + annotate_types under the same schema.
 */
class SqlFragmentTest {

    private val json = Json

    // ------------------------------------------------------------- output shapes

    @Test
    fun plainSelectShapeTypesFlowFromCatalog() {
        val catalog = ShapeCatalog(
            tables = mapOf("produce" to Shape.of("item" to "TEXT", "sold" to "INT")),
        )
        val fragment = SqlFragment("SELECT SUM(sold) AS total_sold, COUNT(*) AS n FROM produce")
        // Python-verified: SUM(INT) -> BIGINT, COUNT(*) -> BIGINT.
        assertEquals(
            Shape.of("total_sold" to "BIGINT", "n" to "BIGINT"),
            fragment.outputShape(catalog),
        )
    }

    @Test
    fun arithmeticShapeCrossChecked() {
        val catalog = ShapeCatalog(
            tables = mapOf("orders" to Shape.of("price" to "DECIMAL(10, 2)", "qty" to "INT")),
        )
        // Python-verified: DECIMAL(10, 2) * INT -> DECIMAL(10, 2).
        assertEquals(
            Shape.of("amount" to "DECIMAL(10, 2)"),
            SqlFragment("SELECT price * qty AS amount FROM orders").outputShape(catalog),
        )
    }

    @Test
    fun stringFunctionShapeCrossChecked() {
        val catalog = ShapeCatalog(
            tables = mapOf("people" to Shape.of("first_name" to "TEXT", "last_name" to "TEXT")),
        )
        // Python-verified: CONCAT(TEXT, ...) -> VARCHAR, LENGTH(TEXT) -> INT.
        assertEquals(
            Shape.of("full_name" to "VARCHAR", "l" to "INT"),
            SqlFragment(
                "SELECT CONCAT(first_name, ' ', last_name) AS full_name, " +
                    "LENGTH(first_name) AS l FROM people"
            ).outputShape(catalog),
        )
    }

    @Test
    fun starExpandsAgainstCatalog() {
        val catalog = ShapeCatalog(
            tables = mapOf("t" to Shape.of("a" to "INT", "b" to "TEXT")),
        )
        assertEquals(
            Shape.of("a" to "INT", "b" to "TEXT"),
            SqlFragment("SELECT * FROM t").outputShape(catalog),
        )
    }

    @Test
    fun unknownTableYieldsUnexpandedStarAndUnknownTypes() {
        // qualify runs with validateQualifyColumns=false: unknown sources don't
        // explode; the star survives as a single "*" column of UNKNOWN type.
        assertEquals(
            Shape.of("*" to "UNKNOWN"),
            SqlFragment("SELECT * FROM mystery").outputShape(),
        )
        // Unresolvable named projections degrade to UNKNOWN, not an error.
        assertEquals(
            Shape.of("a" to "UNKNOWN"),
            SqlFragment("SELECT a FROM mystery").outputShape(),
        )
    }

    @Test
    fun pipeQueryShapeWithAggregateStage() {
        // The README pipe example: AGGREGATE ... GROUP BY item desugars to
        // SELECT item, SUM(sold) AS total_sold ... GROUP BY item (group keys lead).
        val catalog = ShapeCatalog(
            tables = mapOf("produce" to Shape.of("item" to "TEXT", "sold" to "INT")),
        )
        val fragment = SqlFragment(
            "FROM produce " +
                "|> WHERE item != :varthing " +
                "|> AGGREGATE SUM(sold) AS total_sold GROUP BY item " +
                "|> ORDER BY item DESC"
        )
        assertTrue(fragment.isPipe)
        assertEquals(3, fragment.stages.size)
        // Python-verified group-key typing: item TEXT flows through the CTE chain;
        // SUM(INT) -> BIGINT as in plainSelectShapeTypesFlowFromCatalog.
        assertEquals(
            Shape.of("item" to "TEXT", "total_sold" to "BIGINT"),
            fragment.outputShape(catalog),
        )
    }

    // ---------------------------------------------------------------- table slots

    @Test
    fun slotBindingResolvesOutputShape() {
        val fragment = SqlFragment("FROM source(x) |> WHERE a > 1")
        assertEquals(listOf("source"), fragment.tableSlots)
        assertEquals(emptyList(), fragment.sourceTables)

        val catalog = ShapeCatalog(
            tables = emptyMap(),
            slots = mapOf("source" to Shape.of("a" to "INT", "b" to "TEXT")),
        )
        assertEquals(
            Shape.of("a" to "INT", "b" to "TEXT"),
            fragment.outputShape(catalog),
        )
    }

    @Test
    fun unknownSlotBindingRaises() {
        val fragment = SqlFragment("SELECT * FROM source(x)")
        val err = assertFailsWith<ShapeError> {
            fragment.outputShape(
                ShapeCatalog(tables = emptyMap(), slots = mapOf("nope" to Shape.EMPTY))
            )
        }
        assertTrue("nope" in err.message!!)
        assertTrue("source" in err.message!!)
    }

    @Test
    fun knownFunctionsAreNotSlots() {
        // UNNEST parses into a typed node and ABS is in the function registry —
        // neither is offered as a slot; the plain table is a source, not a slot.
        val fragment = SqlFragment("SELECT * FROM t JOIN mystery_tvf(1) ON TRUE")
        assertEquals(listOf("mystery_tvf"), fragment.tableSlots)
        assertEquals(listOf("t"), fragment.sourceTables)
    }

    @Test
    fun sourceTablesAreFullyQualifiedAndExcludeCtes() {
        val fragment = SqlFragment(
            "WITH cte AS (SELECT a FROM cat.db.x) SELECT * FROM cte JOIN db.y ON TRUE"
        )
        // Order is deterministic AST traversal order (the WITH clause is attached to
        // the Select after its body, so outer sources precede CTE-body sources).
        assertEquals(listOf("db.y", "cat.db.x"), fragment.sourceTables)
    }

    // -------------------------------------------------------------- scalar params

    @Test
    fun scalarParamsAllThreeStyles() {
        val fragment = SqlFragment(
            "SELECT * FROM t WHERE a > :min AND b < @max AND c IN (?, ?) AND d = :min"
        )
        // Positions follow textual occurrence: :min(0) @max(1) ?(2) ?(3) :min(4).
        assertEquals(
            listOf(
                ScalarParam(name = "min", style = ParamStyle.NAMED_COLON, count = 2, positions = listOf(0, 4)),
                ScalarParam(name = "max", style = ParamStyle.NAMED_AT, count = 1, positions = listOf(1)),
                ScalarParam(name = null, style = ParamStyle.POSITIONAL, count = 2, positions = listOf(2, 3)),
            ),
            fragment.scalarParams,
        )
    }

    // ------------------------------------------------------------------- lineage

    @Test
    fun columnDependenciesTwoHopThroughCte() {
        val fragment = SqlFragment(
            "WITH cte AS (SELECT a AS b FROM x) SELECT b AS c FROM cte"
        )
        val catalog = ShapeCatalog(tables = mapOf("x" to Shape.of("a" to "INT")))
        assertEquals(
            mapOf("c" to setOf("x")),
            fragment.columnDependencies(inputs = catalog),
        )
        // Single-column form agrees.
        assertEquals(
            mapOf("c" to setOf("x")),
            fragment.columnDependencies(column = "c", inputs = catalog),
        )
    }

    @Test
    fun columnDependenciesThroughBoundSlot() {
        val fragment = SqlFragment("FROM source(x) |> AGGREGATE SUM(a) AS total GROUP BY b")
        val catalog = ShapeCatalog(
            tables = emptyMap(),
            slots = mapOf("source" to Shape.of("a" to "INT", "b" to "TEXT")),
        )
        val deps = fragment.columnDependencies(inputs = catalog)
        assertEquals(setOf("source"), deps["total"])
        assertEquals(setOf("source"), deps["b"])
    }

    // ------------------------------------------------------------- toStandardSql

    @Test
    fun toStandardSqlExpandsStarsForEnginesWithoutStarExcept() {
        val fragment = SqlFragment("FROM t |> SELECT *")
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT", "b" to "TEXT")))
        // MySQL has no star-except syntax; the star becomes explicit columns.
        assertEquals(
            "WITH __tmp1 AS (SELECT t.a AS a, t.b AS b FROM t AS t) " +
                "SELECT __tmp1.a AS a, __tmp1.b AS b FROM __tmp1 AS __tmp1",
            fragment.toStandardSql(target = "mysql", inputs = catalog, expandStars = true),
        )
        // Without a catalog the pipes still desugar, stars untouched.
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * FROM __tmp1",
            fragment.toStandardSql(),
        )
    }

    // ------------------------------------------------------- transpileTo pipes

    @Test
    fun transpileToRendersPipeSyntaxByDefault() {
        // Default false preserves pipe rendering (pipe-aware consumers, round-trips).
        val fragment = SqlFragment("FROM t |> WHERE a > 1 |> SELECT a")
        val result = fragment.transpileTo("doris")
        assertTrue("|>" in result.sql, result.sql)
    }

    @Test
    fun transpileToDesugarsPipesOnRequest() {
        // Real engines don't speak |>: desugarPipes=true runs ast/PipeDesugar.kt on a
        // copy before generating — WITH __tmp form, no pipe operator in the output.
        val fragment = SqlFragment("FROM t |> WHERE a > 1 |> SELECT a")
        val result = fragment.transpileTo("doris", desugarPipes = true)
        assertTrue("|>" !in result.sql, result.sql)
        assertTrue("__tmp" in result.sql, result.sql)
        assertEquals(emptyList(), result.unsupportedMessages)
        // The fragment itself is untouched (copy semantics) and still pipe-shaped.
        assertTrue(fragment.isPipe)
        assertTrue("|>" in fragment.transpileTo("doris").sql)
    }

    // ------------------------------------------------------- guards and describe

    @Test
    fun multiStatementFragmentRaises() {
        val err = assertFailsWith<ShapeError> {
            SqlFragment("SELECT 1; SELECT 2").outputShape()
        }
        assertTrue("exactly one statement" in err.message!!)
    }

    @Test
    fun describeRollUpAndSerializationRoundTrip() {
        val fragment = SqlFragment(
            "FROM source(x) " +
                "|> WHERE item != :varthing " +
                "|> AGGREGATE SUM(sold) AS total_sold GROUP BY item " +
                "|> ORDER BY item DESC"
        )
        val description = fragment.describe()
        assertEquals(
            FragmentDescription(
                dialect = "",
                isPipe = true,
                stageOperators = listOf("WHERE", "AGGREGATE", "ORDERBY"),
                scalarParams = listOf(
                    ScalarParam(
                        name = "varthing",
                        style = ParamStyle.NAMED_COLON,
                        count = 1,
                        positions = listOf(0),
                    )
                ),
                tableSlots = listOf("source"),
                sourceTables = emptyList(),
            ),
            description,
        )
        assertEquals(
            description,
            json.decodeFromString<FragmentDescription>(json.encodeToString(description)),
        )
    }

    @Test
    fun contractRollsUpInputsShapeAndDependencies() {
        val fragment = SqlFragment("SELECT a AS c FROM x")
        val catalog = ShapeCatalog(tables = mapOf("x" to Shape.of("a" to "INT")))
        val contract = fragment.contract(catalog)
        assertEquals(listOf("x"), contract.inputsUsed)
        assertEquals(Shape.of("c" to "INT"), contract.output)
        assertEquals(mapOf("c" to setOf("x")), contract.dependencies)
        assertEquals(
            contract,
            json.decodeFromString<FragmentContract>(json.encodeToString(contract)),
        )
    }

    @Test
    fun plainSelectIsNotPipe() {
        val fragment = SqlFragment("SELECT a FROM t", dialect = "duckdb")
        assertEquals(false, fragment.isPipe)
        assertTrue(fragment.stages.isEmpty())
        assertEquals("duckdb", fragment.describe().dialect)
    }
}
