package dev.brikk.house.intellij.tooling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NoticesTest {
    private class Fixture {
        val root: Path = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "opencode")), "brikk-notices-")
        val license: Path = root.resolve("LICENSE")
        val notice: Path = root.resolve("NOTICE")
        val sources: Path = Files.createDirectories(root.resolve("source-notices"))
        val resources: Path = root.resolve("resources")

        init {
            Files.writeString(license, "test Apache license text\n")
            Files.writeString(notice, "test current attribution text\n")
            Files.copy(license, sources.resolve("LICENSE"))
            Files.copy(notice, sources.resolve("NOTICE"))
        }

        fun prepare() = prepareSupportNotices(license, notice, sources, resources)
    }

    @Test fun matchingSourceNoticesProduceExactBinaryResources() {
        val fixture = Fixture()
        fixture.prepare()
        assertContentEquals(Files.readAllBytes(fixture.license), Files.readAllBytes(fixture.resources.resolve("META-INF/LICENSE")))
        assertContentEquals(Files.readAllBytes(fixture.notice), Files.readAllBytes(fixture.resources.resolve("META-INF/NOTICE")))
    }

    @Test fun missingSourceNoticeBlocksPublicationPreparation() {
        val fixture = Fixture()
        Files.delete(fixture.sources.resolve("NOTICE"))
        assertFailsWith<IllegalStateException> { fixture.prepare() }
        assertFalse(Files.exists(fixture.resources))
    }

    @Test fun staleSourceLicenseBlocksPublicationPreparation() {
        val fixture = Fixture()
        Files.writeString(fixture.sources.resolve("LICENSE"), "wrong license\n")
        assertFailsWith<IllegalStateException> { fixture.prepare() }
        assertFalse(Files.exists(fixture.resources))
    }

    @Test fun staleSourceNoticeBlocksPublicationPreparation() {
        val fixture = Fixture()
        Files.writeString(fixture.sources.resolve("NOTICE"), "stale attribution\n")
        assertFailsWith<IllegalStateException> { fixture.prepare() }
        assertFalse(Files.exists(fixture.resources))
    }
}
