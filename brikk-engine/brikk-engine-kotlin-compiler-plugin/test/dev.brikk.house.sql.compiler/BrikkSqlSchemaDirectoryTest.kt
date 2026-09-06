package dev.brikk.house.sql.compiler

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import dev.brikk.house.sql.shape.SchemaCache
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-process kctfork compilations, with synthetic snapshots and no database connection. */
@OptIn(ExperimentalCompilerApi::class)
class BrikkSqlSchemaDirectoryTest {
    private val tableSource = """
        package demo
        import dev.brikk.house.sql.runtime.*

        @BrikkSql
        fun records() = Sql.doris("SELECT * FROM sample.analytics.records")
    """.trimIndent()

    private fun compile(
        schema: String,
        source: String = tableSource,
        workingDir: File? = null,
        schemaDialect: String = "postgres",
        defaultSchema: String? = null,
    ): JvmCompilationResult = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("main.kt", source))
        compilerPluginRegistrars = listOf(BrikkSqlCompilerPluginRegistrar())
        commandLineProcessors = listOf(BrikkSqlCommandLineProcessor())
        pluginOptions = buildList {
            add(PluginOption(BrikkSqlNames.PLUGIN_ID, "schema", schema))
            add(PluginOption(BrikkSqlNames.PLUGIN_ID, "schemaDialect", schemaDialect))
            if (defaultSchema != null) add(PluginOption(BrikkSqlNames.PLUGIN_ID, "defaultSchema", defaultSchema))
        }
        if (workingDir != null) this.workingDir = workingDir
        inheritClassPath = true
        verbose = false
        messageOutputStream = java.io.OutputStream.nullOutputStream()
    }.compile()

    private fun records(type: String = "BIGINT") = CapturedObject(
        catalog = "sample",
        schema = "analytics",
        name = "records",
        kind = "TABLE",
        columns = listOf(CapturedColumn("record_id", type, nullable = false)),
    )

    private fun snapshot(root: Path, objects: List<CapturedObject> = listOf(records())): Path =
        SchemaCache.replace(
            root = root,
            catalog = "sample",
            schema = "analytics",
            dialect = "doris",
            sourceId = "synthetic-fixture",
            objects = objects,
        )

    @Test
    fun `captured large integers generate BigInteger properties and satisfy wide traits`() {
        val root = createTempDirectory("brikk-schema-wide-integer")
        try {
            snapshot(root, listOf(records("LARGEINT")))
            val result = compile(root.toString(), source = """
                package demo
                import dev.brikk.house.sql.runtime.*
                import java.math.BigInteger

                @BrikkTrait interface WideRecord : Partial { val record_id: BigInteger }
                @BrikkSql fun records() = Sql.doris("SELECT record_id FROM sample.analytics.records")
                @BrikkSql fun minimum() = Sql.doris("SELECT MIN(record_id) AS smallest FROM sample.analytics.records")
                @BrikkSql fun identity(src: Rel<WideRecord>) = Sql.doris("SELECT record_id FROM src()")
                fun id(row: RecordsOut): BigInteger = row.record_id
                fun smallest(row: MinimumOut): BigInteger? = row.smallest
                fun chained() = identity(records())
            """.trimIndent())
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            assertEquals("java.math.BigInteger", result.classLoader.loadClass("demo.RecordsOut").getMethod("getRecord_id").returnType.name)
            assertEquals("java.math.BigInteger", result.classLoader.loadClass("demo.MinimumOut").getMethod("getSmallest").returnType.name)
            assertEquals("java.math.BigInteger", result.classLoader.loadClass("demo.IdentityOut").getMethod("getRecord_id").returnType.name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a Doris snapshot also permits PostgreSQL query declarations`() {
        val root = createTempDirectory("brikk-schema-mixed-dialect")
        try {
            snapshot(root)
            val result = compile(root.toString(), source = """
                package demo
                import dev.brikk.house.sql.runtime.*
                @BrikkSql fun constant() = Sql.postgres("SELECT 1 AS id")
                @BrikkSql fun records() = Sql.postgres("SELECT record_id FROM sample.analytics.records")
                fun id(row: RecordsOut): Long = row.record_id
            """.trimIndent())
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `root catalog and scope directories type Doris tables and views without changing native SQL`() {
        val root = createTempDirectory("brikk-schema-native")
        try {
            val scope = snapshot(root, listOf(
                records().copy(columns = listOf(
                    CapturedColumn("record_id", "BIGINT", nullable = false),
                    CapturedColumn("label", "STRING", nullable = false),
                    CapturedColumn("amount", "DECIMAL(12,2)", nullable = false),
                    CapturedColumn("observed_at", "DATETIMEV2(3)", nullable = true),
                )),
                CapturedObject(
                    catalog = "sample",
                    schema = "analytics",
                    name = "record_view",
                    kind = "VIEW",
                    columns = listOf(CapturedColumn("note", "STRING", nullable = false)),
                ),
            ))
            val source = """
                package demo
                import dev.brikk.house.sql.runtime.*
                import java.math.BigDecimal
                import java.time.Instant

                @BrikkSql
                fun records() = Sql.doris("  \n-- captured table\nselect * from sample.analytics.records; -- trailing\n  ")
                @BrikkSql
                fun notes() = Sql.doris(" select note from sample.analytics.record_view; ")

                fun id(row: RecordsOut): Long = row.record_id
                fun label(row: RecordsOut): String = row.label
                fun amount(row: RecordsOut): BigDecimal = row.amount
                fun observedAt(row: RecordsOut): Instant? = row.observed_at
                fun note(row: NotesOut): String = row.note
                fun renderTable(): String = records().render()
                fun renderView(): String = notes().render()
            """.trimIndent()

            for (path in listOf(root, scope.parent, scope)) {
                assertTrue(path.toFile().isDirectory, "snapshot directory: $path")
                val result = compile(
                    schema = path.toString(),
                    source = source,
                    // Neither legacy option may influence a self-describing snapshot.
                    schemaDialect = "not-a-dialect",
                    defaultSchema = "unused",
                )
                assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
                val out = result.classLoader.loadClass("demo.RecordsOut")
                assertEquals("long", out.getMethod("getRecord_id").returnType.name)
                assertEquals("java.lang.String", out.getMethod("getLabel").returnType.name)
                assertEquals("java.math.BigDecimal", out.getMethod("getAmount").returnType.name)
                assertEquals("java.time.Instant", out.getMethod("getObserved_at").returnType.name)
                val view = result.classLoader.loadClass("demo.NotesOut")
                assertEquals("java.lang.String", view.getMethod("getNote").returnType.name)
                val main = result.classLoader.loadClass("demo.MainKt")
                assertEquals(
                    "  \n-- captured table\nselect * from sample.analytics.records; -- trailing\n  ",
                    main.getMethod("renderTable").invoke(null),
                )
                assertEquals(
                    " select note from sample.analytics.record_view; ",
                    main.getMethod("renderView").invoke(null),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relative JSON directory resolves through the compilation workingDir source ancestors`() {
        val project = createTempDirectory("brikk-schema-relative")
        try {
            val relative = "schemas/${project.fileName}"
            snapshot(project.resolve(relative))
            assertFalse(File(relative).exists(), "test precondition: schema must not resolve from cwd")
            val result = compile(relative, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            val out = result.classLoader.loadClass("demo.RecordsOut")
            assertEquals("long", out.getMethod("getRecord_id").returnType.name)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same JVM projects with the same relative schema path never share resolved snapshots`() {
        val projects = createTempDirectory("brikk-schema-projects")
        try {
            val relative = "schemas/${projects.fileName}"
            val first = projects.resolve("first")
            val second = projects.resolve("second")
            snapshot(first.resolve(relative), listOf(records("BIGINT")))
            snapshot(second.resolve(relative), listOf(records("TEXT")))
            assertFalse(File(relative).exists(), "test precondition: schema must not resolve from cwd")

            for ((project, expected) in listOf(first to "long", second to "java.lang.String", first to "long")) {
                val result = compile(relative, workingDir = project.toFile())
                assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
                val out = result.classLoader.loadClass("demo.RecordsOut")
                assertEquals(expected, out.getMethod("getRecord_id").returnType.name)
            }

            // A session with no matching source ancestor must not reuse either project's path.
            val missing = compile(relative, workingDir = projects.resolve("missing").toFile())
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, missing.exitCode, missing.messages)
            assertContains(missing.messages, "[BRIKK_SQL] schema file not found: '$relative'")
        } finally {
            projects.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source ancestor wins over a foreign project schema in the JVM cwd`() {
        // Use the real cwd, not user.dir, which does not change java.io.File's filesystem lookups.
        val foreign = createTempDirectory(File("").absoluteFile.toPath(), "brikk-schema-cwd-")
        val project = createTempDirectory("brikk-schema-anchor")
        try {
            val relative = foreign.fileName.toString()
            snapshot(foreign, listOf(records("TEXT")))
            snapshot(project.resolve(relative), listOf(records("BIGINT")))
            assertTrue(File(relative).isDirectory, "test precondition: conflicting cwd schema must exist")

            val result = compile(relative, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            val out = result.classLoader.loadClass("demo.RecordsOut")
            assertEquals("long", out.getMethod("getRecord_id").returnType.name)

            val absolute = compile(foreign.toString(), workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, absolute.exitCode, absolute.messages)
            assertEquals(
                "java.lang.String",
                absolute.classLoader.loadClass("demo.RecordsOut").getMethod("getRecord_id").returnType.name,
            )
        } finally {
            foreign.toFile().deleteRecursively()
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacing a snapshot changes getters and removes columns and objects on the next compilation`() {
        val project = createTempDirectory("brikk-schema-refresh")
        try {
            val relative = "schemas/${project.fileName}"
            val root = project.resolve(relative)
            val viewSource = """
                package demo
                import dev.brikk.house.sql.runtime.*

                @BrikkSql
                fun notes() = Sql.doris("SELECT note FROM sample.analytics.record_view")
                fun note(row: NotesOut): String = row.note
            """.trimIndent()
            snapshot(root, listOf(
                records().copy(columns = records().columns + CapturedColumn("old_column", "STRING", false)),
                CapturedObject(
                    catalog = "sample",
                    schema = "analytics",
                    name = "record_view",
                    kind = "VIEW",
                    columns = listOf(CapturedColumn("note", "STRING", false)),
                ),
            ))
            val before = compile(relative, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, before.exitCode, before.messages)
            val beforeOut = before.classLoader.loadClass("demo.RecordsOut")
            assertEquals("long", beforeOut.getMethod("getRecord_id").returnType.name)
            assertEquals(setOf("getRecord_id", "getOld_column"), beforeOut.declaredMethods.map { it.name }.toSet())
            val beforeView = compile(relative, source = viewSource, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, beforeView.exitCode, beforeView.messages)

            snapshot(root, listOf(records("TEXT").copy(columns = listOf(
                CapturedColumn("record_id", "TEXT", false),
                CapturedColumn("new_column", "BIGINT", false),
            ))))

            val after = compile(relative, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.OK, after.exitCode, after.messages)
            val afterOut = after.classLoader.loadClass("demo.RecordsOut")
            assertEquals("java.lang.String", afterOut.getMethod("getRecord_id").returnType.name)
            assertEquals("long", afterOut.getMethod("getNew_column").returnType.name)
            assertEquals(setOf("getRecord_id", "getNew_column"), afterOut.declaredMethods.map { it.name }.toSet())

            val removed = compile(relative, source = viewSource, workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, removed.exitCode, removed.messages)
            // Unknown projections map to Any?, so the removed view no longer satisfies String.
            assertContains(removed.messages, "Any?")
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed JSON directory is a compiler diagnostic not an exception`() {
        val root = createTempDirectory("brikk-schema-malformed")
        try {
            snapshot(root)
            val objectFile = root.toFile().walkTopDown().first {
                it.isFile && it.extension == "json" && it.readText().contains("\"columns\"")
            }
            objectFile.writeText("{")
            val result = compile(root.toString())
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertContains(result.messages, "[BRIKK_SQL] schema directory '$root' could not be loaded")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing snapshot directory reports a file or directory diagnostic`() {
        val project = createTempDirectory("brikk-schema-missing")
        try {
            val missing = project.resolve("schemas/missing")
            val result = compile(missing.toString(), workingDir = project.toFile())
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertContains(result.messages, "[BRIKK_SQL] schema file not found: '$missing'")
            assertContains(result.messages, "file or directory")
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
