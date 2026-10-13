package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.progress.ProcessCanceledException
import dev.brikk.house.intellij.*

/** The opt-in platform queries: any failure is "unknown", never a missing-plugin action, and permission falls back to write access. */
class BrikkPlatformApiTest : BrikkFixtureCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("module.yaml", "product: jvm/app\n")
    }

    override fun tearDown() {
        try { BrikkPlatformApi.compilerPluginOverride = null } finally { super.tearDown() }
    }

    // A row type no compiler generates, so the outcome depends only on the plugin answer, whatever
    // facet earlier generated-lane fixtures left on the shared light project.
    private val generated = """
        @BrikkSql fun query(rows: Rel<NoSuchOut>) = Sql.plain("FROM ${'$'}rows()")
    """.trimIndent()

    fun testFailuresAndChangedApisAreUnknown() {
        assertEquals(true, BrikkPlatformApi.guarded { true })
        assertEquals(false, BrikkPlatformApi.guarded { false })
        assertNull(BrikkPlatformApi.guarded { null })
        for (failure in listOf(NoSuchMethodError("getRegisteredExtensions"), NoClassDefFoundError("KotlinCompilerPluginsProvider"),
            AbstractMethodError(), IncompatibleClassChangeError(), IllegalStateException("not registered"), UnsupportedOperationException())) {
            assertNull(failure.toString(), BrikkPlatformApi.guarded { throw failure })
        }
        try {
            BrikkPlatformApi.guarded { throw ProcessCanceledException() }
            fail("Cancellation must propagate")
        } catch (_: ProcessCanceledException) {
        }
    }

    fun testUnknownNeverReportsAMissingCompilerPlugin() {
        BrikkPlatformApi.compilerPluginOverride = { throw NoSuchMethodError("getRegisteredExtensions") }
        val result = BrikkRelationFacts().facts(injectedFile(host(generated)))
        assertEquals(BrikkPendingCause.UNRESOLVED, (result as BrikkRelationFactsResult.Pending).cause)
        assertTrue(BrikkHosts.actionsNeeded(myFixture.file).none { it.kind == BrikkActionKind.COMPILER_PLUGIN_MISSING })
    }

    fun testAKnownAbsentPluginIsStillReported() {
        BrikkPlatformApi.compilerPluginOverride = { false }
        val result = BrikkRelationFacts().facts(injectedFile(host(generated)))
        assertEquals(BrikkPendingCause.COMPILER_PLUGIN_MISSING, (result as BrikkRelationFactsResult.Pending).cause)
        assertTrue(BrikkHosts.actionsNeeded(myFixture.file).any { it.kind == BrikkActionKind.COMPILER_PLUGIN_MISSING })
    }

    fun testAnalysisIsNotAllowedInAWriteAction() {
        val host = host(generated)
        assertTrue(BrikkPlatformApi.analysisAllowed(host))
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            assertFalse(BrikkPlatformApi.analysisAllowed(host))
        }
    }
}
