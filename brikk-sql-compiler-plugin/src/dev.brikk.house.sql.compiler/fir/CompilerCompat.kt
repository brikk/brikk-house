package dev.brikk.house.sql.compiler.fir

import org.jetbrains.kotlin.KtFakeSourceElementKind

/**
 * Shims for compiler-API shapes that differ between the Kotlin version this plugin is compiled
 * against and the one the IDE runs it on (the KEFS setup publishes the same jar under the IDE's
 * compiler version; see docs/virtual-pipelines-wiring.md).
 *
 * Only reflection over `org.jetbrains.kotlin.*` types here: those are not relocated in the
 * embeddable compiler, so the names are identical in both worlds.
 */
internal object CompilerCompat {

    /**
     * `KtFakeSourceElementKind.PluginGenerated`: an `object` up to 2.4.10, a sealed class with
     * `Default` (object) and `Custom(marker)` from 2.4.20. Referencing the 2.4.10 object directly
     * compiles to `GETSTATIC PluginGenerated.INSTANCE`, which is a `NoSuchFieldError` in 2.4.20.
     */
    val pluginGenerated: KtFakeSourceElementKind by lazy {
        val loader = KtFakeSourceElementKind::class.java.classLoader
        val base = "org.jetbrains.kotlin.KtFakeSourceElementKind\$PluginGenerated"
        val instance = instanceOf(loader, base) ?: instanceOf(loader, "$base\$Default")
            ?: error("neither $base nor $base\$Default has an INSTANCE; unsupported Kotlin compiler version")
        instance as KtFakeSourceElementKind
    }

    private fun instanceOf(loader: ClassLoader?, className: String): Any? = try {
        Class.forName(className, true, loader).getField("INSTANCE").get(null)
    } catch (e: ReflectiveOperationException) {
        null
    }
}
