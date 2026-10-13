package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.*
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkRelationFactsTest : BrikkFixtureCase() {
    private val provider = BrikkRelationFacts()

    private fun <T> dumb(action: () -> T): T =
        DumbModeTestUtils.computeInDumbModeSynchronously(project, com.intellij.openapi.util.ThrowableComputable<T, Throwable> { action() })

    private fun fragment(body: String): PsiFile = injectedFile(host(body))

    private fun available(file: PsiFile): BrikkRelationFactsResult.Available {
        val result = provider.facts(file)
        assertTrue(result.toString(), result is BrikkRelationFactsResult.Available)
        return result as BrikkRelationFactsResult.Available
    }

    private val events = """
        import java.time.Instant
        @BrikkTrait interface RawEvent : Partial { val id: Long; val event_at: Instant }
        interface Closed : Shape { val code: String? }
    """.trimIndent()

    fun testContractsBecomeSlotFactsWithKotlinDisplayTypesAndNoInventedSqlType() {
        val facts = available(fragment("""
            $events
            @BrikkSql fun <T : RawEvent> query(events: Rel<T>, closed: Rel<Closed>, any: Rel<Partial>, n: Int) =
                Sql.duckdb("FROM ${'$'}events() |> WHERE id > ${'$'}n")
        """.trimIndent())).facts
        assertEquals(setOf("events", "closed", "any"), facts.slots.keys)
        assertTrue(facts.tables.isEmpty() && facts.calls.isEmpty())
        val events = facts.slots.getValue("events")
        assertEquals(Knowledge.PARTIAL, events.knowledge)
        val eventAt = events.columns.single { it.name == "event_at" }
        assertEquals("Instant", eventAt.displayType)
        assertNull(eventAt.sqlType)
        assertEquals(Nullability.NON_NULL, eventAt.nullability)
        assertEquals(ColumnOrigin.DECLARED, eventAt.origin)
        val closed = facts.slots.getValue("closed")
        assertEquals(Knowledge.FULL, closed.knowledge)
        assertEquals(Nullability.NULLABLE, closed.columns.single().nullability)
        assertEquals("String", closed.columns.single().displayType)
        assertEquals(Knowledge.PARTIAL, facts.slots.getValue("any").knowledge)
        assertTrue(facts.slots.getValue("any").columns.isEmpty())
        // Provenance ids are distinct per column and per relation.
        val ids = facts.slots.values.flatMap { relation -> relation.columns.map { it.provenance } + relation.provenance }
        assertEquals(ids.size, ids.filterNotNull().toSet().size)
    }

    fun testUnsupportedContractIsUnknownNotMissing() {
        val facts = available(fragment("""
            interface NotARow { val id: Int }
            @BrikkSql fun query(src: Rel<NotARow>) = Sql.duckdb("FROM ${'$'}src()")
        """.trimIndent())).facts
        val src = facts.slots.getValue("src")
        assertEquals(Knowledge.UNKNOWN, src.knowledge)
        assertTrue(src.columns.isEmpty())
        assertNotNull(src.provenance)
    }

    fun testProvenanceNavigatesToTheKotlinPropertyAndRelParameter() {
        val file = fragment("""
            $events
            @BrikkSql fun <T : RawEvent> query(events: Rel<T>) = Sql.trino("FROM ${'$'}events()")
        """.trimIndent())
        val relation = available(file).facts.slots.getValue("events")
        val property = provider.navigationTarget(file, relation.columns.single { it.name == "id" }.provenance!!)
        assertEquals("id", (property as KtProperty).name)
        val parameter = provider.navigationTarget(file, relation.provenance!!)
        assertEquals("events", (parameter as KtParameter).name)
        assertNull(provider.navigationTarget(file, "brikk:column:events:missing"))
    }

    fun testNavigationReusesCachedFactsWhilePsiIsUnchangedAndIsNullWhilePending() {
        val file = fragment("""
            $events
            @BrikkSql fun <T : RawEvent> query(events: Rel<T>) = Sql.duckdb("FROM ${'$'}events()")
        """.trimIndent())
        val first = available(file)
        val id = first.facts.slots.getValue("events").columns.single { it.name == "id" }.provenance!!
        // Find usages asks per candidate: with unchanged PSI each ask reuses the same computed facts.
        repeat(3) { assertEquals("id", (provider.navigationTarget(file, id) as KtProperty).name) }
        assertSame(first.facts, available(file).facts)
        // While analysis is unavailable there are no current facts, so no target rather than a stale one.
        assertNull(dumb { provider.navigationTarget(file, id) })
    }

    fun testPendingWhileKotlinAnalysisIsUnavailable() {
        val file = injectedFile(explicitHost("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")"))
        assertTrue(dumb { provider.facts(file) } is BrikkRelationFactsResult.Pending)
        assertTrue(provider.facts(file) is BrikkRelationFactsResult.Available)
        // Previously recognized and pending again: still pending, never stale facts.
        assertTrue(dumb { provider.facts(file) } is BrikkRelationFactsResult.Pending)
    }

    fun testOtherFragmentsAndOtherDialectsHaveNoFacts() {
        assertNull(provider.facts(fragment("fun plain() = println(\"SELECT 1\")")))
        val trino = fragment("@BrikkSql fun query(n: Int) = Sql.trino(\"SELECT \$n\")")
        assertNull(BrikkRelationFacts(BrikkDialect.DUCKDB).facts(trino))
        assertTrue(BrikkRelationFacts(BrikkDialect.TRINO).facts(trino) is BrikkRelationFactsResult.Available)
        assertNull(provider.facts(myFixture.file))
    }

    fun testRevisionIsStableUntilTheContractChanges() {
        val first = available(fragment("""
            interface Event : Partial { val id: Int }
            @BrikkSql fun query(rows: Rel<Event>) = Sql.duckdb("FROM ${'$'}rows()")
            fun other() = 1
        """.trimIndent()))
        val again = available(injectedFile(lastHost()))
        assertEquals(first.revision, again.revision)
        edit("fun other() = 1", "fun other() = 2")
        assertEquals(first.revision, available(injectedFile(lastHost())).revision)
        edit("val id: Int", "val id: String?")
        val changed = available(injectedFile(lastHost()))
        assertFalse(first.revision == changed.revision)
        assertEquals("String", changed.facts.slots.getValue("rows").columns.single().displayType)
        assertEquals(Nullability.NULLABLE, changed.facts.slots.getValue("rows").columns.single().nullability)
    }

    fun testBrikkLabelsArePlainData() {
        assertEquals(BrikkColumnLabels("brikk column", "relation contract", brikkColumnStyle = true), BrikkRelationFacts.LABELS)
    }

    fun testNonKotlinInjectionHostsAreNeverBrikk() {
        val java = myFixture.addFileToProject("J.java", "class J { String sql = \"SELECT 1\"; }")
        val literal = PsiTreeUtil.findChildOfType(java, com.intellij.psi.PsiLiteralExpression::class.java) as com.intellij.psi.PsiLanguageInjectionHost
        val fragment = injectedFile(literal)
        assertNull(provider.facts(fragment))
        assertNull(provider.navigationTarget(fragment, "brikk:relation:src"))
        assertFalse(BrikkInspectionView.isInspectionOnly(fragment, 0))
        assertEquals(BrikkHostState.NotBrikk, BrikkHosts.state(literal))
    }

    fun testDialectProviderAnswersPendingHostsWithoutAKnownDialectOnly() {
        val postgres = injectedFile(explicitHost("@BrikkSql fun query(n: Int) = Sql.postgres(\"SELECT \$n\")"))
        assertTrue(dumb { BrikkRelationFacts(BrikkDialect.DUCKDB).facts(postgres) } is BrikkRelationFactsResult.Pending)
        val trino = injectedFile(explicitHost("@BrikkSql fun query(n: Int) = Sql.trino(\"SELECT \$n\")"))
        assertNull(dumb { BrikkRelationFacts(BrikkDialect.DUCKDB).facts(trino) })
        assertTrue(dumb { BrikkRelationFacts(BrikkDialect.TRINO).facts(trino) } is BrikkRelationFactsResult.Pending)
    }

    private fun replace(from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            val start = document.text.indexOf(from)
            document.replaceString(start, start + from.length, to)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    fun testNavigationFollowsTheCurrentContractNeverAStaleOne() {
        val file = fragment("""
            interface A : Partial { val id: Int }
            interface B : Partial { val id: Int }
            @BrikkSql fun query(src: Rel<A>) = Sql.duckdb("FROM ${'$'}src()")
        """.trimIndent())
        val id = available(file).facts.slots.getValue("src").columns.single().provenance!!
        assertEquals("A", PsiTreeUtil.getParentOfType(provider.navigationTarget(file, id), org.jetbrains.kotlin.psi.KtClass::class.java)?.name)
        replace("Rel<A>", "Rel<B>")
        val target = provider.navigationTarget(injectedFile(lastHost()), id)
        assertEquals("B", PsiTreeUtil.getParentOfType(target, org.jetbrains.kotlin.psi.KtClass::class.java)?.name)
    }

    fun testNavigationOfARenamedColumnIsGone() {
        val file = fragment("""
            interface A : Partial { val id: Int }
            @BrikkSql fun query(src: Rel<A>) = Sql.duckdb("FROM ${'$'}src()")
        """.trimIndent())
        val id = available(file).facts.slots.getValue("src").columns.single().provenance!!
        replace("val id:", "val other:")
        assertNull(provider.navigationTarget(injectedFile(lastHost()), id))
    }

    fun testRenameWriteActionNeverRunsKotlinAnalysisAndKeepsTargets() {
        // The contract follows the host so the rename does not move the host string.
        val file = fragment("""
            @BrikkSql fun query(src: Rel<Row>) = Sql.plain("SELECT e.code FROM ${'$'}src() AS e WHERE e.code IS NOT NULL")
            interface Row : Shape { val code: String? }
        """.trimIndent())
        val before = available(file)
        val code = before.facts.slots.getValue("src").columns.single().provenance!!
        WriteCommandAction.runWriteCommandAction(project) {
            (provider.navigationTarget(file, code) as KtProperty).setName("renamed")
            // The rename's first PSI change invalidated the cache; no Kotlin analysis may run here.
            val during = provider.facts(file)
            assertTrue(during.toString(), during is BrikkRelationFactsResult.Available)
            assertEquals(before.facts, (during as BrikkRelationFactsResult.Available).facts)
            assertEquals("renamed", (provider.navigationTarget(file, code) as KtProperty).name)
            val state = BrikkHosts.state(lastHost())
            assertEquals(state.toString(), BrikkPendingCause.ANALYSIS_PROHIBITED, (state as BrikkHostState.Pending).cause)
        }
        // Facts served during the write action were not cached as fresh.
        val after = available(injectedFile(lastHost()))
        assertEquals("renamed", after.facts.slots.getValue("src").columns.single().name)
        assertFalse(after.revision == before.revision)
    }

    fun testWithoutEarlierFactsAWriteActionIsPending() {
        val file = fragment("@BrikkSql fun query(src: Rel<Row>) = Sql.plain(\"SELECT 1 FROM \$src()\")\ninterface Row : Shape { val id: Int }")
        WriteCommandAction.runWriteCommandAction(project) {
            val result = provider.facts(file)
            assertEquals(result.toString(), BrikkPendingCause.ANALYSIS_PROHIBITED, (result as BrikkRelationFactsResult.Pending).cause)
        }
        assertTrue(provider.facts(file) is BrikkRelationFactsResult.Available)
    }

    private fun lastHost(): KtStringTemplateExpression =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last { it.text.contains("FROM") }

    private fun edit(from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(myFixture.editor.document.text.replace(from, to))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }
}
