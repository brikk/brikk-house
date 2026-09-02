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
    /**
     * Path to the schema cache: a file of `CREATE TABLE` statements (the DDL-as-cache format,
     * see docs/virtual-pipelines-wiring.md). Parsed with [schemaDialect].
     */
    val schemaPath: String? = null,
    val schemaDialect: String = "postgres",
    /** Qualifies single-part table names in the schema file (`t` -> `public.t`). */
    val defaultSchema: String? = null,
) {
    companion object {
        val KEY_DEBUG = CompilerConfigurationKey<Boolean>("brikk-sql debug")
        val KEY_SCHEMA = CompilerConfigurationKey<String>("brikk-sql schema path")
        val KEY_SCHEMA_DIALECT = CompilerConfigurationKey<String>("brikk-sql schema dialect")
        val KEY_DEFAULT_SCHEMA = CompilerConfigurationKey<String>("brikk-sql default schema")

        fun from(configuration: CompilerConfiguration): BrikkSqlOptions = BrikkSqlOptions(
            debug = configuration.get(KEY_DEBUG, false),
            schemaPath = configuration.get(KEY_SCHEMA),
            schemaDialect = configuration.get(KEY_SCHEMA_DIALECT, "postgres"),
            defaultSchema = configuration.get(KEY_DEFAULT_SCHEMA),
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

    override val pluginOptions: Collection<AbstractCliOption> = listOf(DEBUG, SCHEMA, SCHEMA_DIALECT, DEFAULT_SCHEMA)

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            DEBUG.optionName -> configuration.put(BrikkSqlOptions.KEY_DEBUG, value.toBooleanStrict())
            SCHEMA.optionName -> configuration.put(BrikkSqlOptions.KEY_SCHEMA, value)
            SCHEMA_DIALECT.optionName -> configuration.put(BrikkSqlOptions.KEY_SCHEMA_DIALECT, value)
            DEFAULT_SCHEMA.optionName -> configuration.put(BrikkSqlOptions.KEY_DEFAULT_SCHEMA, value)
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
            description = "Path to the schema cache file (CREATE TABLE statements)",
            required = false,
        )
        val SCHEMA_DIALECT = CliOption(
            optionName = "schemaDialect",
            valueDescription = "<dialect>",
            description = "brikk-sql dialect the schema file is written in (default: postgres)",
            required = false,
        )
        val DEFAULT_SCHEMA = CliOption(
            optionName = "defaultSchema",
            valueDescription = "<name>",
            description = "Schema used to qualify unqualified table names in the schema file",
            required = false,
        )
    }
}
