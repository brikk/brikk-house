package dev.brikk.house.sql

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The sqlglot commit every oracle-derived fixture under `testResources/` must have been
 * generated from. Bump this together with the fixtures when re-pinning (see
 * TODO-sqlglot-catchup.md); the generators in `tools/` stamp `sqlglot_version` from
 * `git describe --tags` of `reference/sqlglot`.
 */
const val SQLGLOT_PIN = "v30.17.0-93-gdcc36544a"

/**
 * EVAL-05 (TODO-rectify-from-eval.md): fail loudly when any fixture is out of sync with the
 * pin. Before this test, a stale `v30.12.0` stamp sat unnoticed in two files and one generator
 * hardcoded its VERSION string, so "all gates green" could not be read as "matches the pin".
 *
 * Rules:
 *  - every top-level `sqlglot_version` (or legacy `version`) stamp must equal [SQLGLOT_PIN];
 *  - the oracle corpora (`ast-corpus`, dialect fixtures, lineage/qualify/scope corpora)
 *    must carry a stamp at all — a missing stamp is itself a failure;
 *  - brikk-side files (`*known-failures*.json` ledgers, brikk-native DataFusion fixtures,
 *    `semantics/` probe data) are not oracle-derived and are exempt from the "must be
 *    stamped" rule, but if they do carry a stamp it must still match.
 */
class FixturePinSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun testResourcesRoot(): File =
        listOf("brikk-sql/testResources", "testResources").map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot locate testResources/ from ${File(".").absolutePath}")

    private fun isOracleDerived(rel: String): Boolean =
        !rel.contains("known-failures") &&
            !rel.startsWith("semantics/") &&
            !rel.contains("datafusion") && // brikk-native dialect: Polyglot/SLT provenance, no sqlglot oracle
            rel.endsWith(".json")

    private fun stampOf(obj: JsonObject): String? =
        (obj["sqlglot_version"] as? JsonPrimitive)?.content ?: (obj["version"] as? JsonPrimitive)?.content

    @Test
    fun everyOracleFixtureIsStampedWithThePinnedSqlglotVersion() {
        val root = testResourcesRoot()
        val problems = mutableListOf<String>()
        var stamped = 0
        var checked = 0

        root.walkTopDown().filter { it.isFile && it.extension == "json" }.sorted().forEach { file ->
            val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
            val element = runCatching { json.parseToJsonElement(file.readText()) }
                .getOrElse { e -> problems.add("$rel: not valid JSON (${e.message?.take(80)})"); return@forEach }
            val obj = element as? JsonObject ?: return@forEach
            checked += 1
            val stamp = stampOf(obj)
            when {
                stamp == null && isOracleDerived(rel) -> problems.add("$rel: oracle-derived fixture has no sqlglot_version stamp")
                stamp != null && stamp != SQLGLOT_PIN -> problems.add("$rel: stamped '$stamp', pin is '$SQLGLOT_PIN'")
                stamp != null -> stamped += 1
            }
        }

        println("FixturePinSync: $stamped fixtures stamped $SQLGLOT_PIN (of $checked JSON objects checked)")
        assertTrue(stamped > 50, "suspiciously few stamped fixtures ($stamped) — did testResources move?")
        if (problems.isNotEmpty()) {
            fail(
                "${problems.size} fixture(s) out of sync with SQLGLOT_PIN=$SQLGLOT_PIN " +
                    "(regenerate with tools/*.py against reference/sqlglot @ pin, or bump the pin):\n" +
                    problems.joinToString("\n"),
            )
        }
    }
}
