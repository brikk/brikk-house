package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.dialects.BigqueryDialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.NormalizationStrategy
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CorpusCoverageTest {
    @Test
    fun registryMatchesPolicy() {
        val supported = CorpusDialects.policyNames("sqlglot_dialects")
        val native = CorpusDialects.policyNames("native_dialects")
        assertEquals("", Dialects.BASE.name)
        assertEquals(supported + native, Dialects.NAMES.map { Dialects.forName(it).name }.toSet())
        assertTrue(supported.intersect(native).isEmpty(), "SQLGlot/native overlap")
        assertTrue((supported + native).intersect(CorpusDialects.OUT_OF_SCOPE).isEmpty(), "supported/excluded overlap")
        for (name in supported + native) assertEquals(name, CorpusDialects.resolveOrSkip(name)?.name)
        for (name in CorpusDialects.OUT_OF_SCOPE) assertNull(CorpusDialects.resolveOrSkip(name), name)
    }

    @Test
    fun everyDialectFixtureHasExplicitCoverage() {
        val directory = listOf("brikk-sql/brikk-sql/testResources/dialect-corpus", "testResources/dialect-corpus")
            .map(::File).firstOrNull { it.isDirectory } ?: fail("cannot locate dialect-corpus/")
        val fixtures = directory.listFiles()!!.filter {
            it.isFile && it.extension == "json" && "known-failures" !in it.name && !it.name.startsWith("datafusion")
        }.sorted().associate { it.nameWithoutExtension to Json.parseToJsonElement(it.readText()).jsonObject }
            .filterValues { "identity" in it || "transpile" in it }
        val gated = CorpusDialects.policyNames("gated_dialect_fixtures")
        assertTrue((gated + "base").all { it in fixtures }, "missing gated/base fixtures")
        assertTrue(gated.all { it in CorpusDialects.policyNames("sqlglot_dialects") }, "unsupported gate")
        assertTrue(gated.intersect(CorpusDialects.OUT_OF_SCOPE).isEmpty(), "gated/excluded overlap")
        for (name in gated) {
            val gate = Class.forName("dev.brikk.house.sql.${name.replaceFirstChar { it.uppercaseChar() }}TranspileTest")
            assertTrue(!Modifier.isAbstract(gate.modifiers) && TranspileCorpusGate::class.java.isAssignableFrom(gate),
                "$name must have a concrete TranspileCorpusGate")
        }
        var dynamicTotal = 0
        var ungatedNamedIdentities = 0
        for ((name, fixture) in fixtures) {
            val classification = when {
                name in gated -> "transpile-gated; identity assertions supported-but-unexecuted"
                name == "base" -> "supported-but-unexecuted (explicit base deferral, except pipe identities)"
                name in CorpusDialects.OUT_OF_SCOPE -> "excluded"
                else -> fail("Unclassified SQLGlot dialect fixture: $name.json")
            }
            assertEquals(if (name == "base") "" else name, fixture.getValue("dialect").jsonPrimitive.content)
            val identities = fixture["identity"]?.jsonArray.orEmpty()
            if (name in gated) ungatedNamedIdentities += identities.size
            var supported = 0
            var excluded = 0
            for (case in fixture["transpile"]?.jsonArray.orEmpty()) {
                for (direction in listOf("read", "write")) {
                    for (target in case.jsonObject[direction]?.jsonObject.orEmpty().keys) {
                        val resolved = CorpusDialects.resolveOrSkip(target)
                        if (resolved == null || name in CorpusDialects.OUT_OF_SCOPE) excluded++ else supported++
                    }
                }
            }
            val dynamic = fixture.getValue("stats").jsonObject.getValue("skipped_dynamic").jsonPrimitive.int
            dynamicTotal += dynamic
            println("CorpusCoverage $name: $classification; ${identities.size} identities, " +
                "$supported supported transpile directions, $excluded excluded directions; skipped_dynamic=$dynamic")
            if (name == "base") {
                val deferral = CorpusDialects.policy.getValue("base_deferral").jsonObject
                assertTrue(deferral.getValue("reason").jsonPrimitive.content.isNotBlank())
                val pipes = identities.count { "|>" in it.jsonObject.getValue("sql").jsonPrimitive.content }
                assertEquals(59, pipes, "base pipe identities")
                assertEquals(pipes, PipeDesugarCorpusTest.loadPipeCases().size, "pipe consumer inventory")
                val actual = mapOf("non_pipe_identities" to identities.size - pipes,
                    "supported_transpile_directions" to supported, "excluded_transpile_directions" to excluded,
                    "skipped_dynamic" to dynamic)
                for ((key, count) in actual) assertEquals(deferral.getValue(key).jsonPrimitive.int, count, "base $key")
                println("CorpusCoverage base: ${identities.size - pipes} identities and $supported transpile " +
                    "directions supported-but-unexecuted; $pipes pipe identities run in PipeDesugarCorpusTest")
            }
        }
        val identityDeferral = CorpusDialects.policy.getValue("named_identity_deferral").jsonObject
        assertTrue(identityDeferral.getValue("reason").jsonPrimitive.content.isNotBlank())
        assertEquals(identityDeferral.getValue("count").jsonPrimitive.int, ungatedNamedIdentities)
        println("CorpusCoverage: $ungatedNamedIdentities named-fixture identity assertions supported-but-unexecuted")
        println("CorpusCoverage: ${fixtures.size} SQLGlot fixture files inventoried; skipped_dynamic=$dynamicTotal " +
            "extractor limitations, counted separately, not a unique upstream assertion denominator")
    }

    @Test
    fun resolverRejectsUnknownsAndPreservesAliasesAndVersions() {
        for (name in listOf("missing_dialect", "postgress", "mysql,unknown=true", "postgres,version=16,unknown=true",
                "snowflake,unknown=true", "mysql,version", "mysql,version=", "mysql,",
                "postgres,normalization_strategy=case_insensitive_uppercase", "bigquery,normalization_strategy=unknown")) {
            assertFailsWith<AssertionError>(name) { CorpusDialects.resolveOrSkip(name) }
        }
        for (name in Dialects.NAMES + "") assertSame(Dialects.forName(name), CorpusDialects.resolveOrSkip(name))
        assertSame(Dialects.POSTGRES, CorpusDialects.resolveOrSkip(" PostgreSQL , version=16"))
        assertSame(Dialects.DUCKDB, CorpusDialects.resolveOrSkip("duckdb, version=1.1.0"))
        assertEquals("postgres", CorpusDialects.baseName(" postgres, version=16"))
        assertNull(CorpusDialects.resolveOrSkip(" SNOWFLAKE , version=1"))
    }

    @Test
    fun bigqueryNormalizationOptionKeepsDialectBehavior() {
        val dialect = assertIs<BigqueryDialect>(CorpusDialects.resolveOrSkip(
            "bigquery,normalization_strategy=case_insensitive_uppercase"))
        assertEquals(NormalizationStrategy.CASE_INSENSITIVE_UPPERCASE, dialect.normalizationStrategy)
        val expression = dialect.parseOne("SELECT `MiXeD`")
        dialect.normalizeIdentifier(expression.findAll<Identifier>().single())
        assertEquals("SELECT `MIXED`", dialect.generate(expression))
        assertEquals(NormalizationStrategy.CASE_INSENSITIVE, Dialects.BIGQUERY.normalizationStrategy)
    }
}
