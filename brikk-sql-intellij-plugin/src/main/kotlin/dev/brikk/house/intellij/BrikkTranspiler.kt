package dev.brikk.house.intellij

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.sqlNames
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError

/**
 * The plugin's transpilation entry point: multi-statement aware (unlike
 * [dev.brikk.house.sql.shape.SqlFragment], which is single-statement by design),
 * never throws, and folds all diagnostics into a [TranspileOutcome]:
 *
 *  - parse failures under the source dialect -> [TranspileOutcome.Failure] with position;
 *  - generator `unsupported(...)` messages (best-effort output, flagged) -> [TranspileOutcome.Success.unsupported];
 *  - function names that would reach the target engine verbatim but are not in its
 *    function catalog (silent-passthrough holes) -> [TranspileOutcome.Success.unmappable].
 */
object BrikkTranspiler {

    sealed interface TranspileOutcome {
        data class Success(
            val sql: String,
            val source: String,
            val target: String,
            /** Generator "flagged but still emitted" diagnostics; non-empty = review needed. */
            val unsupported: List<String>,
            /** Functions unknown to the target engine's catalog (when the target ships one). */
            val unmappable: List<String>,
            val statementCount: Int,
        ) : TranspileOutcome {
            val isClean: Boolean get() = unsupported.isEmpty() && unmappable.isEmpty()
        }

        data class Failure(
            val message: String,
            val line: Int? = null,
            val col: Int? = null,
        ) : TranspileOutcome
    }

    fun transpile(
        sql: String,
        source: String,
        target: String,
        pretty: Boolean = true,
    ): TranspileOutcome {
        val readDialect = Dialects.forNameOrNull(source)
            ?: return TranspileOutcome.Failure("Unknown source dialect: '$source'")
        val writeDialect = Dialects.forNameOrNull(target)
            ?: return TranspileOutcome.Failure("Unknown target dialect: '$target'")

        val statements = try {
            readDialect.parse(sql).filterNotNull()
        } catch (e: ParseError) {
            val info = e.errors.firstOrNull()
            return TranspileOutcome.Failure(
                message = info?.description ?: e.message ?: "Parse error",
                line = info?.line,
                col = info?.col,
            )
        } catch (e: Exception) {
            return TranspileOutcome.Failure(e.message ?: "Parse error (${e::class.simpleName})")
        }
        if (statements.isEmpty()) {
            return TranspileOutcome.Failure("No SQL statements found in the ${BrikkDialects.displayName(source)} input.")
        }

        val generator = writeDialect.generator(pretty = pretty)
        val rendered = ArrayList<String>(statements.size)
        val unsupported = LinkedHashSet<String>()
        for (statement in statements) {
            val out = try {
                generator.generate(statement, copy = true)
            } catch (e: Exception) {
                // UnsupportedError and friends: hard-fail features with no best-effort output.
                return TranspileOutcome.Failure(
                    "Cannot render to ${BrikkDialects.displayName(target)}: ${e.message}"
                )
            }
            // generate() clears the list per call; harvest after each statement.
            unsupported.addAll(generator.unsupportedMessages)
            rendered.add(out)
        }

        val outSql = rendered.joinToString(separator = ";\n\n", postfix = if (rendered.size > 1) ";" else "")
        return TranspileOutcome.Success(
            sql = outSql,
            source = source,
            target = target,
            unsupported = unsupported.toList(),
            unmappable = unmappableFunctions(outSql, target),
            statementCount = statements.size,
        )
    }

    /**
     * Port of [dev.brikk.house.sql.shape.SqlFragment.unmappableFunctions] over already
     * generated (possibly multi-statement) output: re-parse the OUTPUT under the target
     * dialect and collect function calls that would reach the engine as plain
     * `NAME(args)` — unresolved [Anonymous] calls plus typed [Func] nodes with no
     * dedicated renderer — whose names the target's function catalog does not register.
     * Returns empty when the target ships no catalog (doris/trino/duckdb do today) or
     * when the output cannot be re-parsed.
     */
    fun unmappableFunctions(generatedSql: String, target: String): List<String> {
        val targetDialect = Dialects.forNameOrNull(target) ?: return emptyList()
        val catalog = targetDialect.functionCatalog ?: return emptyList()
        val generator = targetDialect.generator()
        val statements = try {
            targetDialect.parse(generatedSql).filterNotNull()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = LinkedHashSet<String>()
        for (statement in statements) {
            for (node in statement.walk(bfs = false)) {
                when {
                    node is Anonymous -> {
                        val name = node.name
                        if (name.isNotEmpty() && name !in catalog) out.add(name)
                    }
                    node is Func && !generator.hasDedicatedRenderer(node::class) -> {
                        val name = node.sqlNames().firstOrNull()?.uppercase() ?: continue
                        if (name !in catalog) out.add(name)
                    }
                    else -> {}
                }
            }
        }
        return out.toList()
    }
}
