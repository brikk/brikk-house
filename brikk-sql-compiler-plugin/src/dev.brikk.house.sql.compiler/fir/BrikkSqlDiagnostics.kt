package dev.brikk.house.sql.compiler.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.psi.KtElement

/**
 * Frontend diagnostics for brikk-sql.
 *
 * Note: arbitrary sub-literal ranges (pointing *inside* the SQL string) are possible via a
 * custom `SourceElementPositioningStrategy` returning explicit `TextRange`s — not done yet;
 * diagnostics anchor on the whole literal.
 */
object BrikkSqlDiagnostics : KtDiagnosticsContainer() {
    /** SQL argument was not a compile-time constant string. Arg: callee name. */
    val SQL_NOT_CONSTANT by error1<KtElement, String>()

    /** A `${'$'}{...}` entry the template rules do not allow, or a Rel used as a value. Arg: message. */
    val SQL_BAD_INTERPOLATION by error1<KtElement, String>()

    /** SQL argument was constant but blank. Arg: callee name. */
    val SQL_EMPTY by error1<KtElement, String>()

    /** `Sql.<dialect>(...)` used outside a `@BrikkSql` function body. Arg: callee name. */
    val SQL_OUTSIDE_BRIKK_FUNCTION by error1<KtElement, String>()

    /** brikk-sql could not analyze the function's SQL. Arg: message. */
    val SQL_ANALYSIS_FAILED by error1<KtElement, String>()

    /** A `:name` placeholder has no matching parameter. Args: name, declared parameters. */
    val SQL_UNBOUND_PARAM by error2<KtElement, String, String>()

    /** A scalar parameter the SQL never references (`:name` or `$name`). Arg: name. */
    val SQL_UNUSED_PARAM by error1<KtElement, String>()

    /** Debug-only: analysis summary of a `@BrikkSql` function (`debug=true`). */
    val SQL_DEBUG by warning1<KtElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Renderers

    object Renderers : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("brikk-sql") {
            it.put(
                SQL_NOT_CONSTANT,
                "[BRIKK_SQL] argument to ''{0}'' must be a string literal or template " +
                    "(interpolating only parameters, local vals, properties or const vals; optionally .trimIndent()/.trimMargin())",
                TO_STRING,
            )
            it.put(SQL_BAD_INTERPOLATION, "[BRIKK_SQL] {0}", TO_STRING)
            it.put(SQL_EMPTY, "[BRIKK_SQL] argument to ''{0}'' is blank", TO_STRING)
            it.put(
                SQL_OUTSIDE_BRIKK_FUNCTION,
                "[BRIKK_SQL] ''{0}'' must be the body of a function annotated @BrikkSql",
                TO_STRING,
            )
            it.put(SQL_ANALYSIS_FAILED, "[BRIKK_SQL] {0}", TO_STRING)
            it.put(SQL_DEBUG, "[BRIKK_SQL_DEBUG] {0}", TO_STRING)
            it.put(
                SQL_UNUSED_PARAM,
                "[BRIKK_SQL] parameter ''{0}'' is never referenced by the SQL - use it as '':{0}'' / ''${'$'}{0}'' or remove it",
                TO_STRING,
            )
            it.put(
                SQL_UNBOUND_PARAM,
                "[BRIKK_SQL] placeholder '':{0}'' does not match any parameter (declared: {1})",
                TO_STRING, TO_STRING,
            )
        }
    }
}
