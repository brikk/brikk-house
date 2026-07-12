package dev.brikk.house.intellij

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * The transpile review surface: a before/after diff of the scope against the generated
 * SQL, a diagnostics strip (generator `unsupported(...)` messages + functions unknown
 * to the target engine's catalog), and the apply modes.
 *
 * Two shapes:
 *  - preview mode ([onExecute] == null): Replace In Place / Insert After / Copy;
 *  - execute-review mode ([onExecute] != null, the Phase 2 gate): Execute / Copy —
 *    the callback runs after the user approves the generated SQL.
 */
class TranspilePreviewDialog(
    private val project: Project,
    private val editor: Editor,
    private val scope: TranspileFlow.Scope,
    private val outcome: BrikkTranspiler.TranspileOutcome.Success,
    private val onExecute: (() -> Unit)? = null,
) : DialogWrapper(project) {

    private val replaceAction = object : DialogWrapperAction("Replace In Place") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            applyEdit(replace = true)
            close(OK_EXIT_CODE)
        }
    }

    private val insertAfterAction = object : DialogWrapperAction("Insert After") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            applyEdit(replace = false)
            close(OK_EXIT_CODE)
        }
    }

    private val copyAction = object : DialogWrapperAction("Copy") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(outcome.sql))
            TranspileFlow.notify(project, "Generated ${BrikkDialects.displayName(outcome.target)} SQL copied to clipboard.", NotificationType.INFORMATION)
            close(OK_EXIT_CODE)
        }
    }

    private val executeAction = object : DialogWrapperAction("Execute") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            close(OK_EXIT_CODE)
            onExecute?.invoke()
        }
    }

    init {
        val arrow = "${BrikkDialects.displayName(outcome.source)} \u2192 ${BrikkDialects.displayName(outcome.target)}"
        title = if (onExecute != null) "Execute via Transpilation: $arrow" else "Transpile: $arrow"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.add(createDiffComponent(), BorderLayout.CENTER)
        createDiagnosticsComponent()?.let { panel.add(it, BorderLayout.SOUTH) }
        panel.preferredSize = JBUI.size(900, 500)
        return panel
    }

    private fun createDiffComponent(): JComponent {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            null,
            factory.create(project, scope.text, fileType),
            factory.create(project, outcome.sql, fileType),
            "Source: ${BrikkDialects.displayName(outcome.source)}",
            "Generated: ${BrikkDialects.displayName(outcome.target)}",
        )
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        diffPanel.setRequest(request)
        return diffPanel.component
    }

    private fun createDiagnosticsComponent(): JComponent? {
        val pipeNote = if (outcome.pipesDesugared) {
            " Pipe (|>) syntax was desugared to standard ${BrikkDialects.displayName(outcome.target)} SQL."
        } else {
            ""
        }
        val lines = buildList {
            outcome.unsupported.forEach { add("Unsupported: $it") }
            outcome.unmappable.forEach {
                add("Unknown to ${BrikkDialects.displayName(outcome.target)}: function '$it' is not in the engine's function catalog")
            }
        }
        if (lines.isEmpty()) {
            return JBLabel("Clean transpile \u2014 no diagnostics.$pipeNote", AllIcons.General.InspectionsOK, JBLabel.LEADING)
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(
            JBLabel(
                "${lines.size} diagnostic${if (lines.size > 1) "s" else ""} \u2014 output is best-effort, review before use:$pipeNote",
                AllIcons.General.Warning,
                JBLabel.LEADING,
            ),
            BorderLayout.NORTH,
        )
        val area = JTextArea(lines.joinToString("\n")).apply {
            isEditable = false
            rows = lines.size.coerceAtMost(6)
        }
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> =
        if (onExecute != null) {
            executeAction.putValue(DEFAULT_ACTION, true)
            arrayOf(executeAction, copyAction, cancelAction)
        } else {
            replaceAction.putValue(DEFAULT_ACTION, true)
            arrayOf(replaceAction, insertAfterAction, copyAction, cancelAction)
        }

    private fun applyEdit(replace: Boolean) {
        val document = editor.document
        WriteCommandAction.runWriteCommandAction(project, "Brikk Transpile", null, {
            if (replace) {
                document.replaceString(scope.range.startOffset, scope.range.endOffset, outcome.sql)
            } else {
                val insertion = buildString {
                    append("\n\n-- dialect: ").append(outcome.target).append('\n')
                    append(outcome.sql)
                    append('\n')
                }
                document.insertString(scope.range.endOffset, insertion)
            }
        })
        // Insert After may have just introduced a foreign-dialect block into a
        // vendor-dialect file — refresh so the "go Generic SQL" banner shows right away.
        com.intellij.ui.EditorNotifications.getInstance(project).updateAllNotifications()
    }
}
