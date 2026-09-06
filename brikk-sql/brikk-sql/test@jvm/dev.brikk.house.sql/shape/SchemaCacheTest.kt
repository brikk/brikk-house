package dev.brikk.house.sql.shape

import java.net.URLDecoder
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileLockInterruptionException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaCacheTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTripKeepsDirectMetadataAndSupportsAllLoadRoots() = withRoot { root ->
        val table = captured().copy(columns = listOf(
            CapturedColumn("z", "bigint", false),
            CapturedColumn("a", "varchar(32)", true),
            CapturedColumn("unknown_nullability", "int"),
        ))
        val view = captured("report").copy(kind = "VIEW", columns = listOf(CapturedColumn("total", "decimal(18,4)")))
        val scope = SchemaCache.replace(root, "cat", "db", "doris", "sha256:synthetic", listOf(view, table), "3.synthetic")
        assertEquals(root.resolve("cat/db"), scope)
        val manifest = document(scope.resolve("_snapshot.json"))
        assertEquals(1, manifest.getValue("formatVersion").jsonPrimitive.content.toInt())
        assertEquals("doris", manifest.getValue("dialect").jsonPrimitive.content)
        assertEquals("sha256:synthetic", manifest.getValue("sourceId").jsonPrimitive.content)
        assertEquals("3.synthetic", manifest.getValue("engineVersion").jsonPrimitive.content)
        assertEquals(listOf("items", "report"), manifest.getValue("objects").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(table, json.decodeFromString<CapturedObject>(Files.readString(objectDir(scope).resolve("items.json"))))
        assertEquals(view, json.decodeFromString<CapturedObject>(Files.readString(objectDir(scope).resolve("report.json"))))
        assertTrue(Files.readString(objectDir(scope).resolve("items.json")).contains("\n    \"columns\": [\n"))

        val loaded = SchemaCache.load(root)
        assertEquals(loaded, SchemaCache.load(scope.parent))
        assertEquals(loaded, SchemaCache.load(scope))
        assertEquals(listOf("`cat`.`db`.`items`", "`cat`.`db`.`report`"), loaded.tables.keys.toList())
        assertEquals(listOf(
            ColumnShape("z", "BIGINT", false),
            ColumnShape("a", "VARCHAR(32)", true),
            ColumnShape("unknown_nullability", "INT"),
        ), loaded.tables.getValue("`cat`.`db`.`items`").columns)
        assertEquals(Shape.of("total" to "DECIMAL(18, 4)"), loaded.tables.getValue("`cat`.`db`.`report`"))
        assertEquals(loaded.tables.getValue("`cat`.`db`.`items`"),
            SqlFragment("SELECT * FROM cat.db.items", "doris").outputShape(loaded))
        assertTrue(loaded.slots.isEmpty())
    }

    @Test
    fun richNativeTypesNormalizeWithoutInventingNestedColumns() = withRoot { root ->
        val native = listOf(
            "LARGEINT", "DECIMALV3(27,9)", "DATETIMEV2(6)", "DATEV2", "JSON",
            "ARRAY<INT>", "MAP<STRING, ARRAY<DECIMAL(12,2)>>", "STRUCT<x:INT,y:ARRAY<STRING>>",
            "FUTURE_NATIVE_TYPE(7)", "INT trailing_garbage", "INT; BIGINT", "BITMAP",
            "VARIANT<'nested':INT>", "AGG_STATE<sum(INT NOT NULL)>", "ARRAY<FUTURE_NATIVE_TYPE>",
        )
        val record = captured().copy(columns = native.mapIndexed { i, type -> CapturedColumn("c$i", type, i % 2 == 0) })
        val scope = store(root, listOf(record))
        val columns = SchemaCache.load(root).tables.values.single().columns
        assertEquals(record.columns.map { it.name }, columns.map { it.name })
        assertEquals(record.columns.map { it.nullable }, columns.map { it.nullable })
        assertEquals(listOf(
            "INT128", "DECIMAL(27, 9)", "DATETIME(6)", "DATE", "JSON",
            "ARRAY<INT>", "MAP<TEXT, ARRAY<DECIMAL(12, 2)>>", "STRUCT<x INT, y ARRAY<TEXT>>",
        ), columns.take(8).map { it.type })
        assertTrue(columns.drop(8).all { it.type == "UNKNOWN" })
        assertEquals(record, json.decodeFromString<CapturedObject>(Files.readString(objectDir(scope).resolve("items.json"))))
    }

    @Test
    fun sourceDialectRatherThanBaseParsesNativeTypes() = withRoot { root ->
        val record = captured().copy(dialect = "postgres", columns = listOf(CapturedColumn("ids", "INTEGER[]", false)))
        SchemaCache.replace(root, "cat", "db", "postgres", "synthetic", listOf(record))
        assertEquals(Shape(listOf(ColumnShape("ids", "ARRAY<INT>", false))),
            SchemaCache.load(root).tables.getValue("\"cat\".\"db\".\"items\""))
    }

    @Test
    fun escapedDottedIdentifiersStayThreeComponentsAndBindSlots() = withRoot { root ->
        val record = captured("t.`icks").copy(catalog = "c.at", schema = "s.`chema", columns = listOf(
            CapturedColumn("a.b", "INT", false),
            CapturedColumn("a`b", "BIGINT", true),
            CapturedColumn("`literal`", "VARCHAR(12)"),
        ))
        SchemaCache.replace(root, record.catalog, record.schema, "doris", "synthetic", listOf(record))
        val loaded = SchemaCache.load(root)
        val key = loaded.tables.keys.single()
        assertEquals("`c.at`.`s.``chema`.`t.``icks`", key)
        assertEquals(listOf(record.catalog, record.schema, record.name), catalogTableParts(key).map { it.name })
        assertEquals(record.columns.map { it.name }, loaded.tables.values.single().names())
        val result = SqlFragment("SELECT * FROM $key", "doris").outputShape(loaded)
        assertEquals(loaded.tables.values.single(), result)
        val slot = Shape.of("from_slot" to "INT")
        val withSlot = loaded.copy(slots = mapOf("source" to slot))
        assertEquals(slot, SqlFragment("SELECT * FROM source()", "doris").outputShape(withSlot))
    }

    @Test
    fun doubleQuotedComponentsAndUnqualifiedDottedNamesResolve() {
        val expected = Shape.of("id" to "INT")
        for (key in listOf("\"ca.t\".\"s\"\"chema\".\"it.ems\"", "\"it.ems\"")) {
            val catalog = ShapeCatalog(mapOf(key to expected))
            assertEquals(expected, SqlFragment("SELECT * FROM $key", "postgres").outputShape(catalog))
            assertEquals(expected, SqlFragment("SELECT * FROM source()", "postgres")
                .outputShape(catalog.copy(slots = mapOf("source" to expected))))
        }
        for (key in listOf("cat.db.items; other", "a.b.c.d", "\"unclosed")) {
            assertFailsWith<ShapeError> {
                SqlFragment("SELECT * FROM items", "postgres")
                    .outputShape(ShapeCatalog(mapOf(key to expected)))
            }
        }
    }

    @Test
    fun refreshRemovesStaleObjectsRetainsOldGenerationsAndAllowsEmptyScopes() = withRoot { root ->
        val scope = store(root, listOf(captured("z"), captured("a")))
        val firstManifest = Files.readString(scope.resolve("_snapshot.json"))
        val firstObjects = objectDir(scope)
        val firstRecord = Files.readString(firstObjects.resolve("a.json"))
        store(root, listOf(captured("b")))
        val secondObjects = objectDir(scope)
        assertNotEquals(firstObjects, secondObjects)
        assertEquals(listOf("`cat`.`db`.`b`"), SchemaCache.load(root).tables.keys.toList())
        assertEquals(firstRecord, Files.readString(firstObjects.resolve("a.json")))

        // Neither stale generations nor unlisted JSON (even named _snapshot.json) are read.
        Files.writeString(firstObjects.resolve("z.json"), "invalid obsolete JSON")
        Files.writeString(secondObjects.resolve("_snapshot.json"), "not a manifest")
        assertEquals(1, SchemaCache.load(scope).tables.size)
        store(root, emptyList())
        assertEquals(ShapeCatalog.EMPTY, SchemaCache.load(root))
        assertEquals(ShapeCatalog.EMPTY, SchemaCache.load(scope))
        assertTrue(Files.isDirectory(firstObjects))
        assertTrue(Files.isDirectory(secondObjects))
        assertEquals(emptyList(), document(scope.resolve("_snapshot.json")).getValue("objects").jsonArray.toList())
        assertNotEquals(firstManifest, Files.readString(scope.resolve("_snapshot.json")))
    }

    @Test
    fun snapshotNamedObjectIsNotConfusedWithManifest() = withRoot { root ->
        store(root, listOf(captured("_snapshot")))
        assertEquals(listOf("`cat`.`db`.`_snapshot`"), SchemaCache.load(root).tables.keys.toList())
    }

    @Test
    fun invalidReplacementPreservesPreviousManifestByteForByte() = withRoot { root ->
        val valid = captured()
        val scope = store(root)
        val manifest = Files.readString(scope.resolve("_snapshot.json"))
        val loaded = SchemaCache.load(root)
        val invalid = listOf(
            listOf(valid, valid),
            listOf(valid.copy(catalog = "other")),
            listOf(valid.copy(schema = "other")),
            listOf(valid.copy(dialect = "postgres")),
            listOf(valid.copy(formatVersion = 2)),
            listOf(valid.copy(name = "")),
            listOf(valid.copy(name = "\uD800")),
            listOf(valid.copy(kind = "")),
            listOf(valid.copy(columns = listOf(CapturedColumn("id", "INT"), CapturedColumn("id", "TEXT")))),
            listOf(valid.copy(columns = listOf(CapturedColumn("id", "")))),
        )
        for (records in invalid) {
            assertFailsWith<ShapeError> { store(root, records) }
            assertEquals(manifest, Files.readString(scope.resolve("_snapshot.json")))
            assertEquals(loaded, SchemaCache.load(root))
        }
        // An I/O failure after writing part of a new generation also cannot publish it.
        val userFile = scope.resolve(".snapshots/user-note.txt")
        Files.writeString(userFile, "keep this user file")
        val previousPaths = Files.walk(scope).use { it.toList().toSet() }
        assertFailsWith<ShapeError> { store(root, listOf(captured("a"), captured("z".repeat(1000)))) }
        assertEquals(manifest, Files.readString(scope.resolve("_snapshot.json")))
        assertEquals(loaded, SchemaCache.load(root))
        assertEquals(previousPaths, Files.walk(scope).use { it.toList().toSet() })
        assertEquals("keep this user file", Files.readString(userFile))
    }

    @Test
    fun failedFirstCaptureRemovesOnlyItsCreatedPathsAndCanBeRetried() = withRoot { root ->
        Files.createDirectory(root)
        val userFile = root.resolve("user-note.txt")
        Files.writeString(userFile, "keep this user file")
        assertFailsWith<ShapeError> { store(root, listOf(captured("a"), captured("z".repeat(1000)))) }
        assertFalse(Files.exists(root.resolve("cat")))
        assertEquals("keep this user file", Files.readString(userFile))
        store(root)
        assertEquals(listOf("`cat`.`db`.`items`"), SchemaCache.load(root).tables.keys.toList())
    }

    @Test
    fun failedNewScopeLeavesOtherScopesLoadableAndCanBeRetried() = withRoot { root ->
        val existing = store(root)
        val manifest = Files.readString(existing.resolve("_snapshot.json"))
        for (catalog in listOf("cat", "new-catalog")) {
            val before = SchemaCache.load(root)
            val previousPaths = Files.walk(root).use { it.toList().toSet() }
            val records = listOf(captured("a"), captured("z".repeat(1000)))
                .map { it.copy(catalog = catalog, schema = "new-scope") }
            assertFailsWith<ShapeError> {
                SchemaCache.replace(root, catalog, "new-scope", "doris", "sha256:synthetic", records)
            }
            assertEquals(previousPaths, Files.walk(root).use { it.toList().toSet() })
            assertEquals(manifest, Files.readString(existing.resolve("_snapshot.json")))
            assertEquals(before, SchemaCache.load(root))
            val scope = SchemaCache.replace(root, catalog, "new-scope", "doris", "sha256:synthetic", records.take(1))
            assertEquals(listOf("`$catalog`.`new-scope`.`a`"), SchemaCache.load(scope).tables.keys.toList())
        }
    }

    @Test
    fun preexistingIncompleteScopeIsNeitherIgnoredNorCleanedUp() = withRoot { root ->
        store(root)
        val incomplete = root.resolve("cat/incomplete/.snapshots/preexisting-generation/objects")
        Files.createDirectories(incomplete)
        val userFile = incomplete.resolve("user-file.txt")
        Files.writeString(userFile, "preexisting data")
        val previousPaths = Files.walk(root).use { it.toList().toSet() }
        assertFailsWith<ShapeError> { SchemaCache.load(root) }
        assertFailsWith<ShapeError> {
            SchemaCache.replace(root, "cat", "incomplete", "doris", "sha256:synthetic", emptyList())
        }
        assertEquals(previousPaths, Files.walk(root).use { it.toList().toSet() })
        assertEquals("preexisting data", Files.readString(userFile))
    }

    @Test
    fun missingMalformedAndWrongVersionRecordsFailWithoutPayloads() = withRoot { root ->
        val scope = store(root)
        val path = objectDir(scope).resolve("items.json")
        val original = Files.readString(path)
        val mutations = listOf(
            "formatVersion" to JsonPrimitive(2),
            "catalog" to JsonPrimitive("other"),
            "schema" to JsonPrimitive("other"),
            "dialect" to JsonPrimitive("postgres"),
            "name" to JsonPrimitive("other"),
            "columns" to JsonArray(List(2) { json.parseToJsonElement(json.encodeToString(CapturedColumn("id", "INT"))) }),
        )
        for (mutation in mutations) {
            rewrite(path, mutation)
            assertFailsWith<ShapeError> { SchemaCache.load(root) }
            Files.writeString(path, original)
        }
        Files.writeString(path, "{\"columns\":\"payload-not-for-errors\"")
        val failure = assertFailsWith<ShapeError> { SchemaCache.load(root) }
        assertTrue(failure.message!!.contains("malformed object record"))
        assertFalse(failure.message!!.contains("payload-not-for-errors"))
        assertNull(failure.cause)
        Files.delete(path)
        assertTrue(assertFailsWith<ShapeError> { SchemaCache.load(root) }.message!!.contains("object record"))
    }

    @Test
    fun corruptManifestsAndIncompleteScopesFailClearly() = withRoot { root ->
        assertFailsWith<ShapeError> { SchemaCache.load(root) }
        Files.createDirectory(root)
        assertFailsWith<ShapeError> { SchemaCache.load(root) }
        val scope = store(root)
        val path = scope.resolve("_snapshot.json")
        val original = Files.readString(path)
        val mutations = listOf(
            "formatVersion" to JsonPrimitive(99),
            "catalog" to JsonPrimitive("wrong"),
            "schema" to JsonPrimitive("wrong"),
            "dialect" to JsonPrimitive("not-a-dialect"),
            "capturedAt" to JsonPrimitive("not-an-instant"),
            "generation" to JsonPrimitive("../outside"),
            "generation" to JsonPrimitive("/outside"),
            "generation" to JsonPrimitive("1-1-1-1-1"),
            "objects" to JsonArray(listOf(JsonPrimitive("items"), JsonPrimitive("items"))),
            "objects" to JsonArray(listOf(JsonPrimitive(""))),
            "objects" to JsonArray(listOf(JsonPrimitive("../outside"))),
        )
        for (mutation in mutations) {
            rewrite(path, mutation)
            assertFailsWith<ShapeError> { SchemaCache.load(root) }
            Files.writeString(path, original)
        }
        Files.writeString(path, JsonObject(document(path) - "formatVersion").toString())
        assertFailsWith<ShapeError> { SchemaCache.load(root) }
        Files.writeString(path, "{payload-not-for-errors")
        val failure = assertFailsWith<ShapeError> { SchemaCache.load(root) }
        assertFalse(failure.message!!.contains("payload-not-for-errors"))
        Files.writeString(path, original)
        Files.delete(objectDir(scope).resolve("items.json"))
        Files.delete(objectDir(scope))
        assertFailsWith<ShapeError> { SchemaCache.load(root) }
        Files.delete(path)
        assertTrue(assertFailsWith<ShapeError> { SchemaCache.load(root) }.message!!.contains("manifest"))
    }

    @Test
    fun sourceFingerprintGuardsOtherScopesIncludingEmptyOnes() = withRoot { root ->
        val scope = store(root)
        val original = Files.readString(scope.resolve("_snapshot.json"))
        assertFailsWith<ShapeError> {
            SchemaCache.replace(root, "cat", "other", "doris", "different", emptyList())
        }
        assertFalse(Files.exists(root.resolve("cat/other")))
        val other = SchemaCache.replace(root, "cat", "other", "doris", "sha256:synthetic", emptyList())
        assertFailsWith<ShapeError> {
            SchemaCache.replace(root, "cat", "db", "doris", "different", listOf(captured()))
        }
        assertEquals(original, Files.readString(scope.resolve("_snapshot.json")))
        rewrite(other.resolve("_snapshot.json"), "sourceId" to JsonPrimitive("different"))
        assertTrue(assertFailsWith<ShapeError> { SchemaCache.load(root) }.message!!.contains("sourceId"))
    }

    @Test
    fun soleScopeRejectsSourceChangesAndConnectionStrings() = withRoot { root ->
        val scope = store(root)
        val loaded = SchemaCache.load(root)
        val manifest = Files.readString(scope.resolve("_snapshot.json"))
        for (source in listOf("new-fingerprint", "", "jdbc:doris://example.invalid/db", "user:password@example.invalid", "has spaces")) {
            assertFailsWith<ShapeError> { SchemaCache.replace(root, "cat", "db", "doris", source, emptyList()) }
            assertEquals(manifest, Files.readString(scope.resolve("_snapshot.json")))
            assertEquals(loaded, SchemaCache.load(root))
        }
        store(root, emptyList())
        val emptyManifest = Files.readString(scope.resolve("_snapshot.json"))
        assertFailsWith<ShapeError> {
            SchemaCache.replace(root, "cat", "db", "doris", "new-fingerprint", emptyList())
        }
        assertEquals(emptyManifest, Files.readString(scope.resolve("_snapshot.json")))
        assertEquals(ShapeCatalog.EMPTY, SchemaCache.load(root))
    }

    @Test
    fun multipleCatalogsLoadInDeterministicOrder() = withRoot { root ->
        for ((catalog, schema) in listOf("z" to "z", "a" to "z", "a" to "a")) {
            val record = captured().copy(catalog = catalog, schema = schema)
            SchemaCache.replace(root, catalog, schema, "doris", "synthetic", listOf(record))
        }
        assertEquals(listOf("`a`.`a`.`items`", "`a`.`z`.`items`", "`z`.`z`.`items`"), SchemaCache.load(root).tables.keys.toList())
        assertEquals(2, SchemaCache.load(root.resolve("a")).tables.size)
        assertFailsWith<ShapeError> { store(root.resolve("a")) }
        assertFalse(Files.exists(root.resolve("a/.schema-cache.lock")))
        assertEquals(2, SchemaCache.load(root.resolve("a")).tables.size)
    }

    @Test
    fun shadowLocksCannotReplaceTheOwningRootLock() = withRoot { root ->
        val scope = store(root)
        assertFailsWith<ShapeError> { store(scope.resolve("nested")) }
        assertFalse(Files.exists(scope.resolve("nested")))
        val shadow = scope.parent.resolve(".schema-cache.lock")
        Files.writeString(shadow, "")
        assertFailsWith<ShapeError> { SchemaCache.load(scope.parent) }
        assertFailsWith<ShapeError> { SchemaCache.load(scope) }
        Files.delete(shadow)
        assertEquals(SchemaCache.load(root), SchemaCache.load(scope))
    }

    @Test
    fun namesAreReversiblyEncodedWithoutTraversalOrCollisions() = withRoot { root ->
        val names = listOf("..", ".", "../outside", "%2E", "a/b", "a\\b", "a+b", "\u96EA\uD83D\uDE80")
        val catalog = "../catalog"
        val schema = "..\\schema"
        val records = names.map { captured(it).copy(catalog = catalog, schema = schema) }
        val scope = SchemaCache.replace(root, catalog, schema, "doris", "synthetic", records)
        assertEquals(root, scope.parent.parent)
        assertEquals(catalog, URLDecoder.decode(scope.parent.fileName.toString(), UTF_8))
        assertEquals(schema, URLDecoder.decode(scope.fileName.toString(), UTF_8))
        val filenames = Files.newDirectoryStream(objectDir(scope)).use { paths -> paths.map { it.fileName.toString() } }
        assertEquals(names.toSet(), filenames.map { URLDecoder.decode(it.removeSuffix(".json"), UTF_8) }.toSet())
        assertTrue(filenames.all { Regex("(?:[A-Za-z0-9_-]|%[0-9A-F]{2})+\\.json").matches(it) })
        assertEquals(names.size, SchemaCache.load(root).tables.size)
        assertFalse(Files.exists(root.parent.resolve("outside")))
    }

    @Test
    fun managedDirectoriesRecordsManifestsAndLocksRejectSymlinks() {
        for (part in listOf("root", "catalog", "scope", "snapshots", "generation", "objects", "record", "manifest", "lock")) {
            withRoot { root ->
                val scope = store(root)
                val objects = objectDir(scope)
                val target = when (part) {
                    "root" -> root
                    "catalog" -> scope.parent
                    "scope" -> scope
                    "snapshots" -> scope.resolve(".snapshots")
                    "generation" -> objects.parent
                    "objects" -> objects
                    "record" -> objects.resolve("items.json")
                    "manifest" -> scope.resolve("_snapshot.json")
                    else -> root.resolve(".schema-cache.lock")
                }
                val held = root.parent.resolve("held")
                Files.move(target, held)
                Files.createSymbolicLink(target, held)
                assertFailsWith<ShapeError>(part) { SchemaCache.load(root) }
                if (part in setOf("root", "catalog", "scope", "snapshots", "manifest", "lock")) {
                    assertFailsWith<ShapeError>(part) { store(root) }
                }
            }
        }
    }

    @Test
    fun symlinkBeforeParentComponentCannotBeNormalizedAway() = withRoot { root ->
        store(root)
        val link = root.parent.resolve("link")
        Files.createSymbolicLink(link, root)
        val disguised = link.resolve("../cache")
        assertFailsWith<ShapeError> { SchemaCache.load(disguised) }
        assertFailsWith<ShapeError> { store(disguised) }
    }

    @Test
    fun loadRequiresAnExistingRegularRootLockFromEveryEntryPoint() = withRoot { root ->
        val scope = store(root)
        val entries = listOf(root, scope.parent, scope)
        val lock = root.resolve(".schema-cache.lock")
        Files.delete(lock)
        val previousPaths = Files.walk(root).use { it.toList().toSet() }
        for (entry in entries) {
            assertTrue(assertFailsWith<ShapeError> { SchemaCache.load(entry) }.message!!.contains("lock"))
            assertFalse(Files.exists(lock))
            assertEquals(previousPaths, Files.walk(root).use { it.toList().toSet() })
        }

        val target = root.parent.resolve("user-lock.txt")
        Files.writeString(target, "do not modify")
        Files.createSymbolicLink(lock, target)
        for (entry in entries) {
            assertTrue(assertFailsWith<ShapeError> { SchemaCache.load(entry) }.message!!.contains("lock"))
            assertTrue(Files.isSymbolicLink(lock))
            assertEquals("do not modify", Files.readString(target))
            assertEquals(previousPaths + setOf(lock), Files.walk(root).use { it.toList().toSet() })
        }
    }

    @Test
    fun concurrentReaderWaitsForNewScopePublication() = withRoot { root ->
        store(root)
        val existing = SchemaCache.load(root).tables.getValue("`cat`.`db`.`items`")
        val scope = root.resolve("new-catalog/new-schema")
        val records = List(2048) { index ->
            captured("item_$index").copy(catalog = "new-catalog", schema = "new-schema")
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publisher = executor.submit<Path> {
                SchemaCache.replace(root, "new-catalog", "new-schema", "doris", "sha256:synthetic", records)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.isDirectory(scope) && !publisher.isDone && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertTrue(Files.isDirectory(scope), "Writer did not create the new scope")
            assertFalse(Files.exists(scope.resolve("_snapshot.json")), "Test must observe the scope before activation")
            assertFalse(publisher.isDone, "Reader must start while the writer is active")

            // The visible, incomplete scope used to make manifest discovery fail here.
            val reader = executor.submit<ShapeCatalog> { SchemaCache.load(root) }
            assertEquals(scope, publisher.get(60, TimeUnit.SECONDS))
            val loaded = reader.get(60, TimeUnit.SECONDS)
            assertEquals(records.size + 1, loaded.tables.size)
            assertEquals(existing, loaded.tables.getValue("`cat`.`db`.`items`"))
            for (record in records) {
                assertEquals(existing, loaded.tables.getValue("`new-catalog`.`new-schema`.`${record.name}`"))
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentWritersCannotMixSources() = withRoot { root ->
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf("one", "two").map { id ->
                executor.submit<Boolean> {
                    try {
                        SchemaCache.replace(root, "cat", id, "doris", id, emptyList())
                        true
                    } catch (_: ShapeError) {
                        false
                    }
                }
            }
            assertEquals(1, writes.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(ShapeCatalog.EMPTY, SchemaCache.load(root))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun cancellationAndInterruptsPropagateUnchanged() = withRoot { root ->
        for (signal in listOf(
            CancellationException(), InterruptedException(), ClosedByInterruptException(),
            FileLockInterruptionException(), DerivedProcessCancellation(),
        )) {
            val failingPath = object : Path by root {
                override fun toAbsolutePath(): Path = throw signal
            }
            assertSame(signal, assertFailsWith<Exception> { SchemaCache.load(failingPath) })
            assertSame(signal, assertFailsWith<Exception> { store(failingPath) })
        }
    }

    private open class ProcessCanceledException : RuntimeException()
    private class DerivedProcessCancellation : ProcessCanceledException()

    private fun captured(name: String = "items") = CapturedObject(
        catalog = "cat", schema = "db", name = name, kind = "TABLE",
        columns = listOf(CapturedColumn("id", "BIGINT", false)),
    )

    private fun store(root: Path, records: List<CapturedObject> = listOf(captured())): Path =
        SchemaCache.replace(root, "cat", "db", "doris", "sha256:synthetic", records)

    private fun document(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun objectDir(scope: Path): Path = scope.resolve(".snapshots")
        .resolve(document(scope.resolve("_snapshot.json")).getValue("generation").jsonPrimitive.content)
        .resolve("objects")

    private fun rewrite(path: Path, vararg fields: Pair<String, JsonElement>) {
        Files.writeString(path, JsonObject(document(path) + fields.toMap()).toString())
    }

    private fun withRoot(block: (Path) -> Unit) {
        val temporary = Files.createTempDirectory("schema-cache-test-")
        try {
            block(temporary.resolve("cache"))
        } finally {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
