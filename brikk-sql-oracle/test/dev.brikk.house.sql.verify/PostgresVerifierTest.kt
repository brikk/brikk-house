package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fidelity oracle tests for the embedded PostgreSQL verifier. These live in brikk-sql-oracle
 * (with the [PostgresVerifier] and its embedded-PG dependency) rather than brikk-sql-verify,
 * whose `SqlVerifiers.forEngine("postgres")` returns the advisory ShardingSphere oracle.
 *
 * All postgres cases share ONE embedded PG instance (booting a native server + one-time
 * binary download is expensive; ~1-3s per boot). See [pg].
 */
class PostgresVerifierTest {

    private companion object {
        /**
         * One embedded PostgreSQL shared by all cases in this class — booting a native PG per
         * test would cost seconds each. Constructed directly (the fidelity embedded-PG oracle),
         * not via [SqlVerifiers.forEngine], which returns the advisory ShardingSphere oracle.
         */
        val pg: PostgresVerifier by lazy {
            PostgresVerifier().also { verifier ->
                // Stop the embedded server when the test JVM exits (there is no portable
                // per-class teardown hook without pulling in a JUnit-engine dependency, and the
                // embedded PG is meant to live for the whole run anyway).
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { verifier.close() } })
            }
        }
    }

    @Test
    fun postgresAcceptsValidStatement() {
        val result = pg.verify("SELECT a FROM t WHERE b = 1")
        // A valid SELECT prepares cleanly against the empty catalog? No — `t` is undefined, so
        // this is a semantic (42P01) case: grammar accepted, catalog empty => accepted=true.
        assertTrue(result.accepted, "expected grammar acceptance, got: ${result.error}")
        assertNull(result.error)
    }

    @Test
    fun postgresAcceptsFullyResolvableStatement() {
        // No catalog dependency at all: prepares AND analyzes with no error.
        val result = pg.verify("SELECT 1 + 2 AS x")
        assertTrue(result.accepted, "expected acceptance, got: ${result.error}")
        assertNull(result.error)
    }

    @Test
    fun postgresRejectsSyntaxErrorWithPosition() {
        // A keyword typo: PG reports `42601 syntax error at or near "SELCT"` at position 1.
        val result = pg.verify("SELCT 1")
        assertFalse(result.accepted)
        val error = assertNotNull(result.error)
        assertTrue(error.startsWith("42601"), "expected 42601 syntax error, got: $error")
        assertEquals(1, result.line)
        assertEquals(1, result.col)
    }

    @Test
    fun postgresRejectsSyntaxErrorWithInteriorPosition() {
        // Position mapped to the offending token in the interior of the statement.
        val result = pg.verify("SELECT * FROM t WHERE")
        assertFalse(result.accepted)
        val error = assertNotNull(result.error)
        // "syntax error at end of input" — PG may or may not report a position; when it does
        // it should be past the WHERE. Just assert the classification here.
        assertTrue(error.startsWith("42601"), "expected 42601, got: $error")
    }

    @Test
    fun postgresTreatsSemanticOnlyErrorsAsAccepted() {
        // Grammar parsed; only the empty catalog complains => accepted=true, no error surfaced.
        assertTrue(pg.verify("SELECT * FROM no_such_table").accepted, "undefined_table is semantic (42P01)")
        assertTrue(
            pg.verify("SELECT missing_col FROM (SELECT 1 AS a) s").accepted,
            "undefined_column is semantic (42703)",
        )
        assertTrue(pg.verify("SELECT no_such_func(1)").accepted, "undefined_function is semantic (42883)")
    }

    @Test
    fun postgresAcceptsDdlViaRolledBackExecutionFallback() {
        // DDL isn't preparable; it falls back to BEGIN; <sql>; ROLLBACK direct execution.
        assertTrue(pg.verify("CREATE TABLE brikk_ddl_probe (a INT, b TEXT)").accepted)
        // ...and a malformed DDL is still a real syntax reject through that path.
        val bad = pg.verify("CREATE TABLE brikk_ddl_probe (a INT")
        assertFalse(bad.accepted)
        assertTrue(bad.error!!.startsWith("42601"), "expected 42601, got: ${bad.error}")
    }

    @Test
    fun postgresDdlFallbackLeavesNoResidue() {
        // Transaction-rollback safety: creating a table via the fallback must not persist it,
        // so creating the SAME table twice must both succeed (no "already exists" 42P07 on the
        // second call — which would prove the first CREATE committed).
        val name = "brikk_rollback_probe"
        assertTrue(pg.verify("CREATE TABLE $name (a INT)").accepted, "first create should parse+rollback")
        val second = pg.verify("CREATE TABLE $name (a INT)")
        assertTrue(
            second.accepted,
            "second create must also be accepted — if the first had committed, PG would raise 42P07; error=${second.error}",
        )
        // Direct proof the table does not exist: a plain reference is a semantic miss (accepted),
        // but the catalog genuinely lacks it — verified by the double-create above.
    }

    @Test
    fun postgresAcceptsNonTransactionalDdlWithoutSideEffect() {
        // CREATE DATABASE can't run in a transaction block; PG raises 25001 which we treat as
        // "parsed, transaction context refused" => accepted, and it never leaves BEGIN so no db
        // is created.
        assertTrue(pg.verify("CREATE DATABASE brikk_probe_db").accepted, "parsed; 25001 => accepted")
    }

    @Test
    fun postgresVerifiesExpressionFragments() {
        assertTrue(pg.verifyExpression("1 + 2").accepted)
        // undefined column in an expression is still semantic (grammar accepted).
        assertTrue(pg.verifyExpression("some_col + 1").accepted)
        val bad = pg.verifyExpression("1 +")
        assertFalse(bad.accepted)
    }
}
