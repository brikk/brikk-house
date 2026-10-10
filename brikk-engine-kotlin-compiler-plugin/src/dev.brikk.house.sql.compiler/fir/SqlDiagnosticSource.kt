package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.analysis.SqlLiteralRanges
import dev.brikk.house.sql.compiler.analysis.SqlTemplate
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.text

/** Narrow only proven literal intervals; otherwise retain the whole argument's source. */
internal fun sqlDiagnosticSource(source: KtSourceElement?, template: SqlTemplate?, start: Int?, end: Int?): KtSourceElement? {
    if (source == null || template == null || start == null || end == null) return source
    val text = source.text?.toString() ?: return source
    val range = SqlLiteralRanges.map(text, template, start, end) ?: return source
    return source.fakeElement(CompilerCompat.pluginGenerated,
        KtSourceElementOffsetStrategy.Custom.Initialized(source.startOffset + range.first, source.startOffset + range.last + 1))
}
