package dev.brikk.house.sql

import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TranspileCorpusGateTest {
    private val unsupported = buildJsonObject { put("error", "UnsupportedError") }

    @Test
    fun descriptorIncludesEveryAssertionInputButNoObservedOutput() {
        fun descriptor(
            namespace: String = "transpile:bigquery",
            direction: String = "read",
            dialect: String = "postgres, version=16",
            input: String = "SELECT a",
            expected: JsonElement = JsonPrimitive("SELECT b"),
            pretty: Boolean = false,
            identify: Boolean = false,
        ) = transpileAssertionDescriptor(namespace, direction, dialect, input, expected, pretty, identify)
        val descriptors = listOf(
            descriptor(), descriptor(namespace = "transpile:mysql"), descriptor(direction = "write"),
            descriptor(dialect = "postgres, version=17"), descriptor(dialect = "postgres"),
            descriptor(input = "SELECT  a"), descriptor(expected = JsonPrimitive("SELECT c")),
            descriptor(pretty = true), descriptor(identify = true), descriptor(expected = unsupported),
            descriptor(expected = JsonPrimitive("UnsupportedError")),
        )
        val ids = CorpusAssertionIds("transpile:test")
        assertEquals(descriptors.size, descriptors.map { ids.next(it) }.toSet().size)
        assertNotEquals(descriptor()["expected"], descriptor(expected = unsupported)["expected"])
        assertEquals(setOf("namespace", "direction", "dialect", "inputSQL", "expected", "pretty", "identify"), descriptor().keys)
    }

    @Test
    fun unsupportedPassesOnlyFromGenerationOrSuccessfulGenerationWithWarnings() {
        assertNull(transpileAssertionFailure("case", unsupported, { Unit }, { throw UnsupportedError("unsupported") }))
        assertNull(transpileAssertionFailure("case", unsupported, { Unit }, { "sql" }, { listOf("unsupported") }))
        assertEquals(
            CorpusFailure.missingError("case", "UnsupportedError", "sql"),
            transpileAssertionFailure("case", unsupported, { Unit }, { "sql" }),
        )
        val parseError = UnsupportedError("not a generation error")
        val failure = transpileAssertionFailure<Unit>(
            "case", unsupported, { throw parseError }, { error("must not generate after a parse error") }, { listOf("warning") },
        )
        assertEquals(CorpusFailure.exception("case", "parse", parseError), failure)
    }

    @Test
    fun warningsCannotHideAnUnrelatedGenerationException() {
        val warnings = mutableListOf<String>()
        val error = IllegalStateException("full message " + "x".repeat(200) + " late detail")
        val failure = transpileAssertionFailure(
            "case", unsupported, { Unit },
            {
                warnings.add("unsupported feature")
                throw error
            },
            { warnings },
        )
        assertNotNull(failure)
        assertEquals(CorpusFailure.exception("case", "generation", error), failure)
        assertNotEquals(CorpusFailure.missingError("case", "UnsupportedError").signature, failure.signature)
        assertEquals(
            CorpusFailure.exception("case", "generation", error),
            transpileAssertionFailure("case", unsupported, { Unit }, { throw error }),
        )
    }

    @Test
    fun fatalJvmErrorsEscapeBothPhasesEvenWithWarnings() {
        val fatal = LinkageError("fatal")
        assertSame(fatal, assertFailsWith<LinkageError> {
            transpileAssertionFailure<Unit>("case", unsupported, { throw fatal }, { "sql" }, { listOf("warning") })
        })
        assertSame(fatal, assertFailsWith<LinkageError> {
            transpileAssertionFailure("case", unsupported, { Unit }, { throw fatal }, { listOf("warning") })
        })
    }

    @Test
    fun duplicateDisplayLabelsPreserveOnlyActuallyFailingAssertions() {
        val ids = CorpusAssertionIds("transpile:test")
        val failures = linkedMapOf<String, CorpusFailure>()
        var passed = 0
        val outputs = listOf("expected", "wrong", "other wrong")
        for (output in outputs) {
            val descriptor = transpileAssertionDescriptor("transpile:test", "read", "postgres", "input", JsonPrimitive("expected"), false, false)
            val id = ids.next(descriptor)
            val failure = transpileAssertionFailure("same display label", JsonPrimitive("expected"), { Unit }, { output })
            if (failure == null) passed++ else failures[id] = failure
        }
        assertEquals(outputs.size, passed + failures.size)
        assertEquals(2, failures.size)
        assertEquals(listOf("2", "3"), failures.keys.map { it.substringAfterLast(':') })
        val serialized = serializeCorpusLedger(failures, "case")
        assertEquals(2, serialized.getValue("cases").jsonArray.size)
        assertEquals(1, failures.values.map { it.case }.toSet().size)
    }

    @Test
    fun ordinarySqlExpectationsDoNotAcceptUnsupportedExceptionsOrWrongSqlWithWarnings() {
        val error = UnsupportedError("unsupported")
        assertEquals(
            CorpusFailure.exception("case", "generation", error),
            transpileAssertionFailure("case", JsonPrimitive("sql"), { Unit }, { throw error }),
        )
        assertEquals(
            CorpusFailure.sqlMismatch("case", "sql", "other"),
            transpileAssertionFailure("case", JsonPrimitive("sql"), { Unit }, { "other" }, { listOf("warning") }),
        )
        assertNull(transpileAssertionFailure("case", JsonPrimitive("sql"), { Unit }, { "sql" }))
    }

    @Test
    fun namedTranspileInventoryRetainsCollisionsAndStableAssertionIds() {
        val allIds = mutableListOf<String>()
        val allLabels = mutableListOf<String>()
        for (corpusDialect in listOf("bigquery", "clickhouse", "doris", "duckdb", "hive", "mysql", "postgres", "presto", "spark", "starrocks", "trino")) {
            val namespace = "transpile:$corpusDialect"
            val cases = Json.parseToJsonElement(testResource("dialect-corpus/$corpusDialect.json")).jsonObject.getValue("transpile").jsonArray
            val descriptors = mutableListOf<JsonObject>()
            for (element in cases) {
                val case = element.jsonObject
                val sql = case.getValue("sql").jsonPrimitive.content
                for (direction in listOf("read", "write")) {
                    for ((dialect, value) in (case[direction] as? JsonObject).orEmpty()) {
                        if (CorpusDialects.resolveOrSkip(dialect) == null) continue
                        val isSql = value is JsonPrimitive && value.isString
                        val isUnsupported = direction == "write" && value is JsonObject &&
                            (value["error"] as? JsonPrimitive)?.content == "UnsupportedError"
                        if (!isSql && !isUnsupported) continue
                        descriptors.add(transpileAssertionDescriptor(
                            namespace, direction, dialect,
                            if (direction == "read") value.jsonPrimitive.content else sql,
                            if (direction == "read") JsonPrimitive(sql) else value,
                            (case["pretty"] as? JsonPrimitive)?.content == "true",
                            (case["identify"] as? JsonPrimitive)?.content == "true",
                        ))
                        allLabels.add("$namespace|$direction|$dialect|$sql")
                    }
                }
            }
            assertTrue(descriptors.isNotEmpty(), corpusDialect)
            val ids = CorpusAssertionIds(namespace)
            val assigned = descriptors.map { ids.next(it) }
            allIds.addAll(assigned)
            val reversedIds = CorpusAssertionIds(namespace)
            assertEquals(assigned.toSet(), descriptors.reversed().map { reversedIds.next(it) }.toSet(), "$corpusDialect reordered")
            val insertedIds = CorpusAssertionIds(namespace)
            val withInsertions = descriptors.map {
                insertedIds.next(buildJsonObject { put("unrelated-inventory-assertion", true) })
                insertedIds.next(it)
            }
            assertEquals(assigned, withInsertions, "$corpusDialect unrelated insertion")

            if (corpusDialect == "presto") {
                val pair = descriptors.zip(assigned).filter { (descriptor, _) ->
                    descriptor.getValue("direction").jsonPrimitive.content == "write" &&
                        descriptor.getValue("dialect").jsonPrimitive.content == "hive" &&
                        descriptor.getValue("inputSQL").jsonPrimitive.content == "SELECT APPROX_DISTINCT(a, 0.1) FROM foo"
                }
                assertEquals(2, pair.size)
                assertEquals(setOf("sql", "error"), pair.flatMap { it.first.getValue("expected").jsonObject.keys }.toSet())
                assertEquals(pair[0].first.filterKeys { it != "expected" }, pair[1].first.filterKeys { it != "expected" })
                // Different expectations must change the descriptor hash, not just the occurrence suffix.
                assertNotEquals(pair[0].second.substringBeforeLast(':'), pair[1].second.substringBeforeLast(':'))
            }
        }
        assertEquals(3059, allIds.size)
        assertEquals(3059, allIds.toSet().size)
        assertEquals(3040, allLabels.toSet().size)
        assertEquals(19, allLabels.groupingBy { it }.eachCount().count { it.value > 1 })
    }
}
