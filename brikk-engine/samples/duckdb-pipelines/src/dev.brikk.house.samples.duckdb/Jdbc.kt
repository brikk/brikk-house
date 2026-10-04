package dev.brikk.house.samples.duckdb

import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.runtime.RelRenderResult
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset

data class JdbcQuery(val sql: String, val values: List<Any?>)
data class QueryRows(val columns: List<String>, val rows: List<List<Any?>>)
data class SampleExecution(val rendered: RelRenderResult, val jdbcSql: String, val result: QueryRows)

/** Sample-only driver bridge. Exact SQL ranges, not regex or AST regeneration. */
fun jdbcQuery(sql: String, bindings: Map<String, Any?>): JdbcQuery {
    val parsed = SqlFragment(sql, "duckdb").toSourcePreservingExecutable()
    require(parsed.sql == sql) { "Driver adaptation must not lower or regenerate executable SQL" }
    val parameters = parsed.parameterOccurrences.sortedBy { it.range.start }
    require(parameters.all { it.style == ParamStyle.NAMED_COLON && it.name != null }) {
        "This sample bridge accepts named parameters only (not positional placeholders)"
    }
    val referenced = parameters.mapTo(linkedSetOf()) { checkNotNull(it.name) }
    require(referenced == bindings.keys) { "Missing or unused bindings in sample query" }
    val values = mutableListOf<Any?>()
    val adapted = buildString {
        var cursor = 0
        for (parameter in parameters) {
            val range = parameter.range
            require(range.start >= cursor && range.end <= sql.length) { "Invalid/overlapping parameter ranges" }
            append(sql, cursor, range.start)
            append('?')
            values += bindings.getValue(checkNotNull(parameter.name))
            cursor = range.end
        }
        append(sql, cursor, sql.length)
    }
    return JdbcQuery(adapted, values)
}

fun execute(connection: Connection, relation: Rel<*>): SampleExecution {
    val rendered = relation.renderWithDiagnostics("duckdb")
    val query = jdbcQuery(rendered.sql, relation.bindings())
    val result = connection.prepareStatement(query.sql).use { statement ->
        query.values.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setNull(index + 1, Types.VARCHAR)
                is Instant -> statement.setObject(index + 1, value.atOffset(ZoneOffset.UTC))
                is BigDecimal -> statement.setBigDecimal(index + 1, value)
                else -> statement.setObject(index + 1, value)
            }
        }
        statement.executeQuery().use { rows ->
            val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }
            val data = buildList {
                while (rows.next()) add((1..columns.size).map { index ->
                    when (val value = rows.getObject(index)) {
                        is java.sql.Date -> value.toLocalDate()
                        else -> value
                    }
                })
            }
            QueryRows(columns, data)
        }
    }
    return SampleExecution(rendered, query.sql, result)
}
