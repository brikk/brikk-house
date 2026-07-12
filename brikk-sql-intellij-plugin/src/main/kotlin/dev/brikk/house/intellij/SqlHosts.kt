package dev.brikk.house.intellij

import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.SqlLanguageDialect

/**
 * Host-editor policy for the marker workflow (the scratch-vs-console split):
 *
 *  - LENIENT hosts (Generic SQL / base SQL / non-SQL files): the host parser doesn't
 *    fight foreign dialects and statement boundaries stay `;`-accurate, so no-selection
 *    scopes (marker segment, whole file) and marker gutter affordances are safe.
 *  - STRICT hosts (vendor dialects: MySQL console, PostgreSQL-mapped file, ...): the
 *    host parser mis-parses foreign blocks, so Brikk actions require an explicit
 *    selection and the marker gutter stays out of the way.
 */
object SqlHosts {

    private val LENIENT_LANGUAGE_IDS = setOf("SQL", "GenericSQL", "BrikkSQL")

    fun isLenient(psiFile: PsiFile?): Boolean {
        val language = psiFile?.language ?: return true
        if (language !is SqlLanguageDialect) return true // non-SQL editors don't grumble
        return isLenientLanguageId(language.id)
    }

    fun isLenientLanguageId(id: String): Boolean =
        LENIENT_LANGUAGE_IDS.any { it.equals(id, ignoreCase = true) }
}
