package dev.brikk.house.sql.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import dev.brikk.house.sql.compiler.analysis.SqlPiece
import dev.brikk.house.sql.compiler.fir.TemplateScope
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

    private fun compile(
        source: String,
        debug: Boolean = false,
        schema: String = schemaFile.absolutePath,
        workingDir: File? = null,
    ): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("main.kt", source))
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
            val user_id: String
            val action: String
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
                val user_id: String
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
        assertContains(sql, "%(start)s")                    // parameter bind
        assertContains(sql, "%(cutoff)s")                   // local bind
        assertContains(sql, "%(minDuration)s")              // top-level val bind
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
