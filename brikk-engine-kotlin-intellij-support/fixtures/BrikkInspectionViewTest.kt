package dev.brikk.house.intellij.fixtures

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkInspectionViewTest : BrikkFixtureCase() {
    private fun <T> dumb(action: () -> T): T =
        DumbModeTestUtils.computeInDumbModeSynchronously(project, com.intellij.openapi.util.ThrowableComputable<T, Throwable> { action() })

    private fun atCaret(body: String): Boolean {
        myFixture.configureByText("Views.kt", "import dev.brikk.house.sql.runtime.*\n$body")
        val editor = myFixture.editor
        val offset = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionStart else editor.caretModel.offset
        return BrikkInspectionView.isInspectionOnly(myFixture.file, offset)
    }

    fun testCaretInRecognizedBrikkStringIsInspectionOnly() {
        assertTrue(atCaret("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT <caret>\$n\")"))
    }

    fun testCaretInOtherStringOrOutsideStringsIsNot() {
        assertFalse(atCaret("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")\nval other = \"SELECT <caret>1\""))
        assertFalse(atCaret("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")\nval <caret>other = 1"))
    }

    fun testSelectionStartDecides() {
        assertTrue(atCaret("@BrikkSql fun query(n: Int) = Sql.duckdb(\"<selection>SELECT \$n\")\nval other = \"x</selection>\""))
    }

    fun testBrikkDialectNoPluginOwnsIsInspectionOnly() {
        assertTrue(atCaret("@BrikkSql fun query(n: Int) = Sql.postgres(\"SELECT <caret>\$n\")"))
    }

    fun testPendingBrikkHostIsInspectionOnlyButUnresolvedPlainStringIsNot() {
        val brikk = host("@BrikkSql fun query(n: Int) = Sql.trino(\"SELECT \$n\")")
        assertTrue(dumb { BrikkInspectionView.isInspectionOnly(brikk) })
        val plain = host("fun query(n: Int) = println(\"SELECT \$n\")")
        assertFalse(dumb { BrikkInspectionView.isInspectionOnly(plain) })
    }

    fun testInjectedFragmentIsJudgedByItsHost() {
        myFixture.configureByText("Views.kt", """
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun query(n: Int) = Sql.doris("SELECT ${'$'}n")
            fun plain() = println("SELECT 1")
        """.trimIndent())
        val (brikk, plain) = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).filter { it.text.contains("SELECT") }
        val brikkFragment = injectedFile(brikk)
        val plainFragment = injectedFile(plain)
        assertTrue(BrikkInspectionView.isInspectionOnly(brikkFragment, 0))
        assertFalse(BrikkInspectionView.isInspectionOnly(plainFragment, 0))
    }

    fun testCaretInNestedLiteralBelongsToTheOuterBrikkTemplate() {
        assertTrue(atCaret("@BrikkSql fun query() = Sql.duckdb(\"SELECT \${\"<caret>1\"} AS n\")"))
        val inner = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single { it.text == "\"1\"" }
        assertTrue(BrikkInspectionView.isInspectionOnly(inner))
    }

    fun testParenthesizedSqlArgumentIsStillInspectionOnly() {
        assertTrue(atCaret("@BrikkSql fun query(n: Int) = Sql.duckdb((\"SELECT <caret>\$n\"))"))
        assertTrue(atCaret("@BrikkSql fun query(n: Int) = Sql.trino(((\"SELECT <caret>\$n\").trimIndent()))"))
    }
}
