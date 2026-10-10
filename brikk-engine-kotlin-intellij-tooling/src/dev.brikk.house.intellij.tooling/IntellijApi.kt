package dev.brikk.house.intellij.tooling

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

private const val VERSION = "2.4.0-ij261-64"
internal const val SDK_BUILD = "261.25134.95"
internal const val SDK_ARTIFACT = "intellij-kotlin-api"

/** Build-only compile SDK. Never publish these JetBrains binaries as part of the support library. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun prepareIntellijApi(@Output outputDir: Path, @Output revisionDir: Path) {
    Files.createDirectories(outputDir)
    val inputs = listOf("kotlin-compiler", "analysis-api-for-ide", "analysis-api-platform-interface-for-ide").map { name ->
        val target = outputDir.resolve("$name-$VERSION.jar")
        if (!Files.isRegularFile(target)) {
            val url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/$name/$VERSION/${target.fileName}"
            val temporary = Files.createTempFile(outputDir, ".download-", ".jar")
            try {
                val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(temporary))
                check(response.statusCode() == 200) { "SDK download failed: $url (${response.statusCode()})" }
                ZipFile(temporary.toFile()).use { check(it.size() > 0) }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        target
    } + listOf(outputDir.resolve("kotlin-stdlib-2.4.10.jar").also { target ->
        if (!Files.isRegularFile(target)) {
            val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI("https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.10/${target.fileName}")).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
            check(response.statusCode() == 200) { "Kotlin stdlib download failed" }
        }
    })
    val expected = mapOf(
        "kotlin-compiler-$VERSION.jar" to "483b4f072e10939e65740e25a43fabba52ab09b1aa87874c001cb3f9d4b52bf5",
        "analysis-api-for-ide-$VERSION.jar" to "ce02d3afcf74e5d545c52c5cad1dd7fc51eb218c0b41d073c39c065a084ddbe2",
        "analysis-api-platform-interface-for-ide-$VERSION.jar" to "3ce18bd1aa9de4df6602aac4500c0f946c53ae7254d1f45dbe3bcda921bcef4b",
        "kotlin-stdlib-2.4.10.jar" to "4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df",
    )
    inputs.forEach { check(sha(it) == expected.getValue(it.fileName.toString())) { "Compile SDK checksum mismatch: $it" } }
    val jar = outputDir.resolve("$SDK_ARTIFACT-$SDK_BUILD.jar")
    val fingerprint = inputs.joinToString("\n") { "${it.fileName}=${sha(it)}" }
    val stamp = outputDir.resolve("inputs.sha256")
    val jarStamp = outputDir.resolve("api.sha256")
    if (!Files.isRegularFile(jar) || !Files.isRegularFile(stamp) || Files.readString(stamp) != fingerprint ||
        !Files.isRegularFile(jarStamp) || Files.readString(jarStamp) != sha(jar)) {
        val seen = hashSetOf<String>()
        val temporaryJar = Files.createTempFile(outputDir, ".api-", ".jar")
        try {
            ZipOutputStream(Files.newOutputStream(temporaryJar)).use { out ->
                for (input in inputs) ZipFile(input.toFile()).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name != "META-INF/MANIFEST.MF" &&
                        !(input.fileName.toString().startsWith("kotlin-compiler-") && it.name.endsWith(".kotlin_builtins")) &&
                        !it.name.startsWith("META-INF/versions/") && !it.name.endsWith("module-info.class") &&
                        !it.name.endsWith(".SF") && !it.name.endsWith(".RSA") }.forEach { entry ->
                        check(seen.add(entry.name)) { "Duplicate SDK entry ${entry.name}" }
                        out.putNextEntry(ZipEntry(entry.name).also { it.time = 0 })
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            Files.move(temporaryJar, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporaryJar) }
        Files.writeString(stamp, fingerprint)
        Files.writeString(jarStamp, sha(jar))
    }
    // A generated opaque revision both orders compilation after SDK preparation and
    // invalidates compilation if the compile API changes. No SDK source stubs are generated.
    Files.createDirectories(revisionDir)
    val revisionFile = revisionDir.resolve("IntellijApiRevision.kt")
    val revision = "package dev.brikk.house.intellij.internal.api\ninternal const val INTELLIJ_API_REVISION = \"${sha(fingerprint.toByteArray())}\"\n"
    if (!Files.isRegularFile(revisionFile) || Files.readString(revisionFile) != revision) Files.writeString(revisionFile, revision)
    println("Prepared compile-only IDEA $SDK_BUILD API; JetBrains SDK classes are not distributable helper contents")
}

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
