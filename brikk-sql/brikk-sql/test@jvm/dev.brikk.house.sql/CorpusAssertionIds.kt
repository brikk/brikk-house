package dev.brikk.house.sql

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** IDs describe assertions, never their observed output or position in a fixture array. */
class CorpusAssertionIds(private val namespace: String) {
    private val occurrences = mutableMapOf<String, Int>()
    private val assigned = mutableSetOf<String>()

    fun next(descriptor: JsonElement): String {
        val canonical = canonicalCorpusJson(buildJsonObject {
            put("namespace", namespace)
            put("descriptor", descriptor)
        })
        val occurrence = (occurrences[canonical] ?: 0) + 1
        occurrences[canonical] = occurrence
        val id = "assertion:v1:sha256:${corpusSha256(canonical)}:$occurrence"
        check(assigned.add(id)) { "Duplicate assigned corpus assertion ID: $id" }
        return id
    }
}

internal fun canonicalCorpusJson(element: JsonElement): String {
    fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { (_, child) -> sorted(child) })
        is JsonArray -> JsonArray(value.map { sorted(it) })
        else -> value
    }
    return sorted(element).toString()
}

internal fun corpusSha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
