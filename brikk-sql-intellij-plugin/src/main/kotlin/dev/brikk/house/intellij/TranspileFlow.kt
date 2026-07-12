package dev.brikk.house.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * The shared "pick dialects -> transpile -> preview" flow behind every entry point
 * (editor popup actions, the marker gutter icon, and later the execute pipeline).
 *
 * Scope resolution: the editor selection when present, otherwise the `-- dialect:`
 * marker segment at the caret when present, otherwise the whole document.
 *
 * Source-dialect resolution (overridable via the "Transpile From" entry point):
 * marker at scope start -> IDE SQL dialect of the file -> explicit picker.
 */
object TranspileFlow {

    data class Scope(val range: TextRange, val text: String, val markerDialect: String?)

    /** Resolves what SQL the flow operates on. */
    fun resolveScope(editor: Editor): Scope {
        val document = editor.document
        val fullText = document.text
        if (editor.selectionModel.hasSelection()) {
            val range = TextRange(editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)
            return Scope(range, range.substring(fullText), DialectMarker.dialectAt(fullText, range.startOffset))
        }
        val caret = editor.caretModel.offset
        val marker = DialectMarker.findAll(fullText).lastOrNull { it.lineStartOffset <= caret }
        if (marker != null) {
            val (start, end) = DialectMarker.segmentAfter(fullText, marker)
            if (caret <= end) {
                return Scope(TextRange(start, end), fullText.substring(start, end), marker.dialect)
            }
        }
        return Scope(TextRange(0, fullText.length), fullText, DialectMarker.findAll(fullText).firstOrNull()?.dialect)
    }

    /** Best guess for the scope's source dialect, or null when undetectable. */
    fun detectSourceDialect(scope: Scope, psiFile: PsiFile?): String? {
        scope.markerDialect?.takeIf { BrikkDialects.isSupported(it) }?.let { return it }
        return psiFile?.language?.id?.let(BrikkDialects::fromLanguageId)
    }

    /**
     * Runs the flow. Null [source]/[target] are resolved by detection and/or a picker
     * popup; non-null values are taken as explicit user choices (overrides).
     */
    fun run(
        project: Project,
        editor: Editor,
        psiFile: PsiFile?,
        source: String? = null,
        target: String? = null,
    ) {
        val scope = resolveScope(editor)
        if (scope.text.isBlank()) {
            notify(project, "Nothing to transpile: the ${if (editor.selectionModel.hasSelection()) "selection" else "file"} is empty.", NotificationType.WARNING)
            return
        }
        val detected = detectSourceDialect(scope, psiFile)
        pickDialect(project, editor, "Transpile From", source ?: detected) { chosenSource ->
            pickDialect(project, editor, "Transpile To", target) { chosenTarget ->
                transpileAndPreview(project, editor, scope, chosenSource, chosenTarget)
            }
        }
    }

    /**
     * Picks a dialect: uses [preset] directly when given; otherwise shows a list popup
     * titled [title]. The continuation runs on the EDT.
     */
    private fun pickDialect(
        project: Project,
        editor: Editor,
        title: String,
        preset: String?,
        onChosen: (String) -> Unit,
    ) {
        if (preset != null) {
            onChosen(preset)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(BrikkDialects.names)
            .setTitle(title)
            .setRenderer { list, value, _, isSelected, cellHasFocus ->
                javax.swing.JLabel(BrikkDialects.displayName(value)).apply {
                    isOpaque = true
                    border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)
                    background = if (isSelected) list.selectionBackground else list.background
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                }
            }
            .setItemChosenCallback { onChosen(it) }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun transpileAndPreview(
        project: Project,
        editor: Editor,
        scope: Scope,
        source: String,
        target: String,
    ) {
        when (val outcome = BrikkTranspiler.transpile(scope.text, source, target)) {
            is BrikkTranspiler.TranspileOutcome.Failure -> {
                val position = outcome.line?.let { " (line ${outcome.line}, col ${outcome.col ?: "?"})" } ?: ""
                notify(
                    project,
                    "Cannot parse as ${BrikkDialects.displayName(source)}$position: ${outcome.message}",
                    NotificationType.ERROR,
                )
            }
            is BrikkTranspiler.TranspileOutcome.Success -> {
                TranspilePreviewDialog(project, editor, scope, outcome).show()
            }
        }
    }

    fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Brikk SQL")
            .createNotification("Brikk SQL", content, type)
            .notify(project)
    }
}
