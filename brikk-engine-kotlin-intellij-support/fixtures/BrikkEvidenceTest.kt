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

    fun testNoEvidenceAtAllIsNeverInspectionOnly() {
        val host = host("annotation class Other\n@Other fun query() = println(\"SELECT 1\")")
        assertFalse(dumb { BrikkInspectionView.isInspectionOnly(host) })
    }
}
