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

    private fun kinds(): Set<BrikkActionKind> = BrikkHosts.actionsNeeded(myFixture.file).map { it.kind }.toSet()

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

    // Actionable status

    fun testIndexingAndRecognizedOwnedHostsNeedNoAction() {
        val host = explicitHost("@BrikkSql fun query(n: Int) = Sql.plain(\"SELECT \$n\")")
        dumb { BrikkHosts.state(host) }
        assertNull(BrikkHosts.actionNeeded(myFixture.file))
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        assertNull(BrikkHosts.actionNeeded(myFixture.file))
    }

    fun testRuntimeWithoutTheEntryPointNeedsAnUpdate() {
        val host = explicitHost("@BrikkSql fun query(n: Int) = Sql.snowflake(\"SELECT \$n\")")
        BrikkHosts.state(host)
        val action = checkNotNull(BrikkHosts.actionNeeded(myFixture.file))
        assertEquals(BrikkActionKind.RUNTIME_OUTDATED, action.kind)
        assertTrue(action.message, action.message.contains("snowflake"))
        assertTrue(action.fixText.isNotBlank())
        assertTrue(action.buildFileUrl!!.endsWith("/module.yaml"))
        assertNotNull(action.docsUrl)
    }

    fun testGeneratedShapeWithoutTheCompilerPluginNeedsThePlugin() {
        val host = host("""
            @BrikkSql fun source() = Sql.plain("SELECT 1 AS id")
            @BrikkSql fun query(rows: Rel<SourceOut>) = Sql.plain("FROM ${'$'}rows()")
        """.trimIndent())
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        assertTrue("Recognition alone reads no shapes", kinds().isEmpty())
        BrikkRelationFacts().facts(injectedFile(host))
        val action = BrikkHosts.actionsNeeded(myFixture.file).single()
        assertEquals(BrikkActionKind.COMPILER_PLUGIN_MISSING, action.kind)
        assertTrue(action.buildFileUrl!!.endsWith("/module.yaml"))
        assertNotNull(action.docsUrl)
    }

    fun testUnresolvedRowTypeWithThePluginAbsentButNoRelIsNoAction() {
        host("@BrikkSql fun query(n: Int) = Sql.plain(\"SELECT \$n\")").let { BrikkRelationFacts().facts(injectedFile(it)) }
        assertTrue(kinds().isEmpty())
    }

    fun testDialectNoInstalledPluginOwnsNeedsAPlugin() {
        BrikkHosts.state(host("@BrikkSql fun query(n: Int) = Sql.clickhouse(\"SELECT \$n\")"))
        val action = BrikkHosts.actionsNeeded(myFixture.file).single()
        assertEquals(BrikkActionKind.DIALECT_PLUGIN_MISSING, action.kind)
        assertEquals(BrikkDialect.CLICKHOUSE, action.dialect)
        assertTrue(action.message, action.message.contains("ClickHouseSQL"))
        assertNotNull(action.docsUrl)
    }

    fun testActionsAreCachedDumbSafeAndClearedWhenFixed() {
        val host = explicitHost("@BrikkSql fun query(n: Int) = Sql.snowflake(\"SELECT \$n\")")
        BrikkHosts.state(host)
        assertEquals(BrikkActionKind.RUNTIME_OUTDATED, dumb { BrikkHosts.actionNeeded(myFixture.file) }?.kind)
        val fragment = injectedFile(host)
        assertEquals(BrikkActionKind.RUNTIME_OUTDATED, BrikkHosts.actionNeeded(fragment)?.kind)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val start = document.text.indexOf("snowflake")
            document.replaceString(start, start + "snowflake".length, "plain")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last()
        assertTrue(BrikkHosts.state(updated) is BrikkHostState.Recognized)
        assertNull(BrikkHosts.actionNeeded(myFixture.file))
    }
}
