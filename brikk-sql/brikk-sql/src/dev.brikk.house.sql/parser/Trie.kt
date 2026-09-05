package dev.brikk.house.sql.parser

/**
 * Character trie used for longest-match scanning of multi-char keywords.
 *
 * sqlglot: trie.py — the Python version is a nested dict keyed by chars with `0`
 * marking a terminal; this is the same structure as a class.
 */
class TrieNode {
    val children: HashMap<Char, TrieNode> = HashMap()

    /** sqlglot: the `0 in trie` terminal marker. */
    var isWord: Boolean = false
}

/** sqlglot: trie.new_trie */
fun buildTrie(keywords: Iterable<String>): TrieNode {
    val root = TrieNode()
    for (key in keywords) {
        var current = root
        for (char in key) {
            current = current.children.getOrPut(char) { TrieNode() }
        }
        current.isWord = true
    }
    return root
}
