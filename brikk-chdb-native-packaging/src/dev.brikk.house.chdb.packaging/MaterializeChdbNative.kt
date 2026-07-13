package dev.brikk.house.chdb.packaging

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Downloads one pinned chdb-core release archive and exposes just its shared library as a JVM
 * resource. Archive bytes are SHA-256 checked before extraction; generated metadata records a
 * second checksum of the library bytes for the runtime loader.
 */
@TaskAction
fun materializeChdbNative(
    @Input manifest: Path,
    @Output resourceDir: Path,
) {
    require(Files.isRegularFile(manifest)) { "chDB native manifest does not exist: $manifest" }
    val properties = Properties().also { Files.newInputStream(manifest).use(it::load) }
    fun required(name: String): String = properties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("$manifest is missing `$name`")

    val platform = required("platform")
    val version = required("version")
    val url = required("url")
    val expectedArchiveSha256 = required("archiveSha256").lowercase()
    require(expectedArchiveSha256.matches(Regex("[0-9a-f]{64}"))) {
        "$manifest archiveSha256 must be a lowercase SHA-256 hex digest"
    }
    require(URI(url).scheme == "https") { "$manifest url must use https: $url" }

    val archive = Files.createTempFile("brikk-chdb-$platform-", ".tar.gz")
    try {
        downloadAndVerify(url, archive, expectedArchiveSha256)
        resourceDir.toFile().deleteRecursively()
        val nativeDir = resourceDir.resolve("META-INF/brikk/chdb/native/$platform")
        Files.createDirectories(nativeDir)
        val library = nativeDir.resolve("libchdb.so")
        extractLibrary(archive, library)
        val librarySha256 = sha256(library)
        nativeDir.resolve("manifest.properties").toFile().writeText(
            "platform=$platform\n" +
                "version=$version\n" +
                "sourceUrl=$url\n" +
                "archiveSha256=$expectedArchiveSha256\n" +
                "librarySha256=$librarySha256\n",
        )
    } finally {
        Files.deleteIfExists(archive)
    }
}

private fun downloadAndVerify(url: String, destination: Path, expectedSha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    URI(url).toURL().openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 120_000
    }.getInputStream().use { input ->
        Files.newOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
    }
    val actual = digest.digest().toHex()
    check(actual == expectedSha256) {
        "SHA-256 mismatch for $url: expected $expectedSha256, got $actual"
    }
}

/** Minimal safe reader for the single top-level `libchdb.so` member in the upstream tar archive. */
private fun extractLibrary(archive: Path, destination: Path) {
    var extracted = false
    GZIPInputStream(Files.newInputStream(archive)).use { tar ->
        while (true) {
            val header = readTarBlock(tar) ?: break
            if (header.all { it == 0.toByte() }) break
            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val entryName = listOf(prefix, name).filter(String::isNotEmpty).joinToString("/")
            val size = tarOctal(header, 124, 12)
            require(size >= 0) { "Invalid tar entry size for $entryName" }
            val type = header[156].toInt().toChar()
            if (entryName == "libchdb.so") {
                check(!extracted) { "Archive contains libchdb.so more than once" }
                check(type == '\u0000' || type == '0') { "libchdb.so is not a regular file in archive" }
                Files.newOutputStream(destination).use { copyExactly(tar, it, size) }
                extracted = true
            } else {
                skipExactly(tar, size)
            }
            skipExactly(tar, (512 - size % 512) % 512)
        }
    }
    check(extracted) { "Upstream archive did not contain top-level libchdb.so" }
}

private fun readTarBlock(input: InputStream): ByteArray? {
    val block = ByteArray(512)
    var offset = 0
    while (offset < block.size) {
        val count = input.read(block, offset, block.size - offset)
        if (count < 0) {
            if (offset == 0) return null
            error("Truncated tar header")
        }
        offset += count
    }
    return block
}

private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long) {
    var remaining = length
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) error("Truncated tar entry")
        output.write(buffer, 0, count)
        remaining -= count
    }
}

private fun skipExactly(input: InputStream, length: Long) {
    var remaining = length
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) error("Truncated tar entry")
        remaining -= count
    }
}

private fun tarString(bytes: ByteArray, offset: Int, length: Int): String =
    bytes.copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

private fun tarOctal(bytes: ByteArray, offset: Int, length: Int): Long =
    tarString(bytes, offset, length).trim().ifEmpty { "0" }.toLong(8)

private fun sha256(file: Path): String =
    MessageDigest.getInstance("SHA-256").also { digest ->
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }.digest().toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
