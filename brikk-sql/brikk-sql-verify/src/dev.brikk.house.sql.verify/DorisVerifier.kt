package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies SQL against Apache Doris's own FE parser (the Nereids ANTLR grammar), using the
 * vendored `fe-sql-parser` jar (vendor/lib/doris-fe-sql-parser-*.jar; see vendor/README.md
 * for provenance).
 *
 * ### Why reflection instead of a declared dependency
 * Doris does not (yet) publish `fe-sql-parser` to any public Maven repository, and Kotlin Toolchain
 * cannot depend on a local jar declaratively: its schema has no local-jar dependency kind and
 * it rejects non-https repositories (so a committed `file://` Maven layout doesn't work
 * either; `mavenLocal` would require a non-hermetic install step). Until the coordinate lands
 * on Maven Central, the jar is loaded reflectively through a [URLClassLoader] whose parent is
 * this class's loader — the ANTLR runtime (a declared Maven dependency) is resolved from the
 * parent, so only the Doris classes come from the vendored jar.
 *
 * ### Locating the jar
 * In order: the `brikk.doris.parser.jar` system property (for editors/embedders shipping the
 * jar themselves), then `org.apache.doris.sqlparser.DorisSqlParser` already on the classpath
 * (future Maven-published world), then a walk up from the working directory looking for
 * `vendor/lib/doris-fe-sql-parser-*.jar` (works from the repo root or any module directory —
 * i.e. for corpus gates and repo-local tooling). [SqlVerifiers] returns an unavailable verifier
 * with an actionable warning when none of these succeed.
 *
 * Cold start: one-time classloader creation plus ANTLR grammar class loading on the first
 * parse (~tens of ms). A single instance is safe to share across threads: `DorisSqlParser`
 * builds a fresh lexer/parser per call.
 */
class DorisVerifier private constructor(
    private val parser: Any,
    private val parseStatement: java.lang.reflect.Method,
    private val parseExpression: java.lang.reflect.Method,
) : SqlVerifier {
    override val engine: String = "doris"

    override fun verify(sql: String): VerifyResult = parse(parseStatement, sql)

    override fun verifyExpression(sql: String): VerifyResult = parse(parseExpression, sql)

    private fun parse(method: java.lang.reflect.Method, sql: String): VerifyResult = try {
        method.invoke(parser, sql)
        VerifyResult(accepted = true)
    } catch (e: InvocationTargetException) {
        val cause = e.targetException
        if (cause is Error) throw cause // don't swallow OOM/linkage errors
        val message = cause.message?.trim()
        val position = positionOf(message, sql)
        VerifyResult(
            accepted = false,
            error = if (isParseException(cause)) message
            else "${cause::class.java.simpleName}: $message",
            line = position?.first,
            col = position?.second,
        )
    }

    /** True when [cause] is (a subclass of) Doris's ParseException, checked by name across classloaders. */
    private fun isParseException(cause: Throwable): Boolean {
        var cls: Class<*>? = cause.javaClass
        while (cls != null) {
            if (cls.name == PARSE_EXCEPTION_CLASS) return true
            cls = cls.superclass
        }
        return false
    }

    /**
     * Doris appends `(line N, pos P)` to parse errors. Its code-point stream reports
     * `pos` in 0-based code points; brikk positions index Kotlin strings in UTF-16.
     */
    private fun positionOf(message: String?, sql: String): Pair<Int, Int>? {
        val m = POSITION.find(message ?: return null) ?: return null
        val line = m.groupValues[1].toInt()
        val codePointCol = m.groupValues[2].toInt()
        val lineText = sql.lineSequence().elementAtOrNull(line - 1)
        val utf16Col = if (lineText == null) {
            codePointCol
        } else {
            val codePoints = lineText.codePointCount(0, lineText.length)
            lineText.offsetByCodePoints(0, codePointCol.coerceAtMost(codePoints))
        }
        return line to utf16Col + 1
    }

    companion object {
        private const val PARSER_CLASS = "org.apache.doris.sqlparser.DorisSqlParser"
        private const val PARSE_EXCEPTION_CLASS = "org.apache.doris.nereids.exceptions.ParseException"
        private const val JAR_PROPERTY = "brikk.doris.parser.jar"
        private val POSITION = Regex("""line\s+(\d+),\s*pos\s+(\d+)""")
        private val JAR_CLASSES = ConcurrentHashMap<Path, Class<*>>()

        private data class Creation(val verifier: DorisVerifier? = null, val reason: String? = null)

        /** Returns a verifier, or null when the Doris parser jar cannot be located/loaded. */
        fun createOrNull(): DorisVerifier? = create().verifier

        internal fun createOrUnavailable(): SqlVerifier {
            val creation = create()
            return creation.verifier ?: UnavailableDorisVerifier(
                creation.reason ?: "Doris verification was not performed: parser unavailable.",
            )
        }

        private fun create(): Creation {
            val loaded = loadParserClass()
            val parserClass = loaded.first ?: return Creation(reason = loaded.second)
            return try {
                val parser = parserClass.getDeclaredConstructor().newInstance()
                Creation(verifier = DorisVerifier(
                    parser = parser,
                    parseStatement = parserClass.getMethod("parseStatement", String::class.java),
                    parseExpression = parserClass.getMethod("parseExpression", String::class.java),
                ))
            } catch (e: InvocationTargetException) {
                val cause = e.targetException
                if (cause is Error) throw cause
                Creation(reason = unavailableReason("could not construct parser", cause))
            } catch (e: Exception) {
                Creation(reason = unavailableReason("could not construct parser", e))
            }
        }

        private fun loadParserClass(): Pair<Class<*>?, String?> {
            val parent = DorisVerifier::class.java.classLoader
            // 1) Explicit jar path (editors/embedders).
            System.getProperty(JAR_PROPERTY)?.let { prop ->
                val path = try {
                    Paths.get(prop)
                } catch (e: Exception) {
                    return null to unavailableReason("invalid -D$JAR_PROPERTY path '$prop'", e)
                }
                if (!Files.isRegularFile(path)) {
                    return null to "Doris verification was not performed: -D$JAR_PROPERTY='$prop' is not a regular file."
                }
                return try {
                    loadFromJar(path, parent) to null
                } catch (e: Exception) {
                    null to unavailableReason("could not load -D$JAR_PROPERTY='$prop'", e)
                }
            }
            // 2) Already on the classpath (once Doris publishes the coordinate).
            try {
                return Class.forName(PARSER_CLASS, false, parent) to null
            } catch (_: ClassNotFoundException) {
                // Try the repo-local jar next.
            } catch (e: Exception) {
                return null to unavailableReason("could not inspect the application classpath", e)
            }
            // 3) Repo-local vendored jar, found by walking up from the working directory.
            val jar = try {
                findVendoredJar()
            } catch (e: Exception) {
                return null to unavailableReason("could not search for the vendored parser jar", e)
            } ?: return null to "Doris verification was not performed: parser class not found; " +
                "set -D$JAR_PROPERTY=/path/to/doris-fe-sql-parser.jar."
            return try {
                loadFromJar(jar, parent) to null
            } catch (e: Exception) {
                null to unavailableReason("could not load vendored parser jar '$jar'", e)
            }
        }

        private fun loadFromJar(jar: Path, parent: ClassLoader?): Class<*> {
            val real = jar.toRealPath()
            JAR_CLASSES[real]?.let { return it }
            synchronized(JAR_CLASSES) {
                JAR_CLASSES[real]?.let { return it }
                val loader = URLClassLoader(arrayOf(real.toUri().toURL()), parent)
                try {
                    return Class.forName(PARSER_CLASS, false, loader).also { JAR_CLASSES[real] = it }
                } catch (error: Throwable) {
                    try {
                        loader.close()
                    } catch (closeError: Exception) {
                        error.addSuppressed(closeError)
                    }
                    throw error
                }
            }
        }

        private fun findVendoredJar(): Path? {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null) {
                val lib = dir.resolve("vendor").resolve("lib")
                if (Files.isDirectory(lib)) {
                    Files.newDirectoryStream(lib, "doris-fe-sql-parser-*.jar").use { stream ->
                        val jars = stream.toList().sortedBy { it.fileName.toString() }
                        if (jars.size > 1) {
                            throw IllegalStateException(
                                "multiple Doris parser jars found in $lib; set -D$JAR_PROPERTY explicitly",
                            )
                        }
                        jars.firstOrNull()?.let { return it }
                    }
                }
                dir = dir.parent
            }
            return null
        }

        private fun unavailableReason(action: String, error: Throwable): String =
            "Doris verification was not performed: $action (${error::class.simpleName}: " +
                "${error.message ?: "no detail"})."
    }
}

private class UnavailableDorisVerifier(private val reason: String) : SqlVerifier {
    override val engine: String = "doris"
    override fun verify(sql: String): VerifyResult = unavailable()
    override fun verifyExpression(sql: String): VerifyResult = unavailable()
    private fun unavailable() = VerifyResult(accepted = false, verified = false, warning = reason)
}
