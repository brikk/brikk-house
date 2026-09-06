package dev.brikk.house.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CorpusAssertionIdTest {
    private fun assign(descriptors: List<JsonElement>, namespace: String = "parser:test"): List<String> {
        val ids = CorpusAssertionIds(namespace)
        return descriptors.map { ids.next(it) }
    }

    @Test
    fun reorderingAndUnrelatedInsertionKeepIdsAndDuplicateOccurrences() {
        val a = JsonPrimitive("SELECT a")
        val b = JsonPrimitive("SELECT b")
        val c = JsonPrimitive("SELECT c")
        val original = assign(listOf(a, b, a))
        val reordered = assign(listOf(c, b, a, c, a))
        assertEquals(original[0], reordered[2])
        assertEquals(original[1], reordered[1])
        assertEquals(original[2], reordered[4])
        assertEquals(3, original.toSet().size)
        assertTrue(original[0].endsWith(":1"))
        assertTrue(original[2].endsWith(":2"))
        assertTrue(original[0].matches(Regex("assertion:v1:sha256:[0-9a-f]{64}:1")))
        assertNotEquals(original[0], assign(listOf(a), "generator:test").single())
    }

    @Test
    fun objectKeysAreCanonicalButArraysAndSqlStringsAreNotNormalized() {
        val a = Json.parseToJsonElement("""{"z":[{"b":2,"a":1},3],"sql":"SELECT  a"}""")
        val reordered = Json.parseToJsonElement("""{"sql":"SELECT  a","z":[{"a":1,"b":2},3]}""")
        val reversedArray = Json.parseToJsonElement("""{"sql":"SELECT  a","z":[3,{"a":1,"b":2}]}""")
        val normalizedSql = Json.parseToJsonElement("""{"sql":"SELECT a","z":[{"a":1,"b":2},3]}""")
        assertEquals(assign(listOf(a)), assign(listOf(reordered)))
        assertNotEquals(assign(listOf(a)), assign(listOf(reversedArray)))
        assertNotEquals(assign(listOf(a)), assign(listOf(normalizedSql)))
        assertEquals("""{"sql":"SELECT  a","z":[{"a":1,"b":2},3]}""", canonicalCorpusJson(a))
    }

    @Test
    fun fullFixtureInputsAndExpectationsDetermineParserAndGeneratorIds() {
        fun descriptor(sql: String = "SELECT a", expected: String = "a", dump: Int = 1) = buildJsonObject {
            put("sql", sql)
            put("expected", expected)
            put("dump", dump)
        }
        val descriptors = listOf(
            descriptor(), descriptor(sql = "SELECT b"), descriptor(expected = "b"), descriptor(dump = 2),
        )
        assertEquals(descriptors.size, assign(descriptors).toSet().size)
        assertEquals(descriptors.size, assign(descriptors, "generator:test").toSet().size)
    }

    @Test
    fun observedOutputChangesOnlyTheFailureSignature() {
        val descriptor = buildJsonObject {
            put("sql", "SELECT a")
            put("expected", "SELECT a")
        }
        fun observe(actual: String) = assign(listOf(descriptor)).single() to
            CorpusFailure.sqlMismatch("SELECT a", "SELECT a", actual)
        val before = observe("SELECT b")
        val after = observe("SELECT c")
        assertNotEquals(before.second.signature, after.second.signature)
        assertEquals(before.first, after.first)

        // A passing first occurrence still reserves its ID before the failing duplicate.
        val ids = CorpusAssertionIds("parser:test")
        ids.next(descriptor)
        assertEquals(assign(listOf(descriptor, descriptor))[1], ids.next(descriptor))
    }

    @Test
    fun hashingUsesUtf8AndKeepsUnicodeAndEscapingExact() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", corpusSha256("abc"))
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c", corpusSha256("\u00e9"))
        val sql = "SELECT '\u00e9 \ud83d\ude00', '\\n'\n"
        val literal = JsonPrimitive(sql)
        val escaped = Json.parseToJsonElement("\"SELECT '\\u00e9 \\ud83d\\ude00', '\\\\n'\\n\"")
        assertEquals(assign(listOf(literal)), assign(listOf(escaped)))
        assertNotEquals(assign(listOf(literal)), assign(listOf(JsonPrimitive(sql.replace("\u00e9", "e\u0301")))))
        assertNotEquals(assign(listOf(JsonPrimitive("\\n"))), assign(listOf(JsonPrimitive("\n"))))
    }
}
