package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryUuidTest {
    @Test
    fun generatedUuidKeepsTheStringResultContract() {
        val source = Dialects.BIGQUERY.parseOne("SELECT GENERATE_UUID() AS id")
        val before = Serde.dump(source)
        for ((target, type) in listOf("duckdb" to "TEXT", "spark" to "STRING", "spark2" to "STRING",
            "presto" to "VARCHAR", "trino" to "VARCHAR")) {
            val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
            assertEquals("SELECT CAST(UUID() AS $type) AS id", generator.generate(source), target)
            assertEquals(emptyList(), generator.unsupportedMessages)
            assertEquals(before, Serde.dump(source))
        }
        assertEquals("SELECT GENERATE_UUID() AS id", Dialects.BIGQUERY.generate(source))
        assertEquals("SELECT CAST(GEN_RANDOM_UUID() AS VARCHAR) AS id", Dialects.POSTGRES.generate(source, sourceDialect = "bigquery"))
    }

    @Test
    fun nativeUuidRemainsNative() {
        for (dialect in listOf(Dialects.DUCKDB, Dialects.PRESTO, Dialects.TRINO)) {
            assertEquals("SELECT UUID()", dialect.generate(dialect.parseOne("SELECT UUID()")))
        }
    }
}
