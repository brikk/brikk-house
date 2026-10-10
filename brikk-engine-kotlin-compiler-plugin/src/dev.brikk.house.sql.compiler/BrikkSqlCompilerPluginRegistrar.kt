package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.fir.BrikkSqlFirExtensionRegistrar
import dev.brikk.house.sql.compiler.ir.BrikkSqlIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
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
        // The IDE (KEFS / KtCompilerPluginsCache) registers plugins with a bare configuration that
        // has no MESSAGE_COLLECTOR_KEY; `getNotNull` would throw there and take the plugin down.
        // A plugin must never throw at registration, so fall back to MessageCollector.NONE.
        val messageCollector = configuration.messageCollector
        val options = BrikkSqlOptions.from(configuration)

        FirExtensionRegistrarAdapter.registerExtension(BrikkSqlFirExtensionRegistrar(options))
        IrGenerationExtension.registerExtension(BrikkSqlIrGenerationExtension(messageCollector, options))
    }
}
