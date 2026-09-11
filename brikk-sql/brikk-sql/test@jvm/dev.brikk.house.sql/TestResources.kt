package dev.brikk.house.sql

import java.io.File
import kotlin.test.fail

/**
 * Shared test-resource loading for the brikk-sql corpus tests.
 *
 * Resolution order: module classpath, then the source tree located from the project root.
 */
private object TestResources

/** Loads a test resource as text, or returns null if it is not present. */
internal fun testResourceOrNull(path: String): String? {
    val stream = TestResources::class.java.classLoader.getResourceAsStream(path)
        ?: File(testResourcesRoot(), path).takeIf { it.isFile }?.inputStream()
        ?: return null
    return stream.use { it.readBytes().decodeToString() }
}

/** Loads a test resource as text, failing the test if it is not present. */
internal fun testResource(path: String): String =
    testResourceOrNull(path) ?: fail("resource $path not found on classpath or filesystem")

internal fun projectRoot(start: File? = null): File {
    val starts = if (start != null) {
        listOf(start)
    } else {
        listOf(
            File(TestResources::class.java.protectionDomain.codeSource.location.toURI()),
            File("").absoluteFile,
        )
    }
    for (candidate in starts) {
        val directory = if (candidate.isFile) candidate.parentFile else candidate
        generateSequence(directory.canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "project.yaml").isFile }
            ?.let { return it }
    }
    fail("cannot locate project root from ${starts.map { it.absolutePath }}")
}

internal fun testResourcesRoot(root: File = projectRoot()): File =
    File(root, "brikk-sql/brikk-sql/testResources").also {
        if (!it.isDirectory) fail("cannot locate testResources at ${it.absolutePath}")
    }

internal const val LEDGER_OUT_PROPERTY = "brikk.ledgerOut"

internal fun resolveLedgerOutputDirectory(configured: String?, root: File): File {
    require(configured == null || configured.isNotBlank()) { "-$LEDGER_OUT_PROPERTY must not be blank" }
    val requested = configured?.let(::File) ?: File(root, "build/ledger-actual")
    return (if (requested.isAbsolute) requested else File(root, configured!!)).canonicalFile
}

internal fun ledgerActualFile(
    name: String,
    configured: String? = System.getProperty(LEDGER_OUT_PROPERTY),
    root: File = projectRoot(),
): File {
    require(name == File(name).name && name.endsWith("-ledger-actual.json")) {
        "actual ledger name must be a plain *-ledger-actual.json filename: $name"
    }
    val directory = resolveLedgerOutputDirectory(configured, root)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        error("cannot create ledger output directory ${directory.absolutePath}")
    }
    return File(directory, name)
}
