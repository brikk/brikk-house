package dev.brikk.house.sql

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LedgerGateTest {
    private val failure = CorpusFailure.sqlMismatch("display SQL", "expected", "actual")

    private class Harness : LedgerGate() {
        fun loadMissing() = loadLedger("ledger-gate-test/nonexistent.json", "case")
        fun enforce(ledger: Map<String, CorpusFailure>, failures: Map<String, CorpusFailure>, name: String) =
            enforceLedger(ledger, failures, "ledger contract test", name, "case")
    }

    @Test
    fun unledgeredStaleAndSignatureChangedAreIndependentFailures() {
        val changed = CorpusFailure.sqlMismatch(failure.case, "expected", "changed")
        val problems = validateCorpusLedger(
            mapOf("stale" to failure, "changed" to failure),
            mapOf("new" to failure, "changed" to changed),
        ).joinToString("\n")
        for (category in listOf("UNLEDGERED", "STALE", "SIGNATURE_CHANGED")) assertTrue(category in problems)
        assertTrue(failure.case in problems)
        assertTrue(validateCorpusLedger(emptyMap(), emptyMap()).isEmpty())
        assertTrue(Harness().loadMissing().isEmpty())
    }

    @Test
    fun curatedReasonsAndDisplayLabelsAreNotApprovalSignatures() {
        val curated = failure.copy(reason = "Reviewed upstream limitation, retain this explanation")
        assertTrue(validateCorpusLedger(mapOf("id" to curated), mapOf("id" to failure)).isEmpty())
        assertEquals(failure.signature, CorpusFailure.sqlMismatch("another label", "expected", "actual").signature)
        val loaded = parseCorpusLedger(serializeCorpusLedger(mapOf("id" to curated), "sql"), "sql")
        assertEquals(curated, loaded.getValue("id"))
    }

    @Test
    fun serializationUsesDisplayCaseAndPreservesMetadataAndDuplicateLabels() {
        val root = Json.parseToJsonElement("""{"sqlglot_version":"old","dialect":"test","review":{"ticket":15},"cases":[]}""").jsonObject
        val ledger = parseCorpusLedger(root, "sql")
        val failures = linkedMapOf("first" to failure, "second" to failure)
        val actual = serializeCorpusLedger(failures, "sql", ledger = ledger)
        assertEquals(root["sqlglot_version"], actual["sqlglot_version"])
        assertEquals(root["dialect"], actual["dialect"])
        assertEquals(root["review"], actual["review"])
        assertEquals(2, actual.getValue("cases").jsonArray.size)
        val row = actual.getValue("cases").jsonArray.first().jsonObject
        assertEquals("first", row.getValue("id").jsonPrimitive.content)
        assertEquals(failure.case, row.getValue("sql").jsonPrimitive.content)
        assertEquals(failure.signature, row.getValue("signature").jsonPrimitive.content)
        assertEquals(failures, parseCorpusLedger(actual, "sql"))
        assertEquals(JsonPrimitive("new"), serializeCorpusLedger(failures, "sql", "new", ledger)["sqlglot_version"])
    }

    @Test
    fun unsignedRowsNeverApproveEvenWithMatchingDisplayKeysOrNoFailures() {
        val root = Json.parseToJsonElement("""{"cases":[{"case":"same","reason":"one"},{"case":"same","reason":"two"}]}""").jsonObject
        val ledger = parseCorpusLedger(root, "case")
        assertEquals(2, ledger.size)
        assertEquals(listOf("one", "two"), ledger.values.map { it.reason })
        assertTrue(validateCorpusLedger(ledger, emptyMap()).single().startsWith("MIGRATION_REQUIRED"))
        assertTrue(validateCorpusLedger(ledger, mapOf("same" to failure)).single().startsWith("MIGRATION_REQUIRED"))
        for (partial in listOf("\"id\":\"id\",", "\"signature\":\"signature\",")) {
            val parsed = parseCorpusLedger(Json.parseToJsonElement("""{"cases":[{$partial"case":"same","reason":"reason"}]}""").jsonObject, "case")
            assertTrue(validateCorpusLedger(parsed, emptyMap()).single().startsWith("MIGRATION_REQUIRED"))
        }
    }

    @Test
    fun duplicateExplicitLedgerIdsAreRejectedEvenWhenUnsigned() {
        for (signature in listOf("", "\"signature\":\"signed\",")) {
            val root = Json.parseToJsonElement("""{"cases":[{"id":"same",$signature"case":"one","reason":"one"},{"id":"same",$signature"case":"two","reason":"two"}]}""").jsonObject
            val error = assertFailsWith<IllegalArgumentException> { parseCorpusLedger(root, "case") }
            assertTrue(error.message.orEmpty().contains("Duplicate ledger ID"))
        }
    }

    @Test
    fun migrationFailureStillWritesActualArtifactWithoutApprovingIt() {
        val ledger = parseCorpusLedger(Json.parseToJsonElement("""{"cases":[{"case":"old","reason":"curated"}]}""").jsonObject, "case")
        val name = "ledger-gate-test-${UUID.randomUUID()}.json"
        val file = File(File("build").takeIf { it.isDirectory } ?: File("."), name)
        try {
            val error = assertFailsWith<AssertionError> { Harness().enforce(ledger, mapOf("new-id" to failure), name) }
            assertTrue(error.message.orEmpty().contains("MIGRATION_REQUIRED"))
            assertTrue(file.isFile)
            val actual = parseCorpusLedger(Json.parseToJsonElement(file.readText()).jsonObject, "case")
            assertEquals(mapOf("new-id" to failure), actual)
            assertEquals("curated", ledger.values.single().reason)
        } finally {
            file.delete()
        }
    }

    @Test
    fun signaturesUseFullSqlAndCanonicalAstInsteadOfTruncatedDiagnostics() {
        val prefix = "SELECT " + "x".repeat(200)
        val before = CorpusFailure.sqlMismatch("case", "expected", prefix + "a")
        val after = CorpusFailure.sqlMismatch("case", "expected", prefix + "b")
        assertEquals(before.reason, after.reason)
        assertNotEquals(before.signature, after.signature)
        assertTrue(validateCorpusLedger(mapOf("id" to before), mapOf("id" to after)).single().contains("SIGNATURE_CHANGED"))
        assertNotEquals(before.signature, CorpusFailure.sqlMismatch("case", "changed expectation", prefix + "a").signature)
        val ast = Json.parseToJsonElement("""[{"z":1,"a":{"d":2,"c":3}}]""")
        val reordered = Json.parseToJsonElement("""[{"a":{"c":3,"d":2},"z":1}]""")
        assertEquals(CorpusFailure.mismatch("case", ast, JsonNull).signature, CorpusFailure.mismatch("case", reordered, JsonNull).signature)
        assertNotEquals(CorpusFailure.mismatch("case", ast, JsonNull).signature, CorpusFailure.mismatch("case", ast, ast).signature)
        assertNotEquals(CorpusFailure.mismatch("case", ast, JsonNull).signature, CorpusFailure.mismatch("case", ast, JsonNull, kind = "other").signature)
    }

    private class First { class SameName(message: String?) : Exception(message) }
    private class Second { class SameName(message: String?) : Exception(message) }

    @Test
    fun exceptionsSignPhaseFullClassAndFullNullableMessage() {
        fun exception(error: Throwable, phase: String = "parse") = CorpusFailure.exception("case", phase, error)
        val message = "x".repeat(200)
        val before = exception(First.SameName(message + "a"))
        val after = exception(First.SameName(message + "b"))
        assertEquals(before.reason, after.reason)
        assertNotEquals(before.signature, after.signature)
        assertNotEquals(before.signature, exception(Second.SameName(message + "a")).signature)
        assertNotEquals(before.signature, exception(First.SameName(message + "a"), "generation").signature)
        assertNotEquals(exception(First.SameName(null)).signature, exception(First.SameName("null")).signature)
        assertNotEquals(exception(First.SameName(null)).signature, exception(First.SameName("")).signature)
        assertTrue(validateCorpusLedger(mapOf("id" to failure), mapOf("id" to before)).single().contains("SIGNATURE_CHANGED"))
    }

    @Test
    fun missingExpectedErrorsHaveTheirOwnFullSignatures() {
        val prefix = "x".repeat(200)
        val missing = CorpusFailure.missingError("case", "UnsupportedError", prefix + "a")
        assertNotEquals(missing.signature, CorpusFailure.missingError("case", "UnsupportedError", prefix + "b").signature)
        assertNotEquals(missing.signature, CorpusFailure.missingError("case", "ParseError", prefix + "a").signature)
        assertNotEquals(missing.signature, CorpusFailure.sqlMismatch("case", "UnsupportedError", prefix + "a").signature)
        assertNotEquals(CorpusFailure.missingError("case", "error").signature, CorpusFailure.missingError("case", "error", "null").signature)
    }
}
