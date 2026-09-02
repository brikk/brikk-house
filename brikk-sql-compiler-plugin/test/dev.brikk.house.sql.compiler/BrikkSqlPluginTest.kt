package dev.brikk.house.sql.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
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

    private fun compile(source: String, debug: Boolean = false): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("main.kt", source))
            compilerPluginRegistrars = listOf(BrikkSqlCompilerPluginRegistrar())
            commandLineProcessors = listOf(BrikkSqlCommandLineProcessor())
            pluginOptions = buildList {
                add(PluginOption(BrikkSqlNames.PLUGIN_ID, "schema", schemaFile.absolutePath))
                add(PluginOption(BrikkSqlNames.PLUGIN_ID, "defaultSchema", "public"))
                if (debug) add(PluginOption(BrikkSqlNames.PLUGIN_ID, "debug", "true"))
            }
            inheritClassPath = true
            verbose = false
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

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
        interface HasPayload : Partial { val payload: String }

        @BrikkTrait
        interface LoginInput : Partial {
            val user_id: String
            val action: String
            val event_at: Instant
        }

        @BrikkSql
        fun eventsInRange(start: Instant, end: Instant) = Sql.postgres($q
            FROM public.events
            |> WHERE event_at >= :start AND event_at < :end
        $q)

        @BrikkSql
        fun <T : HasPayload> extractEvent(src: Rel<T>) = Sql.postgres($q
            |> EXTEND payload->>'user_id' AS user_id,
                      payload->>'action' AS action,
                      (payload->>'duration_ms')::BIGINT AS duration_ms
        $q)

        @BrikkSql
        fun loginDaily(src: Rel<LoginInput>) = Sql.postgres($q
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
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM public.events WHERE event_at >= %(start)s AND event_at < %(end)s), s1 AS ("), sql)
        assertContains(sql, "FROM s0")
        assertContains(sql, "payload ->> 'user_id' AS user_id")
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
        assertContains(result.messages, "[BRIKK_SQL] unknown column(s): evnt_at")
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
            fun sumAmount(src: Rel<NeedsAmount>) = Sql.postgres("|> AGGREGATE sum(amount) AS total")

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
        assertContains(result.messages, "must be a compile-time constant string")
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
}
