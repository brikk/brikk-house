package dev.brikk.house.intellij.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

@TaskAction
fun prepareSupportNotices(@Input license: Path, @Input notice: Path, @Input sourceNotices: Path, @Output resourceDir: Path) {
    // Toolchain 0.13's sources JAR includes static source roots but not resources.
    // These source-root copies are checked in so parallel sources-JAR assembly
    // never races a generator, and mismatches block publication rather than drift.
    for ((name, original) in listOf("LICENSE" to license, "NOTICE" to notice)) {
        val copy = sourceNotices.resolve(name)
        check(Files.isRegularFile(copy) && Files.mismatch(original, copy) == -1L) {
            "Sources-JAR $name is missing or stale; synchronize src/META-INF/$name with the module's $name"
        }
    }
    val meta = resourceDir.resolve("META-INF")
    Files.createDirectories(meta)
    Files.copy(license, meta.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING)
    Files.copy(notice, meta.resolve("NOTICE"), StandardCopyOption.REPLACE_EXISTING)
}
