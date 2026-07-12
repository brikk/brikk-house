package dev.brikk.house.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Editor popup entry points. Both funnel into [TranspileFlow.run]; they differ only in
 * which side the explicit picker overrides:
 *
 *  - "Transpile To...": source auto-detected (marker -> IDE dialect -> picker), target picked.
 *  - "Transpile From...": source picked (explicit override), target auto-detected/picked.
 */
abstract class TranspileActionBase : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}

class TranspileToAction : TranspileActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        // Source: detected when possible (picker fallback inside the flow); target: picked.
        TranspileFlow.run(project, editor, e.getData(CommonDataKeys.PSI_FILE))
    }
}

/**
 * Phase 2 entry point: transpile the scope into the attached console's engine dialect,
 * review (unless unchanged since the last approval), and run it in the console.
 * Visible only when a database console/session is attached to the editor's file.
 */
class ExecuteViaTranspileAction : TranspileActionBase() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            project != null && editor != null && ExecuteFlow.consoleFor(project, editor) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        ExecuteFlow.run(project, editor, e.getData(CommonDataKeys.PSI_FILE))
    }
}

class TranspileFromAction : TranspileActionBase() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        // Force an explicit source pick (override whatever we detect), then detect the
        // target from the file's own dialect — "convert xyz into what this file is".
        val scope = TranspileFlow.resolveScope(editor)
        val detectedTarget = psiFile?.language?.id?.let(BrikkDialects::fromLanguageId)
        TranspileFlowFromPicker.run(project, editor, psiFile, detectedTarget, scope)
    }
}

/**
 * "Transpile From..." needs the pick order inverted (source picker first, even when
 * detection would succeed). Kept as its own tiny object to keep [TranspileFlow]'s
 * common path straightforward.
 */
private object TranspileFlowFromPicker {
    fun run(
        project: com.intellij.openapi.project.Project,
        editor: com.intellij.openapi.editor.Editor,
        psiFile: com.intellij.psi.PsiFile?,
        detectedTarget: String?,
        scope: TranspileFlow.Scope,
    ) {
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(BrikkDialects.names)
            .setTitle("Transpile From")
            .setItemChosenCallback { source ->
                TranspileFlow.run(project, editor, psiFile, source = source, target = detectedTarget)
            }
            .createPopup()
            .showInBestPositionFor(editor)
    }
}
