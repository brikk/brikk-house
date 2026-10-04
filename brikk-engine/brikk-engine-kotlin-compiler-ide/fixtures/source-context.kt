// PLUGIN-API
// RUN: acceptance.Source_contextKt
package acceptance

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.IdeaStandaloneExecutionSetup
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.toKtPsiSourceElement

/** Exercise the finished IDE artifact with PSI but no containing FIR file. */
@OptIn(org.jetbrains.kotlin.K1Deprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
fun main() {
    IdeaStandaloneExecutionSetup.doSetup()
    val disposable = Disposer.newDisposable()
    try {
        val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
        val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val file = KtPsiFactory(environment.project).createPhysicalFile("Views.kt", """
            package sample
            import java.time.Instant
            import java.math.BigDecimal as Money
            import dev.brikk.house.sql.runtime.*
            interface RawEvent { val event_at: Instant; val amount: Money? }
        """.trimIndent())
        val source = file.declarations.single().toKtPsiSourceElement()
        val context = Class.forName("dev.brikk.house.sql.compiler.fir.SourceContextKt")
        val imports = context.getMethod("sourceImports", FirFile::class.java, KtSourceElement::class.java)
            .invoke(null, null, source) as List<*>
        val actual = imports.map { imp ->
            checkNotNull(imp)
            listOf(imp.javaClass.getMethod("getFqName").invoke(imp).toString(),
                imp.javaClass.getMethod("getAlias").invoke(imp), imp.javaClass.getMethod("isAllUnder").invoke(imp))
        }
        check(actual == listOf(listOf("java.time.Instant", null, false), listOf("java.math.BigDecimal", "Money", false),
            listOf("dev.brikk.house.sql.runtime", null, true))) { actual }
        val path = context.getMethod("sourceFilePath", FirFile::class.java, KtSourceElement::class.java).invoke(null, null, source)
        check(path == file.virtualFile.path)
        println("IDE artifact recovers imports and schema anchor without a containing FIR file")
    } finally { ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) } }
}
