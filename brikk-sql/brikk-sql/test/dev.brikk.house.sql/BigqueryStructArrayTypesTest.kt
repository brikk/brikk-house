package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryStructArrayTypesTest {
    @Test
    fun nullFieldsUseSiblingTypesWithoutMutatingSource() {
        for (array in listOf(
            "[STRUCT('x' AS b), STRUCT(NULL AS b)]",
            "[STRUCT(NULL AS b), STRUCT('x' AS b)]",
            "[STRUCT('x' AS b), STRUCT(NULL AS b), STRUCT('x' AS b)]",
        )) {
            val tree = Dialects.BIGQUERY.parseOne("SELECT t.b FROM UNNEST($array) AS t")
            val before = Serde.dump(tree)
            for (target in listOf(Dialects.PRESTO, Dialects.TRINO)) {
                val generator = target.generator(sourceDialect = "bigquery")
                val result = generator.generate(tree)
                assertTrue(result.contains("CAST(ROW(NULL) AS ROW(b VARCHAR))"), result)
                assertEquals(emptyList(), generator.unsupportedMessages, result)
                assertEquals(before, Serde.dump(tree))
            }
        }
    }

    @Test
    fun nestedFieldsAndAllNullDefaultsKeepTheirShape() {
        for ((array, expected) in listOf(
            "[STRUCT(STRUCT(NULL AS b) AS s), STRUCT(STRUCT('x' AS b) AS s)]" to "ROW(s ROW(b VARCHAR))",
            "[STRUCT(NULL AS a), STRUCT(['x'] AS a)]" to "ROW(a ARRAY(VARCHAR))",
            "[STRUCT([NULL] AS a), STRUCT(['x'] AS a)]" to "ROW(a ARRAY(VARCHAR))",
            "[STRUCT(NULL AS b), STRUCT(NULL AS b)]" to "ROW(b BIGINT)",
            "[STRUCT(NULL AS b), STRUCT(CAST(NULL AS STRING) AS b)]" to "ROW(b VARCHAR)",
        )) {
            for (target in listOf(Dialects.PRESTO, Dialects.TRINO)) {
                val generator = target.generator(sourceDialect = "bigquery")
                val result = generator.generate(Dialects.BIGQUERY.parseOne("SELECT t FROM UNNEST($array) AS t"))
                assertEquals(emptyList(), generator.unsupportedMessages, result)
                assertTrue(result.contains(expected), result)
            }
        }
    }
}
