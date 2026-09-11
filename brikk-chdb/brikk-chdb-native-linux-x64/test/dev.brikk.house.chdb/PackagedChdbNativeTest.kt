package dev.brikk.house.chdb

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import java.lang.foreign.SymbolLookup
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedChdbNativeTest {
    @Test
    fun packagedLinuxLibraryExtractsPrivatelyAndExposesTheAbi() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        assumeTrue(os.contains("linux") && arch in setOf("amd64", "x86_64"))

        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val log = Files.createTempFile("brikk-chdb-packaged-probe-", ".log")
        val work = Files.createTempDirectory("brikk-chdb-packaged-probe-")
        try {
            val builder = ProcessBuilder(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                PackagedChdbProbe::class.java.name,
            ).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            builder.environment()["XDG_CACHE_HOME"] = work.resolve("cache").toString()
            val process = builder.start()

            val finished = process.waitFor(2, TimeUnit.MINUTES)
            if (!finished) process.destroyForcibly().waitFor()
            val output = Files.readString(log)
            assertTrue(finished, "packaged chDB probe timed out:\n$output")
            assertEquals(0, process.exitValue(), output)
            assertContains(output, "BRIKK_CHDB_PACKAGED_OK")
        } finally {
            Files.deleteIfExists(log)
            work.toFile().deleteRecursively()
        }
    }
}

/** Runs native chDB outside the test launcher because its process-global runtime delays JVM shutdown. */
object PackagedChdbProbe {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        try {
            check(System.getProperty(Chdb.libraryPathProperty) == null)
            val packaged = Class.forName("dev.brikk.house.chdb.PackagedChdbNative")
            val instance = packaged.getDeclaredField("INSTANCE").also { it.trySetAccessible() }.get(null)
            val library = packaged.getDeclaredMethod("extractForCurrentHost")
                .also { it.trySetAccessible() }
                .invoke(instance) as Path
            println("BRIKK_CHDB_PROBE_EXTRACTED")
            System.out.flush()
            System.load(library.toString())
            val symbols = SymbolLookup.loaderLookup()
            for (name in listOf(
                "chdb_connect",
                "chdb_close_conn",
                "chdb_query",
                "chdb_destroy_query_result",
                "chdb_result_buffer",
                "chdb_result_length",
                "chdb_result_error",
            )) {
                check(symbols.find(name).isPresent) { "packaged libchdb does not export $name" }
            }
            println("BRIKK_CHDB_PROBE_PERMISSIONS")
            System.out.flush()
            verifyCachePermissions(library)
            println("BRIKK_CHDB_PACKAGED_OK")
            System.out.flush()
            Runtime.getRuntime().halt(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            System.err.flush()
            Runtime.getRuntime().halt(1)
        }
    }

    private fun verifyCachePermissions(library: Path) {
        check(PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(library)) == "rw-------")
        for (directory in generateSequence(library.parent) { it.parent }.take(3)) {
            check(PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(directory)) == "rwx------")
        }
    }
}
