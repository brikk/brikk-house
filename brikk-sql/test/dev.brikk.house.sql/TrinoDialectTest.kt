package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.AtLocal
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.MatchPredicate
import dev.brikk.house.sql.ast.UniquePredicate
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the Trino grammar-legality fixes (brikk extension #8,
 * docs/brikk-extensions.md) — deliberate divergences from the Python oracle where
 * sqlglot emits SQL that Trino's own grammar (reference/trino .../SqlBase.g4) rejects.
 * Engine-side acceptance of every rendering below is pinned in
 * SqlVerifierTest.trinoAcceptsBrikkGrammarLegalityRenderings (brikk-sql-verify).
 */
class TrinoDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "trino").sql("trino")

    // -- JSON_QUERY wrapper behavior ---------------------------------------------------
    // jsonQueryWrapperBehavior : WITHOUT ARRAY? | WITH (CONDITIONAL | UNCONDITIONAL)? ARRAY?
    // The CONDITIONAL/UNCONDITIONAL modifier is only legal after WITH; under WITHOUT no
    // wrapping happens at all, so dropping the vacuous modifier preserves semantics.

    @Test
    fun jsonQueryWithoutConditionalWrapperDropsTheIllegalModifier() {
        assertEquals(
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT WRAPPER)",
            roundTrip("JSON_QUERY(content, 'strict $.HY.*' WITHOUT CONDITIONAL WRAPPER)"),
        )
        assertEquals(
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT WRAPPER)",
            roundTrip("JSON_QUERY(content, 'strict $.HY.*' WITHOUT UNCONDITIONAL WRAPPER)"),
        )
    }

    @Test
    fun grammarLegalJsonQueryWrapperFormsAreUntouched() {
        for (legal in listOf(
            "JSON_QUERY(content, 'strict $.HY.*')",
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT ARRAY WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH ARRAY WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH CONDITIONAL WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH UNCONDITIONAL WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH UNCONDITIONAL ARRAY WRAPPER)",
        )) {
            assertEquals(legal, roundTrip(legal))
        }
    }

    @Test
    fun jsonQueryWrappedOptionTableTypoIsRepaired() {
        // sqlglot's JSON_QUERY_OPTIONS contains a ("CONDITIONAL", "ARRAY", "WRAPPED") typo;
        // the parsed Var is re-emitted verbatim upstream. WRAPPER is the only keyword in
        // Trino's grammar.
        assertEquals(
            "JSON_QUERY(content, 'strict $.HY.*' WITH CONDITIONAL ARRAY WRAPPER)",
            roundTrip("JSON_QUERY(content, 'strict $.HY.*' WITH CONDITIONAL ARRAY WRAPPED)"),
        )
    }

    // -- ALTER TABLE ... SET PROPERTIES ------------------------------------------------
    // property : identifier EQ propertyValue — keys must be identifiers, so a
    // string-literal key is normalized to a quoted identifier; sqlglot leaves the whole
    // statement as a raw Command and re-emits the illegal string literal.

    @Test
    fun setPropertiesStringLiteralKeyBecomesQuotedIdentifier() {
        assertEquals(
            "ALTER TABLE people SET PROPERTIES foo = 123, \"foo bar\" = 456",
            roundTrip("ALTER TABLE people SET PROPERTIES foo = 123, 'foo bar' = 456"),
        )
    }

    @Test
    fun setPropertiesIdentifierKeysRoundTrip() {
        assertEquals(
            "ALTER TABLE people SET PROPERTIES x = 'y'",
            roundTrip("ALTER TABLE people SET PROPERTIES x = 'y'"),
        )
        assertEquals(
            "ALTER TABLE people SET PROPERTIES x = DEFAULT",
            roundTrip("ALTER TABLE people SET PROPERTIES x = DEFAULT"),
        )
    }

    @Test
    fun trino483PredicatesAndAtLocalRoundTrip() {
        for (sql in listOf(
            "SELECT ts AT LOCAL",
            "SELECT ROW(1) MATCH SIMPLE (SELECT 1)",
            "SELECT ROW(1) MATCH UNIQUE FULL (SELECT 1)",
            "SELECT UNIQUE (SELECT 1)",
            "SELECT CASE ROW(1) WHEN MATCH PARTIAL (SELECT 1) THEN TRUE ELSE FALSE END",
        )) {
            assertEquals(sql, roundTrip(sql))
        }
    }

    @Test
    fun matchRemainsUsableAsANonReservedName() {
        assertEquals("SELECT match FROM t", roundTrip("SELECT match FROM t"))
    }

    @Test
    fun trino483NativeNodesRoundTripThroughSerde() {
        for (sql in listOf(
            "SELECT ts AT LOCAL",
            "SELECT ROW(1) MATCH UNIQUE FULL (SELECT 1)",
            "SELECT UNIQUE (SELECT 1)",
        )) {
            val parsed = parseOne(sql, "trino")
            assertEquals(sql, Serde.loadExpression(Serde.dump(parsed)).sql("trino"))
        }
    }

    @Test
    fun trino483NativeNodesHaveTrinoTypes() {
        val parsed = annotateTypes(
            parseOne(
                "SELECT ts AT LOCAL, ROW(1) MATCH SIMPLE (SELECT 1), UNIQUE (SELECT 1)",
                "trino",
            ),
            dialect = "trino",
        )
        assertEquals(DType.TIMESTAMP, (parsed.find(AtLocal::class)!!.type as DataType).thisArg)
        assertEquals(DType.BOOLEAN, (parsed.find(MatchPredicate::class)!!.type as DataType).thisArg)
        assertEquals(DType.BOOLEAN, (parsed.find(UniquePredicate::class)!!.type as DataType).thisArg)
    }

    @Test
    fun rowLiteralFieldAliasesRoundTrip() {
        assertEquals(
            "SELECT ROW(1 AS a, 2 AS b, 3)",
            roundTrip("SELECT ROW(1 AS a, 2 AS b, 3)"),
        )
    }

    @Test
    fun dmlTargetBranchesRoundTrip() {
        for (sql in listOf(
            "INSERT INTO orders@dev SELECT 1",
            "DELETE FROM orders@dev WHERE id = 1",
            "UPDATE orders@dev SET value = 1",
            "MERGE INTO target@dev USING source ON target.id = source.id WHEN MATCHED THEN DELETE",
        )) {
            val parsed = parseOne(sql, "trino")
            assertEquals("dev", (parsed.findAll(Table::class).first() as Table).text("branch"))
            assertEquals(sql, parsed.sql("trino"))
        }
    }

    @Test
    fun columnDefaultsAndNewTypesRoundTrip() {
        for (sql in listOf(
            "CREATE TABLE metrics (value INTEGER DEFAULT 0)",
            "ALTER TABLE metrics ADD COLUMN value INTEGER DEFAULT 0",
            "ALTER TABLE metrics ALTER COLUMN value SET DEFAULT 1",
            "ALTER TABLE metrics ALTER COLUMN value DROP DEFAULT",
            "SELECT CAST(value AS NUMBER)",
            "SELECT CAST(value AS VARIANT)",
        )) {
            assertEquals(sql, roundTrip(sql))
        }
    }
}
