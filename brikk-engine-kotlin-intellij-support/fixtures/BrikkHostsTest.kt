package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkHostsTest : BrikkFixtureCase() {
    private fun <T> dumb(action: () -> T): T = DumbModeTestUtils.computeInDumbModeSynchronously(project, com.intellij.openapi.util.ThrowableComputable<T, Throwable> { action() })

    private fun pending(host: KtStringTemplateExpression): BrikkHostState.Pending {
        val state = dumb { BrikkHosts.state(host) }
        assertTrue(state.toString(), state is BrikkHostState.Pending)
        return state as BrikkHostState.Pending
    }

    fun testResolvedHostIsRecognized() {
        val state = BrikkHosts.state(host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")"))
        assertTrue(state.toString(), state is BrikkHostState.Recognized)
        assertEquals(BrikkDialect.DUCKDB, state.dialect)
        assertEquals(listOf("n"), (state as BrikkHostState.Recognized).context.binds)
    }

    fun testPreviouslyRecognizedHostIsProjectedAsPendingWhileAnalysisIsUnavailable() {
        val host = host("""
            interface Event : Partial { val id: Int }
            @BrikkSql fun query(src: Rel<Event>, n: Int) = Sql.trino("FROM ${'$'}src() |> WHERE id > ${'$'}n".trimIndent())
        """.trimIndent())
        val recognized = BrikkHosts.state(host, includeShapes = true) as BrikkHostState.Recognized
        val state = pending(host)
        val projection = checkNotNull(state.projection) { "A previously recognized host keeps a pending projection" }
        assertEquals(BrikkDialect.TRINO, state.dialect)
        assertEquals(listOf("trimIndent"), projection.trims)
        assertEquals(recognized.context.entries.map { it.range }, projection.entries.map { it.range })
        // No remembered role is reused: every interpolation is pending, literal text stays.
        val interpolations = projection.entries.filter { it.role != EntryRole.LITERAL }
        assertEquals(listOf(EntryRole.PENDING, EntryRole.PENDING), interpolations.map { it.role })
        assertEquals(listOf(":__brikk_pending_1", ":__brikk_pending_3"), interpolations.map { it.replacement })
        assertTrue(interpolations.all { it.problem == state.reason })
        assertTrue(projection.entries.filter { it.role == EntryRole.LITERAL }.all { it.replacement == null })
        assertTrue(projection.binds.isEmpty() && projection.slots.isEmpty())
        assertTrue(projection.relations.isEmpty())
        assertTrue(projection.uncertain)
        assertFalse(projection.executable)
    }

    fun testNeverRecognizedBrikkHostIsPendingWithoutProjection() {
        val state = pending(host("@BrikkSql fun query(n: Int) = Sql.doris(\"SELECT \$n\")"))
        assertNull(state.projection)
        assertEquals(BrikkDialect.DORIS, state.dialect)
    }

    fun testPendingDialectHintFollowsAnImportAlias() {
        val state = pending(host("""
            import dev.brikk.house.sql.runtime.BrikkSql as Query
            import dev.brikk.house.sql.runtime.Sql.clickhouse as sql
            @Query fun query(n: Int) = sql("SELECT ${'$'}n")
        """.trimIndent()))
        assertNull(state.projection)
        assertEquals(BrikkDialect.CLICKHOUSE, state.dialect)
    }

    fun testPendingWithAnUnknownCalleeHasNoDialectHint() {
        val state = pending(host("@BrikkSql fun query(n: Int) = Sql.postgres(\"SELECT \$n\")"))
        assertNull(state.dialect)
    }

    fun testUnannotatedHostIsNotClaimedWhileAnalysisIsUnavailable() {
        val host = host("fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
        assertNull(BrikkHostState.NotBrikk.dialect)
    }

    fun testForgottenHostIsNoLongerProjected() {
        val host = host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        BrikkHosts.forget(host)
        assertNull(pending(host).projection)
    }

    fun testOtherStringsAreNotBrikk() {
        val host = host("@BrikkSql fun query(n: Int) = listOf(\"SELECT \$n\", \"x\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
    }

    private fun edit(from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val start = document.text.indexOf(from)
            check(start >= 0) { from }
            document.replaceString(start, start + from.length, to)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    private fun sqlHost(marker: String = "SELECT") =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).first { it.text.contains(marker) }

    fun testNativeSqlInsideAnAnnotatedFunctionIsNeverClaimed() {
        host("""
            fun native(@org.intellij.lang.annotations.Language("SQL") text: String) = Unit
            @BrikkSql fun query() {
                native("SELECT 123")
                return Sql.duckdb("FROM real_table")
            }
        """.trimIndent())
        val plain = sqlHost("123")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(plain))
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(plain) })
    }

    fun testLookalikeAnnotationConfersNoOwnershipWhileIndexing() {
        val host = host("annotation class BrikkSql\n@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
    }

    fun testLookalikeEntryObjectConfersNoOwnershipWhileIndexing() {
        val host = host("object Sql { fun duckdb(sql: String) = sql }\n@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
    }

    fun testUnresolvedNonEntryCalleeIsNotBrikkWhenSmart() {
        val host = host("@BrikkSql fun query(n: Int) = missing(\"SELECT \$n\")")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(host))
    }

    fun testValidatedNegativeHoldsWhileIndexingUntilItsSyntaxChanges() {
        val host = host("fun String.trimIndent() = this\n@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\".trimIndent())")
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(host))
        assertEquals(BrikkHostState.NotBrikk, dumb { BrikkHosts.state(host) })
        dumb {
            edit(".trimIndent()", ".trimMargin()")
            assertTrue(BrikkHosts.state(sqlHost()) is BrikkHostState.Pending)
        }
    }

    fun testDialectEditWhilePendingDropsTheOldProjection() {
        val host = host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        dumb {
            edit("Sql.duckdb", "Sql.trino")
            val state = BrikkHosts.state(sqlHost()) as BrikkHostState.Pending
            assertNull(state.projection)
            assertEquals(BrikkDialect.TRINO, state.dialect)
        }
    }

    fun testRemovingTheAnnotationWhilePendingWithdrawsOwnership() {
        val host = host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        dumb {
            edit("@BrikkSql ", "")
            assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(sqlHost()))
        }
    }

    fun testSqlTextEditWhilePendingKeepsTheProjectionWithCurrentRanges() {
        val host = host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        dumb {
            edit("SELECT", "SELECT 123,")
            val updated = sqlHost()
            val projection = checkNotNull((BrikkHosts.state(updated) as BrikkHostState.Pending).projection)
            assertEquals(updated.entries.map { HostRange(it.startOffsetInParent, it.startOffsetInParent + it.textLength) }, projection.entries.map { it.range })
        }
    }
}
