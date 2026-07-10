package dev.brikk.house.sql

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MANDATORY gate (100%): every pipe-syntax identity case from dialect-corpus/base.json
 * (sql contains "|>") must:
 *  1. parse into an AST that KEEPS the pipe stage structure (contains a PipeQuery);
 *  2. desugar (desugarPipes) + generate to exactly the Python oracle's desugared output
 *     ("expected", falling back to the input sql for identity cases).
 */
class PipeDesugarCorpusTest {

    data class PipeCase(val sql: String, val expected: String, val pretty: Boolean)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun loadPipeCases(): List<PipeCase> {
            val stream = PipeDesugarCorpusTest::class.java.classLoader
                .getResourceAsStream("dialect-corpus/base.json")
                ?: java.io.File("brikk-sql/testResources/dialect-corpus/base.json")
                    .takeIf { it.exists() }
                    ?.inputStream()
                ?: error("dialect-corpus/base.json not found on classpath or filesystem")

            val root = stream.use { json.parseToJsonElement(it.readBytes().decodeToString()) }.jsonObject
            return root.getValue("identity").jsonArray
                .map { it.jsonObject }
                .filter { "|>" in it.getValue("sql").jsonPrimitive.content }
                .map { case ->
                    val sql = case.getValue("sql").jsonPrimitive.content
                    val expected = case["expected"]?.jsonPrimitive?.contentOrNull ?: sql
                    val pretty = case["pretty"]?.jsonPrimitive?.booleanOrNull ?: false
                    PipeCase(sql, expected, pretty)
                }
        }
    }

    @Test
    fun pipeCorpusDesugarsToPythonOracle() {
        val cases = loadPipeCases()
        check(cases.isNotEmpty()) { "no pipe cases found in dialect-corpus/base.json" }

        val failures = mutableListOf<String>()

        for (case in cases) {
            val result = runCatching {
                val ast = parseOne(case.sql)
                check(ast.findAll<PipeQuery>().any()) { "no PipeQuery in parsed AST (stage structure lost)" }
                Generator(pretty = case.pretty).generate(desugarPipes(ast))
            }

            val actual = result.getOrNull()
            if (actual != case.expected) {
                val reason = result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
                failures.add(
                    "SQL: ${case.sql}\n" +
                        (reason?.let { "  error:    $it\n" } ?: "") +
                        "  expected: ${case.expected}\n" +
                        "  actual:   $actual"
                )
            }
        }

        println("PipeDesugarCorpus: ${cases.size - failures.size}/${cases.size} pass (${cases.size} pipe cases found)")

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size}/${cases.size} pipe desugar failures (showing up to 25):\n\n" +
                    failures.take(25).joinToString("\n\n")
            )
        }
    }
}
