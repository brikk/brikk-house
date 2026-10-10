package dev.brikk.house.intellij.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun verifyLocalIntellijPublication(
    @Input library: CompilationArtifact,
    @Input wrapper: Path,
    @Input consumerSource: Path,
    @Input license: Path,
    @Input notice: Path,
    version: String?,
    @Output outputDir: Path,
) {
    val revision = checkNotNull(version)
    require(revision.matches(Regex("[A-Za-z0-9.+-]+"))) { "Invalid publication version" }
    val artifact = "brikk-engine-kotlin-intellij-support"
    val repository = Path.of(System.getProperty("user.home"), ".m2/repository/dev/brikk/house", artifact, revision)
    val jar = repository.resolve("$artifact-$revision.jar")
    check(Files.isRegularFile(jar)) { "Publish the helper to Maven Local before verification" }
    check(Files.mismatch(library.artifact, jar) == -1L) { "Maven Local contains a stale helper JAR" }
    val xmlFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val pom = xmlFactory.newDocumentBuilder().parse(repository.resolve("$artifact-$revision.pom").toFile())
    val dependencies = pom.getElementsByTagName("dependency")
    for (index in 0 until dependencies.length) {
        val dependency = dependencies.item(index) as org.w3c.dom.Element
        val group = dependency.getElementsByTagName("groupId").item(0).textContent
        val name = dependency.getElementsByTagName("artifactId").item(0).textContent
        check(group == "org.jetbrains.kotlin" && name == "kotlin-stdlib") { "Build-only dependency leaked into POM: $group:$name" }
    }
    val metadata = Files.readString(repository.resolve("$artifact-$revision.module"))
    check("dev.brikk.build" !in metadata && "kotlin-compiler" !in metadata && "analysis-api" !in metadata) { "SDK dependency leaked into Gradle metadata" }
    ZipFile(jar.toFile()).use { zip ->
        verifyNotices(zip, "META-INF", license, notice)
        check(zip.getEntry("META-INF/plugin.xml") == null) { "The helper is a library, not a second plugin registration" }
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
            check(entry.name.startsWith("dev/brikk/house/intellij/")) { "Foreign class bundled: ${entry.name}" }
            check(!entry.name.startsWith("dev/brikk/house/intellij/fixtures/")) { "Test fixture bundled: ${entry.name}" }
            zip.getInputStream(entry).use { input ->
                val header = input.readNBytes(8)
                check(((header[6].toInt() and 255) shl 8) + (header[7].toInt() and 255) == 65) { "Helper must target Java 21" }
            }
        }
    }
    val sources = repository.resolve("$artifact-$revision-sources.jar")
    check(Files.isRegularFile(sources)) { "Sources publication is missing" }
    ZipFile(sources.toFile()).use { zip -> verifyNotices(zip, "main/META-INF", license, notice) }
    Files.createDirectories(outputDir)
    val consumer = outputDir.resolve("consumer")
    Files.createDirectories(consumer.resolve("src"))
    Files.copy(consumerSource, consumer.resolve("src/LocalConsumer.kt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(consumer.resolve("module.yaml"), """
        product: jvm/app
        repositories:
          - mavenLocal
        dependencies:
          - dev.brikk.house:$artifact:$revision
        settings:
          jvm:
            release: 21
            mainClass: dev.brikk.house.intellij.fixtures.LocalConsumer
          kotlin:
            version: 2.4.10
    """.trimIndent() + "\n")
    val log = outputDir.resolve("consumer.log")
    val process = ProcessBuilder(wrapper.toAbsolutePath().toString(), "run", "--project-dir=${consumer.toAbsolutePath()}")
        .directory(wrapper.toAbsolutePath().parent.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
    if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); error("Local consumer timed out") }
    check(process.exitValue() == 0) { "Local consumer failed:\n${Files.readString(log)}" }
    Files.writeString(outputDir.resolve("verification.txt"), "artifact=dev.brikk.house:$artifact:$revision\nsha256=${sha(jar)}\nsources-sha256=${sha(sources)}\nnotices-match=true\nsources-notices-match=true\nSDK-bundled=false\nSDK-published-dependencies=false\njava-release=21\nlocal-consumer=passed\n")
    println("Maven Local artifact, metadata, notices and independent consumer verified")
}

private fun verifyNotices(zip: ZipFile, prefix: String, license: Path, notice: Path) {
    for ((name, expected) in listOf("LICENSE" to license, "NOTICE" to notice)) {
        val entry = zip.getEntry("$prefix/$name") ?: error("Missing $name in ${zip.name}")
        check(zip.getInputStream(entry).use { it.readAllBytes() }.contentEquals(Files.readAllBytes(expected))) {
            "Published $name does not match the module's committed notice in ${zip.name}"
        }
    }
}
