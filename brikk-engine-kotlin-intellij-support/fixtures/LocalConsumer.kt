package dev.brikk.house.intellij.fixtures

import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkTypeFamilies
import dev.brikk.house.intellij.SqlFamily

/** This consumer must compile/run from Maven Local with no IntelliJ or compiler JARs. */
object LocalConsumer {
    @JvmStatic fun main(args: Array<String>) {
        check(BrikkDialect.DUCKDB.languageId == "DuckDBSQL")
        check(BrikkTypeFamilies.forClassId("java.time.Instant") == SqlFamily.TIMESTAMP)
        println("Maven Local consumer: contract layer works without IDE SDK dependencies")
    }
}
