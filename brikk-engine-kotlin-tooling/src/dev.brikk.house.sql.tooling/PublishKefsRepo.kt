package dev.brikk.house.sql.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

private const val GROUP = "dev.brikk.house"

/**
 * Publishes the assembled plugin jar into a local Maven-layout repository for KEFS.
 *
 * Writes
 * ```
 * <repoDir>/dev/brikk/house/<artifactId>/<ide>-<lib>/<artifactId>-<ide>-<lib>.jar
 * <repoDir>/dev/brikk/house/<artifactId>/<ide>-<lib>/<artifactId>-<ide>-<lib>.pom
 * <repoDir>/dev/brikk/house/<artifactId>/maven-metadata.xml
 * ```
 * The version follows the KEFS scheme `<kotlin-version>-<lib-version>`. [ideKotlinVersion] is
 * the value from the IDE action "KEFS: Copy Kotlin IDE Version". Publication refuses
 * to relabel a JAR built against a different compiler or without dependency relocation.
 *
 * Execution avoidance is disabled: the task rewrites `maven-metadata.xml` from whatever
 * versions already exist in the repo, which is state outside its declared inputs.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun publishKefsRepo(
    @Input assembledDir: Path,
    artifactId: String,
    ideKotlinVersion: String,
    kotlinVersion: String,
    libVersion: String,
    @Output repoDir: Path,
) {
    // <artifactId>-<kotlin>-<lib>.jar -> <kotlin>
    require(artifactId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid artifact ID" }
    val kotlinSegment = ideKotlinVersion.ifBlank { kotlinVersion }
    val jar = assembledDir.resolve("$artifactId-$kotlinSegment-$libVersion.jar")
    require(Files.isRegularFile(jar)) { "Missing assembled artifact $jar" }
    val builtKotlinVersion = jar.name.removePrefix("$artifactId-").removeSuffix("-$libVersion.jar")
    validatePublication(jar, builtKotlinVersion, kotlinSegment, libVersion)
    val version = "$kotlinSegment-$libVersion"

    val artifactDir = repoDir.resolve(GROUP.replace('.', '/')).resolve(artifactId)
    val versionDir = artifactDir.resolve(version).createDirectories()
    val base = "$artifactId-$version"
    val jarBytes = Files.readAllBytes(jar)
    atomicWrite(versionDir.resolve("$base.jar"), jarBytes)
    atomicWrite(versionDir.resolve("$base.jar.sha256"), sha256(jarBytes).toByteArray())
    atomicWrite(versionDir.resolve("$base.pom"),
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<project xmlns="http://maven.apache.org/POM/4.0.0">
        |  <modelVersion>4.0.0</modelVersion>
        |  <groupId>$GROUP</groupId>
        |  <artifactId>$artifactId</artifactId>
        |  <version>$version</version>
        |  <packaging>jar</packaging>
        |</project>
        |""".trimMargin().toByteArray(),
    )

    val versions = Files.list(artifactDir).use { s -> s.filter { it.isDirectory() }.map { it.name }.sorted().toList() }
    val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    atomicWrite(artifactDir.resolve("maven-metadata.xml"),
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<metadata>
        |  <groupId>$GROUP</groupId>
        |  <artifactId>$artifactId</artifactId>
        |  <versioning>
        |    <latest>$version</latest>
        |    <release>$version</release>
        |    <versions>
        |${versions.joinToString("\n") { "      <version>$it</version>" }}
        |    </versions>
        |    <lastUpdated>$stamp</lastUpdated>
        |  </versioning>
        |</metadata>
        |""".trimMargin().toByteArray(),
    )
    println("published $GROUP:$artifactId:$version -> $versionDir")
}

private fun atomicWrite(target: Path, content: ByteArray) {
    val temporary = Files.createTempFile(target.parent, ".publish-", ".tmp")
    try {
        Files.write(temporary, content)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally { Files.deleteIfExists(temporary) }
}

internal fun validatePublication(jar: Path, filenameVersion: String, requestedVersion: String, libVersion: String) {
    val stamp = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry(BUILD_STAMP) ?: error("Missing compiler build provenance in $jar")
        Properties().also { props -> zip.getInputStream(entry).use { props.load(it) } }
    }
    require(stamp.getProperty("relocated") == "true") { "Refusing to publish an unrelocated compiler plugin" }
    val built = stamp.getProperty("compilerVersion")
    require(built == filenameVersion && built == requestedVersion) {
        "Refusing compiler-version relabel: built=$built, filename=$filenameVersion, requested=$requestedVersion"
    }
    require(stamp.getProperty("libVersion") == libVersion) { "Library version does not match build provenance" }
}
