package dev.brikk.house.sql.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText
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
 * the value from the IDE action "KEFS: Copy Kotlin IDE Version"; the jar itself is always the
 * one compiled against the project's Kotlin version - naming it with the IDE's version is the
 * cheap compatibility experiment, and KEFS's exception analyzer reports if the IDE compiler
 * rejects it.
 *
 * Execution avoidance is disabled: the task rewrites `maven-metadata.xml` from whatever
 * versions already exist in the repo, which is state outside its declared inputs.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun publishKefsRepo(
    @Input assembledDir: Path,
    artifactId: String,
    ideKotlinVersion: String,
    libVersion: String,
    @Output repoDir: Path,
) {
    val jar = singleJarIn(assembledDir)
    // <artifactId>-<kotlin>-<lib>.jar -> <kotlin>
    val builtKotlinVersion = jar.name.removePrefix("$artifactId-").removeSuffix("-$libVersion.jar")
    val kotlinSegment = ideKotlinVersion.ifBlank { builtKotlinVersion }
    val version = "$kotlinSegment-$libVersion"

    val artifactDir = repoDir.resolve(GROUP.replace('.', '/')).resolve(artifactId)
    val versionDir = artifactDir.resolve(version).createDirectories()
    val base = "$artifactId-$version"
    Files.copy(jar, versionDir.resolve("$base.jar"), StandardCopyOption.REPLACE_EXISTING)
    versionDir.resolve("$base.pom").writeText(
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<project xmlns="http://maven.apache.org/POM/4.0.0">
        |  <modelVersion>4.0.0</modelVersion>
        |  <groupId>$GROUP</groupId>
        |  <artifactId>$artifactId</artifactId>
        |  <version>$version</version>
        |  <packaging>jar</packaging>
        |</project>
        |""".trimMargin(),
    )

    val versions = Files.list(artifactDir).use { s -> s.filter { it.isDirectory() }.map { it.name }.sorted().toList() }
    val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    artifactDir.resolve("maven-metadata.xml").writeText(
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
        |""".trimMargin(),
    )
    if (ideKotlinVersion.isNotBlank() && ideKotlinVersion != builtKotlinVersion) {
        println("note: jar compiled against Kotlin $builtKotlinVersion, published as $kotlinSegment for the IDE")
    }
    println("published $GROUP:$artifactId:$version -> $versionDir")
}
