package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Astra's round-2 evidence probes under the split policy: injection claims (`BrikkHosts.state`)
 * need strong evidence while Kotlin cannot resolve; the execution guard (`BrikkInspectionView`)
 * blocks on weak evidence then.
 */
class BrikkEvidenceTest : BrikkFixtureCase() {
    private fun <T> dumb(action: () -> T): T = DumbModeTestUtils.computeInDumbModeSynchronously(project, ThrowableComputable<T, Throwable> { action() })

    private fun edit(from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val start = document.text.indexOf(from)
            check(start >= 0) { from }
            document.replaceString(start, start + from.length, to)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun currentHost() = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last { it.text.contains("SELECT") }

    /** Unrelated by Kotlin; while indexing never claimed, but Execute is blocked on its weak evidence. */
    private fun shadowed(host: KtStringTemplateExpression) {
        assertEquals("Smart mode: Kotlin rules it out", BrikkHostState.NotBrikk, BrikkHosts.state(host))
        assertFalse(BrikkInspectionView.isInspectionOnly(host))
        assertEquals("Indexing must not claim unrelated SQL", BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
        assertTrue("The guard errs toward Brikk while indexing", dumb { BrikkInspectionView.isInspectionOnly(host) })
    }

    // Probe 1
    fun testBrikkNestedWithinAnUnrelatedOuterStringIsProtected() {
        val host = host("""
            fun wrap() = "wrapped ${'$'}{run {
                @BrikkSql fun query() = Sql.duckdb("SELECT 1")
                query()
            }}"
        """.trimIndent())
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(host) })
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        assertTrue(BrikkInspectionView.isInspectionOnly(host))
    }

    fun testUnrelatedStringInsideABrikkTemplateIsProtected() {
        val host = host("@BrikkSql fun query() = Sql.duckdb(\"SELECT ${'$'}{listOf(\"x\").joinToString()}\")")
        val inner = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single { it.text == "\"x\"" }
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        assertTrue(BrikkInspectionView.isInspectionOnly(inner))
    }

    // Probes 2-8: shadowing is weak evidence for the guard, never strong evidence for a claim.
    fun testForeignImportAliasedToSqlShadowsRuntimeStar() {
        myFixture.addFileToProject("Foreign.kt", "package foreign\nobject Fake { fun duckdb(sql: String) = sql }")
        shadowed(host("import foreign.Fake as Sql\n@BrikkSql fun query() = Sql.duckdb(\"SELECT 1\")"))
    }

    fun testForeignAnnotationAliasedToBrikkSqlShadowsRuntimeStar() {
        myFixture.addFileToProject("Foreign.kt", "package foreign\nannotation class Fake")
        shadowed(host("import foreign.Fake as BrikkSql\n@BrikkSql fun query() = Sql.duckdb(\"SELECT 1\")"))
    }

    fun testParameterNamedSqlShadowsRuntimeImport() {
        shadowed(explicitHost("class Fake { fun duckdb(sql: String) = sql }\n@BrikkSql fun query(Sql: Fake) = Sql.duckdb(\"SELECT 1\")"))
    }

    fun testMemberNamedSqlShadowsRuntimeImport() {
        shadowed(explicitHost("""
            class Fake { fun duckdb(sql: String) = sql }
            class Owner {
                val Sql = Fake()
                @BrikkSql fun query() = Sql.duckdb("SELECT 1")
            }
        """.trimIndent()))
    }

    fun testLocalFunctionShadowsImportedRuntimeMember() {
        shadowed(host("""
            import dev.brikk.house.sql.runtime.Sql.duckdb as sql
            @BrikkSql fun query(): String {
                fun sql(text: String) = text
                return sql("SELECT 1")
            }
        """.trimIndent(), EXPLICIT_IMPORTS))
    }

    fun testSamePackageObjectInAnotherFileShadowsRuntimeStar() {
        myFixture.addFileToProject("app/Sql.kt", "package app\nobject Sql { fun duckdb(sql: String) = sql }")
        myFixture.configureByText("Views.kt", "package app\nimport dev.brikk.house.sql.runtime.*\n@BrikkSql fun query() = Sql.duckdb(\"SELECT 1\")")
        shadowed(currentHost())
    }

    fun testCompetingStarImportDoesNotProveRuntimeSql() {
        myFixture.addFileToProject("Foreign.kt", "package foreign\nobject Sql { fun duckdb(sql: String) = sql }")
        val host = host("import foreign.*\n@BrikkSql fun query() = Sql.duckdb(\"SELECT 1\")")
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(host) })
    }

    // Probes 9-10: typealiases to the runtime are weak evidence while indexing, proven by Kotlin when smart.
    fun testRuntimeAnnotationTypealiasIsProtectedOnColdIndexing() {
        val host = host("typealias Query = dev.brikk.house.sql.runtime.BrikkSql\n@Query fun query() = Sql.duckdb(\"SELECT 1\")")
        // Cold first: nothing is remembered yet.
        assertEquals("A typealias is never a claim while indexing", BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(host) })
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
    }

    fun testRuntimeObjectTypealiasIsProtectedOnColdIndexing() {
        val host = host("typealias Queries = dev.brikk.house.sql.runtime.Sql\n@BrikkSql fun query() = Queries.duckdb(\"SELECT 1\")")
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(host) })
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
    }

    fun testTypealiasChainsAreFollowedAndCyclesEnd() {
        val chained = host("""
            typealias Base = dev.brikk.house.sql.runtime.BrikkSql
            typealias Query = Base
            typealias Entry = Queries
            typealias Queries = dev.brikk.house.sql.runtime.Sql
            @Query fun query() = Entry.trino("SELECT 1")
        """.trimIndent())
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(chained) })
        assertTrue(BrikkHosts.state(chained) is BrikkHostState.Recognized)
        val cyclic = host("""
            typealias A = B
            typealias B = A
            @A fun query() = println("SELECT 1")
        """.trimIndent())
        assertFalse(dumb { BrikkInspectionView.isInspectionOnly(cyclic) })
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(cyclic) })
    }

    fun testOlderRuntimeMemberThroughATypealiasIsPendingWhenSmart() {
        val host = host("typealias Queries = dev.brikk.house.sql.runtime.Sql\n@BrikkSql fun query() = Queries.snowflake(\"SELECT 1\")")
        assertTrue(BrikkKotlinContext.inspect(host).toString(), BrikkKotlinContext.inspect(host) is ContextResult.Unavailable)
        assertTrue(BrikkInspectionView.isInspectionOnly(host))
        val cyclic = host("typealias A = B\ntypealias B = A\n@BrikkSql fun query() = A.snowflake(\"SELECT 1\")")
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(cyclic))
        assertFalse(BrikkInspectionView.isInspectionOnly(cyclic))
    }

    // Probes 11-13: remembered decisions are reused only under the same bindings and typealias targets.
    fun testNegativeFingerprintMustNoticeRemovedParameterShadow() {
        // Explicit imports keep the probe's expectation: once the shadow is gone the claim is strong again.
        val host = explicitHost("class Fake { fun duckdb(sql: String) = sql }\n@BrikkSql fun query(Sql: Fake) = Sql.duckdb(\"SELECT 1\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        val state = dumb {
            edit("Sql: Fake", "other: Fake")
            assertSame("Probe requires PSI host to survive", host, currentHost())
            BrikkHosts.state(currentHost())
        }
        assertTrue(state.toString(), state is BrikkHostState.Pending)
        assertTrue(BrikkHosts.state(currentHost()) is BrikkHostState.Recognized)
    }

    fun testNegativeWithOnlyAStarImportIsNoDecisionButBlocksExecute() {
        // Policy split: the same edit with a star import gives no claim, while the guard still blocks.
        val host = host("class Fake { fun duckdb(sql: String) = sql }\n@BrikkSql fun query(Sql: Fake) = Sql.duckdb(\"SELECT 1\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        dumb {
            edit("Sql: Fake", "other: Fake")
            assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(currentHost()))
            assertTrue(BrikkInspectionView.isInspectionOnly(currentHost()))
        }
    }

    fun testPositiveFingerprintMustNoticeIntroducedParameterShadow() {
        val host = host("class Fake { fun duckdb(sql: String) = sql }\n@BrikkSql fun query(other: Fake) = Sql.duckdb(\"SELECT 1\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        val state = dumb {
            edit("other: Fake", "Sql: Fake")
            assertSame("Probe requires PSI host to survive", host, currentHost())
            BrikkHosts.state(currentHost())
        }
        assertEquals(BrikkHostState.NotBrikk, state)
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(currentHost()))
    }

    fun testNegativeFingerprintMustNoticeSameNamedTypealiasTargetEdit() {
        // Policy split: the probe expected a claim; a typealias is weak evidence, so no claim but Execute is blocked.
        val host = host("annotation class Fake\ntypealias Query = Fake\n@Query fun query() = Sql.duckdb(\"SELECT 1\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        dumb {
            edit("typealias Query = Fake", "typealias Query = dev.brikk.house.sql.runtime.BrikkSql")
            assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(currentHost()))
            assertTrue(BrikkInspectionView.isInspectionOnly(currentHost()))
        }
        assertTrue(BrikkHosts.state(currentHost()) is BrikkHostState.Recognized)
    }

    fun testRememberedProjectionIsDroppedWhenATypealiasTargetChanges() {
        val host = explicitHost("typealias Query = BrikkSql\nannotation class Fake\n@Query fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        dumb {
            edit("typealias Query = BrikkSql", "typealias Query = Fake")
            assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(currentHost()))
        }
    }

    fun testNoEvidenceAtAllIsNeverInspectionOnly() {
        val host = host("annotation class Other\n@Other fun query() = println(\"SELECT 1\")")
        assertFalse(dumb { BrikkInspectionView.isInspectionOnly(host) })
    }
}
