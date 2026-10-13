package dev.brikk.house.intellij

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * The one rule for whether SQL is a Brikk inspection view that must never execute: a recognized
 * Brikk host of any dialect (owned by this plugin or not, registered or only runtime-declared)
 * or a pending one. Execution guards call this instead of checking their own editing profile.
 * Call only from an optional Kotlin integration.
 */
public object BrikkInspectionView {
    /**
     * [file] is an injected fragment, judged by its host, or a Kotlin file, judged by the string
     * holding [offset]: for an execution action, the selection start, else the caret.
     */
    public fun isInspectionOnly(file: PsiFile, offset: Int): Boolean {
        val injected = InjectedLanguageManager.getInstance(file.project).getInjectionHost(file)
        val host = injected as? KtStringTemplateExpression ?: run {
            if (injected != null || file !is KtFile || file.textLength == 0) return false
            PsiTreeUtil.getParentOfType(file.findElementAt(offset.coerceIn(0, file.textLength - 1)), KtStringTemplateExpression::class.java, false)
        } ?: return false
        return isInspectionOnly(host)
    }

    /**
     * Whether [host] is (or is nested in) a Brikk host whose SQL is inspection-only now. A literal
     * interpolated into a template (`${"1"}`) is judged by the outermost template holding it.
     */
    public fun isInspectionOnly(host: PsiElement): Boolean = when (val state = BrikkHosts.state(outermost(host))) {
        is BrikkHostState.Recognized -> !state.context.executable
        is BrikkHostState.Pending -> true
        BrikkHostState.NotBrikk -> false
    }

    private fun outermost(element: PsiElement): PsiElement {
        var outer = element
        while (true) outer = PsiTreeUtil.getParentOfType(outer, KtStringTemplateExpression::class.java, true) ?: return outer
    }
}
