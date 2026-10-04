package dev.brikk.house.sql.compiler.analysis

import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.SqlFragment

/** Strict checks use SQL's scope resolver, never a union of column names from unrelated tables. */
internal fun validateColumns(analysis: FunctionAnalysis, catalog: ShapeCatalog) {
    val dialect = Dialects.forName(analysis.dialect)
    val inputs = ShapeCatalog(catalog.tables, analysis.inputs.mapValues { it.value.toShape() })
    // This is an analysis-only disposable rendering; executable Rel SQL remains untouched.
    // SQL owns slot qualification and pipe lowering, including its synthetic __slots namespace.
    val standard = SqlFragment(analysis.sqlText, analysis.dialect)
        .toStandardSql(inputs = inputs, expandStars = true)
    val schema = MappingSchema(dialect = dialect, allowEmptyTables = true)
    val simpleIdentifier = Regex("[_a-zA-Z][_a-zA-Z0-9]*")
    fun columns(shape: Shape) = shape.columns.associate {
        val key = if (it.quoted || !simpleIdentifier.matches(it.name)) {
            dialect.generate(Identifier(args("this" to it.name, "quoted" to true)))
        } else it.name
        key to it.type
    }
    var depth = 1
    for ((name, shape) in inputs.tables) {
        // Catalog identities are data, not query-dialect syntax: a Doris capture can
        // contain backticks while a PostgreSQL declaration consumes the same snapshot.
        val table = if ('"' !in name && '`' !in name) {
            val parts = name.split('.').map { Identifier(args("this" to it, "quoted" to false)) }.reversed()
            require(parts.size in 1..3 && parts.all { it.name.isNotEmpty() }) { "Invalid catalog table identity '$name'" }
            Table(args("this" to parts[0], "db" to parts.getOrNull(1), "catalog" to parts.getOrNull(2)))
        } else {
            val reader = if ('`' in name) Dialects.MYSQL else Dialects.BASE
            reader.parser().parseIntoTable(reader.tokenize(name), name) as Table
        }
        depth = maxOf(depth, table.parts.size)
        schema.addTable(table, columns(shape), matchDepth = false)
    }
    for ((name, shape) in inputs.slots) {
        val table = (List(depth - 1) { "__slots" } + name).joinToString(".")
        schema.addTable(table, columns(shape), matchDepth = false)
    }
    qualify(dialect.parseOne(standard), dialect = dialect, schema = schema,
        inferSchema = false, validateQualifyColumns = true)
}
