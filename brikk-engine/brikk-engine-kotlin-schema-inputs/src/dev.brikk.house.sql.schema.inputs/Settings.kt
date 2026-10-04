package dev.brikk.house.sql.schema.inputs

import org.jetbrains.amper.plugins.Configurable

@Configurable
interface Settings {
    /** Project-relative schema file/root; must match the compiler's schema option. */
    val schemaPath: String
    /** These options apply only to legacy DDL, as in the compiler plugin. */
    val schemaDialect: String get() = "postgres"
    val defaultSchema: String get() = ""
}
