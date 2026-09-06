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
        // brikk-native nullability: SUM is a NULL-over-empty-group aggregate (nullable),
        // COUNT returns 0 over an empty group (not-null).
        assertEquals(
            Shape(listOf(
                ColumnShape("total_sold", "BIGINT", nullable = true),
                ColumnShape("n", "BIGINT", nullable = false),
            )),
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
    fun setOperationShapesReconcileTypesAndNullability() {
        for ((sql, type, nullable) in listOf(
            Triple("SELECT 1 AS x UNION ALL SELECT CAST(2147483648 AS BIGINT) AS y", "BIGINT", false),
            Triple("SELECT 1 AS x UNION ALL SELECT NULL AS y", "INT", true),
            Triple("SELECT NULL AS x INTERSECT SELECT 1 AS y", "INT", false),
            Triple("SELECT NULL AS x EXCEPT SELECT 1 AS y", "INT", true),
            Triple("SELECT 1 AS x EXCEPT SELECT NULL AS y", "INT", false),
            Triple("SELECT 1 AS x UNION ALL (SELECT 2 AS y UNION ALL SELECT CAST(2147483648 AS BIGINT) AS z)", "BIGINT", false),
            Triple("SELECT 1 AS x UNION ALL (SELECT 2 AS y UNION ALL SELECT NULL AS z)", "INT", true),
        )) {
            val expected = Shape(listOf(ColumnShape("x", type, nullable = nullable)))
            assertEquals(expected, SqlFragment(sql, "duckdb").outputShape(), sql)
            assertEquals(expected, SqlFragment("SELECT x FROM ($sql) AS s", "duckdb").outputShape(), "derived: $sql")
        }
    }

    @Test
    fun unionByNameMatchesNamesAndNullPadsMissingColumns() {
        for ((sql, expected) in listOf(
            "SELECT 1 AS x, 2 AS y UNION ALL BY NAME SELECT NULL AS y, CAST(2147483648 AS BIGINT) AS x" to
                listOf(ColumnShape("x", "BIGINT", nullable = false), ColumnShape("y", "INT", nullable = true)),
            "SELECT 1 AS x UNION ALL BY NAME SELECT CAST(2147483648 AS BIGINT) AS y" to
                listOf(ColumnShape("x", "INT", nullable = true), ColumnShape("y", "BIGINT", nullable = true)),
            "SELECT 1 AS x UNION ALL BY NAME SELECT 2 AS x, 3 AS y" to
                listOf(ColumnShape("x", "INT", nullable = false), ColumnShape("y", "INT", nullable = true)),
        )) {
            assertEquals(Shape(expected), SqlFragment(sql, "duckdb").outputShape(), sql)
            assertEquals(Shape(expected), SqlFragment("SELECT * FROM ($sql) AS s", "duckdb").outputShape(), "derived: $sql")
        }
    }

    @Test
    fun positionalSetOperationsKeepDuplicateAliasesAndUnknownStars() {
        assertEquals(Shape(listOf(ColumnShape("x", "INT", false), ColumnShape("x", "INT", false))),
            SqlFragment("SELECT 1 AS x, 2 AS x UNION ALL SELECT 3 AS a, 4 AS b", "duckdb").outputShape())
        assertEquals(Shape(listOf(ColumnShape("a", "INT", false), ColumnShape("b", "BIGINT", false))),
            SqlFragment("SELECT 1 AS a, 2 AS b UNION ALL (SELECT 3 AS z, 4 AS z " +
                "UNION ALL SELECT 5 AS p, CAST(2147483648 AS BIGINT) AS q)", "duckdb").outputShape())
        val sql = "SELECT CAST(NULL AS INT) AS x, 1 AS y INTERSECT SELECT CAST(NULL AS INT) AS z, 1 AS z"
        val expected = Shape(listOf(ColumnShape("x", "INT", true), ColumnShape("y", "INT", false)))
        assertEquals(expected, SqlFragment(sql, "duckdb").outputShape())
        assertEquals(expected, SqlFragment("SELECT * FROM ($sql) AS s", "duckdb").outputShape())
        assertEquals(Shape.of("x" to "UNKNOWN", "y" to "UNKNOWN"),
            SqlFragment("SELECT 1 AS x, 2 AS y UNION ALL SELECT * FROM t", "duckdb").outputShape())
    }

    @Test
    fun byNameModifiersPreserveOutputSubsetAndOrder() {
        for ((operator, expected) in listOf(
            "INNER UNION ALL BY NAME" to listOf(ColumnShape("y", "BIGINT", false)),
            "LEFT OUTER UNION ALL BY NAME" to listOf(ColumnShape("x", "INT", true), ColumnShape("y", "BIGINT", false)),
            "FULL OUTER UNION ALL BY NAME ON (z, y, x)" to
                listOf(ColumnShape("z", "INT", true), ColumnShape("y", "BIGINT", false), ColumnShape("x", "INT", true)),
        )) {
            val sql = "SELECT 1 AS x, 2 AS y $operator SELECT CAST(3 AS BIGINT) AS y, 4 AS z"
            assertEquals(Shape(expected), SqlFragment(sql, "bigquery").outputShape(), sql)
            assertEquals(Shape(expected), SqlFragment("SELECT * FROM ($sql) AS s", "bigquery").outputShape(), "derived: $sql")
        }
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
        // brikk-native nullability: item is undeclared (unknown); SUM is a nullable
        // aggregate — both survive the pipe's CTE-chain desugar.
        assertEquals(
            Shape(listOf(
                ColumnShape("item", "TEXT", nullable = null),
                ColumnShape("total_sold", "BIGINT", nullable = true),
            )),
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

    @Test
    fun sourceTablesResolveCtesWithinTheirActualScope() {
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT"), "u" to Shape.of("a" to "INT")))
        val nested = SqlFragment("SELECT a FROM t UNION ALL SELECT a FROM (WITH t AS (SELECT a FROM u) SELECT a FROM t) AS s", "postgres")
        assertEquals(listOf("t", "u"), nested.sourceTables)
        val contract = nested.contract(catalog)
        assertEquals(listOf("t", "u"), contract.inputsUsed)
        assertEquals(mapOf("a" to setOf("t", "u")), contract.dependencies)
        assertEquals(listOf("t"), SqlFragment("WITH t AS (SELECT a FROM t) SELECT a FROM t", "postgres").sourceTables)
        assertEquals(listOf("u"), SqlFragment("WITH T AS (SELECT a FROM U) SELECT a FROM t", "postgres").sourceTables)
        assertEquals(listOf("t", "u"), SqlFragment("WITH \"T\" AS (SELECT a FROM u) SELECT a FROM t", "postgres").sourceTables)
        assertEquals(listOf("db.t", "u"), SqlFragment("WITH t AS (SELECT a FROM u) SELECT a FROM db.t", "postgres").sourceTables)
    }

    @Test
    fun physicalInputsIncludeFilterSourcesButNotSlotsOrPlaceholders() {
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT"), "u" to Shape.of("a" to "INT")),
            slots = mapOf("src" to Shape.of("a" to "INT")))
        val fragment = SqlFragment("SELECT a FROM src() UNION ALL SELECT a FROM t", "postgres")
        assertEquals(listOf("t"), fragment.sourceTables)
        assertEquals(listOf("src"), fragment.tableSlots)
        val contract = fragment.contract(catalog)
        assertEquals(listOf("t", "src"), contract.inputsUsed)
        assertTrue(contract.dependencies.values.flatten().all { it in contract.inputsUsed })
        val filtered = SqlFragment("SELECT t.a FROM t WHERE EXISTS(SELECT 1 FROM u WHERE u.a = t.a)", "postgres")
        assertEquals(listOf("t", "u"), filtered.sourceTables)
        assertEquals(mapOf("a" to setOf("t")), filtered.columnDependencies(inputs = catalog.copy(slots = emptyMap())))
        assertEquals(listOf("t", "u"), SqlFragment("SELECT t.a FROM t SEMI JOIN u ON t.a = u.a", "spark").sourceTables)
        assertEquals(emptyList(), SqlFragment("SELECT missing", "postgres").sourceTables)
        assertEquals(listOf("t"), SqlFragment("UPDATE t SET a = 1", "postgres").sourceTables)
    }

    @Test
    fun derivedJoinsAndExtendedNamesKeepTheirPhysicalInputs() {
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("a" to "INT"), "u" to Shape.of("b" to "INT")))
        val joined = SqlFragment("SELECT s.a, s.b FROM (t JOIN u ON t.a = u.b) AS s", "postgres").contract(catalog)
        assertEquals(listOf("t", "u"), joined.inputsUsed)
        assertTrue(joined.dependencies.values.flatten().all { it in joined.inputsUsed })
        val extended = SqlFragment("SELECT t.a FROM lake.ns1.ns2.t AS t", "spark")
        assertEquals(listOf("lake.ns1.ns2.t"), extended.sourceTables)
        assertEquals(mapOf("a" to setOf("lake.ns1.ns2.t")), extended.columnDependencies())
    }

    @Test
    fun recursiveAndPivotedCtesAreNotPhysicalTables() {
        for (body in listOf(
            "SELECT a FROM t UNION ALL SELECT a + 1 FROM r WHERE a < 3",
            "(SELECT a FROM t UNION ALL SELECT a + 1 FROM r WHERE a < 3)",
        )) {
            assertEquals(listOf("t"), SqlFragment("WITH RECURSIVE r(a) AS ($body) SELECT a FROM r", "postgres").sourceTables)
        }
        assertEquals(listOf("t"), SqlFragment("WITH RECURSIVE x AS (SELECT a FROM y), y AS (SELECT a FROM t) SELECT a FROM x", "postgres").sourceTables)
        assertEquals(listOf("y", "t"), SqlFragment("WITH x AS (SELECT a FROM y), y AS (SELECT a FROM t) SELECT a FROM x", "postgres").sourceTables)
        val pivot = SqlFragment("WITH c AS (SELECT a, b FROM t) SELECT c.one FROM c PIVOT (SUM(a) FOR b IN (1 AS one)) AS c", "duckdb")
        assertEquals(listOf("t"), pivot.sourceTables)
        assertEquals(mapOf("one" to setOf("t")), pivot.columnDependencies())
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
