package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the release-tag grammar, not Doris server-side validation or execution. */
class Doris414GrammarTest {
    private val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))

    private fun acceptsRoundTrip(sql: String) {
        val fragment = SqlFragment(sql, "doris")
        assertFalse(fragment.ast is Command, sql)
        val generated = fragment.transpileTo("doris")
        assertTrue(generated.unsupportedMessages.isEmpty(), generated.unsupportedMessages.toString())
        for (text in listOf(sql, generated.sql)) {
            val result = verifier.verify(text)
            assertTrue(result.verified, result.warning)
            assertFalse(result.advisory)
            assertTrue(result.accepted, "$text: ${result.error}")
        }
        assertEquals(generated.sql, SqlFragment(generated.sql, "doris").transpileTo("doris").sql)
    }

    @Test
    fun defaultExpressionsAndBareDefaults() {
        for (sql in listOf(
            "UPDATE t SET c = DEFAULT(c)",
            "INSERT INTO t (id, c) VALUES (1, DEFAULT(c)), (2, DEFAULT)",
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET c = DEFAULT(t.c) " +
                "WHEN NOT MATCHED THEN INSERT (id, c) VALUES (s.id, DEFAULT(c))",
        )) acceptsRoundTrip(sql)
    }

    @Test
    fun nestedPathsAcrossAllAlterActions() {
        for (sql in listOf(
            "ALTER TABLE t ADD COLUMN s.b INT NULL",
            "ALTER TABLE t ADD COLUMN arr.element.b INT NULL FIRST",
            "ALTER TABLE t ADD COLUMN m.value.b INT NULL AFTER a",
            "ALTER TABLE t ADD COLUMN `s.t`.`b.c` STRING COMMENT 'new field'",
            "ALTER TABLE t ADD COLUMN s.b INT, ADD COLUMN s.c BIGINT",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT",
            "ALTER TABLE t MODIFY COLUMN arr.element BIGINT",
            "ALTER TABLE t MODIFY COLUMN m.value BIGINT NULL",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT FIRST",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT AFTER a",
            "ALTER TABLE t MODIFY COLUMN s.b COMMENT ''",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT FROM r PROPERTIES ('k'='v')",
            "ALTER TABLE t DROP COLUMN s.b",
            "ALTER TABLE t RENAME COLUMN m.value.b TO c",
            "ALTER TABLE t RENAME COLUMN s.b c",
        )) acceptsRoundTrip(sql)
    }

    @Test
    fun administrativeStatements() {
        for (kind in listOf("BASE", "CUMULATIVE", "FULL", "cumulative")) {
            acceptsRoundTrip("ADMIN COMPACT TABLET 12345 WHERE TYPE = '$kind'")
        }
        acceptsRoundTrip("SHOW COMPUTE GROUPS")
    }

    @Test
    fun functionsAndTimezoneAwareTypes() {
        for (sql in listOf(
            "SELECT PARSE_TO_VARIANT('{\"k\":1}'), TRY_PARSE_TO_VARIANT('{')",
            "SELECT * FROM VECTOR_SEARCH('table' = 'lance.db.t', 'column' = 'embedding', 'query_vector' = '[0,0]')",
            "SELECT EMBED('model', CAST('{\"image\":\"https://example.org/image.png\"}' AS JSON))",
            "SELECT CAST('2024-01-15 12:00:00 +00:00' AS TIMESTAMPTZ(6))",
            "CREATE TABLE t (ts TIMESTAMPTZ(6)) PARTITION BY RANGE (ts) " +
                "(PARTITION p VALUES [('2024-01-15 12:00:00 +00:00'), ('2024-01-15 21:00:00 Asia/Shanghai')))",
            "ALTER TABLE ice.db.t SET ('write.target-file-size-bytes' = '134217728')",
            "CREATE INDEX ann_idx ON t (vec) USING ANN PROPERTIES ('index_type'='hnsw')",
        )) acceptsRoundTrip(sql)
    }

    @Test
    fun releaseOracleRejectsInvalidFormsAndRemovedPlayCommand() {
        for (sql in listOf(
            "SELECT DEFAULT()", "SELECT DEFAULT(1)", "SELECT DEFAULT(c + 1)",
            "ADMIN COMPACT TABLET 12345",
            "ADMIN COMPACT TABLET '12345' WHERE TYPE = 'BASE'",
            "ALTER TABLE t ADD COLUMN s.b INT DEFAULT 7",
            "ALTER TABLE t MODIFY COLUMN s.b INT DEFAULT NULL",
            "ALTER TABLE t ADD COLUMN (s.b INT)",
            "PLAN REPLAYER PLAY '/tmp/plan.json'",
        )) {
            val result = verifier.verify(sql)
            assertTrue(result.verified, result.warning)
            assertFalse(result.accepted, "Unexpected 4.1.4 grammar acceptance: $sql")
        }
    }
}
