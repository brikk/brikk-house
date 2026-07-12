package dev.brikk.house.intellij

import com.intellij.database.console.JdbcConsole
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import javax.swing.JComponent

/**
 * "Send to Console...": moves transpiled SQL from the polyglot workbench (.bsql /
 * scratch) into a native console — appends
 *
 *   -- transpiled from <file> (<source> -> <target>)
 *   <generated sql>
 *
 * at the bottom of the chosen console and focuses it (no execution; you take over
 * natively from there). Consoles whose engine matches the transpile target sort
 * first; the last-used console is preselected so working through a file
 * block-by-block keeps a stable target.
 */
object SendToConsole {

    fun choose(
        project: Project,
        outcome: BrikkTranspiler.TranspileOutcome.Success,
        sourceFileName: String,
        anchor: JComponent?,
        onSent: () -> Unit,
    ) {
        val consoles = try {
            JdbcConsole.getActiveConsoles(project)
        } catch (_: Exception) {
            emptyList()
        }
        if (consoles.isEmpty()) {
            TranspileFlow.notify(
                project,
                "No open database consoles to send to \u2014 open a console on a data source first.",
                NotificationType.WARNING,
            )
            return
        }

        val memory = project.service<ConsoleTargetMemory>()
        val sorted = consoles.sortedWith(
            compareByDescending<JdbcConsole> { targetDialectOf(it) == outcome.target }
                .thenByDescending { consoleUrl(it) == memory.lastConsoleFileUrl }
        )
        val preselect = sorted.firstOrNull { consoleUrl(it) == memory.lastConsoleFileUrl } ?: sorted.first()

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sorted)
            .setTitle("Send Transpiled SQL To")
            .setRenderer { list, console, _, isSelected, _ ->
                val engine = console.dataSource.dbms.name
                val match = if (targetDialectOf(console) == outcome.target) "" else "  (engine: $engine \u2260 ${BrikkDialects.displayName(outcome.target)})"
                javax.swing.JLabel("${console.title} \u2014 $engine$match").apply {
                    isOpaque = true
                    border = javax.swing.BorderFactory.createEmptyBorder(2, 8, 2, 8)
                    background = if (isSelected) list.selectionBackground else list.background
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                }
            }
            .setSelectedValue(preselect, true)
            .setItemChosenCallback { console ->
                memory.lastConsoleFileUrl = consoleUrl(console)
                append(project, console, outcome, sourceFileName)
                onSent()
            }
            .createPopup()
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
    }

    private fun targetDialectOf(console: JdbcConsole): String? = try {
        BrikkDialects.fromLanguageId(console.dataSource.dbms.name)
    } catch (_: Exception) {
        null
    }

    private fun consoleUrl(console: JdbcConsole): String? = try {
        console.file.virtualFile?.url
    } catch (_: Exception) {
        null
    }

    private fun append(
        project: Project,
        console: JdbcConsole,
        outcome: BrikkTranspiler.TranspileOutcome.Success,
        sourceFileName: String,
    ) {
        val document = console.document
        var caretOffset = document.textLength
        WriteCommandAction.runWriteCommandAction(project, "Brikk Send to Console", null, {
            val length = document.textLength
            val prefix = if (length > 0 && document.charsSequence[length - 1] != '\n') "\n\n" else "\n"
            val header = "-- transpiled from $sourceFileName (${outcome.source} \u2192 ${outcome.target})\n"
            document.insertString(length, prefix + header + outcome.sql + "\n")
            caretOffset = document.textLength
        })

        console.file.virtualFile?.let { vf ->
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, vf, caretOffset), true)
        }

        val mismatch = targetDialectOf(console)?.takeIf { it != outcome.target }
        if (mismatch != null) {
            TranspileFlow.notify(
                project,
                "Sent ${BrikkDialects.displayName(outcome.target)} SQL to a ${console.dataSource.dbms.name} console \u2014 re-check the target dialect if that wasn't intended.",
                NotificationType.WARNING,
            )
        }
    }
}
