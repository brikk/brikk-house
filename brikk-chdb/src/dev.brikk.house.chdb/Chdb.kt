package dev.brikk.house.chdb

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * An output format understood by ClickHouse/chDB, passed separately from the SQL text.
 *
 * This is intentionally a small value type rather than an enum: ClickHouse supports a large
 * and evolving format set, and callers should not need a brikk release to use a new one.
 */
class ChdbOutputFormat private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is ChdbOutputFormat && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** The compact JSON array format, suitable for simple inspection. */
        val JSON_COMPACT = custom("JSONCompact")

        /** One JSON object per row, useful for line-oriented consumers. */
        val JSON_EACH_ROW = custom("JSONEachRow")

        val CSV = custom("CSV")
        val TSV = custom("TSV")
        val PARQUET = custom("Parquet")

        /** Creates a format value accepted by the installed chDB engine. */
        fun custom(value: String): ChdbOutputFormat {
            require(value.isNotBlank()) { "chDB output format must not be blank" }
            require('\u0000' !in value) { "chDB output format must not contain a NUL byte" }
            return ChdbOutputFormat(value)
        }
    }
}

/** Configuration for one in-process chDB session. */
data class ChdbConfig(
    /**
     * ClickHouse database directory. `null` uses chDB's in-memory default; a caller may also
     * explicitly pass [Path.of](java.nio.file.Path.of)(":memory:") when that is clearer.
     */
    val databasePath: Path? = null,
    /** Extra chDB command-line arguments, excluding the executable name and `--path`. */
    val arguments: List<String> = emptyList(),
    /**
     * Absolute or relative path to the platform-specific `libchdb` binary. When absent,
     * [Chdb.libraryPathProperty] is used. Phase 0 intentionally has no download or bundled
     * native artifact fallback.
     */
    val libraryPath: Path? = null,
)

/** A fully materialized chDB query result. Native result memory is released before this returns. */
data class ChdbResult(
    /** Raw bytes in the [format][ChdbSession.query] requested for the query. */
    val bytes: ByteArray,
    val rowsRead: Long,
    val bytesRead: Long,
    val elapsedSeconds: Double,
) {
    fun text(charset: Charset = StandardCharsets.UTF_8): String = bytes.toString(charset)
}

/** The configured native library could not be loaded or does not expose the expected chDB ABI. */
class ChdbUnavailableException internal constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** chDB accepted the call but rejected the SQL or its execution. */
class ChdbQueryException(
    message: String,
) : IllegalArgumentException(message)

/**
 * One stateful, in-process ClickHouse session backed by chDB.
 *
 * A session serializes calls: chDB owns mutable connection state, so use separate sessions for
 * independent concurrent workloads. Results are copied into JVM memory before native resources
 * are destroyed, making a [ChdbResult] safe to retain after [close].
 */
interface ChdbSession : AutoCloseable {
    fun query(
        sql: String,
        format: ChdbOutputFormat = ChdbOutputFormat.JSON_COMPACT,
    ): ChdbResult

    override fun close()
}

/** Entry point for the pure in-process chDB API. This module deliberately does not expose JDBC. */
object Chdb {
    /** System property used when [ChdbConfig.libraryPath] is not supplied. */
    const val libraryPathProperty: String = "brikk.chdb.library"

    /**
     * Opens a chDB session using the final Java foreign-function API.
     *
     * The JVM must be started with `--enable-native-access=ALL-UNNAMED`. The native library is
     * loaded once per JVM, so all sessions must use the same canonical library path.
     */
    fun open(config: ChdbConfig = ChdbConfig()): ChdbSession = NativeChdb.open(config)
}
