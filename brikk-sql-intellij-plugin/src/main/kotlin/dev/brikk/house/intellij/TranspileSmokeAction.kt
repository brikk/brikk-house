package dev.brikk.house.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.brikk.house.sql.shape.SqlFragment

/**
 * Phase 0 smoke action: proves the Kotlin Toolchain-built brikk-sql jars load and run
 * inside the IDE process. Transpiles the editor selection from MySQL to DuckDB and
 * reports the result (plus any unsupported-node messages) as a notification.
 *
 * Replaced by the real "Transpile from/to..." UX in Phase 1.
 */
class TranspileSmokeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val sql = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: return

        val (content, type) = try {
            val result = SqlFragment(sql, "mysql").transpileTo("duckdb", pretty = true)
            val warnings = result.unsupportedMessages
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "\n\nUnsupported:\n- ", separator = "\n- ")
                .orEmpty()
            "mysql -> duckdb:\n${result.sql}$warnings" to NotificationType.INFORMATION
        } catch (t: Throwable) {
            "Transpile failed: ${t.message}" to NotificationType.ERROR
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Brikk SQL")
            .createNotification("Brikk SQL", content, type)
            .notify(project)
    }
}
