package dev.brikk.house.samples.duckdb

import dev.brikk.house.sql.runtime.BrikkSql
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.runtime.Sql

/** Minimal public-surface regressions for DDB-001, separate from business reports. */
@BrikkSql
fun parameterEcho(n: Int) = Sql.duckdb("SELECT /* DDB-001 */ $n AS n")

@BrikkSql
fun parameterAdd(src: Rel<ParameterEchoOut>, n: Int) = Sql.duckdb("SELECT src.n + $n AS result FROM $src()")

@BrikkSql
fun nullableParameterEcho(n: Int?) = Sql.duckdb("SELECT $n AS n")

fun composedParameterRegression(): Rel<*> = parameterAdd(parameterEcho(2), 3)
fun nullParameterRegression(): Rel<*> = nullableParameterEcho(null)
