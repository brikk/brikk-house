package dev.brikk.house.sql.verify

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Verifies SQL against Apache Doris's own FE parser (the Nereids ANTLR grammar), using the
 * vendored `fe-sql-parser` jar (vendor/lib/doris-fe-sql-parser-*.jar; see vendor/README.md
 * for provenance).
 *
 * ### Why reflection instead of a declared dependency
 * Doris does not (yet) publish `fe-sql-parser` to any public Maven repository, and Amper 0.11
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
 * i.e. for corpus gates and repo-local tooling). Use [createOrNull] (what [SqlVerifiers]
 * calls) to get null instead of an exception when none of these succeed.
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
        val position = positionOf(message)
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
     * Doris appends `(line N, pos P)` to parse errors; `pos` is ANTLR's 0-based
     * charPositionInLine, normalized here to a 1-based column.
     */
    private fun positionOf(message: String?): Pair<Int, Int>? {
        val m = POSITION.find(message ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt() + 1
    }

    companion object {
        private const val PARSER_CLASS = "org.apache.doris.sqlparser.DorisSqlParser"
        private const val PARSE_EXCEPTION_CLASS = "org.apache.doris.nereids.exceptions.ParseException"
        private const val JAR_PROPERTY = "brikk.doris.parser.jar"
        private val POSITION = Regex("""line\s+(\d+),\s*pos\s+(\d+)""")

        /** Returns a verifier, or null when the Doris parser jar cannot be located/loaded. */
        fun createOrNull(): DorisVerifier? {
            val parserClass = loadParserClass() ?: return null
            return runCatching {
                val parser = parserClass.getDeclaredConstructor().newInstance()
                DorisVerifier(
                    parser = parser,
                    parseStatement = parserClass.getMethod("parseStatement", String::class.java),
                    parseExpression = parserClass.getMethod("parseExpression", String::class.java),
                )
            }.getOrNull()
        }

        private fun loadParserClass(): Class<*>? {
            val parent = DorisVerifier::class.java.classLoader
            // 1) Explicit jar path (editors/embedders).
            System.getProperty(JAR_PROPERTY)?.let { prop ->
                val path = Paths.get(prop)
                if (Files.isRegularFile(path)) {
                    return runCatching { loadFromJar(path, parent) }.getOrNull()
                }
            }
            // 2) Already on the classpath (once Doris publishes the coordinate).
            runCatching { return Class.forName(PARSER_CLASS, false, parent) }
            // 3) Repo-local vendored jar, found by walking up from the working directory.
            val jar = findVendoredJar() ?: return null
            return runCatching { loadFromJar(jar, parent) }.getOrNull()
        }

        private fun loadFromJar(jar: Path, parent: ClassLoader?): Class<*> {
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), parent)
            return Class.forName(PARSER_CLASS, false, loader)
        }

        private fun findVendoredJar(): Path? {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null) {
                val lib = dir.resolve("vendor").resolve("lib")
                if (Files.isDirectory(lib)) {
                    Files.newDirectoryStream(lib, "doris-fe-sql-parser-*.jar").use { stream ->
                        stream.firstOrNull()?.let { return it }
                    }
                }
                dir = dir.parent
            }
            return null
        }
    }
}
