package dev.brikk.house.chdb

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Properties

/**
 * Direct binding to the non-deprecated opaque-handle API in chdb-core's
 * `programs/local/chdb.h`: connection/query calls, result accessors, and explicit result destroy.
 *
 * Keep this narrow and handwritten. In particular, do not bind the deprecated public structs:
 * their layout has changed before and would make a Kotlin API silently ABI-fragile.
 */
internal object NativeChdb {
    private val loadLock = Any()
    private var loadedPath: Path? = null
    private var bindings: Bindings? = null

    fun open(config: ChdbConfig): ChdbSession {
        validateConfig(config)
        val native = load(config)
        val connection = native.connect(argumentsFor(config))
        if (connection.address() == 0L) {
            throw ChdbUnavailableException("chDB returned a null connection handle")
        }
        return NativeChdbSession(native, connection)
    }

    private fun load(config: ChdbConfig): Bindings = synchronized(loadLock) {
        val requested = config.libraryPath ?: System.getProperty(Chdb.libraryPathProperty)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val candidate = requested?.toAbsolutePath()?.normalize() ?: PackagedChdbNative.extractForCurrentHost()
            ?: throw unavailable(
                "No compatible packaged chDB native resource was found for this host. Add the " +
                    "matching brikk-chdb-native-* runtime artifact, or set ${Chdb.libraryPathProperty} " +
                    "or ChdbConfig.libraryPath to libchdb.",
            )
        if (!Files.isRegularFile(candidate)) {
            throw unavailable("chDB native library does not exist or is not a regular file: $candidate")
        }
        val path = candidate.toRealPath()

        loadedPath?.let { loaded ->
            if (loaded != path) {
                throw unavailable(
                    "chDB is already loaded from $loaded; a JVM can use only one libchdb path " +
                        "per class loader (requested $path).",
                )
            }
            return@synchronized bindings!!
        }

        try {
            System.load(path.toString())
            val initialized = Bindings.create()
            loadedPath = path
            bindings = initialized
            initialized
        } catch (error: Throwable) {
            throw unavailable("Failed to load compatible chDB library at $path", error)
        }
    }

    private fun argumentsFor(config: ChdbConfig): List<String> = buildList {
        // chdb_connect consumes argv in the same form as the chdb executable.
        add("brikk-chdb")
        config.databasePath?.let { add("--path=$it") }
        addAll(config.arguments)
    }

    private fun validateConfig(config: ChdbConfig) {
        config.arguments.forEachIndexed { index, argument ->
            require(argument.isNotBlank()) { "chDB argument $index must not be blank" }
            require('\u0000' !in argument) { "chDB argument $index must not contain a NUL byte" }
        }
    }

    private fun unavailable(message: String, cause: Throwable? = null): ChdbUnavailableException =
        ChdbUnavailableException(
            "$message Configure libchdb with ${Chdb.libraryPathProperty} or ChdbConfig.libraryPath. " +
                "Start the JVM with --enable-native-access=ALL-UNNAMED. " +
                "Expected a 64-bit libchdb matching ${System.getProperty("os.name")} " +
                "${System.getProperty("os.arch")}.",
            cause,
        )
}

/** Extracts a verified platform resource into a private content-addressed cache for System.load. */
private object PackagedChdbNative {
    private const val resourceRoot = "META-INF/brikk/chdb/native"
    private val extractionLock = Any()
    private val directoryPermissions = PosixFilePermissions.fromString("rwx------")
    private val filePermissions = PosixFilePermissions.fromString("rw-------")

    fun extractForCurrentHost(): Path? = platformForCurrentHost()?.let(::extract)

    private fun extract(platform: String): Path? = synchronized(extractionLock) {
        val base = "$resourceRoot/$platform"
        val manifest = Chdb::class.java.classLoader.getResourceAsStream("$base/manifest.properties")
            ?: return@synchronized null
        val properties = Properties().also { manifest.use(it::load) }
        val expectedSha256 = properties.getProperty("librarySha256")?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw ChdbUnavailableException("Packaged chDB resource $base has no valid librarySha256")
        val library = properties.getProperty("library") ?: "libchdb.so"
        require(library == "libchdb.so") { "Unsupported packaged chDB library name: $library" }
        val destinationDirectory = secureCacheDirectory(expectedSha256)
        val destination = destinationDirectory.resolve(library)
        val lockFile = destinationDirectory.resolve(".extract.lock")
        createPrivateFile(lockFile)
        FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    requirePrivateRegularFile(destination)
                    check(sha256(destination) == expectedSha256) {
                        "Cached chDB library checksum mismatch at $destination"
                    }
                    return@synchronized destination
                }

                val resource = Chdb::class.java.classLoader.getResourceAsStream("$base/$library")
                    ?: throw ChdbUnavailableException("Packaged chDB manifest exists but $base/$library is missing")
                val temporary = Files.createTempFile(
                    destinationDirectory,
                    "$library-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(filePermissions),
                )
                try {
                    resource.use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
                    check(sha256(temporary) == expectedSha256) {
                        "Packaged chDB resource checksum mismatch for $platform"
                    }
                    Files.setPosixFilePermissions(temporary, filePermissions)
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
                    Files.setPosixFilePermissions(destination, filePermissions)
                    destination
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        }
    }

    private fun secureCacheDirectory(digest: String): Path {
        val configured = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val cacheRoot = configured ?: Path.of(System.getProperty("user.home"), ".cache")
        Files.createDirectories(cacheRoot)
        check(Files.isDirectory(cacheRoot)) { "chDB cache root is not a directory: $cacheRoot" }
        var directory = cacheRoot
        for (part in listOf("brikk", "chdb", digest)) {
            directory = directory.resolve(part)
            createPrivateDirectory(directory)
        }
        return directory
    }

    private fun createPrivateDirectory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(directoryPermissions))
            } catch (_: FileAlreadyExistsException) {
                // A concurrent process created it; validate below.
            }
        }
        check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "chDB cache path is not a private directory: $path"
        }
        Files.setPosixFilePermissions(path, directoryPermissions)
    }

    private fun createPrivateFile(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(filePermissions))
            } catch (_: FileAlreadyExistsException) {
                // A concurrent process created it; validate below.
            }
        }
        requirePrivateRegularFile(path)
    }

    private fun requirePrivateRegularFile(path: Path) {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "chDB cache path is not a regular file: $path"
        }
        Files.setPosixFilePermissions(path, filePermissions)
    }

    private fun platformForCurrentHost(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            (os.contains("mac") || os.contains("darwin")) && arch in setOf("aarch64", "arm64") -> "macos-arm64"
            os.contains("linux") && arch in setOf("amd64", "x86_64") -> "linux-x64"
            os.contains("linux") && arch in setOf("aarch64", "arm64") -> "linux-arm64"
            else -> null
        }
    }

    private fun sha256(file: Path): String = MessageDigest.getInstance("SHA-256").also { digest ->
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class NativeChdbSession(
    private val native: Bindings,
    private var connection: MemorySegment?,
) : ChdbSession {
    private val lock = Any()

    override fun query(sql: String, format: ChdbOutputFormat): ChdbResult = synchronized(lock) {
        require(sql.isNotBlank()) { "chDB SQL must not be blank" }
        require('\u0000' !in sql) { "chDB SQL must not contain a NUL byte" }
        val activeConnection = connection ?: throw IllegalStateException("chDB session is closed")
        native.query(activeConnection, sql, format.value)
    }

    override fun close() = synchronized(lock) {
        connection?.let(native::close)
        connection = null
    }
}

/** ABI holder, initialized only after System.load has made libchdb visible to loaderLookup(). */
private class Bindings private constructor(
    private val connect: MethodHandle,
    private val close: MethodHandle,
    private val query: MethodHandle,
    private val destroyResult: MethodHandle,
    private val resultBuffer: MethodHandle,
    private val resultLength: MethodHandle,
    private val resultElapsed: MethodHandle,
    private val resultRowsRead: MethodHandle,
    private val resultBytesRead: MethodHandle,
    private val resultError: MethodHandle,
) {
    fun connect(arguments: List<String>): MemorySegment = Arena.ofConfined().use { arena ->
        val pointerArray = arena.allocate(ValueLayout.ADDRESS, arguments.size.toLong())
        arguments.forEachIndexed { index, argument ->
            pointerArray.setAtIndex(ValueLayout.ADDRESS, index.toLong(), arena.allocateFrom(argument))
        }
        connect.invokeWithArguments(arguments.size, pointerArray) as MemorySegment
    }

    fun close(connection: MemorySegment) {
        close.invokeWithArguments(connection)
    }

    fun query(connection: MemorySegment, sql: String, format: String): ChdbResult = Arena.ofConfined().use { arena ->
        val result = query.invokeWithArguments(
            // chdb_connect returns chdb_connection*: the returned outer pointer owns a single
            // chdb_connection value. FFM gives foreign return pointers a zero-sized view, so
            // bound that one pointer slot before dereferencing it.
            connection.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0),
            arena.allocateFrom(sql),
            arena.allocateFrom(format),
        ) as MemorySegment
        if (result.address() == 0L) {
            throw ChdbQueryException("chDB returned a null result handle")
        }

        try {
            val error = cString(resultError.invokeWithArguments(result) as MemorySegment)
            if (error != null) throw ChdbQueryException(error)

            val length = resultLength.invokeWithArguments(result) as Long
            require(length >= 0) { "chDB returned an invalid result length: $length" }
            val buffer = resultBuffer.invokeWithArguments(result) as MemorySegment
            val bytes = if (length == 0L) {
                ByteArray(0)
            } else {
                require(buffer.address() != 0L) { "chDB returned a null buffer for a non-empty result" }
                buffer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
            }

            ChdbResult(
                bytes = bytes,
                rowsRead = resultRowsRead.invokeWithArguments(result) as Long,
                bytesRead = resultBytesRead.invokeWithArguments(result) as Long,
                elapsedSeconds = resultElapsed.invokeWithArguments(result) as Double,
            )
        } finally {
            destroyResult.invokeWithArguments(result)
        }
    }

    private fun cString(pointer: MemorySegment): String? =
        if (pointer.address() == 0L) null else pointer.reinterpret(Long.MAX_VALUE).getString(0)

    companion object {
        fun create(): Bindings {
            val linker = Linker.nativeLinker()
            val symbols = SymbolLookup.loaderLookup()
            fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
                linker.downcallHandle(
                    symbols.find(name).orElseThrow { UnsatisfiedLinkError("libchdb does not export $name") },
                    descriptor,
                )

            val address = ValueLayout.ADDRESS
            val sizeT = ValueLayout.JAVA_LONG // chDB's supported 64-bit targets use an 8-byte size_t.
            return Bindings(
                connect = downcall("chdb_connect", FunctionDescriptor.of(address, ValueLayout.JAVA_INT, address)),
                close = downcall("chdb_close_conn", FunctionDescriptor.ofVoid(address)),
                query = downcall("chdb_query", FunctionDescriptor.of(address, address, address, address)),
                destroyResult = downcall("chdb_destroy_query_result", FunctionDescriptor.ofVoid(address)),
                resultBuffer = downcall("chdb_result_buffer", FunctionDescriptor.of(address, address)),
                resultLength = downcall("chdb_result_length", FunctionDescriptor.of(sizeT, address)),
                resultElapsed = downcall("chdb_result_elapsed", FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, address)),
                resultRowsRead = downcall("chdb_result_rows_read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, address)),
                resultBytesRead = downcall("chdb_result_bytes_read", FunctionDescriptor.of(ValueLayout.JAVA_LONG, address)),
                resultError = downcall("chdb_result_error", FunctionDescriptor.of(address, address)),
            )
        }
    }
}
