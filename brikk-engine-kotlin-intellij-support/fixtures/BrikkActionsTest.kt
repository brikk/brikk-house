package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkActionsTest : BrikkFixtureCase() {
    private fun <T> dumb(action: () -> T): T = DumbModeTestUtils.computeInDumbModeSynchronously(project, ThrowableComputable<T, Throwable> { action() })

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("module.yaml", "product: jvm/app\n")
    }

    // Typed pending causes

    fun testPendingCausesAreTyped() {
        val host = explicitHost("@BrikkSql fun query(n: Int) = Sql.plain(\"SELECT \$n\")")
        assertEquals(BrikkPendingCause.INDEXING, (dumb { BrikkHosts.state(host) } as BrikkHostState.Pending).cause)
        val missing = explicitHost("@BrikkSql fun query(n: Int) = Sql.snowflake(\"SELECT \$n\")")
        assertEquals(BrikkPendingCause.RUNTIME_ENTRY_MISSING, (BrikkHosts.state(missing) as BrikkHostState.Pending).cause)
        val unresolved = host("import dev.brikk.house.sql.runtime.Sql.snowflake as sql\n@BrikkSql fun query(n: Int) = sql(\"SELECT \$n\")", EXPLICIT_IMPORTS)
        assertEquals(BrikkPendingCause.UNRESOLVED, (BrikkHosts.state(unresolved) as BrikkHostState.Pending).cause)
    }

    fun testFactsPendingCauseNamesTheMissingCompilerPlugin() {
        val host = host("""
            @BrikkSql fun source() = Sql.plain("SELECT 1 AS id")
            @BrikkSql fun query(rows: Rel<SourceOut>) = Sql.plain("FROM ${'$'}rows()")
        """.trimIndent())
        val result = BrikkRelationFacts().facts(injectedFile(host))
        assertEquals(BrikkPendingCause.COMPILER_PLUGIN_MISSING, (result as BrikkRelationFactsResult.Pending).cause)
        val fresh = injectedFile(explicitHost("@BrikkSql fun q(n: Int) = Sql.plain(\"SELECT \$n\")"))
        assertEquals(BrikkPendingCause.INDEXING, (dumb { BrikkRelationFacts().facts(fresh) } as BrikkRelationFactsResult.Pending).cause)
    }
}
