package dev.brikk.house.sql.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In-process end-to-end tests via kctfork (kotlin-compile-testing fork, K2, Kotlin 2.4).
 * The plugin is registered in-memory — no jar packaging needed.
 */
@OptIn(ExperimentalCompilerApi::class)
class BrikkSqlPluginTest {

    /** Placeholder runtime API — in the real design this comes from a brikk-sql runtime module. */
    private val runtimeStub = SourceFile.kotlin(
        "SqlRuntime.kt",
        """
        package dev.brikk.house.sql

        @Target(AnnotationTarget.FUNCTION)
        annotation class BrikkSql(val dialect: String = "doris")

        object Sql {
            @BrikkSql("doris")
            fun doris(sql: String): String = error("brikk-sql compiler plugin was not applied")
        }
        """.trimIndent(),
    )

    private fun compile(source: String, debug: Boolean = false): JvmCompilationResult =
        KotlinCompilation().apply {
            sources = listOf(runtimeStub, SourceFile.kotlin("main.kt", source))
            compilerPluginRegistrars = listOf(BrikkSqlCompilerPluginRegistrar())
            commandLineProcessors = listOf(BrikkSqlCommandLineProcessor())
            if (debug) {
                pluginOptions = listOf(PluginOption(BrikkSqlNames.PLUGIN_ID, "debug", "true"))
            }
            inheritClassPath = true
            verbose = false
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    @Test
    fun `constant sql compiles and the ir rewrite runs`() {
        val result = compile(
            """
            package demo

            import dev.brikk.house.sql.Sql

            fun events(): String =
                Sql.doris(
                    ""${'"'}
                    FROM rumble_import.events
                    |> WHERE event_at >= :start
                    ""${'"'}.trimIndent()
                )
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val events = result.classLoader.loadClass("demo.MainKt").getMethod("events")
        val rendered = events.invoke(null) as String

        // The stub body throws; a successful call proves the IR rewrite replaced the call.
        assertTrue(rendered.startsWith("BRIKK["), "expected marker constant, got: $rendered")
        assertContains(rendered, "FROM rumble_import.events")
        // trimIndent was applied at compile time
        assertContains(rendered, "\n|> WHERE event_at >= :start")
    }

    @Test
    fun `debug option reports intercepted sql`() {
        val result = compile(
            """
            package demo

            import dev.brikk.house.sql.Sql

            fun q(): String = Sql.doris("FROM t |> SELECT x")
            """.trimIndent(),
            debug = true,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertContains(result.messages, "brikk-sql[debug]: intercepted doris with SQL:")
        assertContains(result.messages, "brikk-sql: intercepted 1 SQL call(s)")
    }

    @Test
    fun `non-constant sql is a frontend error`() {
        val result = compile(
            """
            package demo

            import dev.brikk.house.sql.Sql

            fun bad(fragment: String): String = Sql.doris(fragment)
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] argument to 'dev.brikk.house.sql.Sql.doris' must be a compile-time constant string")
    }

    @Test
    fun `interpolated sql is rejected for now`() {
        // Interpolation will later become the parameter-binding mechanism (terpal-style
        // parts/params decomposition); for the scaffold it is rejected as non-constant.
        val result = compile(
            """
            package demo

            import dev.brikk.house.sql.Sql

            fun bad(table: String): String = Sql.doris("FROM ${'$'}table |> SELECT x")
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "must be a compile-time constant string")
    }

    @Test
    fun `blank sql is a frontend error`() {
        val result = compile(
            """
            package demo

            import dev.brikk.house.sql.Sql

            fun bad(): String = Sql.doris("   ")
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "[BRIKK_SQL] argument to 'dev.brikk.house.sql.Sql.doris' is blank")
    }

    @Test
    fun `calls without the annotation are untouched`() {
        val result = compile(
            """
            package demo

            fun plain(sql: String): String = sql

            fun ok(): String = plain("FROM t")
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val ok = result.classLoader.loadClass("demo.MainKt").getMethod("ok")
        assertEquals("FROM t", ok.invoke(null))
    }
}
