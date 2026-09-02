package dev.brikk.house.sql.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The source-level contract between the plugin and user code. Mirrors the declarations in
 * the `brikk-sql-runtime` module (package `dev.brikk.house.sql.runtime`); the plugin refers
 * to them by name only.
 */
object BrikkSqlNames {
    const val PLUGIN_ID: String = "dev.brikk.house.sql.compiler"

    val RUNTIME_PACKAGE: FqName = FqName("dev.brikk.house.sql.runtime")

    /** `@BrikkSql` on a user function whose body is one `Sql.<dialect>("...")` literal. */
    val BRIKK_SQL_ANNOTATION: FqName = RUNTIME_PACKAGE.child(Name.identifier("BrikkSql"))
    val BRIKK_SQL_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(BRIKK_SQL_ANNOTATION)

    /** `@BrikkTrait` on a user `Partial` interface. */
    val BRIKK_TRAIT_ANNOTATION: FqName = RUNTIME_PACKAGE.child(Name.identifier("BrikkTrait"))
    val BRIKK_TRAIT_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(BRIKK_TRAIT_ANNOTATION)

    /** `@BrikkSqlDialect("<dialect>")` on the `Sql.<dialect>` entry points. */
    val BRIKK_SQL_DIALECT_ANNOTATION: FqName = RUNTIME_PACKAGE.child(Name.identifier("BrikkSqlDialect"))
    val BRIKK_SQL_DIALECT_ANNOTATION_CLASS_ID: ClassId = ClassId.topLevel(BRIKK_SQL_DIALECT_ANNOTATION)

    val REL_CLASS_ID: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("Rel"))
    val SHAPE_CLASS_ID: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("Shape"))
    val PARTIAL_CLASS_ID: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("Partial"))
    val SQL_OBJECT_CLASS_ID: ClassId = ClassId(RUNTIME_PACKAGE, Name.identifier("Sql"))

    val REL_INPUT: CallableId = CallableId(REL_CLASS_ID, Name.identifier("input"))
    val REL_BIND: CallableId = CallableId(REL_CLASS_ID, Name.identifier("bind"))


    /** Generated output shape class for `fun eventsInRange(...)` is `EventsInRangeOut`. */
    fun outputClassName(functionName: Name): Name =
        Name.identifier(functionName.asString().replaceFirstChar { it.uppercase() } + "Out")
}
