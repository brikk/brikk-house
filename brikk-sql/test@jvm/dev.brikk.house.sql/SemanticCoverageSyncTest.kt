package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.metadata.EngineClass
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.PairCoverage
import dev.brikk.house.sql.metadata.SemanticCoverage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [SemanticCoverage] against its sources of truth:
 *  - every `<a>-<b>-hazards.json` under testResources/semantics ⇒ both ordered scopes are
 *    PARTIAL or explicitly COMPLETE; no generated scope lacks a file;
 *  - every brikk-registered dialect has an explicit [EngineClass] (no silent default);
 *  - two real engines with no file are UNRESEARCHED (conservative refusal source).
 *
 * tools/generate_semantic_coverage.py is byte-deterministic; this fails if the JSON set
 * changes without regenerating the covered-pairs block (or vice versa).
 */
class SemanticCoverageSyncTest {

    private fun hazardPairFiles(): List<Pair<String, String>> {
        val dir = File("testResources/semantics").takeIf { it.isDirectory }
            ?: File("brikk-sql/testResources/semantics")
        return dir.listFiles { f -> f.name.endsWith("-hazards.json") }!!
            .map { it.name.removeSuffix("-hazards.json") }
            .map { stem ->
                val parts = stem.split("-")
                check(parts.size == 2 && parts.all { it.isNotEmpty() }) { "bad hazards file: $stem" }
                parts[0] to parts[1]
            }
    }

    @Test
    fun everyHazardFileCreatesAScopedPairBothWays() {
        for ((a, b) in hazardPairFiles()) {
            assertTrue(
                SemanticCoverage.coverage(a, b) in setOf(PairCoverage.PARTIAL, PairCoverage.COMPLETE),
                "$a->$b",
            )
            assertTrue(
                SemanticCoverage.coverage(b, a) in setOf(PairCoverage.PARTIAL, PairCoverage.COMPLETE),
                "$b->$a",
            )
        }
    }

    @Test
    fun generatedScopesExactlyMatchHazardFiles() {
        val fromFiles = buildSet {
            for ((a, b) in hazardPairFiles()) { add(a to b); add(b to a) }
        }
        assertEquals(fromFiles, SemanticCoverage.pairScopes.keys)
    }

    @Test
    fun everyRegisteredDialectHasAnExplicitEngineClass() {
        // Every name resolvable by the dialect registry must be classified (no default).
        for (name in listOf("", "sqlglot", "mysql", "doris", "starrocks", "presto", "trino",
                "duckdb", "postgres", "postgresql", "clickhouse", "hive", "spark2", "spark",
                "sparksql", "bigquery", "datafusion", "arrow-datafusion")) {
            val resolved = Dialects.forNameOrNull(name) ?: continue
            // The canonical registry name must be classified.
            assertTrue(
                SemanticCoverage.engineClass.containsKey(resolved.name.lowercase()) ||
                    SemanticCoverage.engineClass.containsKey(name.lowercase()),
                "no EngineClass for dialect '$name' (resolved '${resolved.name}')",
            )
        }
    }

    @Test
    fun unresearchedRealPairIsUnresearchedNotCovered() {
        // trino<->starrocks and mysql<->starrocks have no file yet.
        assertEquals(PairCoverage.UNRESEARCHED, SemanticCoverage.coverage("trino", "starrocks"))
        assertEquals(PairCoverage.UNRESEARCHED, SemanticCoverage.coverage("starrocks", "mysql"))
    }

    @Test
    fun starrocksDorisScopeIsHonestlyPartialAndClosed() {
        val scope = SemanticCoverage.scope("starrocks", "doris")
        assertEquals(PairCoverage.PARTIAL, scope.coverage)
        assertTrue("820-function" in scope.scope, scope.scope)
        assertTrue("ABS" in scope.coveredConcepts)
        assertTrue("ACOS" !in scope.coveredConcepts)
        assertTrue(scope.sourceCatalog.startsWith("4.1.4"), scope.sourceCatalog)
    }

    @Test
    fun nonEngineEndpointsAreNotApplicable() {
        assertEquals(PairCoverage.NOT_APPLICABLE, SemanticCoverage.coverage("starrocks", "sqlglot"))
        assertEquals(PairCoverage.NOT_APPLICABLE, SemanticCoverage.coverage("starrocks", "datafusion"))
        assertEquals(EngineClass.TRANSLATION_ONLY, SemanticCoverage.engineClass["sqlglot"])
        assertEquals(EngineClass.BRIKK_NATIVE, SemanticCoverage.engineClass["datafusion"])
    }
}
