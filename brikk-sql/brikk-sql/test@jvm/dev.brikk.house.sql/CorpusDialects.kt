package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.BigqueryDialect
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.NormalizationStrategy
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared execution policy for SQLGlot-derived corpora; unknown names/settings fail closed. */
object CorpusDialects {
    val policy = Json.parseToJsonElement(testResource("corpus-policy.json")).jsonObject
    fun policyNames(field: String): Set<String> =
        policy.getValue(field).jsonArray.map { it.jsonPrimitive.content }.toSet()

    val OUT_OF_SCOPE: Set<String> = policyNames("excluded_dialects")
    private val uppercaseBigquery = object : BigqueryDialect() {
        override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE
    }

    /** `"postgres, version=16"` -> `"postgres"`; plain names pass through. */
    fun baseName(corpusName: String): String = corpusName.substringBefore(',').trim()

    /**
     * Versions remain best-effort base dialect executions, with full spellings retained in
     * ledger keys. Other settings must be implemented explicitly, never silently discarded.
     */
    fun resolveOrSkip(corpusName: String): Dialect? {
        val base = baseName(corpusName)
        var dialect = Dialects.forNameOrNull(base)
        val excluded = base.lowercase() in OUT_OF_SCOPE
        if (excluded && dialect != null) fail("Corpus policy drift: excluded dialect '$base' is registered")
        for (setting in corpusName.split(',').drop(1)) {
            val parts = setting.split('=', limit = 2).map { it.trim() }
            when {
                parts.size == 2 && parts[0] == "version" && parts[1].isNotEmpty() -> Unit
                parts == listOf("normalization_strategy", "case_insensitive_uppercase") &&
                    dialect is BigqueryDialect -> dialect = uppercaseBigquery
                else -> fail("Unsupported corpus dialect setting '$setting' in '$corpusName'")
            }
        }
        return dialect ?: if (excluded) null else fail(
            "Corpus dialect '$corpusName' is neither registered nor excluded by corpus-policy.json",
        )
    }
}
