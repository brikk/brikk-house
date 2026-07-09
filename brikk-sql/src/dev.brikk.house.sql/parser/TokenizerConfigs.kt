package dev.brikk.house.sql.parser

/**
 * Registry mapping dialect names to their [TokenizerConfig].
 *
 * Unknown dialects fall back to [TokenizerConfig.BASE] for now; the registry
 * grows as each dialect phase lands its generated tables.
 */
object TokenizerConfigs {
    fun forName(dialect: String): TokenizerConfig = when (dialect.lowercase()) {
        "", "sqlglot" -> TokenizerConfig.BASE
        "mysql" -> MysqlTokenizerTables.CONFIG
        "doris" -> DorisTokenizerTables.CONFIG
        "trino" -> TrinoTokenizerTables.CONFIG
        "duckdb" -> DuckdbTokenizerTables.CONFIG
        "postgres", "postgresql" -> PostgresTokenizerTables.CONFIG
        else -> TokenizerConfig.BASE
    }
}
