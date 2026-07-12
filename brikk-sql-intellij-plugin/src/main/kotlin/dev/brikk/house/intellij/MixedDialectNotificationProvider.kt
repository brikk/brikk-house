package dev.brikk.house.intellij

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.generic.GenericDialect
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.util.FileContentUtil
import dev.brikk.house.intellij.lang.NewBrikkSqlScratchAction
import java.util.function.Function
import javax.swing.JComponent

/**
 * The "go polyglot" affordance: when a vendor-dialect SQL file contains
 * `-- dialect: xyz` marker blocks in a *different* dialect, the host parser fights
 * them. Offers the two escape hatches:
 *
 *  - switch the file's mapping to Generic SQL (lenient, `;`-bounded statements), or
 *  - extract the content into a Brikk SQL (`.bsql`) scratch — the plugin-owned
 *    polyglot file type.
 *
 * Not shown for lenient hosts, files whose markers all match the host dialect, console
 * files (their dialect is forced by the data source — per-file mapping is ignored
 * there, so a switch would silently do nothing; the scratch route is offered via the
 * editor menu instead), or after an explicit dismiss (per file, per session).
 */
class MixedDialectNotificationProvider : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (project.service<Dismissals>().isDismissed(file.url)) return null
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val language = psiFile.language
        if (language !is SqlLanguageDialect) return null
        if (SqlHosts.isLenientLanguageId(language.id)) return null
        if (isDialectForcedByDataSource(project, file)) return null

        val hostDialect = BrikkDialects.fromLanguageId(language.id)
        val foreign = DialectMarker.findAll(psiFile.text)
            .filter { it.isSupported && it.dialect != hostDialect }
        if (foreign.isEmpty()) return null

        val blockWord = if (foreign.size == 1) "block" else "blocks"
        val dialects = foreign.map { BrikkDialects.displayName(it.dialect) }.distinct().joinToString()
        return Function { _: FileEditor ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "Mixed-dialect SQL: ${foreign.size} $dialects $blockWord in a ${language.displayName} file will be mis-parsed by the ${language.displayName} dialect."
                createActionLabel("Switch file to Generic SQL") {
                    switchToGeneric(project, file)
                }
                createActionLabel("Extract to Brikk SQL scratch") {
                    NewBrikkSqlScratchAction.openScratch(project, psiFile.text)
                }
                createActionLabel("Dismiss") {
                    project.service<Dismissals>().dismiss(file.url)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }

    private fun switchToGeneric(project: Project, file: VirtualFile) {
        SqlDialectMappings.getInstance(project).setMapping(file, GenericDialect.INSTANCE)
        FileContentUtil.reparseFiles(project, listOf(file), false)
        EditorNotifications.getInstance(project).updateNotifications(file)
        // Verify the mapping actually took effect — some files resolve their dialect
        // elsewhere (data-source attachments and consoles override per-file mappings).
        val effective = PsiManager.getInstance(project).findFile(file)?.language
        if (effective is SqlLanguageDialect && !SqlHosts.isLenientLanguageId(effective.id)) {
            TranspileFlow.notify(
                project,
                "The SQL dialect of this file is controlled elsewhere (attached data source or console) and stayed ${effective.displayName}. Use 'Extract to Brikk SQL scratch' instead.",
                NotificationType.WARNING,
            )
        }
    }

    /**
     * Console files get their dialect from the data source; per-file mappings are
     * ignored, so the Generic switch would be a silent no-op there.
     */
    private fun isDialectForcedByDataSource(project: Project, file: VirtualFile): Boolean = try {
        JdbcConsoleProvider.getValidConsole(project, file)?.file?.virtualFile == file
    } catch (_: Exception) {
        false
    }

    /** Per-session banner dismissals (a restart re-offers, which is fine for a hint). */
    @Service(Service.Level.PROJECT)
    class Dismissals {
        private val dismissed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun isDismissed(fileUrl: String): Boolean = fileUrl in dismissed
        fun dismiss(fileUrl: String) {
            dismissed.add(fileUrl)
        }
    }
}
