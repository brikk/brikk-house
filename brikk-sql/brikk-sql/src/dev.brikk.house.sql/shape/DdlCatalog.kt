package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.NotNullColumnConstraint
import dev.brikk.house.sql.ast.PrimaryKeyColumnConstraint
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects

/**
 * BRIKK-NATIVE: builds a [ShapeCatalog] from `CREATE TABLE` DDL text — the schema-cache
 * format proposed in docs/virtual-pipelines-wiring.md ("cache = directory of DDL text").
 * Any statement that is not a table CREATE is ignored. Table keys are the dotted name as
 * written (`schema.table` / `table`); nullability is `false` for NOT NULL / PRIMARY KEY
 * columns and `true` otherwise. Column types are rendered in the base dialect so they are
 * dialect-neutral strings (see [ColumnShape.type]).
 */
object DdlCatalog {

    /**
     * [defaultSchema], when given, qualifies single-part table names (`t` -> `public.t`) so
     * that a catalog mixing qualified and unqualified names keeps one nesting depth — a
     * MappingSchema requirement.
     */
    fun fromDdl(ddl: String, dialect: String, defaultSchema: String? = null): ShapeCatalog {
        val d = Dialects.forName(dialect)
        val tables = LinkedHashMap<String, Shape>()
        for (stmt in d.parse(ddl)) {
            val create = stmt as? Create ?: continue
            if (!create.text("kind").equals("TABLE", ignoreCase = true)) continue
            val schemaNode = create.thisArg as? Expression ?: continue
            val table = schemaNode.find(Table::class) ?: schemaNode as? Table ?: continue
            val parts = (table as Table).parts.map { it.name }
            val name = if (parts.size == 1 && defaultSchema != null) "$defaultSchema.${parts[0]}" else parts.joinToString(".")
            val columns = schemaNode.findAll(ColumnDef::class).map { def ->
                val kind = def.args["kind"] as? DataType
                val constraints = (def.args["constraints"] as? List<*>).orEmpty().filterIsInstance<Expression>()
                val notNull = constraints.any { c ->
                    val k = c.args["kind"]
                    (k is NotNullColumnConstraint && k.args["allow_null"] != true) || k is PrimaryKeyColumnConstraint
                }
                ColumnShape(
                    name = (def.thisArg as Expression).let { (it as? dev.brikk.house.sql.ast.Identifier)?.name ?: it.sqlName() },
                    type = if (kind == null) "UNKNOWN" else Dialects.BASE.generate(kind),
                    nullable = !notNull,
                )
            }.toList()
            tables[name] = Shape(columns)
        }
        return ShapeCatalog(tables)
    }

    private fun Expression.sqlName(): String = Dialects.BASE.generate(this)
}
