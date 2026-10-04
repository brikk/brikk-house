package dev.brikk.house.sql.tooling

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import dev.brikk.house.sql.shape.SchemaCache
import dev.brikk.house.sql.metadata.FunctionCatalog
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ShadePluginJarTest {
    @Test
    fun sourceAdapterRequiresTheReviewedCompilerAndAnExactSourceMatch() {
        val source = "override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>>"
        assertEquals(source.replace("simple", "named"), adaptIdeSource("BrikkSqlFirExtensionRegistrar.kt", source, "2.4.20-ij262-34"))
        assertEquals("unchanged", adaptIdeSource("Other.kt", "unchanged", "2.4.20-ij262-34"))
        assertFailsWith<IllegalStateException> { adaptIdeSource("BrikkSqlFirExtensionRegistrar.kt", "renamed source", "2.4.20-ij262-34") }
        assertFailsWith<IllegalArgumentException> { adaptIdeSource("Other.kt", "unchanged", "2.4.20-ij262-35") }
    }

    @Test
    fun localPublicationKeepsOneArtifactAndNeverCreatesARelabeledVersion() {
        val dir = scratch("brikk-publication-")
        val input = jar(dir, "input.jar", emptyMap())
        val assembled = dir.resolve("assembled").createDirectories()
        val name = "brikk-engine-kotlin-compiler-plugin"
        shadePluginJar(listOf(input), assembled.resolve("$name-2.4.10-0.2.0.jar"), "2.4.10", "0.2.0")
        val repo = dir.resolve("repo")
        publishKefsRepo(assembled, name, "", "2.4.10", "0.2.0", repo)
        val versionDir = repo.resolve("dev/brikk/house/$name/2.4.10-0.2.0")
        val published = versionDir.resolve("$name-2.4.10-0.2.0.jar")
        assertTrue(Files.isRegularFile(published))
        assertEquals(sha256(Files.readAllBytes(published)), Files.readString(versionDir.resolve("${published.fileName}.sha256")))
        assertFalse(Files.readString(versionDir.resolve("$name-2.4.10-0.2.0.pom")).contains("<dependencies>"))
        // Even a counterfeit IDE filename cannot make the CLI binary publishable.
        Files.copy(assembled.resolve("$name-2.4.10-0.2.0.jar"), assembled.resolve("$name-2.4.20-ij262-34-0.2.0.jar"))
        assertFailsWith<IllegalArgumentException> { publishKefsRepo(assembled, name, "2.4.20-ij262-34", "2.4.10", "0.2.0", repo) }
        assertFalse(Files.exists(repo.resolve("dev/brikk/house/$name/2.4.20-ij262-34-0.2.0")))
        // An older assembled version may coexist; publication selects its explicit version.
        shadePluginJar(listOf(input), assembled.resolve("$name-2.4.10-0.1.0.jar"), "2.4.10", "0.1.0")
        publishKefsRepo(assembled, name, "", "2.4.10", "0.2.0", repo)
    }
    @Test
    fun relocatedSchemaCacheDeserializesAPublicSnapshotWithoutPublicDependencyClasses() {
        val dir = scratch("brikk-schema-shaded-")
        val schema = dir.resolve("schema")
        SchemaCache.replace(schema, "sample", "analytics", "doris", "synthetic",
            listOf(CapturedObject("sample", "analytics", "records", "TABLE", listOf(CapturedColumn("id", "BIGINT", nullable = false)))))
        val types = listOf(SchemaCache::class.java, FunctionCatalog::class.java,
            Class.forName("kotlinx.serialization.KSerializer"), Class.forName("kotlinx.serialization.json.Json"))
        val dependencies = types.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct()
        val output = dir.resolve("relocated.jar")
        shadePluginJar(dependencies, output, "2.4.10", "0.2.0")
        val stdlib = kotlin.Unit::class.java.protectionDomain.codeSource.location
        URLClassLoader(arrayOf(output.toUri().toURL(), stdlib), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("kotlinx.serialization.json.Json") }
            val cache = loader.loadClass((PRIVATE_PREFIX + "sql/shape/SchemaCache").replace('/', '.'))
            val catalog = cache.getMethod("load", Path::class.java).invoke(cache.getField("INSTANCE").get(null), schema)
            val tables = catalog.javaClass.getMethod("getTables").invoke(catalog) as Map<*, *>
            assertEquals(setOf("`sample`.`analytics`.`records`"), tables.keys)
            val shape = tables.values.single()!!
            val columns = shape.javaClass.getMethod("getColumns").invoke(shape) as List<*>
            assertEquals("id", columns.single()!!.javaClass.getMethod("getName").invoke(columns.single()))
        }
    }
    @Test
    fun dependenciesDescriptorsAndReflectionNamesMoveButRuntimeAndCompilerIdentitiesDoNot() {
        val dir = scratch("brikk-shade-")
        val dependency = jar(dir, "dependency.jar", mapOf(
            "dev/brikk/house/sql/shape/Example.class" to clazz("dev/brikk/house/sql/shape/Example", "kotlinx/serialization/Example"),
            "kotlinx/serialization/Example.class" to clazz("kotlinx/serialization/Example"),
            "META-INF/dependency.kotlin_module" to byteArrayOf(1),
        ))
        val plugin = jar(dir, "plugin.jar", mapOf(
            "dev/brikk/house/sql/compiler/Example.class" to clazz("dev/brikk/house/sql/compiler/Example", "dev/brikk/house/sql/shape/Example"),
        ))
        val output = dir.resolve("plugin-all.jar")
        shadePluginJar(listOf(plugin, dependency), output, "2.4.10", "0.2.0")
        URLClassLoader(arrayOf(output.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val sql = loader.loadClass((PRIVATE_PREFIX + "sql/shape/Example").replace('/', '.'))
            val serialization = loader.loadClass((PRIVATE_PREFIX + "kotlinx/serialization/Example").replace('/', '.'))
            assertEquals(serialization, sql.getField("value").type)
            val registrar = loader.loadClass("dev.brikk.house.sql.compiler.Example")
            assertEquals(sql, registrar.getField("value").type)
            assertEquals(sql.name, registrar.getMethod("reflectionName").invoke(null))
            assertEquals("dev.brikk.house.sql.runtime.Rel", registrar.getMethod("runtimeName").invoke(null))
            assertTrue(sql.declaredAnnotations.isEmpty()) // no stale Kotlin metadata
            assertFailsWith<ClassNotFoundException> { loader.loadClass("dev.brikk.house.sql.shape.Example") }
        }
        ZipFile(output.toFile()).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".kotlin_module") })
            assertFalse(zip.entries().asSequence().any { it.name.startsWith("kotlinx/serialization/") })
        }
    }

    @Test
    fun serviceProvidersAreMergedDeduplicatedAndRelocated() {
        val dir = scratch("brikk-services-")
        val a = jar(dir, "a.jar", mapOf("META-INF/services/dev.brikk.house.sql.shape.Service" to
            "dev.brikk.house.sql.shape.First\n# comment\n".toByteArray()))
        val b = jar(dir, "b.jar", mapOf("META-INF/services/dev.brikk.house.sql.shape.Service" to
            "dev.brikk.house.sql.shape.Second\ndev.brikk.house.sql.shape.First # duplicate\n".toByteArray()))
        val out = dir.resolve("result.jar")
        shadePluginJar(listOf(a, b), out, "2.4.10", "0.2.0")
        ZipFile(out.toFile()).use { zip ->
            val service = "META-INF/services/" + (PRIVATE_PREFIX + "sql/shape/Service").replace('/', '.')
            val lines = zip.getInputStream(zip.getEntry(service)).reader().readLines()
            assertEquals(listOf("First", "Second").map { (PRIVATE_PREFIX + "sql/shape/$it").replace('/', '.') }, lines)
        }
    }

    @Test
    fun identicalClassDuplicatesAndUnclassifiedDependenciesAreRejected() {
        val dir = scratch("brikk-duplicates-")
        val entries = mapOf("dev/brikk/house/sql/shape/Example.class" to clazz("dev/brikk/house/sql/shape/Example"))
        val a = jar(dir, "a.jar", entries)
        val b = jar(dir, "b.jar", entries)
        assertFailsWith<IllegalArgumentException> { shadePluginJar(listOf(a, b), dir.resolve("out.jar"), "2.4.10", "0.2.0") }
        for (name in listOf("kotlin/String", "org/jetbrains/kotlin/config/CompilerConfiguration", "unknown/Dependency")) {
            val bad = jar(dir, "bad.jar", mapOf("$name.class" to clazz(name)))
            assertFailsWith<IllegalArgumentException> { shadePluginJar(listOf(bad), dir.resolve("out.jar"), "2.4.10", "0.2.0") }
        }
    }

    @Test
    fun signaturesManifestsAndModuleDescriptorsAreDroppedButLegalNoticesArePreserved() {
        val dir = scratch("brikk-resources-")
        val a = jar(dir, "a.jar", mapOf("META-INF/NOTICE" to "notice A".toByteArray(), "META-INF/X.SF" to byteArrayOf(1),
            "META-INF/MANIFEST.MF" to byteArrayOf(1), "META-INF/versions/9/module-info.class" to byteArrayOf(1)))
        val b = jar(dir, "b.jar", mapOf("META-INF/NOTICE" to "notice B".toByteArray()))
        val out = dir.resolve("result.jar")
        shadePluginJar(listOf(a, b), out, "2.4.10", "0.2.0")
        ZipFile(out.toFile()).use { zip ->
            assertEquals(setOf(BUILD_STAMP, "META-INF/bundled/a.jar/NOTICE", "META-INF/bundled/b.jar/NOTICE"),
                zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test
    fun assemblyIsReproducibleAndProvenanceRejectsCompilerRelabels() {
        val dir = scratch("brikk-provenance-")
        val input = jar(dir, "input.jar", mapOf("dev/brikk/house/sql/shape/Example.class" to clazz("dev/brikk/house/sql/shape/Example")))
        val a = dir.resolve("a.jar")
        val b = dir.resolve("b.jar")
        shadePluginJar(listOf(input), a, "2.4.10", "0.2.0")
        shadePluginJar(listOf(input), b, "2.4.10", "0.2.0")
        assertTrue(Files.readAllBytes(a).contentEquals(Files.readAllBytes(b)))
        validatePublication(a, "2.4.10", "2.4.10", "0.2.0")
        assertFailsWith<IllegalArgumentException> { validatePublication(a, "2.4.20-ij262-34", "2.4.20-ij262-34", "0.2.0") }
        assertFailsWith<IllegalArgumentException> { validatePublication(a, "2.4.10", "2.4.10", "0.3.0") }
    }

    @Test
    fun unshadedAndMissingProvenanceCannotBePublished() {
        val dir = scratch("brikk-unshaded-")
        val missing = jar(dir, "missing.jar", emptyMap())
        assertFailsWith<IllegalStateException> { validatePublication(missing, "2.4.10", "2.4.10", "0.2.0") }
        val unshaded = jar(dir, "unshaded.jar", mapOf(BUILD_STAMP to "compilerVersion=2.4.10\nrelocated=false\nlibVersion=0.2.0\n".toByteArray()))
        assertFailsWith<IllegalArgumentException> { validatePublication(unshaded, "2.4.10", "2.4.10", "0.2.0") }
    }

    private fun jar(dir: Path, name: String, entries: Map<String, ByteArray>): Path = dir.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (entry, bytes) -> zip.putNextEntry(ZipEntry(entry)); zip.write(bytes); zip.closeEntry() }
        }
    }

    private fun scratch(prefix: String): Path = createTempDirectory(
        Path.of(System.getProperty("java.io.tmpdir"), "opencode").createDirectories(), prefix)

    private fun clazz(name: String, fieldType: String? = null): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply { visit("d2", "dev/brikk/house/sql/shape/Example"); visitEnd() }
        if (fieldType != null) {
            writer.visitField(Opcodes.ACC_PUBLIC, "value", "L$fieldType;", null, null).visitEnd()
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "reflectionName", "()Ljava/lang/String;", null, null).apply {
                visitCode(); visitLdcInsn(fieldType.replace('/', '.')); visitInsn(Opcodes.ARETURN); visitMaxs(1, 0); visitEnd()
            }
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "runtimeName", "()Ljava/lang/String;", null, null).apply {
            visitCode(); visitLdcInsn("dev.brikk.house.sql.runtime.Rel"); visitInsn(Opcodes.ARETURN); visitMaxs(1, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
