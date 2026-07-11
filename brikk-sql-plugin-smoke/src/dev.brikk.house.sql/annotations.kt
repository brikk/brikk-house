package dev.brikk.house.sql

/** Placeholder marker recognized by the compiler plugin (see BrikkSqlNames). */
@Target(AnnotationTarget.FUNCTION)
annotation class BrikkSql(val dialect: String = "doris")
