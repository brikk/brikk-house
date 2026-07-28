package dev.brikk.house.sql

import java.io.File
import kotlin.test.fail

/**
 * Shared test-resource loading for the brikk-sql corpus tests.
 *
 * Resolution order: module classpath, then `brikk-sql/testResources/` (CWD = repo root,
 * e.g. IDE runs), then `testResources/` (CWD = module root).
 */
private object TestResources

/** Loads a test resource as text, or returns null if it is not present. */
internal fun testResourceOrNull(path: String): String? {
    val stream = TestResources::class.java.classLoader.getResourceAsStream(path)
        ?: File("brikk-sql/testResources/$path").takeIf { it.exists() }?.inputStream()
        ?: File("testResources/$path").takeIf { it.exists() }?.inputStream()
        ?: return null
    return stream.use { it.readBytes().decodeToString() }
}

/** Loads a test resource as text, failing the test if it is not present. */
internal fun testResource(path: String): String =
    testResourceOrNull(path) ?: fail("resource $path not found on classpath or filesystem")
