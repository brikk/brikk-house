package dev.brikk.house.samples.duckdb

fun main(args: Array<String>) {
    require(args.all { it == "--sql" }) { "Usage: duckdb-pipelines [--sql]" }
    sampleDatabase().use { connection ->
        for (report in reports("acme", SAMPLE_START, SAMPLE_END)) {
            val execution = execute(connection, report.relation)
            println("\n${report.name}: ${execution.result.rows.size} result rows, ${execution.rendered.stages.size} SQL stages")
            if ("--sql" in args) {
                println("Runtime SQL (named parameters):\n${execution.rendered.sql}")
                println("JDBC SQL (only parameter representation changed):\n${execution.jdbcSql}")
                println("Binding names: ${report.relation.bindings().keys.joinToString()}")
            }
            println(execution.result.columns.joinToString(" | "))
            execution.result.rows.forEach { println(it.joinToString(" | ") { value -> value?.toString() ?: "NULL" }) }
            val regenerated = execution.rendered.stages.sumOf { it.diagnostics.size }
            val warnings = execution.rendered.stages.sumOf { it.unsupportedMessages.size }
            println("Preservation reports: $regenerated regeneration diagnostics, $warnings unsupported messages")
        }
    }
}
