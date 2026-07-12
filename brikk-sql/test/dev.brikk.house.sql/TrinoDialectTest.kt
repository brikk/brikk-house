package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
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
}
