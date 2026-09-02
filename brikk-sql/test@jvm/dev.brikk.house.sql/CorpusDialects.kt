package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.fail

/**
 * Resolves the dialect names that appear as `read`/`write` keys in the sqlglot-derived
 * corpora (EVAL-04, TODO-rectify-from-eval.md).
 *
 * Two things make this more than `Dialects.forNameOrNull`:
 *
 *  1. Upstream `validate_all` keys can carry dialect settings: `"postgres, version=16"`
 *     (sqlglot: Dialect.get_or_raise splits on `,`). Brikk has no dialect-version model, so
 *     such a key resolves to the *base-named* dialect and the case RUNS (its ledger key keeps
 *     the full spelling, so a version-dependent divergence is ledgered explicitly rather than
 *     silently skipped).
 *  2. Non-ported dialects are skipped only if they are on [OUT_OF_SCOPE]. Any other
 *     unresolvable name (a typo, or a dialect that has since been ported and dropped from the
 *     registry) fails the gate instead of quietly shrinking coverage.
 */
object CorpusDialects {

    /** sqlglot dialects deliberately not ported — the only names a gate may skip. */
    val OUT_OF_SCOPE: Set<String> = setOf(
        "athena", "databricks", "dremio", "drill", "exasol", "fabric", "materialize", "oracle",
        "redshift", "risingwave", "singlestore", "snowflake", "sqlite", "tableau", "teradata",
        "tsql",
    )

    /** `"postgres, version=16"` -> `"postgres"`; plain names pass through. */
    fun baseName(corpusName: String): String = corpusName.substringBefore(',').trim()

    /**
     * The dialect to run a corpus direction under, or `null` if the name is out of scope
     * (and therefore skipped). Fails the calling test for any other unresolvable name.
     */
    fun resolveOrSkip(corpusName: String): Dialect? {
        val base = baseName(corpusName)
        Dialects.forNameOrNull(base)?.let { return it }
        if (base.lowercase() in OUT_OF_SCOPE) return null
        fail(
            "Corpus dialect '$corpusName' is neither registered in Dialects nor listed in " +
                "CorpusDialects.OUT_OF_SCOPE. Port it, or add it to OUT_OF_SCOPE deliberately.",
        )
    }
}
