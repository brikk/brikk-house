package dev.brikk.house.sql.compiler.fir

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.toKtPsiSourceElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(
    org.jetbrains.kotlin.K1Deprecation::class,
    CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.fir.PrivateSessionConstructor::class,
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals::class,
)
class RawTypesSourceTest {
    @Test
    fun importedTraitTypesResolveBeforeTheFirProviderKnowsTheOwningFile() = withFactory { factory ->
        val file = factory.createFile("Views.kt", """
            package sample
            import java.time.Instant
            import java.math.BigDecimal
            import dev.brikk.house.sql.runtime.*
            interface RawEvent { val event_at: Instant; val amount: BigDecimal? }
        """.trimIndent())
        // This is the old failure: without either FIR imports or declaration source,
        // a non-default imported type cannot resolve.
        assertNull(RawTypes(session(), null, file.packageFqName, emptySet()).resolve("Instant"))
        val types = RawTypes(session(), null, file.packageFqName, emptySet(), file.declarations.single().toKtPsiSourceElement())
        assertEquals(id("java.time.Instant"), types.resolve("Instant"))
        assertEquals(id("java.math.BigDecimal"), types.resolve("BigDecimal"))
        assertEquals(id("dev.brikk.house.sql.runtime.Rel"), types.resolve("Rel"))
        assertEquals(id("kotlin.String"), types.resolve("String"))
    }

    @Test
    fun psiFallbackPreservesAliasesAndNestedClassIdentity() = withFactory { factory ->
        val file = factory.createFile("Views.kt", """
            package sample
            import java.time.Instant as Clock
            import java.util.Map as Dictionary
            interface Event { val event_at: Clock }
        """.trimIndent())
        val types = RawTypes(session(), null, file.packageFqName, emptySet(), file.declarations.single().toKtPsiSourceElement())
        assertEquals(id("java.time.Instant"), types.resolve("Clock"))
        assertEquals(mapEntry, types.resolve("Dictionary.Entry"))
        assertNull(types.resolve("Instant")) // An alias must not also import the original short name.
    }

    @Test
    fun importContextIsPerFileAndAmbiguousStarImportsStillFail() = withFactory { factory ->
        fun types(imports: String) = factory.createFile("Views.kt", "package sample\n$imports\ninterface Event").let { file ->
            RawTypes(session(), null, file.packageFqName, emptySet(), file.declarations.single().toKtPsiSourceElement())
        }
        assertEquals(id("java.time.Instant"), types("import java.time.Instant").resolve("Instant"))
        assertEquals(id("other.Instant"), types("import other.Instant").resolve("Instant"))
        assertFailsWith<IllegalStateException> {
            types("import java.time.*\nimport other.*").resolve("Instant")
        }
    }

    @Test
    fun sourcePsiAlsoSuppliesTheSchemaAnchorWithoutAFirFile() = withFactory { factory ->
        val file = factory.createPhysicalFile("Views.kt", "package sample\nfun records() = Unit")
        val source = file.declarations.single().toKtPsiSourceElement()
        assertEquals(file.virtualFile.path, sourceFilePath(null, source))
        assertTrue(sourceFilePath(null, source)!!.endsWith("Views.kt"))
        assertNull(sourceFilePath(null, null))
        assertEquals(emptyList(), sourceImports(null, null))
    }

    private fun withFactory(check: (KtPsiFactory) -> Unit) {
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForTests(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            check(KtPsiFactory(environment.project))
        } finally { ApplicationManager.getApplication().runWriteAction { Disposer.dispose(disposable) } }
    }

    private fun id(name: String) = ClassId.topLevel(FqName(name))
    private val mapEntry = ClassId(FqName("java.util"), FqName("Map.Entry"), false)

    /** No FIR file or predicate index: only class identity lookup is available, as on cold IDE resolution. */
    private fun session(): FirSession {
        val session = object : FirSession(Kind.Source) {}
        val classes = listOf("java.time.Instant", "java.math.BigDecimal", "dev.brikk.house.sql.runtime.Rel", "kotlin.String", "other.Instant")
            .map(::id).plus(mapEntry).associateWith(::FirRegularClassSymbol)
        session.register(FirSymbolProvider::class, object : FirSymbolProvider(session) {
            override val symbolNamesProvider: FirSymbolNamesProvider get() = error("Not used by raw type lookup")
            override fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>? = classes[classId]
            override fun getTopLevelCallableSymbolsTo(destination: MutableList<FirCallableSymbol<*>>, packageFqName: FqName, name: Name) = Unit
            override fun getTopLevelFunctionSymbolsTo(destination: MutableList<FirNamedFunctionSymbol>, packageFqName: FqName, name: Name) = Unit
            override fun getTopLevelPropertySymbolsTo(destination: MutableList<FirPropertySymbol>, packageFqName: FqName, name: Name) = Unit
            override fun hasPackage(fqName: FqName) = false
        })
        return session
    }
}
