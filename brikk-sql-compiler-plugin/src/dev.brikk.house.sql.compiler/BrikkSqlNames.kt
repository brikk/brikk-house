package dev.brikk.house.sql.compiler

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Central place for the (placeholder) source-level contract between the plugin and user code.
 *
 * The real surface syntax (Sql.doris(...), traits, TVF composition) is still being designed;
 * for now the plugin intercepts calls to any function annotated with [BRIKK_SQL_ANNOTATION].
 */
object BrikkSqlNames {
    const val PLUGIN_ID: String = "dev.brikk.house.sql.compiler"

    /** Marker annotation on the callee function (e.g. `Sql.doris`). Placeholder contract. */
    val BRIKK_SQL_ANNOTATION: FqName = FqName("dev.brikk.house.sql.BrikkSql")
    val BRIKK_SQL_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(BRIKK_SQL_ANNOTATION)
}
