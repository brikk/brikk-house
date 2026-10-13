package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** A light project with the Brikk runtime's declarations, not its compiler. */
abstract class BrikkFixtureCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val jdk = com.intellij.openapi.projectRoots.JavaSdk.getInstance().createJdk("Brikk fixture JDK", System.getProperty("java.home"), false)
        WriteCommandAction.runWriteCommandAction(project) { com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable) }
        com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(module, jdk)
        val stdlib = java.io.File(com.intellij.util.PathUtil.getJarPathForClass(Unit::class.java))
        PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        myFixture.addFileToProject("Runtime.kt", """
            package dev.brikk.house.sql.runtime
            @Target(AnnotationTarget.FUNCTION) annotation class BrikkSql
            interface Partial
            interface Shape : Partial
            annotation class BrikkTrait
            class Rel<out T : Partial>
            @Target(AnnotationTarget.FUNCTION) annotation class BrikkSqlDialect(val dialect: String)
            object Sql { fun duckdb(sql: String): Rel<Nothing> = error("compiler")
                fun doris(sql: String): Rel<Nothing> = error("compiler")
                fun trino(sql: String): Rel<Nothing> = error("compiler")
                fun clickhouse(sql: String): Rel<Nothing> = error("compiler")
                @BrikkSqlDialect("postgres") fun postgres(@org.intellij.lang.annotations.Language("BrikkSQL") sql: String): Rel<Nothing> = error("compiler")
                fun raw(sql: String): Rel<Nothing> = error("not an entry point") }
        """.trimIndent())
        myFixture.addFileToProject("Language.kt", """
            package org.intellij.lang.annotations
            annotation class Language(val value: String, val prefix: String = "", val suffix: String = "")
        """.trimIndent())
    }

    protected fun host(body: String, imports: String = "import dev.brikk.house.sql.runtime.*"): KtStringTemplateExpression {
        myFixture.configureByText("Views.kt", "$imports\n$body")
        return PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last { it.text.contains("SELECT") || it.text.contains("FROM") }
    }

    /** A host whose runtime names are explicitly imported: strong evidence for an injection claim while indexing. */
    protected fun explicitHost(body: String): KtStringTemplateExpression = host(body, EXPLICIT_IMPORTS)

    protected companion object {
        const val EXPLICIT_IMPORTS: String = "import dev.brikk.house.sql.runtime.Sql\nimport dev.brikk.house.sql.runtime.BrikkSql\nimport dev.brikk.house.sql.runtime.Rel\nimport dev.brikk.house.sql.runtime.Partial"
    }

    /** Injects plain text into every host of [hosts] until the test ends, as a dialect plugin would inject SQL. */
    protected fun inject(hosts: Set<com.intellij.psi.PsiLanguageInjectionHost>) {
        val injector = object : com.intellij.lang.injection.MultiHostInjector {
            override fun getLanguagesToInject(registrar: com.intellij.lang.injection.MultiHostRegistrar, context: com.intellij.psi.PsiElement) {
                val host = context as? com.intellij.psi.PsiLanguageInjectionHost ?: return
                if (hosts.none { it.javaClass == host.javaClass && it.textRange == host.textRange }) return
                registrar.startInjecting(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE)
                    .addPlace(null, null, host, com.intellij.openapi.util.TextRange(1, host.textLength - 1)).doneInjecting()
            }
            override fun elementsToInjectIn() = hosts.map { it.javaClass }.distinct()
        }
        com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).registerMultiHostInjector(injector, testRootDisposable)
    }

    protected fun injectedFile(host: com.intellij.psi.PsiLanguageInjectionHost): com.intellij.psi.PsiFile {
        inject(setOf(host))
        val files = com.intellij.lang.injection.InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        return checkNotNull(files?.singleOrNull()?.first?.containingFile) { "No injected fragment in ${host.text}" }
    }

    protected fun context(host: KtStringTemplateExpression): BrikkContext {
        val result = BrikkKotlinContext.inspect(host)
        assertTrue(result.toString(), result is ContextResult.Available)
        return (result as ContextResult.Available).context
    }

}
