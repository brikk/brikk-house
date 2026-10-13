package dev.brikk.house.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.platform.permissions.KaAnalysisPermissionChecker
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * The helper's only use of non-stable Analysis API (the platform interface, opted in), confined
 * here. Each call is guarded: if the API is missing, changed or failing on an IDE version, the
 * answer is unknown and falls back conservatively. Unknown never reports a missing compiler
 * plugin, and permission falls back to "not in a write action".
 */
internal object BrikkPlatformApi {
    /** Test seam replacing the compiler-plugin query; null in production. */
    @Volatile internal var compilerPluginOverride: (() -> Boolean?)? = null

    /** Whether the Brikk compiler plugin is active for the analyzed module: true or false when the IDE answers, null when unknown. */
    fun compilerPluginActive(session: KaSession, anchor: PsiElement): Boolean? =
        guarded { compilerPluginOverride?.invoke() ?: PlatformQuery.compilerPluginActive(session, anchor) }

    /** Whether Kotlin analysis may run now; without an answer, whenever no write action is in progress. */
    fun analysisAllowed(element: PsiElement): Boolean =
        guarded { PlatformQuery.analysisAllowed(element) } ?: !ApplicationManager.getApplication().isWriteAccessAllowed

    /** [query]'s answer, or null when it fails in any way, linkage errors included. Cancellation propagates. */
    fun guarded(query: () -> Boolean?): Boolean? = try {
        query()
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is java.util.concurrent.CancellationException) throw e
        if (e is LinkageError || e is Exception) null else throw e
    }

    /** Only touched inside [guarded], so even failing to link this class is caught there. */
    private object PlatformQuery {
        private const val REGISTRAR = "dev.brikk.house.sql.compiler.fir.BrikkSqlFirExtensionRegistrar"

        @OptIn(KaPlatformInterface::class)
        fun compilerPluginActive(session: KaSession, anchor: PsiElement): Boolean? {
            val provider = KotlinCompilerPluginsProvider.getInstance(anchor.project) ?: return null
            return provider.getRegisteredExtensions(session.useSiteModule, FirExtensionRegistrarAdapter.Companion).any { it.javaClass.name == REGISTRAR }
        }

        @OptIn(KaAllowAnalysisOnEdt::class, KaPlatformInterface::class)
        fun analysisAllowed(element: PsiElement): Boolean = allowAnalysisOnEdt {
            KaAnalysisPermissionChecker.getInstance(element.project).isAnalysisAllowed()
        }
    }
}
