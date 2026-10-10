package dev.brikk.house.sql.schema.inputs

import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.SchemaCache
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SchemaInputTest {
    @Test
    fun trackedPathAndLegacyOptionsMustMatchTheActualCompilerArguments() {
        val root = scratch()
        val schema = root.resolve("schema.sql")
        schema.writeText("CREATE TABLE records (id INT);")
        val args = listOf("-P", "plugin:dev.brikk.house.sql.compiler:schema=schema.sql", "-P", "plugin:dev.brikk.house.sql.compiler:defaultSchema=public")
        validateCompilerSchema(schema, root, args, "postgres", "public")
        assertFailsWith<IllegalArgumentException> { validateCompilerSchema(schema, root, args, "duckdb", "public") }
        assertFailsWith<IllegalArgumentException> { validateCompilerSchema(schema, root, args, "postgres", "wrong") }
        val other = root.resolve("other.sql")
        other.writeText(schema.readText())
        assertFailsWith<IllegalArgumentException> { validateCompilerSchema(other, root, args, "postgres", "public") }
        assertFailsWith<IllegalArgumentException> { validateCompilerSchema(schema, root, args + args, "postgres", "public") }
        assertFailsWith<IllegalStateException> { validateCompilerSchema(schema, root, emptyList(), "postgres", "public") }
    }

    @Test
    fun capturedInputsIgnoreLegacyDialectOverridesButStillRequireTheSameSelector() {
        val root = scratch()
        val schema = root.resolve("schema")
        capture(schema)
        validateCompilerSchema(schema, root, listOf("plugin:dev.brikk.house.sql.compiler:schema=schema"), "not-a-dialect", "unused")
    }
    private fun scratch() = createTempDirectory(Path.of(System.getProperty("java.io.tmpdir"), "opencode").createDirectories(), "brikk-schema-input-")
    private fun record(type: String = "BIGINT", nullable: Boolean? = false) =
        CapturedObject("sample", "analytics", "records", "TABLE", listOf(CapturedColumn("id", type, nullable)))
    private fun capture(root: Path, objects: List<CapturedObject> = listOf(record())) =
        SchemaCache.replace(root, "sample", "analytics", "doris", "synthetic", objects)
    private fun revision(root: Path, output: Path) = writeSchemaRevision(root, "postgres", "", "consumer", output)

    @Test
    fun identicalCapturesDoNotRewriteTheSourceOrFingerprintArchivedGenerations() {
        val root = scratch()
        val schema = root.resolve("schema")
        val output = root.resolve(".brikk/schema-inputs")
        val old = capture(schema)
        val archivedObject = Files.walk(old.resolve(".snapshots")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList().single()
        }
        assertTrue(revision(schema, output))
        val source = output.resolve("BrikkSchemaInput.kt")
        val before = source.readText()
        val modified = Files.getLastModifiedTime(source)
        capture(schema)
        // Inactive garbage does not change the active typing contract.
        archivedObject.writeText("inactive garbage")
        assertFalse(revision(schema, output))
        assertEquals(before, source.readText())
        assertEquals(modified, Files.getLastModifiedTime(source))
    }

    @Test
    fun activeColumnChangesAndRemovedObjectsChangeOnlyTheOpaqueRevision() {
        val root = scratch()
        val schema = root.resolve("schema")
        val output = root.resolve(".brikk/schema-inputs")
        capture(schema)
        revision(schema, output)
        val before = output.resolve("BrikkSchemaInput.kt").readText()
        capture(schema, listOf(record("TEXT", true)))
        assertTrue(revision(schema, output))
        val after = output.resolve("BrikkSchemaInput.kt").readText()
        assertNotEquals(before, after)
        assertFalse(after.contains("analytics"))
        assertFalse(after.contains(schema.toString()))
        assertFalse(after.contains("TEXT"))
        capture(schema, emptyList())
        assertTrue(revision(schema, output))
    }

    @Test
    fun malformedActiveDataFailsWithoutOverwritingTheLastGoodRevisionAndMayRecover() {
        val root = scratch()
        val schema = root.resolve("schema")
        val output = root.resolve(".brikk/schema-inputs")
        val active = capture(schema)
        revision(schema, output)
        val before = output.resolve("BrikkSchemaInput.kt").readText()
        active.resolve("_snapshot.json").writeText("{")
        assertFailsWith<Exception> { revision(schema, output) }
        assertEquals(before, output.resolve("BrikkSchemaInput.kt").readText())
        // SQL refuses replacing malformed data. Select a separate valid root to
        // test recovery of the same generated output without masking that refusal.
        val repaired = root.resolve("repaired")
        capture(repaired)
        assertTrue(revision(repaired, output))
    }

    @Test
    fun legacyDdlAndItsDialectDefaultSchemaAndSelectorAreCompilerInputs() {
        val root = scratch()
        val schema = root.resolve("schema.sql")
        val output = root.resolve(".brikk/schema-inputs")
        schema.writeText("CREATE TABLE records (id BIGINT NOT NULL);")
        assertTrue(writeSchemaRevision(schema, "postgres", "public", "consumer", output))
        assertFalse(writeSchemaRevision(schema, "postgres", "public", "consumer", output))
        schema.writeText("CREATE TABLE records (id TEXT);")
        assertTrue(writeSchemaRevision(schema, "postgres", "public", "consumer", output))
        assertTrue(writeSchemaRevision(schema, "postgres", "other", "consumer", output))
        assertTrue(writeSchemaRevision(schema, "duckdb", "other", "consumer", output))
    }

    @Test
    fun fingerprintIncludesOrderedColumnsQuotedIdentityNullabilityAndSlotContracts() {
        val columns = listOf(ColumnShape("id", "INT", nullable = false), ColumnShape("Mixed", "TEXT", nullable = true, quoted = true))
        fun hash(cols: List<ColumnShape>, slots: Boolean = false) = catalogRevision(
            if (slots) ShapeCatalog(tables = emptyMap(), slots = mapOf("records" to Shape(cols))) else ShapeCatalog(tables = mapOf("records" to Shape(cols))), "snapshot-v1", "selector")
        val before = hash(columns)
        assertNotEquals(before, hash(columns.reversed()))
        assertNotEquals(before, hash(columns.map { it.copy(nullable = null) }))
        assertNotEquals(before, hash(columns.map { it.copy(quoted = false) }))
        assertNotEquals(before, hash(columns, slots = true))
        val a = ShapeCatalog(tables = linkedMapOf("a" to Shape(columns), "b" to Shape(columns)))
        val b = ShapeCatalog(tables = linkedMapOf("b" to Shape(columns), "a" to Shape(columns)))
        assertEquals(catalogRevision(a, "snapshot-v1", "selector"), catalogRevision(b, "snapshot-v1", "selector"))
    }

    @Test
    fun missingInputsForeignFilesAndSymlinkOutputsAreNotSilentlyOverwritten() {
        val root = scratch()
        val schema = root.resolve("schema")
        val output = root.resolve(".brikk/schema-inputs").createDirectories()
        assertFailsWith<IllegalArgumentException> { revision(schema, output) }
        capture(schema)
        val source = output.resolve("BrikkSchemaInput.kt")
        source.writeText("user-authored content")
        assertFailsWith<IllegalArgumentException> { revision(schema, output) }
        assertEquals("user-authored content", source.readText())
        val linkDir = root.resolve("linked")
        Files.createSymbolicLink(linkDir, output)
        assertFailsWith<IllegalArgumentException> { revision(schema, linkDir) }
        assertEquals("user-authored content", source.readText())
    }

    @Test
    fun fullRootCatalogAndSchemaSelectorsLoadOnlyTheirActiveInventories() {
        val root = scratch()
        val schema = root.resolve("schema")
        val scope = capture(schema)
        for ((index, selector) in listOf(schema, scope.parent, scope).withIndex()) {
            val output = root.resolve("output-$index")
            assertTrue(revision(selector, output))
            assertFalse(revision(selector, output))
        }
    }
}
