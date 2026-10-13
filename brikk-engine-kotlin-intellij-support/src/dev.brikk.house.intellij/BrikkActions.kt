package dev.brikk.house.intellij

import com.intellij.lang.Language
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** What the user must fix, in priority order. Indexing and other pending states are never actions. */
public enum class BrikkActionKind {
    /** The project's Brikk runtime has no entry point the code calls: it is older than the code. */
    RUNTIME_OUTDATED,

    /** A relation's generated row type cannot exist: the Brikk compiler plugin is not active for the module. */
    COMPILER_PLUGIN_MISSING,

    /** No installed plugin provides the language of a Brikk dialect the runtime declares. */
    DIALECT_PLUGIN_MISSING,
}

/**
 * One condition the user must act on, as plain data for a banner: [message] says what is wrong,
 * [fixText] what to do, and the remediation is [buildFileUrl] (the nearest build file, a VFS URL)
 * and/or [docsUrl]. [dialect] is set for [BrikkActionKind.DIALECT_PLUGIN_MISSING].
 */
public data class BrikkAction(
    public val kind: BrikkActionKind,
    public val message: String,
    public val fixText: String,
    public val docsUrl: String?,
    public val buildFileUrl: String?,
    public val dialect: BrikkDialect? = null,
) {
    public companion object {
        public const val RUNTIME_DOCS: String = "https://github.com/brikk/brikk-house#readme"
        public const val COMPILER_PLUGIN_DOCS: String = "https://github.com/brikk/brikk-house/blob/main/docs/ENG-03-distribution-and-IDE.md"
        public const val MARKETPLACE_SEARCH: String = "https://plugins.jetbrains.com/search?search="
    }
}

/**
 * Records actions on hosts as Kotlin analysis finds them (never computing analysis for them) and
 * answers per file from those records, cached until PSI or a record changes.
 */
internal object BrikkActions {
    private val fromHost: Key<BrikkAction> = Key.create("dev.brikk.house.intellij.hostAction")
    private val fromShapes: Key<BrikkAction> = Key.create("dev.brikk.house.intellij.shapeAction")
    private val cache: Key<CachedValue<List<BrikkAction>>> = Key.create("dev.brikk.house.intellij.fileActions")
    private val records = SimpleModificationTracker()
    private val buildFiles = listOf("module.yaml", "build.gradle.kts", "build.gradle", "pom.xml")

    /** Updates [host]'s records from one inspection [result]; indexing leaves them as they were. */
    fun record(host: KtStringTemplateExpression, result: ContextResult, includeShapes: Boolean) {
        when (result) {
            ContextResult.NotBrikk -> { put(host, fromHost, null); put(host, fromShapes, null) }
            is ContextResult.Unavailable -> when (result.cause) {
                BrikkPendingCause.INDEXING -> Unit
                BrikkPendingCause.RUNTIME_ENTRY_MISSING -> {
                    val entry = BrikkKotlinContext.syntaxContext(host)?.call?.calleeExpression?.text
                    put(host, fromHost, BrikkAction(BrikkActionKind.RUNTIME_OUTDATED,
                        "The Brikk runtime in this project has no Sql.$entry: it is older than this code.",
                        "Update the Brikk runtime (brikk-engine-kotlin) dependency in the build.", BrikkAction.RUNTIME_DOCS, buildFile(host)))
                }
                else -> put(host, fromHost, null)
            }
            is ContextResult.Available -> {
                val dialect = result.context.dialect
                put(host, fromHost, if (Language.findLanguageByID(dialect.languageId) != null) null else BrikkAction(
                    BrikkActionKind.DIALECT_PLUGIN_MISSING,
                    "No installed plugin provides ${dialect.languageId}, the language of Brikk ${dialect.sqlName} SQL.",
                    "Install a plugin for ${dialect.languageId}.", BrikkAction.MARKETPLACE_SEARCH + "${dialect.sqlName}%20sql", null, dialect))
                if (includeShapes) {
                    val missing = result.context.relations.values.any { (it as? ShapeResult.Unavailable)?.cause == BrikkPendingCause.COMPILER_PLUGIN_MISSING }
                    put(host, fromShapes, if (!missing) null else BrikkAction(BrikkActionKind.COMPILER_PLUGIN_MISSING,
                        "Generated Brikk relation shapes need the Brikk compiler plugin, which is not active for this module.",
                        "Apply the Brikk compiler plugin in the build; in the IDE, enable it through Kotlin External FIR Support.",
                        BrikkAction.COMPILER_PLUGIN_DOCS, buildFile(host)))
                }
            }
        }
    }

    /** [file]'s actions in priority order, distinct by kind and dialect. Reads records only: dumb-safe, no analysis. */
    fun of(file: PsiFile): List<BrikkAction> {
        val host = com.intellij.lang.injection.InjectedLanguageManager.getInstance(file.project).getInjectionHost(file)
        if (host != null) return (host as? KtStringTemplateExpression)?.let { ordered(sequenceOf(it)) }.orEmpty()
        return CachedValuesManager.getManager(file.project).getCachedValue(file, cache, {
            CachedValueProvider.Result.create(ordered(PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java).asSequence()),
                PsiModificationTracker.getInstance(file.project), records)
        }, false)
    }

    private fun ordered(hosts: Sequence<KtStringTemplateExpression>): List<BrikkAction> =
        hosts.flatMap { sequenceOf(it.getUserData(fromHost), it.getUserData(fromShapes)) }.filterNotNull()
            .distinctBy { it.kind to it.dialect }.sortedBy { it.kind.ordinal }.toList()

    private fun put(host: KtStringTemplateExpression, key: Key<BrikkAction>, action: BrikkAction?) {
        if (host.getUserData(key) == action) return
        host.putUserData(key, action)
        records.incModificationCount()
    }

    /** The nearest build file above [host]'s file, as a VFS URL. */
    private fun buildFile(host: KtStringTemplateExpression): String? {
        var directory: com.intellij.openapi.vfs.VirtualFile? = host.containingFile.originalFile.virtualFile?.parent
        while (directory != null) {
            for (name in buildFiles) {
                val found: com.intellij.openapi.vfs.VirtualFile? = directory.findChild(name)
                if (found != null) return found.url
            }
            directory = directory.parent
        }
        return null
    }
}
