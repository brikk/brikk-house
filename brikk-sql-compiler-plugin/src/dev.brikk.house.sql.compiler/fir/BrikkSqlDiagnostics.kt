package dev.brikk.house.sql.compiler.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

/**
 * Frontend diagnostics for brikk-sql call sites.
 *
 * Note: arbitrary sub-literal ranges (pointing *inside* the SQL string) are possible via a
 * custom `SourceElementPositioningStrategy` returning explicit `TextRange`s — deferred until
 * the brikk-sql parser is hooked in and can report SQL error offsets.
 */
object BrikkSqlDiagnostics : KtDiagnosticsContainer() {
    /** SQL argument was not a compile-time constant string. Arg: callee name. */
    val SQL_NOT_CONSTANT by error1<KtElement, String>()

    /** SQL argument was constant but blank. Arg: callee name. */
    val SQL_EMPTY by error1<KtElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("brikk-sql") {
            it.put(
                SQL_NOT_CONSTANT,
                "[BRIKK_SQL] argument to ''{0}'' must be a compile-time constant string " +
                    "(string literal without interpolation, optionally .trimIndent()/.trimMargin())",
                TO_STRING,
            )
            it.put(SQL_EMPTY, "[BRIKK_SQL] argument to ''{0}'' is blank", TO_STRING)
        }
    }
}
