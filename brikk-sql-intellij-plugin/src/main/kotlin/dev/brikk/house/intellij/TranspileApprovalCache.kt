package dev.brikk.house.intellij

import com.intellij.openapi.components.Service
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project memory of reviewed transpilations, backing "re-execute without review if
 * the SQL did not change": once the user has approved (executed) a (source dialect,
 * target dialect, source SQL) triple whose generated output was X, re-running the same
 * triple producing the same X skips the review dialog.
 *
 * Keyed AND valued by content: if either the source SQL or the generated output changes
 * (e.g. after a brikk-sql upgrade), review is required again. In-memory only by design —
 * an IDE restart re-arms review, which is the safe default for an execute gate.
 */
@Service(Service.Level.PROJECT)
class TranspileApprovalCache {

    private val approved = ConcurrentHashMap<String, String>()

    fun key(sourceSql: String, source: String, target: String): String =
        sha256("$source\u0000$target\u0000$sourceSql")

    fun isApproved(key: String, generatedSql: String): Boolean = approved[key] == generatedSql

    fun approve(key: String, generatedSql: String) {
        approved[key] = generatedSql
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
