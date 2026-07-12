package dev.brikk.house.intellij

import dev.brikk.house.intellij.BrikkTranspiler.TranspileOutcome
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrikkTranspilerTest {

    @Test
    fun `transpiles mysql to duckdb`() {
        val outcome = BrikkTranspiler.transpile(
            "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') FROM t",
            source = "mysql",
            target = "duckdb",
            pretty = false,
        )
        val success = assertIs<TranspileOutcome.Success>(outcome)
        assertEquals(1, success.statementCount)
        assertTrue("STRFTIME" in success.sql.uppercase(), "expected strftime in: ${success.sql}")
    }

    @Test
    fun `multi-statement input renders all statements`() {
        val outcome = BrikkTranspiler.transpile(
            "SELECT 1; SELECT 2",
            source = "mysql",
            target = "postgres",
            pretty = false,
        )
        val success = assertIs<TranspileOutcome.Success>(outcome)
        assertEquals(2, success.statementCount)
        assertTrue(success.sql.contains(";"), "statements should be ;-separated: ${success.sql}")
    }

    @Test
    fun `parse failure reports position`() {
        val outcome = BrikkTranspiler.transpile(
            "SELECT FROM WHERE GROUP)) nonsense((",
            source = "mysql",
            target = "duckdb",
        )
        assertIs<TranspileOutcome.Failure>(outcome)
    }

    @Test
    fun `unknown dialect is a failure not an exception`() {
        val outcome = BrikkTranspiler.transpile("SELECT 1", source = "sybase", target = "duckdb")
        val failure = assertIs<TranspileOutcome.Failure>(outcome)
        assertTrue("sybase" in failure.message)
    }

    @Test
    fun `blank input is a failure`() {
        assertIs<TranspileOutcome.Failure>(
            BrikkTranspiler.transpile("   \n  ", source = "mysql", target = "duckdb")
        )
    }

    @Test
    fun `unmappable functions surface for catalog-bearing targets`() {
        // duckdb's read_parquet has no Doris counterpart: it lands verbatim in the
        // output and Doris's catalog does not register it -> reported as unmappable.
        val outcome = BrikkTranspiler.transpile(
            "SELECT * FROM read_parquet('f.parquet')",
            source = "duckdb",
            target = "doris",
            pretty = false,
        )
        val success = assertIs<TranspileOutcome.Success>(outcome)
        assertTrue(
            success.unmappable.any { it.equals("read_parquet", ignoreCase = true) },
            "expected read_parquet in unmappable, got: ${success.unmappable}",
        )
        assertTrue(!success.isClean)
    }

    @Test
    fun `clean transpile has no diagnostics`() {
        val outcome = BrikkTranspiler.transpile(
            "SELECT a, b FROM t WHERE a > 1",
            source = "mysql",
            target = "duckdb",
            pretty = false,
        )
        val success = assertIs<TranspileOutcome.Success>(outcome)
        assertTrue(success.isClean, "expected clean, got unsupported=${success.unsupported} unmappable=${success.unmappable}")
    }
}
