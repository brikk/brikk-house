package dev.brikk.house.intellij

import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.settings.DatabaseSettings
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Phase 2: execute-through-transpilation. The pipeline is
 *
 *   transpile -> review (diagnostics shown; skipped when this exact transpilation was
 *   already approved and nothing changed) -> append generated SQL to the attached
 *   console -> run it through DataGrip's own script-model execution.
 *
 * Native-parser verification slots in between transpile and review once
 * brikk-sql-verify lands in this branch (Phase 3).
 */
object ExecuteFlow {

    /** The JDBC console attached to the editor's file, or null (drives action visibility). */
    fun consoleFor(project: Project, editor: Editor): JdbcConsole? {
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return try {
            JdbcConsoleProvider.getValidConsole(project, virtualFile)
        } catch (_: Exception) {
            null
        }
    }

    /** The brikk dialect of the console's data source engine, or null when unsupported. */
    fun targetDialectOf(console: JdbcConsole): String? =
        BrikkDialects.fromLanguageId(console.dataSource.dbms.name)

    fun run(
        project: Project,
        editor: Editor,
        psiFile: PsiFile?,
        sourceOverride: String? = null,
    ) {
        val console = consoleFor(project, editor)
        if (console == null) {
            TranspileFlow.notify(
                project,
                "No database console/session is attached to this editor \u2014 attach a data source to execute through transpilation.",
                NotificationType.WARNING,
            )
            return
        }
        val target = targetDialectOf(console)
        if (target == null) {
            TranspileFlow.notify(
                project,
                "The attached data source engine '${console.dataSource.dbms.name}' has no brikk-sql dialect yet (known: ${BrikkDialects.names.joinToString()}).",
                NotificationType.WARNING,
            )
            return
        }

        val scope = TranspileFlow.resolveScope(editor)
        if (scope.text.isBlank()) {
            TranspileFlow.notify(project, "Nothing to execute: the scope is empty.", NotificationType.WARNING)
            return
        }

        val detected = sourceOverride ?: TranspileFlow.detectSourceDialect(scope, psiFile)
        if (detected == null) {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(BrikkDialects.names)
                .setTitle("Source Dialect")
                .setItemChosenCallback { transpileReviewExecute(project, editor, scope, it, target, console) }
                .createPopup()
                .showInBestPositionFor(editor)
            return
        }
        transpileReviewExecute(project, editor, scope, detected, target, console)
    }

    private fun transpileReviewExecute(
        project: Project,
        editor: Editor,
        scope: TranspileFlow.Scope,
        source: String,
        target: String,
        console: JdbcConsole,
    ) {
        if (source == target && "|>" !in scope.text) {
            // Same dialect, no pipe syntax: nothing to transpile; run the text as-is
            // (still through the console pipeline so behavior is uniform). The engine —
            // not brikk-sql — is the authority on its own dialect, so no parse gate here.
            executeInConsole(project, console, scope.text.trim())
            return
        }
        when (val outcome = BrikkTranspiler.transpile(scope.text, source, target)) {
            is BrikkTranspiler.TranspileOutcome.Failure -> {
                if (source == target) {
                    // Same-dialect text brikk-sql cannot parse (engine-specific corners,
                    // or a `|>` inside a string literal): defer to the engine as-is.
                    executeInConsole(project, console, scope.text.trim())
                    return
                }
                val position = outcome.line?.let { " (line ${outcome.line}, col ${outcome.col ?: "?"})" } ?: ""
                TranspileFlow.notify(
                    project,
                    "Cannot parse as ${BrikkDialects.displayName(source)}$position: ${outcome.message}",
                    NotificationType.ERROR,
                )
            }
            is BrikkTranspiler.TranspileOutcome.Success -> {
                if (source == target && !outcome.pipesDesugared) {
                    // The `|>` was e.g. inside a string literal — no pipe statements,
                    // same dialect: run the original text untouched.
                    executeInConsole(project, console, scope.text.trim())
                    return
                }
                val cache = project.service<TranspileApprovalCache>()
                val key = cache.key(scope.text, source, target)
                if (cache.isApproved(key, outcome.sql)) {
                    // Unchanged since the last review -> re-execute without review.
                    executeInConsole(project, console, outcome.sql)
                    return
                }
                TranspilePreviewDialog(project, editor, scope, outcome) {
                    cache.approve(key, outcome.sql)
                    executeInConsole(project, console, outcome.sql)
                }.show()
            }
        }
    }

    /**
     * Appends [sql] to the console document and runs it through DataGrip's script-model
     * execution — the generated SQL stays visible in the console, exactly like a typed
     * query, and results/errors land in the standard result tabs.
     */
    fun executeInConsole(project: Project, console: JdbcConsole, sql: String) {
        val document = console.document
        var start = 0
        WriteCommandAction.runWriteCommandAction(project, "Brikk Execute via Transpilation", null, {
            val length = document.textLength
            val prefix = if (length > 0 && document.charsSequence[length - 1] != '\n') "\n" else ""
            document.insertString(length, prefix + sql)
            start = length + prefix.length
        })
        PsiDocumentManager.getInstance(project).commitDocument(document)

        val range = TextRange(start, document.textLength)
        val consoleEditor = console.consoleView.currentEditor
        val info = JdbcConsoleProvider.findScriptModelNoInject(
            project,
            console.file,
            consoleEditor,
            range,
            DatabaseSettings.ExecOption(),
        )
        if (info == null) {
            TranspileFlow.notify(
                project,
                "Could not build an executable script model over the generated SQL \u2014 it was appended to the console; run it manually.",
                NotificationType.WARNING,
            )
            return
        }
        JdbcConsoleProvider.doRunQueryInConsole(console, info)
    }
}
