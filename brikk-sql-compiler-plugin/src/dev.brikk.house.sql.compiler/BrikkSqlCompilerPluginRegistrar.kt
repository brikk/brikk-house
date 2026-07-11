package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.fir.BrikkSqlFirExtensionRegistrar
import dev.brikk.house.sql.compiler.ir.BrikkSqlIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Entry point, referenced from
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`.
 *
 * Registers:
 *  - FIR (frontend): checkers/diagnostics for `@BrikkSql`-annotated call sites
 *    (later: declaration generation / return-type refinement for inferred row shapes).
 *  - IR (backend): rewrite of intercepted calls
 *    (later: AST merge of composed virtual TVFs + final SQL rendering).
 */
@OptIn(ExperimentalCompilerApi::class)
class BrikkSqlCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String get() = BrikkSqlNames.PLUGIN_ID

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.getNotNull(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        val options = BrikkSqlOptions.from(configuration)

        FirExtensionRegistrarAdapter.registerExtension(BrikkSqlFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(BrikkSqlIrGenerationExtension(messageCollector, options))
    }
}
