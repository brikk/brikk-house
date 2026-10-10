package dev.brikk.house.intellij.fixtures

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path

/** Test-only equivalent of KEFS substitution, restricted to the reviewed exact IDE compiler. */
class FixtureCompilerPluginProvider : KotlinBundledFirCompilerPluginProvider {
    override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? =
        System.getProperty("brikk.fixture.compiler.plugin")?.let(Path::of)?.takeIf { it == userSuppliedPluginJar }
}
