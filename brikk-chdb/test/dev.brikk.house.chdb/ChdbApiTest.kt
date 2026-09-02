package dev.brikk.house.chdb

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChdbApiTest {
    @Test
    fun outputFormatIsExtensibleButRejectsInvalidCStrings() {
        assertEquals("MyFutureFormat", ChdbOutputFormat.custom("MyFutureFormat").value)
        assertFailsWith<IllegalArgumentException> { ChdbOutputFormat.custom(" ") }
        assertFailsWith<IllegalArgumentException> { ChdbOutputFormat.custom("CSV\u0000evil") }
    }

    @Test
    fun missingExplicitLibraryReportsTheActionablePath() {
        val missing = Path.of("/definitely-not-a-chdb-library/libchdb.so")
        val error = assertFailsWith<ChdbUnavailableException> {
            Chdb.open(ChdbConfig(libraryPath = missing))
        }
        assertContains(error.message.orEmpty(), missing.toString())
        assertContains(error.message.orEmpty(), Chdb.libraryPathProperty)
    }

    @Test
    fun configuredNativeLibraryExecutesAMaterializedQuery() {
        // This is deliberately opt-in until a pinned platform artifact exists. Run with:
        //   -Dbrikk.chdb.integrationLibrary=/absolute/path/to/libchdb.dylib
        // and --enable-native-access=ALL-UNNAMED.
        val library = System.getProperty("brikk.chdb.integrationLibrary") ?: return
        Chdb.open(ChdbConfig(libraryPath = Path.of(library))).use { session ->
            assertEquals("42\n", session.query("SELECT 6 * 7", ChdbOutputFormat.CSV).text())
        }
    }
}
