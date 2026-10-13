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

    fun testNavigationIsCheapAndNeedsNoKotlinAnalysis() {
        val file = fragment("""
            $events
            @BrikkSql fun <T : RawEvent> query(events: Rel<T>) = Sql.duckdb("FROM ${'$'}events()")
        """.trimIndent())
        val id = available(file).facts.slots.getValue("events").columns.single { it.name == "id" }.provenance!!
        // Analysis is unavailable in dumb mode, yet navigation (as find usages calls it per candidate) still answers.
        assertEquals("id", dumb { (provider.navigationTarget(file, id) as KtProperty).name })
    }

    fun testPendingWhileKotlinAnalysisIsUnavailable() {
        val file = fragment("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")
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

    private fun lastHost(): KtStringTemplateExpression =
        PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last { it.text.contains("FROM") }

    private fun edit(from: String, to: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(myFixture.editor.document.text.replace(from, to))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }
}
