package dev.brikk.house.sql.tooling

import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import dev.brikk.house.sql.shape.SchemaCache
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.TaskAction

/** Explicit metadata capture, never part of build/test and never skipped as up-to-date. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun captureDorisSchema(@Input projectFile: Path) {
    var phase = "configuration"
    try {
        val config = dorisCaptureConfig(projectFile.toAbsolutePath().parent, System.getenv())
        val properties = Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password)
            setProperty("connectTimeout", "10000")
            setProperty("socketTimeout", "60000")
        }
        phase = "connection"
        DriverManager.getConnection(config.jdbcUrl, properties).use { connection ->
            val objects = collectDorisSchema(config.catalog, config.schema) { sql, width ->
                phase = if (width == 2) "relation enumeration" else "column metadata"
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 60
                    statement.maxRows = 0
                    statement.executeQuery(sql).use { results ->
                        buildList {
                            while (results.next()) {
                                check(size < 10_000) { "Metadata row limit exceeded" }
                                // SHOW COLUMNS also returns defaults and extras. Do not read them.
                                add((1..width).map { results.getString(it) })
                            }
                        }
                    }
                }
            }
            phase = "snapshot publication"
            SchemaCache.replace(
                config.output, config.catalog, config.schema, "doris", config.sourceId,
                objects, connection.metaData.databaseProductVersion,
            )
            phase = "offline validation"
            val loaded = SchemaCache.load(config.output)
            println("Captured ${objects.size} relations and ${objects.sumOf { it.columns.size }} columns into the private dogfood cache.")
            println("Offline catalog loaded ${loaded.tables.size} active relations.")
        }
    } catch (e: Exception) {
        if (e is InterruptedException || e is java.util.concurrent.CancellationException) throw e
        val detail = if (e is SQLException) "SQLState=${e.sqlState}, code=${e.errorCode}" else e.javaClass.simpleName
        // JDBC/JSON errors can embed URLs, credentials, SQL, or metadata. Do not chain them.
        error("Doris schema capture failed during $phase ($detail). No incomplete snapshot was activated.")
    }
}

/** Not a data class: accidental toString() must not print credentials. */
internal class DorisCaptureConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val catalog: String,
    val schema: String,
    val output: Path,
    val sourceId: String,
)

internal fun dorisCaptureConfig(projectRoot: Path, env: Map<String, String>): DorisCaptureConfig {
    fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
        ?: error("Missing $name")
    val url = required("DORIS_JDBC_URL")
    require(url.startsWith("jdbc:mysql://")) { "DORIS_JDBC_URL must use jdbc:mysql://" }
    val uri = URI(url.removePrefix("jdbc:"))
    require(uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null) { "Use a single-host JDBC URL without credentials" }
    val sensitiveKeys = setOf("user", "username", "password", "password1", "password2", "password3", "passwd", "token", "secret")
    require(uri.rawQuery.orEmpty().split('&').none {
        URLDecoder.decode(it.substringBefore('='), UTF_8).lowercase() in sensitiveKeys
    }) { "Supply credentials through DORIS_USER and DORIS_PASSWORD, not the URL" }

    val root = projectRoot.toAbsolutePath().normalize()
    val privateRoot = root.resolve("brikk-engine/dogfood")
    val configured = Path.of(env["BRIKK_SCHEMA_CACHE_DIR"]?.takeIf { it.isNotBlank() } ?: "schema-cache")
    require(configured.none { it.toString() == ".." }) { "Cache output must not contain parent traversal" }
    val normalized = configured.normalize()
    val output = when {
        normalized.isAbsolute -> normalized
        normalized.startsWith(Path.of("brikk-engine/dogfood")) -> root.resolve(normalized)
        else -> privateRoot.resolve(normalized)
    }
    require(output.normalize().startsWith(privateRoot) && output.normalize() != privateRoot) {
        "Cache output must be a subdirectory of this worktree's brikk-engine/dogfood"
    }
    val endpoint = "mysql://${uri.host.lowercase()}:${uri.port.takeIf { it >= 0 } ?: 3306}"
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    return DorisCaptureConfig(
        url, required("DORIS_USER"), env["DORIS_PASSWORD"] ?: error("Missing DORIS_PASSWORD"),
        required("DORIS_CATALOG"), required("DORIS_SCHEMA"), output, "sha256:$fingerprint",
    )
}

/** The first-round Doris protocol is intentionally small and uses no session switching. */
internal fun collectDorisSchema(
    catalog: String,
    schema: String,
    query: (sql: String, resultColumns: Int) -> List<List<String?>>,
): List<CapturedObject> {
    fun quoted(name: String) = "`" + name.replace("`", "``") + "`"
    val namespace = "${quoted(catalog)}.${quoted(schema)}"
    fun inventory(): List<Pair<String, String>> {
        val rows = query("SHOW FULL TABLES FROM $namespace", 2)
        check(rows.size <= 10_000) { "Relation limit exceeded" }
        val objects = rows.map { row ->
            check(row.size == 2) { "Unexpected SHOW FULL TABLES result" }
            val name = row[0]?.takeIf { it.isNotEmpty() } ?: error("Missing relation name")
            name to (row[1]?.takeIf { it.isNotBlank() } ?: "UNKNOWN")
        }
        check(objects.map { it.first }.distinct().size == objects.size) { "Duplicate relation names" }
        return objects.sortedBy { it.first }
    }

    val before = inventory()
    var columnCount = 0
    val objects = before.map { (name, kind) ->
        val rows = query("SHOW COLUMNS FROM $namespace.${quoted(name)}", 3)
        check(rows.isNotEmpty()) { "No columns returned for an enumerated relation" }
        columnCount += rows.size
        check(columnCount <= 100_000) { "Column limit exceeded" }
        val columns = rows.map { row ->
            check(row.size == 3) { "Unexpected SHOW COLUMNS result" }
            CapturedColumn(
                row[0]?.takeIf { it.isNotEmpty() } ?: error("Missing column name"),
                row[1]?.takeIf { it.isNotBlank() } ?: error("Missing native column type"),
                when (row[2]?.uppercase()) { "YES" -> true; "NO" -> false; else -> null },
            )
        }
        check(columns.map { it.name }.distinct().size == columns.size) { "Duplicate column names" }
        CapturedObject(catalog, schema, name, kind, columns)
    }
    check(inventory() == before) { "Relation inventory changed during capture; retry" }
    return objects
}
