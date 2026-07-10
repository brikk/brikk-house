package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ExpressionRegistry
import dev.brikk.house.sql.ast.NATIVE_EXPRESSION_CLASSES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Gate: the argTypes of every node class (handwritten AND generated) must match
 * sqlglot's arg_types exactly — same keys, same order, same required flags — as
 * recorded in ast-corpus/arg-types-manifest.json by tools/gen_ast_nodes.py.
 */
class ArgTypesManifestTest {

    private fun loadManifest() = run {
        val stream = javaClass.classLoader.getResourceAsStream("ast-corpus/arg-types-manifest.json")
            ?: java.io.File("brikk-sql/testResources/ast-corpus/arg-types-manifest.json")
                .takeIf { it.exists() }
                ?.inputStream()
            ?: fail("arg-types-manifest.json not found on classpath or filesystem")
        stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()) }.jsonObject
    }

    @Test
    fun argTypesMatchManifest() {
        val manifest = loadManifest()
        val classes = manifest.getValue("classes").jsonObject
        check(classes.isNotEmpty()) { "empty manifest" }

        val failures = mutableListOf<String>()

        for ((name, spec) in classes) {
            val obj = spec.jsonObject
            val entry = ExpressionRegistry.entries[name]
            if (entry == null) {
                failures.add("$name: not registered in ExpressionRegistry")
                continue
            }

            val expectedModule = obj.getValue("module").jsonPrimitive.content
            if (entry.module != expectedModule) {
                failures.add("$name: module ${entry.module} != $expectedModule")
            }

            val node = entry.factory()
            if (node::class.simpleName != name) {
                failures.add("$name: factory produced ${node::class.simpleName}")
                continue
            }

            val expected = obj.getValue("arg_types").jsonObject
                .entries.map { (k, v) -> k to v.jsonPrimitive.boolean }
            val actual = node.argTypes.entries.map { (k, v) -> k to v }
            if (expected != actual) {
                failures.add("$name:\n  expected ${expected.toMap()}\n  actual   ${actual.toMap()}")
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size}/${classes.size} argTypes mismatches:\n" + failures.joinToString("\n"))
        }

        // Registry must contain exactly the manifest's classes plus the explicitly
        // allowlisted NATIVE (brikk-original) classes — no gaps, no strays. The Python
        // parity check above is untouched: every manifest class is still verified.
        val native = ExpressionRegistry.entries.filterKeys { it in NATIVE_EXPRESSION_CLASSES }
        assertEquals(
            NATIVE_EXPRESSION_CLASSES,
            native.keys,
            "NATIVE_EXPRESSION_CLASSES out of sync with registry NATIVE section",
        )
        for ((name, entry) in native) {
            assertEquals("brikk.pipes", entry.module, "$name: native class must use a brikk module")
            if (name in classes.keys) fail("$name: native class collides with a Python manifest class")
        }
        assertEquals(
            classes.size + native.size,
            ExpressionRegistry.entries.size,
            "registry size != manifest class count + native allowlist; strays: " +
                (ExpressionRegistry.entries.keys - classes.keys - NATIVE_EXPRESSION_CLASSES),
        )
    }
}
