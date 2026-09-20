package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.DorisCompactTablet
import dev.brikk.house.sql.ast.DorisDefault
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Show
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Doris414Test {
    private fun roundTrip(input: String, expected: String = input): Expression {
        val ast = parseOne(input, "doris")
        assertFalse(ast is Command, input)
        assertEquals(expected, ast.sql("doris"))
        assertEquals(expected, parseOne(expected, "doris").sql("doris"))
        assertEquals(expected, assertIs<Expression>(Serde.load(Serde.dump(ast))).sql("doris"))
        return ast
    }

    @Test
    fun defaultExpressionsInUpdatesInsertsAndBothMergeBranches() {
        for (sql in listOf(
            "UPDATE t SET c = DEFAULT(c)",
            "INSERT INTO t (id, c) VALUES (1, DEFAULT(c)), (2, DEFAULT)",
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET c = DEFAULT(t.c) " +
                "WHEN NOT MATCHED THEN INSERT (id, c) VALUES (s.id, DEFAULT(c))",
        )) {
            val ast = roundTrip(sql)
            assertTrue(ast.findAll(DorisDefault::class).any(), sql)
        }
        roundTrip("INSERT INTO t VALUES (DEFAULT)")
    }

    @Test
    fun defaultArgumentRetainsTargetPathWithoutBecomingRowLineage() {
        val ast = roundTrip("SELECT DEFAULT(t.`c.d`) + 1 AS c FROM t")
        val default = ast.find(DorisDefault::class)!!
        assertIs<Dot>(default.thisArg)
        assertEquals(listOf("t", "c.d"), default.findAll(Identifier::class).map { it.name }.toList())
        assertTrue(default.findAll(Column::class).none(), "Write defaults are not input-row dependencies")
        assertTrue(default.findAll(Anonymous::class).none(), "DEFAULT is a grammar expression")
        for (sql in listOf("SELECT DEFAULT()", "SELECT DEFAULT(1)", "SELECT DEFAULT(c + 1)", "SELECT DEFAULT(a, b)")) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "doris") }
        }
    }

    @Test
    fun nestedColumnActionsStayStructuredAndPreserveIntent() {
        for (sql in listOf(
            "ALTER TABLE t ADD COLUMN s.b INT NULL",
            "ALTER TABLE t ADD COLUMN arr.element.b INT NULL AFTER a",
            "ALTER TABLE t ADD COLUMN s.b INT NULL FIRST",
            "ALTER TABLE t ADD COLUMN `s.t`.`b.c` STRING COMMENT 'new field'",
            "ALTER TABLE t ADD COLUMN s.b INT, ADD COLUMN s.c BIGINT",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT",
            "ALTER TABLE t MODIFY COLUMN arr.element BIGINT",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT NULL AFTER a",
            "ALTER TABLE t MODIFY COLUMN s.b COMMENT ''",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT COMMENT 'nested field'",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT FROM r PROPERTIES ('k'='v')",
            "ALTER TABLE t DROP COLUMN s.b",
        )) {
            assertIs<Alter>(roundTrip(sql))
        }
        roundTrip("ALTER TABLE t RENAME COLUMN s.b TO c", "ALTER TABLE t RENAME COLUMN s.b c")
        roundTrip("ALTER TABLE t RENAME COLUMN m.value.b c", "ALTER TABLE t RENAME COLUMN m.`value`.b c")
        val nested = roundTrip("ALTER TABLE t ADD COLUMN `s.t`.`b.c` INT").find(ColumnDef::class)!!
        assertIs<Dot>(nested.thisArg)
        val flat = roundTrip("ALTER TABLE t ADD COLUMN `s.b` INT").find(ColumnDef::class)!!
        assertIs<Identifier>(flat.thisArg)
        assertEquals("s.b", flat.name)
        // Omitted NULL/COMMENT must stay omitted, rather than overwrite existing field metadata.
        val modified = roundTrip("ALTER TABLE t MODIFY COLUMN s.b BIGINT").find(ColumnDef::class)!!
        assertTrue((modified.args["constraints"] as List<*>).isEmpty())
        for (sql in listOf(
            "ALTER TABLE t ADD COLUMN s.b INT DEFAULT 7",
            "ALTER TABLE t MODIFY COLUMN s.b INT DEFAULT NULL",
            "ALTER TABLE t ADD COLUMN s.b DATETIME ON UPDATE CURRENT_TIMESTAMP",
        )) {
            assertFailsWith<ParseError>(sql) { parseOne(sql, "doris") }
        }
        roundTrip("ALTER TABLE t ADD COLUMN b INT DEFAULT 7")
    }

    @Test
    fun compactionAndComputeGroupsAreStructured() {
        for (kind in listOf("BASE", "CUMULATIVE", "FULL", "cumulative")) {
            val ast = assertIs<DorisCompactTablet>(roundTrip("ADMIN COMPACT TABLET 9223372036854775807 WHERE TYPE = '$kind'"))
            assertEquals("9223372036854775807", (ast.thisArg as Expression).name)
            assertEquals(kind, (ast.args["kind"] as Expression).name)
        }
        assertIs<Show>(roundTrip("SHOW COMPUTE GROUPS"))
        // Other ADMIN statements retain the existing opaque-command policy.
        assertIs<Command>(parseOne("ADMIN SHOW REPLICA STATUS FROM t", "doris"))
        roundTrip("SELECT admin FROM t", "SELECT `admin` FROM t")
    }

    @Test
    fun timezoneAwareTypesAndPartitionStringsSurviveGeneration() {
        for (type in listOf("TIMESTAMPTZ", "TIMESTAMPTZ(0)", "TIMESTAMPTZ(6)")) {
            roundTrip("SELECT CAST('2024-01-15 12:00:00 +00:00' AS $type)")
            roundTrip("CREATE TABLE t (ts $type)")
        }
        roundTrip(
            "CREATE TABLE t (ts TIMESTAMPTZ(6)) PARTITION BY RANGE (ts) " +
                "(PARTITION p VALUES LESS THAN ('2024-01-15 12:00:00 +00:00'), " +
                "PARTITION p2 VALUES LESS THAN ('2024-01-15 21:00:00 Asia/Shanghai'))",
        )
        assertEquals("SELECT CAST(x AS TIMESTAMPTZ(6))", transpile("SELECT CAST(x AS TIMESTAMPTZ(6))", "postgres", "doris"))
    }

    @Test
    fun variantFunctionsHaveTypesAndDistinctNullability() {
        for ((function, nullable) in listOf("PARSE_TO_VARIANT" to false, "TRY_PARSE_TO_VARIANT" to true)) {
            val sql = "SELECT $function('{\"k\":1}') AS v"
            roundTrip(sql)
            val column = SqlFragment(sql, "doris").outputShape().columns.single()
            assertEquals("VARIANT", column.type)
            assertEquals(nullable, column.nullable)
            assertEquals(true, SqlFragment("SELECT $function(NULL) AS v", "doris").outputShape().columns.single().nullable)
        }
        // Qualified calls may be UDFs, and other dialects do not inherit Doris's typing.
        for ((sql, dialect) in listOf(
            "SELECT custom.PARSE_TO_VARIANT('1') AS v" to "doris",
            "SELECT PARSE_TO_VARIANT('1') AS v" to "trino",
        )) {
            val column = SqlFragment(sql, dialect).outputShape().columns.single()
            assertEquals("UNKNOWN", column.type)
            assertEquals(null, column.nullable)
        }
    }

    @Test
    fun vectorSearchIsARealTableFunctionRatherThanACompositionSlot() {
        val sql = "SELECT * FROM VECTOR_SEARCH('table' = 'lance.db.t', 'column' = 'embedding', " +
            "'query_vector' = '[0,0]', 'top_k' = '5')"
        roundTrip(sql)
        val fragment = SqlFragment(sql, "doris")
        assertTrue(fragment.isKnownFunction("vector_search"))
        assertTrue(fragment.tableSlots.isEmpty())
    }
}
