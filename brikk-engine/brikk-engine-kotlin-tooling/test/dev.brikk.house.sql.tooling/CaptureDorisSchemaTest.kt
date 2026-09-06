package dev.brikk.house.sql.tooling

import dev.brikk.house.sql.shape.SchemaCache
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureDorisSchemaTest {
    @Test
    fun metadataQueriesAreQualifiedAndKeepNativeTypesAndColumnOrder() {
        val statements = mutableListOf<String>()
        val objects = collectDorisSchema("cat.with.dot", "db`name") { sql, width ->
            statements += sql
            when (sql) {
                "SHOW FULL TABLES FROM `cat.with.dot`.`db``name`" -> {
                    assertEquals(2, width)
                    listOf(listOf("view.with.dot", "VIEW"), listOf("records", "BASE TABLE"))
                }
                "SHOW COLUMNS FROM `cat.with.dot`.`db``name`.`records`" -> {
                    assertEquals(3, width)
                    listOf(
                        listOf("id", "LARGEINT", "NO"),
                        listOf("payload", "STRUCT<city:VARCHAR(20), flags:ARRAY<INT>>", "YES"),
                        listOf("future", "NEW_ENGINE_TYPE", "UNSPECIFIED"),
                    )
                }
                "SHOW COLUMNS FROM `cat.with.dot`.`db``name`.`view.with.dot`" -> listOf(listOf("amount", "DECIMAL(18,4)", "NO"))
                else -> error("Unexpected metadata query")
            }
        }
        assertEquals(listOf("records", "view.with.dot"), objects.map { it.name })
        assertEquals(listOf("id", "payload", "future"), objects.first().columns.map { it.name })
        assertEquals("LARGEINT", objects.first().columns.first().type)
        assertEquals("STRUCT<city:VARCHAR(20), flags:ARRAY<INT>>", objects.first().columns[1].type)
        assertEquals(listOf(false, true, null), objects.first().columns.map { it.nullable })
        assertEquals("VIEW", objects.last().kind)
        assertEquals(4, statements.size)
        assertEquals(statements.first(), statements.last())
        assertTrue(statements.all { it.startsWith("SHOW FULL TABLES FROM") || it.startsWith("SHOW COLUMNS FROM") })
    }

    @Test
    fun changingInventoryAndMissingColumnMetadataDoNotPassAsCompleteCaptures() {
        var inventories = 0
        assertFailsWith<IllegalStateException> {
            collectDorisSchema("sample", "analytics") { _, width ->
                if (width == 2) {
                    if (inventories++ == 0) listOf(listOf("records", "BASE TABLE")) else emptyList()
                } else listOf(listOf("id", "BIGINT", "NO"))
            }
        }
        assertFailsWith<IllegalStateException> {
            collectDorisSchema("sample", "analytics") { _, width ->
                if (width == 2) listOf(listOf("records", "BASE TABLE")) else emptyList()
            }
        }
        assertEquals(emptyList(), collectDorisSchema("sample", "empty") { _, _ -> emptyList() })
    }

    @Test
    fun forcedCaptureCanReplaceAnEarlierSnapshotAndFailuresLeaveItUsable() {
        val root = createTempDirectory("doris-capture-test")
        try {
            var type = "BIGINT"
            var fail = false
            var inventories = 0
            val query: (String, Int) -> List<List<String?>> = { _, width ->
                if (width == 2) {
                    inventories++
                    listOf(listOf("records", "BASE TABLE"))
                } else {
                    if (fail) throw SQLException("synthetic failure", "08001", 1)
                    listOf(listOf("id", type, "NO"))
                }
            }
            fun capture() = SchemaCache.replace(
                root, "sample", "analytics", "doris", "synthetic-fixture",
                collectDorisSchema("sample", "analytics", query),
            )
            capture()
            type = "STRING"
            capture()
            assertEquals(4, inventories)
            val beforeFailure = SchemaCache.load(root)
            assertEquals("TEXT", beforeFailure.tables.values.single().columns.single().type)
            fail = true
            assertFailsWith<SQLException> { capture() }
            assertEquals(beforeFailure, SchemaCache.load(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private val environment = mapOf(
        "DORIS_JDBC_URL" to "jdbc:mysql://example.invalid:9030/?useSSL=true",
        "DORIS_USER" to "fixture-user",
        "DORIS_PASSWORD" to "fixture-not-a-secret",
        "DORIS_CATALOG" to "sample",
        "DORIS_SCHEMA" to "analytics",
    )

    @Test
    fun relativeOutputPathsResolveUnderDogfoodWithoutExposingConnectionSettings() {
        val root = createTempDirectory("doris-config-test")
        try {
            val expected = root.resolve("brikk-engine/dogfood/schema-cache")
            for (configured in listOf("schema-cache", "./brikk-engine/dogfood/schema-cache", "brikk-engine/dogfood/schema-cache", expected.toString())) {
                val config = dorisCaptureConfig(root, environment + ("BRIKK_SCHEMA_CACHE_DIR" to configured))
                assertEquals(expected, config.output)
                assertTrue(config.sourceId.matches(Regex("sha256:[0-9a-f]{64}")))
                assertFalse(config.toString().contains(environment.getValue("DORIS_PASSWORD")))
                assertFalse(config.sourceId.contains("example.invalid"))
            }
            assertEquals(expected, dorisCaptureConfig(root, environment).output)
            assertFailsWith<IllegalArgumentException> {
                dorisCaptureConfig(root, environment + ("BRIKK_SCHEMA_CACHE_DIR" to "../../public-output"))
            }
            assertFailsWith<IllegalArgumentException> {
                dorisCaptureConfig(root, environment + ("BRIKK_SCHEMA_CACHE_DIR" to root.toString()))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun credentialsMustNotBeEmbeddedInTheJdbcUrl() {
        for (url in listOf(
            "jdbc:mysql://name:password@example.invalid:9030/",
            "jdbc:mysql://example.invalid:9030/?password=value",
            "jdbc:mysql://example.invalid:9030/?password1=value",
            "jdbc:mysql://example.invalid:9030/?%75ser=value",
        )) {
            assertFailsWith<IllegalArgumentException> { dorisCaptureConfig(Path.of("."), environment + ("DORIS_JDBC_URL" to url)) }
        }
    }
}
