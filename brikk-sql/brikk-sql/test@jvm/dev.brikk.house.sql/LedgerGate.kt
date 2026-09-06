package dev.brikk.house.sql

import java.io.File
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The human explanation is deliberately excluded from the approval signature. */
data class CorpusFailure(val case: String, val signature: String, val reason: String) {
    companion object {
        fun sqlMismatch(case: String, expected: String, actual: String): CorpusFailure = mismatch(
            case, JsonPrimitive(expected), JsonPrimitive(actual), kind = "sql-mismatch",
            reason = "output mismatch: expected `${expected.take(120)}` actual `${actual.take(120)}`",
        )

        fun mismatch(
            case: String,
            expected: JsonElement,
            actual: JsonElement,
            kind: String = "ast-mismatch",
            reason: String = "${kind} for ${case}",
        ): CorpusFailure = CorpusFailure(case, signature(kind, buildJsonObject {
            put("expected", expected)
            put("actual", actual)
        }), reason)

        fun exception(case: String, phase: String, error: Throwable): CorpusFailure = CorpusFailure(
            case,
            signature("exception", buildJsonObject {
                put("phase", phase)
                put("class", error.javaClass.name)
                put("message", error.message?.let { JsonPrimitive(it) } ?: JsonNull)
            }),
            "$phase: ${error.javaClass.name}: ${error.message?.take(160)}",
        )

        fun missingError(case: String, expected: String, actual: String? = null): CorpusFailure = CorpusFailure(
            case,
            signature("missing-error", buildJsonObject {
                put("expected", expected)
                put("actual", actual?.let { JsonPrimitive(it) } ?: JsonNull)
            }),
            "expected $expected, got ${actual?.let { "`${it.take(120)}`" } ?: "no error"}",
        )

        private fun signature(kind: String, payload: JsonElement): String =
            "$kind:v1:sha256:${corpusSha256(canonicalCorpusJson(payload))}"
    }
}

private class LoadedCorpusLedger(
    val metadata: JsonObject,
    private val rows: Map<String, CorpusFailure>,
) : AbstractMap<String, CorpusFailure>() {
    override val entries: Set<Map.Entry<String, CorpusFailure>> get() = rows.entries
}

/** Unsigned rows survive loading only so enforcement can emit a fresh, reviewable artifact. */
internal fun parseCorpusLedger(root: JsonObject, caseKey: String): Map<String, CorpusFailure> {
    val entries = linkedMapOf<String, CorpusFailure>()
    val explicitIds = mutableSetOf<String>()
    val cases = root.getValue("cases").jsonArray
    for (entry in cases) {
        val id = entry.jsonObject["id"]
        if (id != null && id != JsonNull) {
            require(id is JsonPrimitive && id.isString && id.content.isNotBlank()) { "Invalid ledger ID: $id" }
            require(explicitIds.add(id.content)) { "Duplicate ledger ID: ${id.content}" }
        }
    }
    for ((index, entry) in cases.withIndex()) {
        val obj = entry.jsonObject
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val signed = (obj["signature"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Legacy display keys may collide. Keep every row, but never approve one by display key.
        var key = id ?: "legacy-unsigned:$index"
        while (id == null && key in explicitIds) key = "legacy-unsigned:$key"
        check(key !in entries) { "Duplicate ledger ID: $key" }
        entries[key] = CorpusFailure(
            obj.getValue(caseKey).jsonPrimitive.content,
            if (id == null || signed.isNullOrBlank()) "" else signed,
            obj.getValue("reason").jsonPrimitive.content,
        )
    }
    return LoadedCorpusLedger(JsonObject(root.filterKeys { it != "cases" }), entries)
}

internal fun serializeCorpusLedger(
    failures: Map<String, CorpusFailure>,
    caseKey: String,
    sqlglotVersion: String? = null,
    ledger: Map<String, CorpusFailure> = emptyMap(),
): JsonObject = buildJsonObject {
    (ledger as? LoadedCorpusLedger)?.metadata?.forEach { (key, value) -> put(key, value) }
    if (sqlglotVersion != null) put("sqlglot_version", sqlglotVersion)
    put("cases", buildJsonArray {
        for ((id, failure) in failures) {
            require(id.isNotBlank() && failure.signature.isNotBlank()) { "Actual failures must have an ID and signature" }
            add(buildJsonObject {
                put("id", id)
                put(caseKey, failure.case)
                put("signature", failure.signature)
                put("reason", failure.reason)
            })
        }
    })
}

internal fun validateCorpusLedger(
    ledger: Map<String, CorpusFailure>,
    failures: Map<String, CorpusFailure>,
    mismatchDetails: List<String> = emptyList(),
): List<String> {
    val unsigned = ledger.filterValues { it.signature.isBlank() }
    if (unsigned.isNotEmpty()) return listOf(
        "MIGRATION_REQUIRED: ${unsigned.size} unsigned ledger entries. " +
            "Review the actual artifact and preserve curated reasons; display-key approval is not supported.",
    )
    val unledgered = failures.keys - ledger.keys
    val stale = ledger.keys - failures.keys
    val changed = (failures.keys intersect ledger.keys).filter { failures.getValue(it).signature != ledger.getValue(it).signature }
    val problems = mutableListOf<String>()
    if (unledgered.isNotEmpty()) problems.add(
        "${unledgered.size} UNLEDGERED failures (showing up to 20):\n" +
            unledgered.take(20).joinToString("\n") { "  $it: ${failures.getValue(it).case}\n    reason: ${failures.getValue(it).reason}" },
    )
    if (stale.isNotEmpty()) problems.add(
        "${stale.size} STALE ledger entries now pass or were removed (showing up to 20):\n" +
            stale.take(20).joinToString("\n") { "  $it: ${ledger.getValue(it).case}" },
    )
    if (changed.isNotEmpty()) problems.add(
        "${changed.size} SIGNATURE_CHANGED failures (showing up to 20):\n" +
            changed.take(20).joinToString("\n") {
                "  $it: ${failures.getValue(it).case}\n" +
                    "    approved: ${ledger.getValue(it).signature}\n" +
                    "    actual: ${failures.getValue(it).signature}\n    reason: ${failures.getValue(it).reason}"
            },
    )
    val changedCases = (unledgered + changed).map { failures.getValue(it).case }.toSet()
    val shown = mismatchDetails.filter { detail -> changedCases.any { detail.startsWith("SQL: $it\n") } }
    if (shown.isNotEmpty()) problems.add("mismatch details (up to 10):\n" + shown.take(10).joinToString("\n\n"))
    return problems
}

/** Gates approve assertion IDs and exact failure signatures, not human-readable case labels. */
abstract class LedgerGate {
    protected val json = Json { ignoreUnknownKeys = true }

    protected fun loadLedger(path: String, caseKey: String): Map<String, CorpusFailure> {
        val text = testResourceOrNull(path) ?: return emptyMap()
        return parseCorpusLedger(json.parseToJsonElement(text).jsonObject, caseKey)
    }

    protected fun enforceLedger(
        ledger: Map<String, CorpusFailure>,
        failures: Map<String, CorpusFailure>,
        summary: String,
        actualLedgerName: String,
        caseKey: String,
        sqlglotVersion: String? = null,
        mismatchDetails: List<String> = emptyList(),
    ) {
        val actualLedger = serializeCorpusLedger(failures, caseKey, sqlglotVersion, ledger)
        val outDir = File("build").takeIf { it.isDirectory } ?: File(".")
        val actualFile = File(outDir, actualLedgerName)
        actualFile.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), actualLedger))
        println(summary)
        val problems = validateCorpusLedger(ledger, failures, mismatchDetails)
        if (problems.isNotEmpty()) fail(
            problems.joinToString("\n\n") + "\n\nActual ledger written to ${actualFile.absolutePath}",
        )
    }
}
