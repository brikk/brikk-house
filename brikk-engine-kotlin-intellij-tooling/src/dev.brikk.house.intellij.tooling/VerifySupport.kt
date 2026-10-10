package dev.brikk.house.intellij.tooling

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.jetbrains.amper.plugins.ExecutionAvoidance

/** Real IDEA test fixtures, with an explicit SDK installation. No Gradle build is introduced here. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun verifyIntellijSupport(
    @Input library: CompilationArtifact,
    @Input compilerClasspath: Classpath,
    @Input fixturesDir: Path,
    @Input compilerPluginJar: Path,
    @Output outputRoot: Path,
) {
    Files.createDirectories(outputRoot)
    val sdk = System.getenv("BRIKK_INTELLIJ_SDK")?.let(Path::of) ?: provisionSdk(outputRoot)
    check(Files.isRegularFile(sdk.resolve("product-info.json"))) { "Missing IDEA SDK: $sdk" }
    val build = sdkBuild(sdk)
    val outputDir = outputRoot.resolve("run-$build")
    Files.createDirectories(outputDir)
    val sdkJars = sdkClasspath(sdk)
    val testFramework = frameworkJars(outputDir, build)
    val runtime = compilerClasspath.resolvedFiles.filter { path ->
        listOf("junit-", "hamcrest-", "opentest4j-", "assertj-", "byte-buddy-").any { path.fileName.toString().startsWith(it) }
    }
    val cp = sdkJars + testFramework + runtime + listOf(library.artifact)
    // Always compile fixtures against the 261 baseline, then run the same ABI on
    // newer SDKs. Compiling against a new SDK would hide compatibility regressions.
    val compileSdk = System.getenv("BRIKK_INTELLIJ_COMPILE_SDK")?.let(Path::of)
        ?: if (build == SDK_BUILD) sdk else provisionSdk(outputRoot)
    check(sdkBuild(compileSdk) == SDK_BUILD) { "Fixture compile SDK must be IDEA $SDK_BUILD" }
    val compileCp = sdkClasspath(compileSdk) + frameworkJars(outputDir, SDK_BUILD) + runtime + listOf(library.artifact)
    val classes = outputDir.resolve("classes")
    Files.createDirectories(classes)
    val sources = Files.walk(fixturesDir).use { paths -> paths.filter { it.toString().endsWith(".kt") }.sorted().toList() }
    val compiler = compilerClasspath.resolvedFiles
    run(compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", listOf("-no-stdlib", "-no-reflect", "-jvm-target", "21",
        "-classpath", compileCp.joinToString(File.pathSeparator), "-d", classes.toString()) + sources.map(Path::toString), outputDir.resolve("compile.log"))
    Files.createDirectories(classes.resolve("META-INF"))
    Files.copy(fixturesDir.resolve("plugin.xml"), classes.resolve("META-INF/plugin.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    val opens = Regex("\"(--add-opens=[^\"]+)\"").findAll(Files.readString(sdk.resolve("product-info.json"))).map { it.groupValues[1] }.toList()
    val ideCompiler = sdkJars.firstNotNullOf { path -> ZipFile(path.toFile()).use { zip ->
        zip.getEntry("META-INF/compiler.version")?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText().trim() } }
    } }
    val generatedArgs = if (ideCompiler == "2.4.20-ij262-34") listOf("-Dbrikk.fixture.compiler.plugin=${compilerPluginJar.toAbsolutePath()}") else emptyList()
    println("IDE compiler=$ideCompiler; generated-shape fixture lane=${generatedArgs.isNotEmpty()}")
    run(cp + listOf(classes), "org.junit.runner.JUnitCore", listOf("dev.brikk.house.intellij.ContractsTest", "dev.brikk.house.intellij.fixtures.BrikkContextTest"), outputDir.resolve("test.log"), opens + generatedArgs + listOf(
        "--enable-native-access=ALL-UNNAMED", "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
        "-Didea.force.use.core.classloader=true",
        "-Djna.boot.library.path=${sdk.resolve("lib/jna/amd64")}", "-Djna.nosys=true", "-Djna.noclasspath=true",
        "-Dintellij.platform.runtime.repository.path=${sdk.resolve("modules/module-descriptors.dat")}",
        "-Didea.home.path=$sdk", "-Didea.config.path=${outputDir.resolve("config")}", "-Didea.system.path=${outputDir.resolve("system")}",
        "-Didea.plugins.path=${sdk.resolve("plugins")}", "-Djava.awt.headless=true",
        "-Didea.load.plugins.id=org.jetbrains.kotlin,com.intellij.java,dev.brikk.house.intellij.fixtures",
    ), sdk.resolve("jbr/bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""))
    ZipFile(library.artifact.toFile()).use { zip ->
        check(zip.entries().asSequence().none { it.name.startsWith("com/intellij/") || it.name.startsWith("org/jetbrains/kotlin/") }) { "SDK classes leaked into helper" }
    }
    val log = Files.readString(outputDir.resolve("test.log"))
    val count = Regex("OK \\((\\d+) tests\\)").find(log)?.groupValues?.get(1) ?: error("Missing successful fixture summary")
    Files.writeString(outputDir.resolve("verification.txt"), "sdk-build=$build\nide-compiler=$ideCompiler\nhelper-sha256=${sha(library.artifact)}\ntests=$count\ngenerated-shape-lane=${generatedArgs.isNotEmpty()}\nSDK-bundled=false\n")
    println("IntelliJ support: $count fixtures passed on $build; generated-shape lane=${generatedArgs.isNotEmpty()}")
}

private fun sdkBuild(sdk: Path): String = (Regex("\"buildNumber\"\\s*:\\s*\"([^\"]+)\"")
    .find(Files.readString(sdk.resolve("product-info.json")))?.groupValues?.get(1) ?: error("Invalid SDK product metadata"))
    .also { require(it.matches(Regex("\\d+\\.\\d+\\.\\d+"))) { "Invalid SDK build number" } }

private fun sdkClasspath(sdk: Path): List<Path> = buildList {
    for (dir in listOf("lib", "lib/modules", "plugins/Kotlin/lib", "plugins/Kotlin/lib/modules", "plugins/java/lib", "plugins/java/lib/modules",
        "plugins/platform-structuralSearch-plugin/lib", "plugins/platform-structuralSearch-plugin/lib/modules")) {
        val root = sdk.resolve(dir)
        if (Files.isDirectory(root)) Files.list(root).use { files -> addAll(files.filter { it.toString().endsWith(".jar") }.sorted().toList()) }
    }
}

private fun frameworkJars(outputDir: Path, build: String): List<Path> =
    listOf("test-framework", "test-framework-core", "test-framework-common", "test-framework-team-city").map { name ->
        val file = outputDir.resolve("$name-$build.jar")
        if (!Files.isRegularFile(file)) {
            val temporary = Files.createTempFile(outputDir, ".framework-", ".jar")
            try {
                val client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                val downloaded = listOf("releases", "snapshots").any { repository ->
                    Files.write(temporary, ByteArray(0), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
                    val url = java.net.URI("https://www.jetbrains.com/intellij-repository/$repository/com/jetbrains/intellij/platform/$name/$build/${file.fileName}")
                    client.send(java.net.http.HttpRequest.newBuilder(url).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofFile(temporary)).statusCode() == 200
                }
                check(downloaded) { "Test SDK download failed: $name:$build" }
                ZipFile(temporary.toFile()).use { check(it.size() > 0) }
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        file
    }

private fun provisionSdk(outputDir: Path): Path {
    check(System.getProperty("os.name").lowercase().contains("linux") && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
        "Set BRIKK_INTELLIJ_SDK on hosts other than Linux x64"
    }
    val sdk = outputDir.resolve("sdk")
    val archive = outputDir.resolve("ideaIU-2026.1.3.tar.gz")
    val expected = "a6f049716da1d09d9e0ec1500c60bf01a5ff8a0fe2419178dd1ff2fdb2b77563"
    val ready = sdk.resolve(".brikk-sdk-ready")
    if (Files.isRegularFile(ready) && Files.readString(ready) == expected && sdkBuild(sdk) == SDK_BUILD) return sdk
    if (!Files.isRegularFile(archive)) {
        val temporary = Files.createTempFile(outputDir, ".sdk-download-", ".tar.gz")
        try {
            val response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
                .send(java.net.http.HttpRequest.newBuilder(java.net.URI("https://download.jetbrains.com/idea/ideaIU-2026.1.3.tar.gz")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofFile(temporary))
            check(response.statusCode() == 200 && sha(temporary) == expected) { "IDEA SDK download failed checksum verification" }
            Files.move(temporary, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
    check(sha(archive) == expected) { "IDEA SDK archive checksum mismatch" }
    Files.createDirectories(sdk)
    val process = ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "--strip-components=1", "--no-same-owner", "-C", sdk.toAbsolutePath().toString())
        .redirectErrorStream(true).redirectOutput(outputDir.resolve("extract.log").toFile()).start()
    if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); error("IDEA SDK extraction timed out") }
    check(process.exitValue() == 0 && sdkBuild(sdk) == SDK_BUILD) { "IDEA SDK extraction failed" }
    Files.writeString(ready, expected)
    return sdk
}

private fun run(cp: List<Path>, main: String, args: List<String>, log: Path, vm: List<String> = emptyList(), javaExecutable: Path? = null) {
    val java = (javaExecutable ?: Path.of(System.getProperty("java.home"), "bin", "java")).toString()
    val process = ProcessBuilder(listOf(java, "-Xmx2g") + vm + listOf("-cp", cp.joinToString(File.pathSeparator), main) + args)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start()
    if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); error("IDE fixture timed out: $log") }
    check(process.exitValue() == 0) { "IDE fixture failed: ${Files.readString(log).takeLast(14000)}" }
}
