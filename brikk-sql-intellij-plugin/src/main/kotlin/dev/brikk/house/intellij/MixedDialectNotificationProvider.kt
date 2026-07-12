package dev.brikk.house.intellij

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
import java.util.function.Function
import javax.swing.JComponent

/**
 * The "go Generic SQL" affordance: when a vendor-dialect SQL file contains
 * `-- dialect: xyz` marker blocks in a *different* dialect, the host parser fights
 * them. This banner offers the one-click fix — map the file to Generic SQL (lenient,
 * `;`-bounded statements), which is the intended host for polyglot marker files.
 *
 * Not shown for lenient hosts (already fine), files whose markers all match the host
 * dialect, or after an explicit dismiss (per file, per session).
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

        val hostDialect = BrikkDialects.fromLanguageId(language.id)
        val foreign = DialectMarker.findAll(psiFile.text)
            .filter { it.isSupported && it.dialect != hostDialect }
        if (foreign.isEmpty()) return null

        val blockWord = if (foreign.size == 1) "block" else "blocks"
        val dialects = foreign.map { BrikkDialects.displayName(it.dialect) }.distinct().joinToString()
        return Function { _: FileEditor ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "Mixed-dialect SQL: ${foreign.size} $blockWord ($dialects) in a ${language.displayName} file \u2014 the $dialects $blockWord will be mis-parsed by the ${language.displayName} dialect."
                createActionLabel("Switch file to Generic SQL") {
                    SqlDialectMappings.getInstance(project).setMapping(file, GenericDialect.INSTANCE)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
                createActionLabel("Dismiss") {
                    project.service<Dismissals>().dismiss(file.url)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
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
