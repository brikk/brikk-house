package dev.brikk.house.sql.generator

import dev.brikk.house.sql.ast.Expression

// brikk-native: no sqlglot counterpart. Emit-span tracking for generated SQL —
// maps [outputStart, outputEnd) ranges of the OUTPUT string back to the AST nodes
// that rendered them, and (via the position meta written by the parser, see
// Expression.updatePositions) back to line/col/start/end in the ORIGINAL source.

/**
 * A position in the original source text, as recorded by the parser into node meta.
 *
 * Coordinate systems (all 1-based columns/lines are 1-based; char offsets are 0-based):
 *  - [line]/[col] (sqlglot token semantics): 1-based, refer to the token's END.
 *  - [start]/[end]: absolute char offsets into the source, [end] inclusive.
 *  - [lineStart]/[colStart] (brikk-native): 1-based line/col of the token's START,
 *    so a caller can underline from where the token begins without counting newlines.
 *
 * [lineEnd]/[colEnd] are provided as unambiguous aliases of [line]/[col]; use the
 * start/end pairs (never the bare [col]) when driving error markers so a 0-based
 * engine start-position is never mixed with the end-anchored [col].
 */
data class SourcePos(
    val line: Int,
    val col: Int,
    val start: Int,
    val end: Int,
    val lineStart: Int = line,
    val colStart: Int = col - (end - start),
) {
    val lineEnd: Int get() = line
    val colEnd: Int get() = col
}

/**
 * Emit-span map for one generated output string.
 *
 * Entries are best-effort: every node the generator rendered whose emitted fragment
 * could be located verbatim inside its parent's fragment gets a span. Nodes whose
 * fragment was transformed after rendering (e.g. re-indented multi-line blocks in
 * pretty mode) carry no span of their own, but their descendants are still resolved
 * against the nearest located ancestor, and position queries fall back to the
 * smallest covering span.
 */
class SourceMap(
    /** The exact output string the spans index into. */
    val output: String,
    entries: List<Entry>,
) {
    /** One rendered node: [start] inclusive, [end] exclusive offsets into [output]. */
    data class Entry(val start: Int, val end: Int, val node: Expression)

    val entries: List<Entry> = entries.sortedWith(compareBy({ it.start }, { -(it.end) }))

    // 0-based offsets of each line start in [output] (line 1 starts at lineStarts[0]).
    private val lineStarts: IntArray = run {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in output.indices) if (output[i] == '\n') starts.add(i + 1)
        IntArray(starts.size) { starts[it] }
    }

    /** Converts a 1-based (line, col) position in [output] to a char offset, or null. */
    fun offsetOf(line: Int, col: Int): Int? {
        if (line < 1 || line > lineStarts.size || col < 1) return null
        val offset = lineStarts[line - 1] + (col - 1)
        return if (offset <= output.length) offset else null
    }

    /** The innermost (smallest-span) node covering [offset], or null. */
    fun nodeAt(offset: Int): Expression? = entryAt(offset)?.node

    /** The innermost node covering the 1-based (line, col) output position. */
    fun nodeAt(line: Int, col: Int): Expression? =
        offsetOf(line, col)?.let { nodeAt(it) }

    /** The innermost covering entry (offsets [start, end) semantics). */
    fun entryAt(offset: Int): Entry? {
        var best: Entry? = null
        for (e in entries) {
            if (e.start > offset) break
            if (offset < e.end) {
                if (best == null || (e.end - e.start) <= (best.end - best.start)) best = e
            }
        }
        return best
    }

    /**
     * Maps an offset in the OUTPUT back to a position in the ORIGINAL source: takes
     * the innermost covering node and walks up the parent chain to the nearest node
     * carrying position meta ("line"/"col"/"start"/"end", written by the parser).
     *
     * Positions live mostly on token-shaped leaves (identifiers, literals, function
     * anchors), so when the covering chain is container-only (e.g. the offset sits on
     * a keyword or punctuation), this falls back to the nearest positioned span
     * BEFORE the offset (the "error is just after this token" reading), then to the
     * nearest one after.
     */
    fun sourcePosition(outputOffset: Int, exact: Boolean = false): SourcePos? {
        var node = nodeAt(outputOffset)
        while (node != null) {
            sourcePosOf(node)?.let { return it }
            node = node.parent
        }

        // With [exact], refuse to guess: a position is returned only when a positioned
        // node (or a positioned ancestor) actually covers the offset. Editors prefer a
        // null here (degrade to statement-level) over a confidently-wrong neighbor span.
        if (exact) return null

        // Fallback: nearest positioned span preceding (then following) the offset.
        var before: Entry? = null
        var after: Entry? = null
        for (e in entries) {
            if (sourcePosOf(e.node) == null) continue
            if (e.start <= outputOffset) {
                if (before == null || e.start > before.start ||
                    (e.start == before.start && e.end <= before.end)
                ) before = e
            } else if (after == null) {
                after = e
            }
        }
        return (before ?: after)?.let { sourcePosOf(it.node) }
    }

    /** [sourcePosition] with a 1-based (line, col) position in the OUTPUT. */
    fun sourcePosition(line: Int, col: Int, exact: Boolean = false): SourcePos? =
        offsetOf(line, col)?.let { sourcePosition(it, exact) }

    /** Serializable projection of the span list (offsets + node kind). */
    fun describeEntries(): List<Triple<Int, Int, String>> =
        entries.map { Triple(it.start, it.end, it.node::class.simpleName ?: "?") }

    companion object {
        /** Position meta of a single node ("line"/"col"/"start"/"end"), if complete. */
        fun sourcePosOf(node: Expression): SourcePos? {
            val meta = node.metaOrNull ?: return null
            val line = (meta["line"] as? Number)?.toInt() ?: return null
            val col = (meta["col"] as? Number)?.toInt() ?: return null
            val start = (meta["start"] as? Number)?.toInt() ?: return null
            val end = (meta["end"] as? Number)?.toInt() ?: return null
            // brikk-native start-anchored line/col (Token.updatePositions). Absent on
            // synthesized nodes / older serialized ASTs: fall back to the single-line
            // derivation (exact unless the token straddles a newline).
            val lineStart = (meta["line_start"] as? Number)?.toInt() ?: line
            val colStart = (meta["col_start"] as? Number)?.toInt() ?: (col - (end - start))
            return SourcePos(line, col, start, end, lineStart, colStart)
        }
    }
}
