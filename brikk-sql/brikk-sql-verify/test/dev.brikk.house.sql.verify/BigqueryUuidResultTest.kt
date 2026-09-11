package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryUuidResultTest {
    @Test
    fun duckdbReturnsTextThatSupportsStringOperations() {
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        val sql = generator.generate(Dialects.BIGQUERY.parseOne("SELECT GENERATE_UUID() AS id"))
        assertEquals(emptyList(), generator.unsupportedMessages)
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, TYPEOF(id), LENGTH(id) FROM ($sql) AS generated").use { result ->
                    assertTrue(result.next())
                    assertEquals("VARCHAR", result.getString(2))
                    assertEquals(36, result.getInt(3))
                    assertTrue(result.getString(1).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                }
            }
        }
    }
}
