package dev.brikk.house.sql.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Registration must never throw. The IDE (KEFS / `KtCompilerPluginsCache`) calls
 * `registerExtensions` with a bare [CompilerConfiguration] that carries only the `-P` plugin
 * options: no message collector, no module name, nothing else the CLI would normally set.
 */
@OptIn(ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class)
class BrikkSqlCompilerPluginRegistrarTest {

    @Test
    fun registersAgainstAnEmptyConfigurationLikeTheIde() {
        val storage = CompilerPluginRegistrar.ExtensionStorage()
        with(BrikkSqlCompilerPluginRegistrar()) {
            storage.registerExtensions(CompilerConfiguration())
        }
        // One FIR registrar adapter + one IR generation extension.
        assertEquals(2, storage.registeredExtensions.values.sumOf { it.size })
    }

    @Test
    fun registersWithOnlyPluginOptionsSet() {
        val configuration = CompilerConfiguration().apply {
            put(BrikkSqlOptions.KEY_SCHEMA, "does/not/need/to/exist.sql")
            put(BrikkSqlOptions.KEY_DEFAULT_SCHEMA, "public")
        }
        val storage = CompilerPluginRegistrar.ExtensionStorage()
        with(BrikkSqlCompilerPluginRegistrar()) {
            storage.registerExtensions(configuration)
        }
        assertEquals(2, storage.registeredExtensions.values.sumOf { it.size })
    }

    @Test
    fun debugOptionAcceptsStrictBooleansOnly() {
        val processor = BrikkSqlCommandLineProcessor()
        val configuration = CompilerConfiguration()
        processor.processOption(BrikkSqlCommandLineProcessor.DEBUG, "true", configuration)
        assertEquals(true, configuration.get(BrikkSqlOptions.KEY_DEBUG))

        val failure = assertFailsWith<CliOptionProcessingException> {
            processor.processOption(BrikkSqlCommandLineProcessor.DEBUG, "yes", configuration)
        }
        assertContains(failure.message.orEmpty(), "debug")
        assertContains(failure.message.orEmpty(), "'yes'")
    }
}
