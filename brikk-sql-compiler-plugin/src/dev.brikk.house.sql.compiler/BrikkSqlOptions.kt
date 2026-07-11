package dev.brikk.house.sql.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** Immutable snapshot of plugin CLI options, resolved once at registration time. */
data class BrikkSqlOptions(
    val debug: Boolean = false,
    /** Path to the exported DB schema JSON (compile-time type safety input). Unused for now. */
    val schemaPath: String? = null,
) {
    companion object {
        val KEY_DEBUG = CompilerConfigurationKey<Boolean>("brikk-sql debug")
        val KEY_SCHEMA = CompilerConfigurationKey<String>("brikk-sql schema path")

        fun from(configuration: CompilerConfiguration): BrikkSqlOptions = BrikkSqlOptions(
            debug = configuration.get(KEY_DEBUG, false),
            schemaPath = configuration.get(KEY_SCHEMA),
        )
    }
}

/**
 * Consumers pass options as: `-P plugin:dev.brikk.house.sql.compiler:<name>=<value>`
 * (or `settings.kotlin.compilerPlugins[].options` in a Kotlin Toolchain module.yaml).
 */
@OptIn(ExperimentalCompilerApi::class)
class BrikkSqlCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = BrikkSqlNames.PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(DEBUG, SCHEMA)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            DEBUG.optionName -> configuration.put(BrikkSqlOptions.KEY_DEBUG, value.toBooleanStrict())
            SCHEMA.optionName -> configuration.put(BrikkSqlOptions.KEY_SCHEMA, value)
            else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
        }
    }

    companion object {
        val DEBUG = CliOption(
            optionName = "debug",
            valueDescription = "true|false",
            description = "Report intercepted SQL calls as compiler warnings (for debugging/tests)",
            required = false,
        )
        val SCHEMA = CliOption(
            optionName = "schema",
            valueDescription = "<path>",
            description = "Path to the exported database schema JSON",
            required = false,
        )
    }
}
