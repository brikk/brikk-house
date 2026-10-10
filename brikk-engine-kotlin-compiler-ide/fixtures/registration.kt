// PLUGIN-API
// RUN: acceptance.RegistrationKt
package acceptance

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.util.ServiceLoader

@OptIn(ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class)
fun main() {
    val registrar = ServiceLoader.load(CompilerPluginRegistrar::class.java)
        .single { it.pluginId == "dev.brikk.house.sql.compiler" }
    val storage = CompilerPluginRegistrar.ExtensionStorage()
    with(registrar) { storage.registerExtensions(CompilerConfiguration()) }
    check(storage.registeredExtensions.values.sumOf { it.size } == 2)
    val guard = Class.forName("dev.brikk.house.sql.compiler.analysis.PluginGuard")
    println("loaded: " + guard.getMethod("getBuild").invoke(guard.getField("INSTANCE").get(null)))
    println("shaded ServiceLoader and bare IDE-like registration OK")
}
