package dev.brikk.house.sql.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.compiler.analysis.SqlPiece
import dev.brikk.house.sql.compiler.fir.TemplateScope
import dev.brikk.house.sql.shape.SqlFragment
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-process end-to-end tests via kctfork (kotlin-compile-testing fork, K2, Kotlin 2.4).
 * The plugin is registered in-memory; the runtime module (`Rel`, `Shape`, `Sql`, ...) is on
 * the inherited classpath.
 */
@OptIn(ExperimentalCompilerApi::class)
class BrikkSqlPluginTest {

    @Test
    fun `same named traits in different packages and import aliases remain distinct`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            import dev.brikk.house.sql.runtime.Rel as R
            import left.Row as LeftRow
            import right.Row as RightRow
            @BrikkSql fun leftSource() = Sql.postgres("SELECT 1 AS id")
            @BrikkSql fun rightSource() = Sql.postgres("SELECT 'x' AS code")
            @BrikkSql fun leftPipe(src: R<LeftRow>) = Sql.postgres("SELECT id FROM src()")
            @BrikkSql fun rightPipe(src: R<RightRow>) = Sql.postgres("SELECT code FROM src()")
            fun left() = leftPipe(leftSource())
            fun right() = rightPipe(rightSource())
        """.trimIndent(), extraSources = listOf(
            SourceFile.kotlin("left.kt", "package left; import dev.brikk.house.sql.runtime.*; @BrikkTrait interface Row : Partial { val id: Int }"),
            SourceFile.kotlin("right.kt", "package right; import dev.brikk.house.sql.runtime.*; @BrikkTrait interface Row : Partial { val code: String }"),
        ))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("int", result.classLoader.loadClass("demo.LeftPipeOut").getMethod("getId").returnType.name)
        assertEquals("java.lang.String", result.classLoader.loadClass("demo.RightPipeOut").getMethod("getCode").returnType.name)
    }

    @Test
    fun `equally named generated inputs in separate packages do not cross resolve`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun combine(a: Rel<left.SourceOut>, b: Rel<right.SourceOut>) =
                Sql.postgres("SELECT a.id, b.code FROM a() CROSS JOIN b()")
            fun report() = combine(left.source(), right.source())
        """.trimIndent(), extraSources = listOf(
            SourceFile.kotlin("left.kt", "package left; import dev.brikk.house.sql.runtime.*; @BrikkSql fun source() = Sql.postgres(\"SELECT 1 AS id\")"),
            SourceFile.kotlin("right.kt", "package right; import dev.brikk.house.sql.runtime.*; @BrikkSql fun source() = Sql.postgres(\"SELECT 'x' AS code\")"),
        ))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val out = result.classLoader.loadClass("demo.CombineOut")
        assertEquals("int", out.getMethod("getId").returnType.name)
        assertEquals("java.lang.String", out.getMethod("getCode").returnType.name)
    }

    @Test
    fun `type aliases and imported scalar aliases work before resolution`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            import java.time.Instant as Stamp
            @BrikkTrait interface Event : Partial { val event_at: Stamp }
            typealias Rows = Rel<Event>
            @BrikkSql fun source() = Sql.postgres("SELECT event_at FROM public.events")
            @BrikkSql fun keep(src: Rows) = Sql.postgres("SELECT event_at FROM src()")
            fun report() = keep(source())
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `generic relation aliases follow their expanded argument not the first written argument`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface Id : Partial { val id: Int }
            @BrikkTrait interface Text : Partial { val code: String }
            typealias PickSecond<A, B> = Rel<B>
            @BrikkSql fun source() = Sql.postgres("SELECT 'x' AS code")
            @BrikkSql fun keep(src: PickSecond<Id, Text>) = Sql.postgres("SELECT code FROM src()")
            fun report() = keep(source())
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("java.lang.String", result.classLoader.loadClass("demo.KeepOut").getMethod("getCode").returnType.name)
    }

    @Test
    fun `a user String class is not treated as kotlin String in trait resolution`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface CustomText : Partial { val text: other.String }
            @BrikkSql fun source() = Sql.postgres("SELECT 'x' AS text")
            fun require(src: Rel<CustomText>) = 1
            val bad = require(source())
        """.trimIndent(), extraSources = listOf(SourceFile.kotlin("other.kt", "package other; class String")))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Argument type mismatch")
    }

    @Test
    fun `an unrelated class named Rel is a scalar parameter not a relation slot`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.BrikkSql
            import dev.brikk.house.sql.runtime.Sql
            import other.Rel
            @BrikkSql fun scalar(value: Rel) = Sql.postgres("SELECT ${'$'}value AS x")
        """.trimIndent(), extraSources = listOf(SourceFile.kotlin("other.kt", "package other; class Rel")))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `inherited traits resolve their parent and property types through imports`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            import other.Event as ImportedEvent
            @BrikkTrait interface Child : ImportedEvent { val extra: Int }
            @BrikkSql fun source() = Sql.postgres("SELECT event_at, 1 AS extra FROM public.events")
            fun require(src: Rel<Child>) = 1
            val good = require(source())
        """.trimIndent(), extraSources = listOf(SourceFile.kotlin("other.kt", """
            package other
            import dev.brikk.house.sql.runtime.*
            import java.time.Instant as Timestamp
            @BrikkTrait interface Event : Partial { val event_at: Timestamp }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `nullable results cannot satisfy a non null trait but may satisfy nullable traits`() {
        val prelude = """
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface Required : Partial { val id: Int }
            @BrikkTrait interface Optional : Partial { val id: Int? }
            @BrikkSql fun absent() = Sql.postgres("SELECT CAST(NULL AS INT) AS id")
            fun require(src: Rel<Required>) = 1
            fun optional(src: Rel<Optional>) = 1
        """.trimIndent()
        val bad = compile(prelude + "\nval bad = require(absent())")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, bad.exitCode)
        assertContains(bad.messages, "Argument type mismatch")
        val good = compile(prelude + "\nval good = optional(absent())")
        assertEquals(KotlinCompilation.ExitCode.OK, good.exitCode, good.messages)
    }

    @Test
    fun `nullable trait inputs remain nullable after a generic identity pipe`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface Optional : Partial { val id: Int? }
            @BrikkTrait interface Required : Partial { val id: Int }
            @BrikkSql fun absent() = Sql.postgres("SELECT CAST(NULL AS INT) AS id")
            @BrikkSql fun <T: Optional> identity(src: Rel<T>) = Sql.postgres("SELECT id FROM src()")
            fun require(src: Rel<Required>) = 1
            val bad = require(identity(absent()))
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Argument type mismatch")
    }

    @Test
    fun `named reordered mixed and omitted default arguments refine the correct Rel input`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface Id : Partial { val id: Int }
            @BrikkTrait interface HasCode : Partial { val code: String }
            @BrikkSql fun source() = Sql.postgres("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun <T: Id> stamp(mark: Int = 7, src: Rel<T>) = Sql.postgres("FROM src() |> EXTEND ${'$'}mark AS mark")
            fun code(src: Rel<HasCode>) = 1
            val reordered = code(stamp(src = source(), mark = 1))
            val omitted = code(stamp(src = source()))
            val mixed = code(stamp(1, src = source()))
            val namedThenPositional = code(stamp(mark = 1, source()))
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `Shape marker bounds preserve the concrete columns of generic identity calls`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface NeedsId : Partial { val id: Int }
            @BrikkSql fun source() = Sql.postgres("SELECT 1 AS id")
            @BrikkSql fun <T: Shape> identity(src: Rel<T>) = Sql.postgres("FROM src()")
            fun require(src: Rel<NeedsId>) = 1
            val good = require(identity(source()))
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `qualified references cannot borrow columns from unrelated catalog tables`() {
        val schema = File.createTempFile("brikk-scopes", ".sql").apply {
            deleteOnExit()
            writeText("CREATE TABLE a (id INT); CREATE TABLE unrelated (secret INT);")
        }
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun bad() = Sql.postgres("SELECT ghost.id FROM a")
            @BrikkSql fun alsoBad() = Sql.postgres("SELECT secret FROM a")
        """.trimIndent(), schema = schema.absolutePath)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "[BRIKK_SQL]")
        assertContains(result.messages, "ghost")
        assertContains(result.messages, "secret")
    }

    @Test
    fun `CTE projections correlated references and aliases are checked by SQL scope`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun cte() = Sql.postgres("WITH q AS (SELECT event_id AS chosen FROM public.events) SELECT chosen FROM q")
            @BrikkSql fun correlated() = Sql.postgres("SELECT e.event_id FROM public.events e WHERE EXISTS (SELECT 1 FROM public.events d WHERE d.event_id = e.event_id)")
            @BrikkSql fun alias() = Sql.postgres("SELECT event_id + 1 AS next_id FROM public.events ORDER BY next_id")
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `columns hidden by a CTE projection cannot leak from the catalog`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun bad() = Sql.postgres("WITH q AS (SELECT event_id AS chosen FROM public.events) SELECT event_id FROM q")
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "event_id")
        assertContains(result.messages, "[BRIKK_SQL]")
    }

    @Test
    fun `quoted generated column identity survives input shape conversion`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun source() = Sql.postgres("SELECT 1 AS \"Mixed\"")
            @BrikkSql fun keep(src: Rel<SourceOut>) = Sql.postgres("SELECT src.\"Mixed\" FROM src()")
            fun report() = keep(source())
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("int", result.classLoader.loadClass("demo.SourceOut").getMethod("getMixed").returnType.name)
        // SQL does not currently prove the quoted slot projection's nullability;
        // retain the numeric type without inventing a non-null guarantee.
        assertEquals("java.lang.Integer", result.classLoader.loadClass("demo.KeepOut").getMethod("getMixed").returnType.name)
    }

    @Test
    fun `dotted placeholders are diagnosed instead of binding only their root`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            data class Filter(val since: Int)
            @BrikkSql fun bad(filter: Filter) = Sql.postgres("SELECT :filter.since AS since")
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "dotted placeholder")
        assertContains(result.messages, "local val")
    }

    @Test
    fun `SQL diagnostics point at decoded escaped and trimmed literal offsets`() {
        val escaped = "package demo\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun bad() = Sql.postgres(\"SELECT event_id FROM public.events WHERE \\n evnt_at > 1\")"
        val raw = "package demo\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun bad() = Sql.postgres(" + q + "\n    |SELECT event_id FROM public.events\n    |WHERE evnt_at > 1\n" + q + ".trimMargin())"
        for (source in listOf(escaped, raw)) {
            val result = compile(source)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
            val before = source.substring(0, source.indexOf("evnt_at"))
            val line = before.count { it == '\n' } + 1
            val col = before.substringAfterLast('\n').length + 1
            assertContains(result.messages, ":$line:$col [BRIKK_SQL]", message = result.messages)
        }
    }

    @Test
    fun `unbound parameter diagnostics map past Kotlin interpolation and Unicode`() {
        val source = "package demo\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun bad(n: Int) = Sql.postgres(\"SELECT '😀' AS note, ${'$'}n AS id, :missing AS x\")"
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val before = source.substring(0, source.indexOf(":missing"))
        val col = before.substringAfterLast('\n').length + 1
        assertContains(result.messages, ":3:$col [BRIKK_SQL]", message = result.messages)
    }

    @Test
    fun `parse errors and Unicode escaped column names underline their authored token`() {
        val broken = "package demo\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun bad() = Sql.postgres(\"SELECT 1 +\")"
        val escaped = "package demo\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun bad() = Sql.postgres(\"SELECT event_id FROM public.events WHERE \\u0065vnt_at > 1\")"
        for ((source, token) in listOf(broken to "+", escaped to "\\u0065")) {
            val result = compile(source)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
            val before = source.substring(0, source.indexOf(token))
            val col = before.substringAfterLast('\n').length + 1
            assertContains(result.messages, ":3:$col [BRIKK_SQL]", message = result.messages)
        }
    }

    /** The schema cache: plain DDL, the "as if it existed" table for the demo. */
    private val schemaFile: File = File.createTempFile("brikk-schema", ".sql").apply {
        deleteOnExit()
        writeText(
            """
            CREATE TABLE public.events (
              event_id BIGINT NOT NULL,
              event_at TIMESTAMPTZ NOT NULL,
              tenant TEXT NOT NULL,
              payload JSONB
            );
            """.trimIndent(),
        )
    }

    private fun compile(
        source: String,
        debug: Boolean = false,
        schema: String = schemaFile.absolutePath,
        workingDir: File? = null,
        extraSources: List<SourceFile> = emptyList(),
    ): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("main.kt", source)) + extraSources
            compilerPluginRegistrars = listOf(BrikkSqlCompilerPluginRegistrar())
            commandLineProcessors = listOf(BrikkSqlCommandLineProcessor())
            pluginOptions = buildList {
                add(PluginOption(BrikkSqlNames.PLUGIN_ID, "schema", schema))
                add(PluginOption(BrikkSqlNames.PLUGIN_ID, "defaultSchema", "public"))
                if (debug) add(PluginOption(BrikkSqlNames.PLUGIN_ID, "debug", "true"))
            }
            if (workingDir != null) this.workingDir = workingDir
            inheritClassPath = true
            verbose = false
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private val simpleSource = """
        package demo
        import dev.brikk.house.sql.runtime.*
        import java.time.Instant

        @BrikkSql
        fun recent(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= :start")
    """.trimIndent()

    @Test
    fun `outer joins generate nullable Kotlin properties only on null supplying sides`() {
        val schema = File.createTempFile("brikk-join-schema", ".sql").apply {
            deleteOnExit()
            writeText("CREATE TABLE public.a (x INT NOT NULL); CREATE TABLE public.b (x INT NOT NULL);")
        }
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun leftJoined() = Sql.postgres("SELECT a.x AS lx, b.x AS rx FROM public.a LEFT JOIN public.b ON a.x = b.x")
            @BrikkSql
            fun rightJoined() = Sql.postgres("SELECT a.x AS lx, b.x AS rx FROM public.a RIGHT JOIN public.b ON a.x = b.x")
            @BrikkSql
            fun fullJoined() = Sql.postgres("SELECT a.x AS lx, b.x AS rx FROM public.a FULL JOIN public.b ON a.x = b.x")
        """.trimIndent(), schema = schema.absolutePath)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        for ((name, types) in listOf(
            "LeftJoinedOut" to listOf("int", "java.lang.Integer"),
            "RightJoinedOut" to listOf("java.lang.Integer", "int"),
            "FullJoinedOut" to listOf("java.lang.Integer", "java.lang.Integer"),
        )) {
            val out = result.classLoader.loadClass("demo.$name")
            assertEquals(types, listOf("getLx", "getRx").map { out.getMethod(it).returnType.name }, name)
        }
    }

    @Test
    fun `set operations generate reconciled numeric and nullable Kotlin properties`() {
        val result = compile("""
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun widened() = Sql.postgres("SELECT 1 AS x UNION ALL SELECT CAST(2147483648 AS BIGINT) AS x")
            @BrikkSql
            fun nullableUnion() = Sql.postgres("SELECT 1 AS x UNION ALL SELECT NULL AS x")
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("long", result.classLoader.loadClass("demo.WidenedOut").getMethod("getX").returnType.name)
        assertEquals("java.lang.Integer", result.classLoader.loadClass("demo.NullableUnionOut").getMethod("getX").returnType.name)
    }

    @Test
    fun `select star preserves partial inputs while explicit projections close them`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkTrait
            interface HasEventId : Partial { val event_id: Long }

            @BrikkSql
            fun star(src: Rel<HasEventId>) = Sql.postgres("FROM src() |> SELECT *")

            @BrikkSql
            fun projected(src: Rel<HasEventId>) = Sql.postgres("FROM src() |> SELECT event_id")

            @BrikkSql
            fun aggregated(src: Rel<HasEventId>) = Sql.postgres("FROM src() |> AGGREGATE COUNT(*) AS n")

            @BrikkSql
            fun source() = Sql.postgres("FROM public.events")

            @BrikkSql
            fun closedStar(src: Rel<SourceOut>) = Sql.postgres("FROM src() |> SELECT *")
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        fun supers(name: String) = result.classLoader.loadClass("demo.$name").interfaces.map { it.simpleName }.toSet()
        assertContains(supers("StarOut"), "Partial")
        assertTrue("Shape" !in supers("StarOut"))
        assertContains(supers("ProjectedOut"), "Shape")
        assertContains(supers("AggregatedOut"), "Shape")
        assertContains(supers("ClosedStarOut"), "Shape")
    }

    @Test
    fun `functions that map to the same generated output type are rejected`() {
        val sources = listOf(
            """
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun rows(n: Long) = Sql.postgres("SELECT CAST(:n AS BIGINT) AS n")
            @BrikkSql
            fun rows(s: String) = Sql.postgres("SELECT CAST(:s AS TEXT) AS s")
            """.trimIndent() to "demo.RowsOut",
            """
            package demo
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun report() = Sql.postgres("SELECT 1 AS n")
            @BrikkSql
            fun Report() = Sql.postgres("SELECT 'x' AS s")
            """.trimIndent() to "demo.ReportOut",
        )

        for ((source, outputType) in sources) {
            val result = compile(source)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertContains(result.messages, "multiple @BrikkSql functions generate output type '$outputType'")
            assertContains(result.messages, "rename them so each output type is unique")
        }
    }

    @Test
    fun `nested output shape is not substituted by a top-level generated output`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkTrait
            interface HasId : Partial { val id: Int }

            object Domain {
                interface EventsOut : Shape, HasId
            }

            @BrikkTrait
            interface HasSecret : Partial { val secret: String }

            @BrikkSql
            fun events() = Sql.postgres("SELECT 1 AS id, 'hidden' AS secret")

            @BrikkSql
            fun <T : HasId> identity(src: Rel<T>) = Sql.postgres("FROM ${'$'}src()")

            @BrikkSql
            fun reveal(src: Rel<HasSecret>) = Sql.postgres("FROM ${'$'}src() |> SELECT secret")

            fun nested(): Rel<Domain.EventsOut> = Rel("SELECT 1 AS id", "postgres")
            fun leak() = reveal(identity(nested()))
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "Argument type mismatch")
        assertContains(result.messages, "Rel<HasSecret>")
    }

    @Test
    fun `computed const sql interpolation is rejected before IR folding`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            const val COLS = "CAST(1 AS BIGINT)" + " AS id"

            @BrikkSql
            fun q() = Sql.postgres("SELECT ${'$'}COLS")
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "computed const val 'COLS' cannot be interpolated as SQL; use a literal initializer")
    }

    @Test
    fun `literal const sql interpolation stays consistent between FIR and IR`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            const val COLS = "CAST(1 AS BIGINT) AS id"

            @BrikkSql
            fun q() = Sql.postgres("SELECT ${'$'}COLS")

            fun column(row: QOut): Long = row.id
            fun storedSql(): String = q().sql
            fun bindingNames(): Set<String> = q().bindings().keys
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("long", result.classLoader.loadClass("demo.QOut").getMethod("getId").returnType.name)
        val main = result.classLoader.loadClass("demo.MainKt")
        assertEquals("SELECT CAST(1 AS BIGINT) AS id", main.getMethod("storedSql").invoke(null))
        assertEquals(emptySet<String>(), main.getMethod("bindingNames").invoke(null))
    }

    // ------------------------------------------------------------------ schema file resolution
    //
    // The IDE runs the plugin with a working directory that is not the project root, and re-runs
    // it on every keystroke; a thrown exception there is a resolve failure of the whole
    // declaration, reported from every highlighting pass. So: never throw, always diagnose.

    @Test
    fun `missing schema file is a diagnostic not an exception`() {
        val result = compile(simpleSource, schema = "does/not/exist/events.sql")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] schema file not found: 'does/not/exist/events.sql'")
    }

    @Test
    fun `relative schema path resolves against the source file's ancestors when cwd differs`() {
        // kctfork writes main.kt to <workingDir>/sources/; the schema sits beside that directory,
        // and the relative option does not resolve against the JVM's own working directory.
        val projectDir = kotlin.io.path.createTempDirectory("brikk-project").toFile().apply { deleteOnExit() }
        val schema = File(projectDir, "schemas/events.sql").apply { parentFile.mkdirs(); writeText(schemaFile.readText()) }
        assertTrue(!File("schemas/events.sql").exists(), "test precondition: relative path must not resolve from cwd")

        val result = compile(simpleSource, schema = "schemas/events.sql", workingDir = projectDir)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        schema.delete()
    }

    @Test
    fun `unreadable schema content is a diagnostic not an exception`() {
        val broken = File.createTempFile("brikk-broken", ".sql").apply { deleteOnExit(); writeText("CREATE TABLE (((") }
        val result = compile(simpleSource, schema = broken.absolutePath)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] schema file '${broken.path}' could not be loaded")
    }

    // ------------------------------------------------------------------ the demo pipeline

    private val q = "\"\"\""

    /**
     * Three steps, option C (no return types written):
     *  1. catalog-bound source with a date-range parameter
     *  2. generic trait pipe: anything with a `payload` gets JSON fields extracted
     *  3. terminating pipe over a Partial input that closes the shape
     */
    private val pipeline = """
        package demo

        import dev.brikk.house.sql.runtime.*
        import java.time.Instant

        @BrikkTrait
        interface HasPayload : Partial { val payload: String? }

        @BrikkTrait
        interface LoginInput : Partial {
            val user_id: String?
            val action: String?
            val event_at: Instant
        }

        @BrikkSql
        fun eventsInRange(start: Instant, end: Instant) = Sql.postgres($q
            FROM public.events
            |> WHERE event_at >= ${'$'}start AND event_at < ${'$'}end
        $q)

        @BrikkSql
        fun <T : HasPayload> extractEvent(src: Rel<T>) = Sql.postgres($q
            FROM ${'$'}src()
            |> EXTEND payload->>'user_id' AS user_id,
                      payload->>'action' AS action,
                      (payload->>'duration_ms')::BIGINT AS duration_ms
        $q)

        @BrikkSql
        fun loginDaily(events: Rel<LoginInput>) = Sql.postgres($q
            FROM ${'$'}events()
            |> WHERE action = 'login'
            |> AGGREGATE count(*) AS logins, max(event_at) AS last_login
               GROUP BY user_id, CAST(event_at AS DATE) AS day
        $q)

        fun report(start: Instant, end: Instant) = loginDaily(extractEvent(eventsInRange(start, end)))

        fun renderReport(): String = report(Instant.EPOCH, Instant.EPOCH).render()

        // Column access through the generated shape types must type-check.
        fun columns(src: EventsInRangeOut, out: LoginDailyOut): String =
            src.event_id.toString() + src.tenant + src.payload + out.user_id + out.logins.toString()
    """.trimIndent()

    @Test
    fun `three-step pipeline compiles with inferred shape types and renders sql`() {
        val result = compile(pipeline, debug = true)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val mainKt = result.classLoader.loadClass("demo.MainKt")
        val sql = mainKt.getMethod("renderReport").invoke(null) as String
        // Source-preserving lowering retains the template's whitespace and bind style;
        // assert CTE structure instead of requiring a canonical single-line prefix.
        val fragment = SqlFragment(sql, "postgres")
        val with = fragment.ast.args["with_"] as With
        assertEquals(listOf("s0", "s1", "s2"), with.expressionsArg.filterIsInstance<CTE>().map { it.alias })
        assertContains(sql, "SELECT * FROM public.events")
        assertContains(sql, "WHERE event_at >= :start AND event_at < :end")
        assertEquals(setOf("start", "end"), fragment.scalarParams.mapNotNull { it.name }.toSet())
        assertContains(sql, "FROM s0")
        assertContains(sql, "payload->>'user_id' AS user_id")
        assertContains(sql, "WHERE action = 'login'")
        assertContains(sql, "GROUP BY user_id, day")
        assertTrue(sql.endsWith(" SELECT * FROM s2"), sql)

        // Generated shape for the source: full Shape, satisfies HasPayload, typed getters.
        val srcOut = result.classLoader.loadClass("demo.EventsInRangeOut")
        assertTrue(srcOut.isInterface)
        val srcSupers = srcOut.interfaces.map { it.name }.toSet()
        assertContains(srcSupers, "dev.brikk.house.sql.runtime.Shape")
        assertContains(srcSupers, "demo.HasPayload")
        assertEquals("long", srcOut.getMethod("getEvent_id").returnType.name)
        assertEquals("java.time.Instant", srcOut.getMethod("getEvent_at").returnType.name)
        assertEquals("java.lang.String", srcOut.getMethod("getPayload").returnType.name)

        // Generic pipe's declared output is only a Partial (bound columns + additions).
        val extOut = result.classLoader.loadClass("demo.ExtractEventOut")
        assertContains(extOut.interfaces.map { it.name }.toSet(), "dev.brikk.house.sql.runtime.Partial")
        assertEquals(setOf("getPayload", "getUser_id", "getAction", "getDuration_ms"), extOut.methods.map { it.name }.toSet())

        // Terminal pipe closes the shape.
        val loginOut = result.classLoader.loadClass("demo.LoginDailyOut")
        assertContains(loginOut.interfaces.map { it.name }.toSet(), "dev.brikk.house.sql.runtime.Shape")
        assertEquals(setOf("getUser_id", "getDay", "getLogins", "getLast_login"), loginOut.methods.map { it.name }.toSet())
        assertEquals("java.time.LocalDate", loginOut.getMethod("getDay").returnType.name)

        // Option C: no return type was written, yet `report` is typed by the named shape.
        val report = mainKt.getMethod("report", java.time.Instant::class.java, java.time.Instant::class.java)
        assertEquals("dev.brikk.house.sql.runtime.Rel<demo.LoginDailyOut>", report.genericReturnType.typeName)

        // The call-site local shape of `extractEvent(eventsInRange(..))` carries the real input
        // columns + the extracted ones, and therefore satisfies LoginInput (that is what let
        // `loginDaily(...)` type-check) as well as HasPayload.
        val localShape = result.compiledClassAndResourceFiles
            .map { it.name }
            .first { it.contains("ExtractEventOut$") && it.endsWith(".class") }
            .removeSuffix(".class")
        val local = result.classLoader.loadClass("demo.$localShape")
        val localSupers = local.interfaces.map { it.name }.toSet()
        assertContains(localSupers, "dev.brikk.house.sql.runtime.Shape")
        assertContains(localSupers, "demo.LoginInput")
        assertContains(localSupers, "demo.HasPayload")
        assertEquals(
            setOf("getEvent_id", "getEvent_at", "getTenant", "getPayload", "getUser_id", "getAction", "getDuration_ms"),
            local.declaredMethods.map { it.name }.toSet(),
        )
    }

    /**
     * Known limitation (documented in RESEARCH-fir-refinement-and-generation.md): the call-site
     * local shape of a generic pipe cannot escape through a *plain* helper function with an
     * inferred return type — Kotlin approximates the local class to its first supertype
     * (`Shape`), dropping the trait conformance. Chain inline, or make the helper a @BrikkSql
     * pipe (whose output is a named, non-local shape).
     */
    @Test
    fun `local shape does not survive a plain helper with inferred return type`() {
        val result = compile(
            pipeline + """

            fun mid(start: Instant, end: Instant) = extractEvent(eventsInRange(start, end))
            fun useMid(start: Instant, end: Instant) = loginDaily(mid(start, end))
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "actual type is 'Rel<Shape>', but 'Rel<LoginInput>' was expected")
    }

    @Test
    fun `a plain inferred helper returning a local SQL shape receives an actionable hint`() {
        val result = compile(pipeline + "\nfun mid(start: Instant, end: Instant) = extractEvent(eventsInRange(start, end))")
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertContains(result.messages, "local SQL shape escape")
        assertContains(result.messages, "chain inline")
    }

    @Test
    fun `unknown column is a frontend error`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*
            import java.time.Instant

            @BrikkSql
            fun bad(start: Instant) = Sql.postgres("FROM public.events |> WHERE evnt_at >= :start")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] Column 'evnt_at' could not be resolved")
    }

    @Test
    fun `unbound placeholder is a frontend error`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*
            import java.time.Instant

            @BrikkSql
            fun bad(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= :since")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "placeholder ':since' does not match any parameter (declared: start)")
    }

    @Test
    fun `sql parse error is a frontend error`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun bad() = Sql.postgres("FROM public.events |> WHERE >= 1")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL]")
    }

    @Test
    fun `trait not satisfied by input is a type error at the call site`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkTrait
            interface NeedsAmount : Partial { val amount: Long }

            @BrikkSql
            fun events() = Sql.postgres("FROM public.events")

            @BrikkSql
            fun sumAmount(src: Rel<NeedsAmount>) = Sql.postgres("FROM src() |> AGGREGATE sum(amount) AS total")

            val r = sumAmount(events())
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "Argument type mismatch")
    }

    @Test
    fun `non-constant sql is a frontend error`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun bad(fragment: String) = Sql.postgres(fragment)
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "must be a string literal or template")
    }

    @Test
    fun `sql outside a BrikkSql function is a frontend error`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            fun plain() = Sql.postgres("FROM public.events")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "must be the body of a function annotated @BrikkSql")
    }

    // ------------------------------------------------------------------ explicit slots

    private val traitsPrelude = """
        package demo
        import dev.brikk.house.sql.runtime.*
        import java.time.Instant

        @BrikkTrait
        interface LoginInput : Partial {
            val user_id: String?
            val action: String?
            val event_at: Instant
        }
    """.trimIndent()

    @Test
    fun `rel parameter never referenced as a slot is a frontend error`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun logins(src: Rel<LoginInput>) = Sql.postgres("FROM public.events |> WHERE action = 'login'")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] parameter 'src' is never used as a source - write 'FROM src()'")
    }

    @Test
    fun `slot without a matching rel parameter is a frontend error`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun logins(src: Rel<LoginInput>) = Sql.postgres("FROM events() |> WHERE action = 'login'")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] 'FROM events()' - no Rel parameter named 'events' (Rel parameters: src)")
    }

    @Test
    fun `rel parameter named like a dialect function gets a rename hint`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun logins(now: Rel<LoginInput>) = Sql.postgres("FROM now() |> WHERE action = 'login'")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] parameter 'now' cannot be used as a source: 'now' is a postgres function")
    }

    @Test
    fun `two rel inputs joined by explicit slots compile and render as ctes`() {
        val result = compile(
            traitsPrelude + """

            @BrikkTrait
            interface UserDim : Partial {
                val user_id: String?
                val tenant: String
            }

            @BrikkSql
            fun rawLogins() = Sql.postgres($q
                FROM public.events
                |> EXTEND payload->>'user_id' AS user_id, payload->>'action' AS action
                |> WHERE action = 'login'
            $q)

            @BrikkSql
            fun users() = Sql.postgres("FROM public.events |> SELECT payload->>'user_id' AS user_id, tenant")

            @BrikkSql
            fun loginsWithTenant(logins: Rel<LoginInput>, dim: Rel<UserDim>) = Sql.postgres($q
                FROM logins()
                |> JOIN dim() ON logins.user_id = dim.user_id
                |> SELECT logins.user_id, dim.tenant, logins.event_at
            $q)

            fun render(): String = loginsWithTenant(rawLogins(), users()).render()
            fun columns(row: LoginsWithTenantOut): String = row.user_id + row.tenant + row.event_at.toString()
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val sql = result.classLoader.loadClass("demo.MainKt").getMethod("render").invoke(null) as String
        assertTrue(sql.startsWith("WITH s0 AS ("), sql)
        assertContains(sql, "JOIN s1")
        assertTrue(!sql.contains("logins()") && !sql.contains("dim()"), sql)
    }

    // ------------------------------------------------------------------ $ template entries

    @Test
    fun `native templates retain source whitespace and parameter spelling`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun native(n: Long) = Sql.postgres("  \n-- leading\nselect CAST(${'$'}n AS BIGINT) AS n; -- trailing\n  ")

            @BrikkSql
            fun trimmed() = Sql.postgres("  SELECT 1 AS n  ".trimIndent())

            fun rendered(): String = native(3L).render()
            fun stored(): String = native(3L).sql
            fun explicitTrim(): String = trimmed().render()
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val main = result.classLoader.loadClass("demo.MainKt")
        val expected = "  \n-- leading\nselect CAST(:n AS BIGINT) AS n; -- trailing\n  "
        assertEquals(expected, main.getMethod("stored").invoke(null))
        assertEquals(expected, main.getMethod("rendered").invoke(null))
        assertEquals("  SELECT 1 AS n  ".trimIndent(), main.getMethod("explicitTrim").invoke(null))
    }

    @Test
    fun `shadowed interpolation binds the local while plain placeholders bind the parameter`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun local(n: Long): Rel<Partial> {
                val n = n + 1
                return Sql.postgres("SELECT CAST(${'$'}n AS BIGINT) AS n")
            }

            @BrikkSql
            fun plain(n: Long): Rel<Partial> {
                val n = n + 1
                return Sql.postgres("SELECT CAST(:n AS BIGINT) AS n")
            }

            @BrikkSql
            fun same(n: Long) = Sql.postgres("SELECT CAST(:n AS BIGINT) + CAST(${'$'}n AS BIGINT) AS n")

            fun bindings(): List<List<Any?>> = listOf(
                local(1L).bindings().values.toList(),
                plain(1L).bindings().values.toList(),
                same(1L).bindings().values.toList(),
            )
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val bindings = result.classLoader.loadClass("demo.MainKt").getMethod("bindings").invoke(null)
        assertEquals(listOf(listOf(2L), listOf(1L), listOf(1L)), bindings)
    }

    @Test
    fun `plain and shadowed template bindings cannot share a name`() {
        for (sql in listOf(
            "SELECT CAST(:n AS BIGINT) + CAST(${'$'}n AS BIGINT) AS n",
            "SELECT CAST(${'$'}n AS BIGINT) + CAST(:n AS BIGINT) AS n",
            "SELECT CAST(${'$'}n AS BIGINT) + ${'$'}PLAIN AS n",
        )) {
            val result = compile(
                """
                package demo
                import dev.brikk.house.sql.runtime.*
                const val PLAIN = "CAST(:n AS BIGINT)"

                @BrikkSql
                fun bad(n: Long): Rel<Partial> {
                    val n = n + 1
                    return Sql.postgres($q
                        |$sql
                    $q.trimMargin())
                }
                """.trimIndent(),
            )
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertContains(result.messages, "plain SQL placeholder 'n' binds the parameter, but '${'$'}n' refers to a different Kotlin declaration")
            assertContains(result.messages, "rename the interpolated local or property")
            val diagnostic = result.messages.lines().first { it.contains("plain SQL placeholder") }
            assertContains(diagnostic, "main.kt:9:") // The template entry, not the function or parameter.
        }
    }

    @Test
    fun `shadow collision detection ignores quoted and commented placeholders`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun local(n: Long, __brikk_template_bind: Long): Rel<Partial> {
                val n = n + 1
                return Sql.postgres($q
                    |SELECT ${'$'}n::BIGINT AS n, ':n' AS note,
                    |       CAST(:__brikk_template_bind AS BIGINT) AS other
                    |/* :n */ -- :n
                $q.trimMargin())
            }

            fun bindings(): Set<Any?> = local(1L, 3L).bindings().values.toSet()
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val bindings = result.classLoader.loadClass("demo.MainKt").getMethod("bindings").invoke(null)
        assertEquals(setOf(2L, 3L), bindings)
    }

    @Test
    fun `parameters used in local computations are not unused or redundantly bound`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            var effects = ""
            fun input(): Long { effects += "input;"; return 1L }
            fun advance(n: Long): Long { effects += "advance;"; return n + 1 }

            @BrikkSql
            fun computed(n: Long): Rel<Partial> {
                val next = advance(n)
                return Sql.postgres("SELECT CAST(${'$'}next AS BIGINT) AS n, CAST(${'$'}next AS BIGINT) AS again")
            }

            @BrikkSql
            fun shadowed(n: Long): Rel<Partial> {
                val n = advance(n)
                return Sql.postgres("SELECT CAST(${'$'}n AS BIGINT) AS n, CAST(${'$'}n AS BIGINT) AS again")
            }

            fun result(): List<Any?> {
                val computedValues = computed(input()).bindings().values.toList()
                val shadowedValues = shadowed(input()).bindings().values.toList()
                return listOf(effects, computedValues, shadowedValues)
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val actual = result.classLoader.loadClass("demo.MainKt").getMethod("result").invoke(null)
        assertEquals(listOf("input;advance;input;advance;", listOf(2L), listOf(2L)), actual)
    }

    @Test
    fun `same-named locals and lambda parameters do not mark outer parameters used`() {
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun unusedLocal(n: Long): Rel<Partial> {
                val n = 2L
                return Sql.postgres("SELECT CAST(${'$'}n AS BIGINT) AS n")
            }

            @BrikkSql
            fun unusedLambda(m: Long): Rel<Partial> {
                val next = 1L.let { m -> m + 1 }
                return Sql.postgres("SELECT CAST(${'$'}next AS BIGINT) AS n")
            }

            @BrikkSql
            fun unusedProbe(__brikk_template_bind: Long): Rel<Partial> {
                val next = 2L
                return Sql.postgres("SELECT CAST(${'$'}next AS BIGINT) AS n")
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "parameter 'n' is never referenced by the SQL")
        assertContains(result.messages, "parameter 'm' is never referenced by the SQL")
        assertContains(result.messages, "parameter '__brikk_template_bind' is never referenced by the SQL")
    }

    @Test
    fun `local shadowing a rel parameter is a bind while the plain slot remains an input`() {
        val scope = TemplateScope(setOf("src"), emptySet(), setOf("src")) { null }
        assertEquals(SqlPiece.Bind("src"), scope.classify("src"))
        val result = compile(
            """
            package demo
            import dev.brikk.house.sql.runtime.*

            @BrikkSql
            fun local(src: Rel<Partial>): Rel<Partial> {
                val src = 2L
                return Sql.postgres("SELECT CAST(${'$'}src AS BIGINT) AS n FROM src()")
            }

            fun bindings(): List<Any?> = local(Rel<Partial>("SELECT 1 AS id", "postgres")).bindings().values.toList()
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val bindings = result.classLoader.loadClass("demo.MainKt").getMethod("bindings").invoke(null)
        assertEquals(listOf(2L), bindings)
    }

    @Test
    fun `local val and top-level val interpolate as binds, const val is spliced as text`() {
        val result = compile(
            traitsPrelude + """

            const val EVENTS_TABLE = "public.events"
            val minDuration = 250L

            @BrikkSql
            fun slow(start: Instant): Rel<Partial> {
                val cutoff = start.plusSeconds(3600)
                return Sql.postgres($q
                    FROM ${'$'}EVENTS_TABLE
                    |> WHERE event_at >= ${'$'}start AND event_at < ${'$'}cutoff
                    |> EXTEND (payload->>'duration_ms')::BIGINT AS duration_ms
                    |> WHERE duration_ms > ${'$'}minDuration
                $q)
            }

            fun render(): String = slow(Instant.EPOCH).render()
            fun bindings(): Set<String> = slow(Instant.EPOCH).bindings().keys
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val main = result.classLoader.loadClass("demo.MainKt")
        val sql = main.getMethod("render").invoke(null) as String
        assertContains(sql, "FROM public.events")          // const spliced as text
        assertContains(sql, ":start")                       // authored parameter bind
        assertContains(sql, ":cutoff")                      // authored local bind
        assertContains(sql, ":minDuration")                 // authored top-level val bind
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("start", "cutoff", "minDuration"), main.getMethod("bindings").invoke(null) as Set<String>)
    }

    @Test
    fun `interpolated expression is rejected at the entry`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun bad(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= ${'$'}{start.plusSeconds(1)}")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] only a parameter, a local val, a property or a const val can be interpolated here")
        // Reported on the entry expression inside `${'$'}{...}` (column 83), not on the string literal
        // (which opens at column 43).
        val line = result.messages.lines().first { it.contains("can be interpolated here") }
        assertContains(line, "main.kt:12:83")
    }

    @Test
    fun `rel parameter interpolated without call parentheses is rejected`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun logins(src: Rel<LoginInput>) = Sql.postgres("FROM ${'$'}src |> WHERE action = 'login'")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] 'src' is a Rel parameter and must be used as a table call: write '${'$'}src()'")
    }

    @Test
    fun `scalar parameter never referenced by the sql is a frontend error on the parameter`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun recent(start: Instant, limit: Int) = Sql.postgres("FROM public.events |> WHERE event_at >= ${'$'}start")
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] parameter 'limit' is never referenced by the SQL")
        val line = result.messages.lines().first { it.contains("'limit' is never referenced") }
        assertContains(line, "main.kt:12:28") // the `limit: Int` parameter, not the string
    }

    @Test
    fun `text forms and template forms of slots and placeholders are interchangeable`() {
        val result = compile(
            traitsPrelude + """

            @BrikkSql
            fun a(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= :start")
            @BrikkSql
            fun b(start: Instant) = Sql.postgres("FROM public.events |> WHERE event_at >= ${'$'}start")
            @BrikkSql
            fun c(src: Rel<LoginInput>) = Sql.postgres("FROM src() |> WHERE action = 'login'")
            @BrikkSql
            fun d(src: Rel<LoginInput>) = Sql.postgres("FROM ${'$'}src() |> WHERE action = 'login'")

            fun render(): String = a(Instant.EPOCH).render() + "|" + b(Instant.EPOCH).render()
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rendered = result.classLoader.loadClass("demo.MainKt").getMethod("render").invoke(null) as String
        val (a, b) = rendered.split("|")
        assertEquals(a, b)
    }
}
