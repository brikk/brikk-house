package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the brikk-native `datafusion` dialect wiring.
 *
 * NO sqlglot oracle exists for DataFusion, so — unlike the other DialectTests — these
 * are NOT diffed against a Python reference. Each is derived from the DataFusion SQL
 * documentation (https://datafusion.apache.org/user-guide/sql/) and the polyglot
 * design reference (reference/polyglot .../dialects/datafusion.rs), and asserts a
 * behavior the fixture/SLT gates exercise in bulk. Provenance is `// brikk:` throughout.
 */
class DatafusionDialectTest {

    private fun roundTrip(sqlText: String): String =
        parseOne(sqlText, "datafusion").sql("datafusion")

    @Test
    fun doubleColonCastRendersAsCast() {
        // brikk: DataFusion parses `::` (GenericDialect) — brikk's AST has no `::` node,
        // so it canonicalizes to CAST(...). Both are legal DataFusion.
        assertEquals("SELECT CAST(x AS INT) FROM t", roundTrip("SELECT x::INT FROM t"))
    }

    @Test
    fun tryCastRoundTrips() {
        // brikk: try_supported=true (polyglot) — TRY_CAST is preserved.
        assertEquals("SELECT TRY_CAST(x AS BIGINT) FROM t", roundTrip("SELECT TRY_CAST(x AS BIGINT) FROM t"))
    }

    @Test
    fun qualifyPassesThrough() {
        // brikk: DataFusion supports QUALIFY natively — it is preserved, not eliminated.
        assertEquals(
            "SELECT * FROM t QUALIFY row_number() OVER (ORDER BY x) = 1",
            roundTrip("SELECT * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY x) = 1"),
        )
    }

    @Test
    fun starExceptRoundTrips() {
        // brikk: star_except="EXCEPT" (polyglot) — SELECT * EXCEPT (col) is preserved.
        assertEquals("SELECT * EXCEPT (col1) FROM t", roundTrip("SELECT * EXCEPT (col1) FROM t"))
    }

    @Test
    fun intervalAllowsPluralForm() {
        // brikk: interval_allows_plural_form=true (polyglot) — DAYS is kept plural.
        assertEquals("SELECT INTERVAL '7' DAYS", roundTrip("SELECT INTERVAL '7' DAYS"))
    }

    @Test
    fun functionNamesRenderLowercase() {
        // brikk: normalize_functions=Lower (polyglot) — a DataFusion headline trait.
        assertEquals("SELECT count(x) FROM t", roundTrip("SELECT COUNT(x) FROM t"))
    }

    @Test
    fun squareTranspilesToPower() {
        // brikk: transform rename SQUARE(x) -> power(x, 2) (polyglot transform_function).
        assertEquals(
            "SELECT power(x, 2) FROM t",
            transpile("SELECT SQUARE(x) FROM t", read = "", write = "datafusion"),
        )
    }

    @Test
    fun leftSemiJoinKeepsSide() {
        // brikk: semi_anti_join_with_side=true (polyglot) — LEFT SEMI JOIN is preserved.
        assertEquals(
            "SELECT * FROM a LEFT SEMI JOIN b ON a.id = b.id",
            roundTrip("SELECT * FROM a LEFT SEMI JOIN b ON a.id = b.id"),
        )
    }
}
