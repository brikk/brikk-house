package dev.brikk.house.sql.parser

/**
 * Port of sqlglot/time.py `format_time` — converts a time-format string from one
 * dialect's specifiers to another via longest-match token scanning.
 *
 * The Python implementation walks a trie with single-symbol backtracking, which is
 * equivalent to a greedy longest-match at each position; we implement the latter
 * directly (mappings are tiny, performance is irrelevant here).
 */
// sqlglot: time.format_time
fun formatTimeString(string: String?, mapping: Map<String, String>): String? {
    if (string.isNullOrEmpty()) return null
    if (mapping.isEmpty()) return string

    val maxLen = mapping.keys.maxOf { it.length }
    val sb = StringBuilder()
    var start = 0
    while (start < string.length) {
        var matched: String? = null
        var len = minOf(maxLen, string.length - start)
        while (len > 0) {
            val candidate = string.substring(start, start + len)
            if (mapping.containsKey(candidate)) {
                matched = candidate
                break
            }
            len -= 1
        }
        if (matched != null) {
            sb.append(mapping.getValue(matched))
            start += matched.length
        } else {
            sb.append(string[start])
            start += 1
        }
    }
    return sb.toString()
}
