package dev.brikk.house.intellij.lang

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware

private val TEMPLATE = """
    -- Brikk SQL scratch: mark blocks with their dialect, then use the gutter icon or
    -- the editor menu (Brikk SQL) to transpile or execute through transpilation.

    -- dialect: mysql
    select 1;
""".trimIndent() + "\n"

/** Creates and opens a `.bsql` scratch — the polyglot marker playground. */
class NewBrikkSqlScratchAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openScratch(project, TEMPLATE)
    }

    companion object {
        fun openScratch(project: com.intellij.openapi.project.Project, content: String) {
            val file = ScratchRootType.getInstance().createScratchFile(
                project,
                "scratch.bsql",
                BrikkSqlDialect.INSTANCE,
                content,
                ScratchFileService.Option.create_new_always,
            ) ?: return
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file), true)
        }
    }
}
