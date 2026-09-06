package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline schema snapshots. Only _snapshot.json activates a generation; old generations
 * are deliberately retained so a reader that already read a manifest can finish safely.
 * Writers need a filesystem supporting atomic replacement within a directory.
 */
object SchemaCache {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }
    private const val MANIFEST = "_snapshot.json"
    private const val SNAPSHOTS = ".snapshots"
    private const val LOCK = ".schema-cache.lock"
    private val sourceIdPattern = Regex("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)?")

    @Serializable
    private data class Manifest(
        val formatVersion: Int,
        val dialect: String,
        val catalog: String,
        val schema: String,
        val sourceId: String,
        val capturedAt: String,
        val engineVersion: String?,
        val generation: String,
        val objects: List<String>,
    )

    /**
     * Replaces one complete catalog/schema scope and returns its activated directory.
     * [sourceId] must be a caller-computed, credential-free fingerprint, using URL-safe
     * alphanumerics, '_' or '-', optionally prefixed with an algorithm and ':'. It must
     * match every active scope in [root], including this scope and empty scopes.
     *
     * The JVM monitor also serializes same-process writers, since FileChannel locks
     * throw rather than wait for overlapping locks in the same JVM.
     */
    @Synchronized
    fun replace(
        root: Path,
        catalog: String,
        schema: String,
        dialect: String,
        sourceId: String,
        objects: List<CapturedObject>,
        engineVersion: String? = null,
    ): Path = operation("replace") {
        validateName(catalog)
        validateName(schema)
        validateDialect(dialect)
        validateSourceId(sourceId)
        val records = objects.map { it.copy(columns = it.columns.toList()) }.sortedBy { it.name }
        checkCache(records.map { it.name }.distinct().size == records.size, "duplicate object names")
        records.forEach { validateObject(it, catalog, schema, dialect) }

        checkCache(generateSequence(root.toAbsolutePath().normalize().parent) { it.parent }.none {
            attributesOrNull(it.resolve(LOCK)) != null
        }, "replace requires the full cache root, not a nested directory")
        val cacheRoot = directory(root, create = true)
        val lockPath = cacheRoot.resolve(LOCK)
        attributesOrNull(lockPath)?.let { checkCache(it.isRegularFile, "invalid writer lock") }
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                val scope = cacheRoot.resolve(encodeName(catalog)).resolve(encodeName(schema))
                for (path in manifests(cacheRoot)) {
                    checkCache(path.parent.parent.parent == cacheRoot, "replace requires the full cache root")
                    val active = readManifest(path)
                    checkCache(active.sourceId == sourceId, "sourceId conflicts with an active scope")
                }

                val created = mutableListOf<Path>()
                try {
                    directory(scope, create = true, created = created)
                    val snapshots = directory(scope.resolve(SNAPSHOTS), create = true, created = created)
                    val generation = UUID.randomUUID().toString()
                    val generationDir = snapshots.resolve(generation)
                    Files.createDirectory(generationDir)
                    created.add(generationDir)
                    val objectDir = directory(generationDir.resolve("objects"), create = true, created = created)
                    for (record in records) {
                        writeNew(objectDir.resolve(encodeName(record.name) + ".json"), json.encodeToString(record), created)
                    }

                    val manifest = Manifest(
                        formatVersion = 1,
                        dialect = dialect,
                        catalog = catalog,
                        schema = schema,
                        sourceId = sourceId,
                        capturedAt = Instant.now().toString(),
                        engineVersion = engineVersion,
                        generation = generation,
                        objects = records.map { it.name },
                    )
                    val temporary = scope.resolve("._snapshot-$generation.tmp")
                    writeNew(temporary, json.encodeToString(manifest), created)
                    directory(scope)
                    attributesOrNull(scope.resolve(MANIFEST))?.let {
                        checkCache(it.isRegularFile, "invalid scope manifest")
                    }
                    // No non-atomic fallback: a failed publication leaves the old manifest intact.
                    Files.move(temporary, scope.resolve(MANIFEST), ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (failure: Throwable) {
                    // Delete only paths this attempt created, never traverse a generation or
                    // recursively remove a directory that could now contain user files.
                    for (path in created.asReversed()) {
                        try {
                            directory(path.parent)
                            checkCache(attributesOrNull(path)?.isSymbolicLink != true, "symlink in cleanup path")
                            Files.deleteIfExists(path)
                        } catch (cleanup: Exception) {
                            rethrowCancellation(cleanup)
                            failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
                scope
            }
        }
    }

    /**
     * Loads a full root, a catalog directory, or a scope directory. Table keys contain
     * exactly three source-dialect-quoted identifiers. Native types remain untouched on
     * disk; the returned column types are BASE SQL, or UNKNOWN when unsupported.
     * Missing, incomplete, or invalid caches throw [ShapeError] without JSON payloads.
     * Readers share the writer's JVM monitor and hold the existing root lock in shared
     * mode, so discovery cannot observe a new scope before publication or rollback.
     */
    @Synchronized
    fun load(root: Path): ShapeCatalog = operation("load") {
        val loadRoot = directory(root)
        val lockPaths = generateSequence(loadRoot) { it.parent }.take(3)
            .map { it.resolve(LOCK) }
            .filter { path ->
                val attributes = attributesOrNull(path) ?: return@filter false
                checkCache(attributes.isRegularFile, "invalid or symlinked root lock")
                true
            }.toList()
        checkCache(lockPaths.size == 1, "missing or ambiguous root lock")
        val lockPath = lockPaths.single()
        FileChannel.open(lockPath, READ, NOFOLLOW_LINKS).use { channel ->
            channel.lock(0L, Long.MAX_VALUE, true).use {
                val paths = manifests(loadRoot)
                checkCache(paths.isNotEmpty(), "no active scope manifest found")
                val tables = linkedMapOf<String, Shape>()
                val identities = hashSetOf<Pair<String, String>>()
                var sourceId: String? = null
                val active = paths.map { it to readManifest(it) }
                    .sortedWith(compareBy({ it.second.catalog }, { it.second.schema }))
                for ((path, manifest) in active) {
                    checkCache(identities.add(manifest.catalog to manifest.schema), "duplicate scope identity")
                    checkCache(sourceId == null || sourceId == manifest.sourceId, "sourceId conflicts across active scopes")
                    sourceId = manifest.sourceId
                    val d = validateDialect(manifest.dialect)
                    val objectDir = directory(path.parent.resolve(SNAPSHOTS).resolve(manifest.generation).resolve("objects"))
                    for (name in manifest.objects.sorted()) {
                        val record = readJson<CapturedObject>(objectDir.resolve(encodeName(name) + ".json"), "object record")
                        validateObject(record, manifest.catalog, manifest.schema, manifest.dialect)
                        checkCache(record.name == name, "object identity does not match manifest")
                        val key = listOf(record.catalog, record.schema, record.name).joinToString(".") {
                            d.generate(Identifier(args("this" to it, "quoted" to true)))
                        }
                        checkCache(key !in tables, "duplicate table identity")
                        tables[key] = Shape(record.columns.map {
                            ColumnShape(it.name, normalizedType(it.type, d), it.nullable)
                        })
                    }
                }
                ShapeCatalog(tables)
            }
        }
    }

    private fun normalizedType(native: String, dialect: Dialect): String = try {
        val tokens = dialect.tokenize(native)
        if (tokens.any { it.tokenType == TokenType.SEMICOLON || it.comments.isNotEmpty() }) {
            "UNKNOWN"
        } else {
            val type = dialect.parser(errorLevel = ErrorLevel.RAISE).parseIntoDataType(tokens, native) as? DataType
            if (type == null || type.walk().filterIsInstance<DataType>().any {
                    it.thisArg == DType.UNKNOWN || it.thisArg == DType.USERDEFINED
                }) {
                "UNKNOWN"
            } else {
                val generator = Dialects.BASE.generator(sourceDialect = dialect.name)
                val rendered = generator.generate(type)
                // Shape types must be reconstructable by BASE consumers, not just printable.
                val baseType = Dialects.BASE.parser(errorLevel = ErrorLevel.RAISE)
                    .parseIntoDataType(Dialects.BASE.tokenize(rendered), rendered)
                if (generator.unsupportedMessages.isEmpty() && baseType is DataType) rendered else "UNKNOWN"
            }
        }
    } catch (_: ParseError) {
        "UNKNOWN"
    } catch (_: TokenError) {
        "UNKNOWN"
    } catch (_: UnsupportedError) {
        "UNKNOWN"
    }

    private fun readManifest(path: Path): Manifest {
        val manifest = readJson<Manifest>(path, "scope manifest")
        checkCache(manifest.formatVersion == 1, "unsupported manifest formatVersion")
        validateName(manifest.catalog)
        validateName(manifest.schema)
        validateDialect(manifest.dialect)
        validateSourceId(manifest.sourceId)
        checkCache(path.parent.fileName.toString() == encodeName(manifest.schema) &&
            path.parent.parent?.fileName?.toString() == encodeName(manifest.catalog),
            "manifest scope identity does not match its directory")
        val generation = try { UUID.fromString(manifest.generation) } catch (_: IllegalArgumentException) { null }
        checkCache(generation != null && generation.toString() == manifest.generation, "invalid manifest generation UUID")
        try { Instant.parse(manifest.capturedAt) } catch (_: DateTimeParseException) {
            throw ShapeError("Schema cache: invalid manifest capturedAt")
        }
        manifest.objects.forEach { validateName(it) }
        checkCache(manifest.objects.distinct().size == manifest.objects.size, "duplicate manifest object names")
        return manifest
    }

    private fun validateObject(record: CapturedObject, catalog: String, schema: String, dialect: String) {
        checkCache(record.formatVersion == 1, "unsupported object formatVersion")
        checkCache(record.catalog == catalog && record.schema == schema && record.dialect == dialect,
            "object scope or dialect does not match manifest")
        validateName(record.name)
        checkCache(record.kind.isNotBlank(), "missing object kind")
        checkCache(record.columns.map { it.name }.distinct().size == record.columns.size, "duplicate column names")
        record.columns.forEach {
            validateName(it.name)
            checkCache(it.type.isNotBlank(), "missing native column type")
        }
    }

    private fun validateName(name: String) {
        checkCache(name.isNotEmpty() && '\u0000' !in name, "invalid empty or NUL-containing identifier")
        try { name.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) {
            throw ShapeError("Schema cache: identifier is not valid UTF-8")
        }
    }

    private fun validateDialect(name: String): Dialect =
        Dialects.forNameOrNull(name) ?: throw ShapeError("Schema cache: unsupported source dialect")

    private fun validateSourceId(sourceId: String) {
        checkCache(sourceId.length in 1..256 && sourceIdPattern.matches(sourceId),
            "sourceId must be an opaque credential-free fingerprint")
    }

    /** Reversible UTF-8 percent encoding; even dots and percent signs are escaped. */
    private fun encodeName(name: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in name.encodeToByteArray(throwOnInvalidSequence = true)) {
            val value = byte.toInt() and 255
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-') {
                append(char)
            } else {
                append('%').append(hex[value shr 4]).append(hex[value and 15])
            }
        }
    }

    // Stop at each scope manifest. Generation records can never be mistaken for manifests.
    private fun manifests(root: Path): List<Path> {
        val found = mutableListOf<Path>()
        fun visit(dir: Path, depth: Int) {
            directory(dir)
            val manifest = dir.resolve(MANIFEST)
            attributesOrNull(manifest)?.let {
                checkCache(it.isRegularFile, "invalid scope manifest")
                found.add(manifest)
                return
            }
            val children = Files.newDirectoryStream(dir).use { it.toList().sortedBy { child -> child.fileName.toString() } }
            for (child in children) {
                val attributes = attributesOrNull(child) ?: continue
                checkCache(!attributes.isSymbolicLink, "symlink in cache directory")
                checkCache(child.fileName.toString() != SNAPSHOTS, "missing scope manifest for snapshot generations")
                if (attributes.isDirectory) {
                    checkCache(depth < 2, "missing scope manifest within search depth")
                    visit(child, depth + 1)
                }
            }
            checkCache(depth == 0 || children.any { Files.isDirectory(it, NOFOLLOW_LINKS) }, "missing scope manifest")
        }
        visit(root, 0)
        return found
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    /** Check ancestors BEFORE normalization, so a symlink followed by '..' is not hidden. */
    private fun directory(path: Path, create: Boolean = false, created: MutableList<Path>? = null): Path {
        val absolute = path.toAbsolutePath()
        var current = absolute.root
        for (part in absolute) {
            current = current.resolve(part)
            var attributes = attributesOrNull(current)
            if (attributes == null && create) {
                try {
                    Files.createDirectory(current)
                    created?.add(current)
                } catch (_: java.nio.file.FileAlreadyExistsException) { }
                attributes = attributesOrNull(current)
            }
            checkCache(attributes?.isDirectory == true && !attributes.isSymbolicLink,
                "missing directory or symlink in cache path")
        }
        return absolute.normalize()
    }

    private inline fun <reified T> readJson(path: Path, label: String): T {
        directory(path.parent)
        checkCache(attributesOrNull(path)?.isRegularFile == true, "missing or invalid $label")
        val text = Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
            Channels.newReader(channel, UTF_8.newDecoder(), -1).readText()
        }
        return try { json.decodeFromString<T>(text) } catch (_: SerializationException) {
            // Serialization exceptions can include the entire input, including native SQL.
            throw ShapeError("Schema cache: malformed $label")
        }
    }

    private fun writeNew(path: Path, text: String, created: MutableList<Path>) {
        directory(path.parent)
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            created.add(path)
            val bytes = ByteBuffer.wrap(text.toByteArray(UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun checkCache(condition: Boolean, message: String) {
        if (!condition) throw ShapeError("Schema cache: $message")
    }

    private fun rethrowCancellation(error: Exception) {
        if (error is CancellationException || error is InterruptedException ||
            error is ClosedByInterruptException || error is FileLockInterruptionException) {
            throw error
        }
        // Recognize IDE cancellation subclasses without linking compiler/IDE classes.
        var type: Class<*>? = error.javaClass
        while (type != null) {
            if (type.simpleName == "ProcessCanceledException") throw error
            type = type.superclass
        }
    }

    private inline fun <T> operation(name: String, block: () -> T): T = try {
        block()
    } catch (e: ShapeError) {
        throw e
    } catch (e: Exception) {
        rethrowCancellation(e)
        throw ShapeError("Schema cache $name failed (${e.javaClass.simpleName})")
    }
}
