package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.intellij.*
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class BrikkContextTest : BrikkFixtureCase() {
    companion object {
        @JvmStatic fun suite(): junit.framework.Test {
            val generated = System.getProperty("brikk.fixture.compiler.plugin") != null
            val suite = junit.framework.TestSuite("Brikk Kotlin IDE contracts")
            BrikkContextTest::class.java.methods.filter { it.name.startsWith("test") && it.parameterCount == 0 &&
                (generated || !it.name.startsWith("testActualCompiler")) }.sortedBy { it.name }.forEach { method ->
                suite.addTest(BrikkContextTest().apply { name = method.name })
            }
            return suite
        }
    }

    private fun enableCompiler() {
        val plugin = checkNotNull(System.getProperty("brikk.fixture.compiler.plugin")) { "Generated fixtures require the supported exact IDE compiler" }
        WriteCommandAction.runWriteCommandAction(project) {
            val manager = com.intellij.facet.FacetManager.getInstance(module)
            val existing = org.jetbrains.kotlin.idea.facet.KotlinFacet.get(module)
            val facet = existing ?: manager.createFacet(org.jetbrains.kotlin.idea.facet.KotlinFacetType.INSTANCE, "Kotlin", null)
            facet.configuration.settings.useProjectSettings = false
            facet.configuration.settings.compilerArguments = org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments().apply {
                pluginClasspaths = arrayOf(plugin)
            }
            if (existing == null) {
                val model = manager.createModifiableModel()
                model.addFacet(facet)
                model.commit()
            }
        }
        val runtime = myFixture.findFileInTempDir("Runtime.kt")
        val document = PsiDocumentManager.getInstance(project).getDocument(com.intellij.psi.PsiManager.getInstance(project).findFile(runtime)!!)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("fun duckdb", "@BrikkSqlDialect(\"duckdb\") fun duckdb")
                .replace("fun doris", "@BrikkSqlDialect(\"doris\") fun doris")
                .replace("fun trino", "@BrikkSqlDialect(\"trino\") fun trino"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    fun testActualCompilerGeneratedNamedShapeIsVisibleOnColdAnalysis() {
        enableCompiler()
        val context = context(host("""
            @BrikkSql fun source() = Sql.duckdb("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun query(rows: Rel<SourceOut>) = Sql.duckdb("FROM ${'$'}rows() |> SELECT id, code")
        """.trimIndent()))
        val shape = shape(context, "rows")
        assertEquals(ShapeKnowledge.FULL, shape.knowledge)
        assertEquals(setOf("id", "code"), shape.columns.map { it.name }.toSet())
    }

    fun testActualCompilerRefinedLocalShapeUsesTheExpressionType() {
        enableCompiler()
        host("""
            @BrikkTrait interface HasId : Partial { val id: Int }
            @BrikkSql fun source() = Sql.duckdb("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun <T : HasId> extend(src: Rel<T>) = Sql.duckdb("FROM ${'$'}src() |> EXTEND 2 AS added")
            fun use() { val refined = extend(source()); println(refined) }
        """.trimIndent())
        val expression = PsiTreeUtil.findChildrenOfType(myFixture.file, org.jetbrains.kotlin.psi.KtCallExpression::class.java)
            .single { it.calleeExpression?.text == "extend" }
        val result = BrikkRelationShapes.ofExpression(expression)
        assertTrue(result.toString(), result is ShapeResult.Available)
        assertEquals(setOf("id", "code", "added"), (result as ShapeResult.Available).shape.columns.map { it.name }.toSet())
    }

    private fun shape(context: BrikkContext, name: String): RelationShape {
        val result = context.relations.getValue(name)
        assertTrue(result.toString(), result is ShapeResult.Available)
        return (result as ShapeResult.Available).shape
    }

    fun testAllDialectsResolveWithoutInjectionOrAnInitialEdit() {
        for (dialect in BrikkDialect.entries) {
            val entry = dialect.sqlName
            val context = context(host("@BrikkSql fun query(tenant: String) = Sql.$entry(\"SELECT tenant WHERE tenant = \$tenant\")"))
            assertEquals(dialect, context.dialect)
            assertEquals(listOf("tenant"), context.binds)
            assertEquals(":tenant", context.entries.last().replacement)
            assertFalse(context.executable)
        }
    }

    fun testTrinoAliasRecognizesSlotsBindingsConstantsAndTrimmedHost() {
        val context = context(host("""
            import dev.brikk.house.sql.runtime.BrikkSql as Query
            import dev.brikk.house.sql.runtime.Sql.trino as sql
            interface Event : Partial { val id: Int; val code: String? }
            const val COLS = "id, code"
            @Query fun query(src: Rel<Event>, minimum: Int) = sql("FROM ${'$'}src() |> WHERE id > ${'$'}minimum |> SELECT ${'$'}COLS".trimIndent())
        """.trimIndent()))
        assertEquals(BrikkDialect.TRINO, context.dialect)
        assertEquals("TrinoSQL", context.dialect.languageId)
        assertEquals(listOf("trimIndent"), context.trims)
        assertEquals(listOf("src"), context.slots)
        assertEquals(listOf("minimum"), context.binds)
        assertEquals(EntryRole.SQL_CONSTANT, context.entries.single { it.name == "COLS" }.role)
        assertEquals(setOf("id", "code"), shape(context, "src").columns.map { it.name }.toSet())
        assertFalse(context.uncertain)
        assertFalse(context.executable)
    }

    fun testClickHouseIsARegisteredDialect() {
        val context = context(host("@BrikkSql fun query(n: Int) = Sql.clickhouse(\"SELECT \$n\")"))
        assertEquals(BrikkDialect.CLICKHOUSE, context.dialect)
        assertEquals("ClickHouseSQL", context.dialect.languageId)
    }

    fun testRuntimeDeclaredDialectIsBrikkEvenWithoutAPluginEntry() {
        val context = context(host("@BrikkSql fun query(n: Int) = Sql.postgres(\"SELECT \$n\")"))
        assertEquals("postgres", context.dialect.sqlName)
        assertEquals("BrikkSQL", context.dialect.languageId)
        assertFalse(context.dialect.registered)
        assertEquals(listOf("n"), context.binds)
    }

    fun testUndeclaredRuntimeMemberIsNotAnEntryPoint() {
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(host("@BrikkSql fun query() = Sql.raw(\"SELECT 1\")")))
    }

    fun testParenthesizedArgumentAndTrimReceiverAreStillTheBrikkHost() {
        val context = context(host("@BrikkSql fun query(n: Int) = Sql.duckdb(((\"SELECT \$n\").trimIndent()))"))
        assertEquals(BrikkDialect.DUCKDB, context.dialect)
        assertEquals(listOf("trimIndent"), context.trims)
        assertEquals(listOf("n"), context.binds)
    }

    fun testTrinoLookalikeIsNotBrikk() {
        val host = host("""
            object Sql { fun trino(sql: String): Rel<Nothing> = error("not Brikk") }
            @BrikkSql fun query() = Sql.trino("SELECT 1 AS id")
        """.trimIndent())
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(host))
    }

    fun testActualCompilerGeneratedTrinoShapeIsVisibleOnColdAnalysis() {
        enableCompiler()
        val context = context(host("""
            @BrikkSql fun source() = Sql.trino("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun query(src: Rel<SourceOut>) = Sql.trino("FROM ${'$'}src() |> SELECT id, code")
        """.trimIndent()))
        assertEquals(BrikkDialect.TRINO, context.dialect)
        val shape = shape(context, "src")
        assertEquals(ShapeKnowledge.FULL, shape.knowledge)
        assertEquals(setOf("id", "code"), shape.columns.map { it.name }.toSet())
    }

    fun testAliasesSlotsScalarsAndSourceLiteralsHaveDistinctRoles() {
        val context = context(host("""
            import dev.brikk.house.sql.runtime.BrikkSql as Query
            import dev.brikk.house.sql.runtime.Sql.doris as sql
            typealias Rows = Rel<Partial>
            const val COLS = "id, tenant"
            @Query fun query(src: Rows, n: Int) {
                val local = n
                return sql("FROM ${'$'}src() |> WHERE id > ${'$'}{n} |> SELECT ${'$'}COLS, ${'$'}local")
            }
        """.trimIndent()))
        assertEquals(listOf("src"), context.slots)
        assertEquals(listOf("n", "local"), context.binds)
        assertEquals(EntryRole.SQL_CONSTANT, context.entries.single { it.name == "COLS" }.role)
    }

    fun testUnsupportedAndPendingRemainDifferent() {
        val context = context(host("""
            const val COLS = "id" + ", tenant"
            @BrikkSql fun query(n: Int) = Sql.duckdb("SELECT ${'$'}COLS, ${'$'}{n + 1}, ${'$'}missing")
        """.trimIndent()))
        assertEquals(2, context.entries.count { it.role == EntryRole.UNSUPPORTED })
        assertEquals(1, context.entries.count { it.role == EntryRole.PENDING })
        assertTrue(context.uncertain)
    }

    fun testBinaryConstantsUseCompilerVerifiedLiteralSemantics() {
        val context = context(host("""
            import kotlin.math.PI
            import kotlin.Int.Companion.MAX_VALUE
            @BrikkSql fun query() = Sql.doris("SELECT ${'$'}PI AS ratio, ${'$'}MAX_VALUE AS id")
        """.trimIndent()))
        assertEquals("3.141592653589793", context.entries.single { it.name == "PI" }.replacement)
        assertEquals("2147483647", context.entries.single { it.name == "MAX_VALUE" }.replacement)
        assertFalse(context.uncertain)
    }

    fun testLiteralInterpolationKeepsNullAndDollarAsText() {
        val context = context(host("""
            @BrikkSql fun query() = Sql.duckdb("SELECT ${'$'}{1} AS n, ${'$'}{null} AS missing, '${'$'}{'$'}' AS dollar")
        """.trimIndent()))
        assertEquals(listOf("1", "null", "$"), context.entries.filter { it.replacement != null }.map { it.replacement })
        assertTrue(context.binds.isEmpty())
        assertFalse(context.uncertain)
    }

    fun testOnlyRealStdlibTrimsAndRealBrikkCallsActivate() {
        val trim = context(host("@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\".trimIndent())"))
        assertEquals(listOf("trimIndent"), trim.trims)
        val fake = host("fun String.trimIndent() = this\n@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\".trimIndent())")
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(fake))
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(host("fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")))
        assertEquals(ContextResult.NotBrikk, BrikkKotlinContext.inspect(host("annotation class BrikkSql\n@BrikkSql fun query(n: Int) = Sql.duckdb(\"SELECT \$n\")")))
    }

    fun testInheritedTraitColumnsAndGenericBoundsComeFromTypeScopes() {
        val context = context(host("""
            import java.time.Instant
            import java.math.BigDecimal
            @BrikkTrait interface RawEvent : Partial { val id: Long; val event_at: Instant }
            @BrikkTrait interface CleanEvent : RawEvent { val amount: BigDecimal? }
            @BrikkSql fun <T : CleanEvent> query(events: Rel<T>) = Sql.duckdb("FROM ${'$'}events() |> WHERE id > 1")
        """.trimIndent()))
        val shape = shape(context, "events")
        assertEquals(ShapeKnowledge.PARTIAL, shape.knowledge)
        assertEquals(ShapeOrigin.TYPE_BOUND, shape.origin)
        assertEquals(setOf("id", "event_at", "amount"), shape.columns.map { it.name }.toSet())
        val amount = shape.columns.single { it.name == "amount" }
        assertEquals("java.math.BigDecimal", amount.kotlinClassId)
        assertEquals(ColumnNullability.NULLABLE, amount.nullability)
        assertEquals(NavigationKind.PROPERTY, amount.navigation!!.kind)
        assertTrue(amount.navigation!!.location!!.fileUrl.endsWith("Views.kt"))
        assertNull(amount.exactSqlType)
    }

    fun testConcreteShapeVersusBareMarkerAndStarProjection() {
        val context = context(host("""
            interface Closed : Shape { val id: Int }
            @BrikkSql fun query(rows: Rel<Closed>, unknown: Rel<Shape>, star: Rel<*>) = Sql.duckdb("FROM ${'$'}rows()")
        """.trimIndent()))
        assertEquals(ShapeKnowledge.FULL, (context.relations.getValue("rows") as ShapeResult.Available).shape.knowledge)
        for (key in listOf("unknown", "star")) {
            val shape = (context.relations.getValue(key) as ShapeResult.Available).shape
            assertEquals(ShapeKnowledge.PARTIAL, shape.knowledge)
            assertEmpty(shape.columns)
        }
    }

    fun testTraitEditRefreshesShapeWithoutEditingSql() {
        val host = host("""
            interface Event : Partial { val id: Int }
            @BrikkSql fun query(rows: Rel<Event>) = Sql.duckdb("FROM ${'$'}rows()")
        """.trimIndent())
        assertEquals("kotlin.Int", (context(host).relations.getValue("rows") as ShapeResult.Available).shape.columns.single().kotlinClassId)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(myFixture.editor.document.text.replace("val id: Int", "val id: String?"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).last()
        val column = (context(updated).relations.getValue("rows") as ShapeResult.Available).shape.columns.single()
        assertEquals("kotlin.String", column.kotlinClassId)
        assertEquals(ColumnNullability.NULLABLE, column.nullability)
    }

    fun testImportedJavaTypesAndJsonDollarAreNotConfusedWithSqlBinds() {
        val context = context(host("""
            import java.time.Instant as Clock
            interface Event : Partial { val at: Clock }
            @BrikkSql fun query(rows: Rel<Event>, n: Int) = Sql.doris("SELECT '${'$'}.customer_id', ${'$'}n FROM ${'$'}rows()")
        """.trimIndent()))
        assertEquals(listOf("n"), context.binds)
        val column = shape(context, "rows").columns.single()
        assertEquals("java.time.Instant", column.kotlinClassId)
        assertEquals(SqlFamily.TIMESTAMP, column.sqlFamily)
    }
}
