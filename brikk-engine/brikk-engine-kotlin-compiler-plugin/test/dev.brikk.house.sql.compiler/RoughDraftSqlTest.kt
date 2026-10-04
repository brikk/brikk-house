package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.ir.RoughDraftSql
import dev.brikk.house.sql.compiler.ir.RoughDraftStage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoughDraftSqlTest {
    @Test
    fun modulePatternsSeparateCompilationsWithoutAllowingModuleNamesToBecomePaths() {
        val pattern = Path.of(System.getProperty("java.io.tmpdir"), "opencode", "drafts", "{module}.draft.sql")
        val main = RoughDraftSql.outputPath(pattern, "<main>")
        val test = RoughDraftSql.outputPath(pattern, "<main-test>")
        kotlin.test.assertNotEquals(main, test)
        assertEquals(pattern.toAbsolutePath().parent, main.parent)
        assertEquals(pattern.toAbsolutePath().parent, RoughDraftSql.outputPath(pattern, "../../untrusted\nmodule").parent)
        assertEquals(main, RoughDraftSql.outputPath(pattern, "<main>"))
    }
    @Test
    fun templateTextIsPreservedAndMetadataCannotEscapeSqlComments() {
        val sql = " \n-- hint\nSELECT /* keep */ :n AS \"x\"; -- tail\n\t"
        val stages = listOf(RoughDraftStage("demo.view\nSELECT secret", "postgres\r\nSELECT bad", sql,
            listOf("src", "src"), listOf("n", "n")))
        val text = RoughDraftSql.report("main\nSELECT sneaky", stages)
        assertContains(text, "-- Compilation: main SELECT sneaky\n")
        assertContains(text, "-- Stage 1: demo.view SELECT secret\n")
        assertContains(text, "-- Source dialect: postgres  SELECT bad\n")
        assertContains(text, "-- Rel input slots: src\n")
        assertContains(text, "-- Named parameters: n\n")
        assertContains(text, "-- BEGIN STAGE TEMPLATE\n$sql\n-- END STAGE TEMPLATE")
        assertEquals(text, RoughDraftSql.report("main\nSELECT sneaky", stages))
        assertContains(RoughDraftSql.report("main\u2028SELECT sneaky\u2029", stages), "-- Compilation: main SELECT sneaky \n")
    }

    @Test
    fun writesAreAtomicAndRefuseSymlinkFilesDirectoriesAndUnownedReports() {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode")), "brikk-draft-writer-")
        val target = root.resolve("views.draft.sql")
        RoughDraftSql.write(target, "main", emptyList())
        assertEquals(RoughDraftSql.report("main", emptyList()), Files.readString(target))
        val link = root.resolve("link.sql")
        Files.createSymbolicLink(link, target)
        assertFailsWith<IllegalArgumentException> { RoughDraftSql.write(link, "main", emptyList()) }
        val directory = root.resolve("linked")
        Files.createSymbolicLink(directory, root)
        assertFailsWith<IllegalArgumentException> { RoughDraftSql.write(directory.resolve("views.draft.sql"), "main", emptyList()) }
        Files.writeString(target, "foreign report")
        assertFailsWith<IllegalArgumentException> { RoughDraftSql.write(target, "main", emptyList()) }
        assertEquals("foreign report", Files.readString(target))
    }
}
