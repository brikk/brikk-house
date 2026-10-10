package dev.brikk.house.sql.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

internal const val BUILD_STAMP = "META-INF/brikk-engine-kotlin-compiler-plugin.build"
internal const val PRIVATE_PREFIX = "dev/brikk/house/sql/compiler/internal/"

/** Runtime and compiler API identities must stay public; only owned dependencies move. */
internal fun relocateName(name: String): String = when {
    name.startsWith("dev/brikk/house/sql/compiler/") || name.startsWith("dev/brikk/house/sql/runtime/") -> name
    name.startsWith("dev/brikk/house/sql/") -> PRIVATE_PREFIX + "sql/" + name.removePrefix("dev/brikk/house/sql/")
    name.startsWith("kotlinx/serialization/") -> PRIVATE_PREFIX + name
    else -> name
}

internal fun shadePluginJar(sources: List<Path>, output: Path, compilerVersion: String, libVersion: String,
    provenance: Map<String, String> = emptyMap()) {
    require(sources.isNotEmpty())
    require(compilerVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")))
    require(libVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")))
    val entries = sortedMapOf<String, ByteArray>()
    val services = sortedMapOf<String, MutableSet<String>>()
    val classNames = sources.flatMap { source ->
        ZipFile(source.toFile()).use { jar -> jar.entries().asSequence()
            .filter { it.name.endsWith(".class") }.map { it.name.removeSuffix(".class") }.toList() }
    }.toSet()
    val remapper = object : Remapper(Opcodes.ASM9) {
        override fun map(internalName: String): String = relocateName(internalName)
        override fun mapValue(value: Any?): Any? {
            // Class.forName names are not descriptors. Only rewrite actual bundled class
            // identities, not arbitrary SQL text or compiler option strings.
            if (value is String && value.replace('.', '/') in classNames) {
                return if ('/' in value) map(value) else map(value.replace('.', '/')).replace('/', '.')
            }
            return super.mapValue(value)
        }
    }
    for (source in sources) {
        ZipFile(source.toFile()).use { jar ->
            for (entry in jar.entries().asSequence()) {
                val name = entry.name
                if (entry.isDirectory || name == "META-INF/MANIFEST.MF" || name == "META-INF/INDEX.LIST" ||
                    name.endsWith(".kotlin_module") || name.endsWith("module-info.class") ||
                    (name.startsWith("META-INF/") && name.substringAfterLast('.').uppercase() in setOf("SF", "RSA", "DSA", "EC"))) continue
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                if (name.startsWith("META-INF/services/")) {
                    val service = relocateName(name.removePrefix("META-INF/services/").replace('.', '/')).replace('/', '.')
                    val providers = services.getOrPut("META-INF/services/$service") { sortedSetOf() }
                    bytes.toString(Charsets.UTF_8).lineSequence().map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }.forEach { providers += relocateName(it.replace('.', '/')).replace('/', '.') }
                    continue
                }
                val target: String
                val content: ByteArray
                if (name.endsWith(".class")) {
                    val internalName = name.removeSuffix(".class")
                    require(internalName.startsWith("dev/brikk/house/sql/compiler/") || relocateName(internalName) != internalName) {
                        "Unexpected unrelocated dependency class '$name' in ${source.name}; explicitly classify it before bundling"
                    }
                    target = relocateName(name)
                    val writer = ClassWriter(0)
                    // A compiler plugin is not a consumable Kotlin library. Drop Kotlin
                    // metadata rather than ship stale encoded type names after relocation.
                    // JVM signatures and generated serialization code are retained/remapped.
                    val stripMetadata = object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                            if (descriptor == "Lkotlin/Metadata;") null else super.visitAnnotation(descriptor, visible)
                    }
                    ClassReader(bytes).accept(ClassRemapper(stripMetadata, remapper), 0)
                    content = writer.toByteArray()
                } else {
                    // Preserve each input's legal/proguard resources without first-wins loss.
                    target = if (name.startsWith("META-INF/") &&
                        (name.contains("LICENSE", true) || name.contains("NOTICE", true) || name.contains("proguard"))) {
                        "META-INF/bundled/${source.name}/" + name.removePrefix("META-INF/")
                    } else relocateName(name)
                    content = bytes
                }
                val previous = entries.putIfAbsent(target, content)
                require(previous == null || (!target.endsWith(".class") && previous.contentEquals(content))) {
                    "Duplicate shaded entry '$target' in ${source.name}"
                }
            }
        }
    }
    services.forEach { (name, providers) -> entries[name] = (providers.joinToString("\n") + "\n").toByteArray() }
    val hashes = sources.joinToString("\n") { "input.${it.name}=${sha256(Files.readAllBytes(it))}" }
    require(provenance.keys.all { it in setOf("compilerArtifact", "compilerSha256", "sourceAdapter") })
    require(provenance.values.none { '\n' in it || '\r' in it })
    val evidence = provenance.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
    entries[BUILD_STAMP] = "compilerVersion=$compilerVersion\nlibVersion=$libVersion\nrelocated=true\n$hashes\n$evidence\n".toByteArray()
    output.parent.createDirectories()
    val temporary = Files.createTempFile(output.parent, ".shaded-", ".jar")
    try {
        ZipOutputStream(Files.newOutputStream(temporary).buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).also { it.time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
    println("wrote $output (${entries.size} entries; dependencies relocated)")
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
