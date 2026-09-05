package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.parseOne
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EVAL-02 (TODO-rectify-from-eval.md): the library is used from many threads through the
 * shared [dev.brikk.house.sql.dialects.Dialects] singletons. Parser/Generator instances are
 * per-call, but `Expression.objectId` is drawn from one global counter and used as an identity
 * key by the optimizer, so it must be collision-free under contention.
 */
class ConcurrencyTest {

    private val sql =
        "SELECT a, b + c * d, CASE WHEN x > 1 THEN 'a' ELSE 'b' END, COALESCE(e, f, g) " +
            "FROM t JOIN u ON t.id = u.id WHERE h IN (1, 2, 3) GROUP BY a ORDER BY b"

    @Test
    fun objectIdsAreUniqueAcrossConcurrentParses() {
        val pool = Executors.newFixedThreadPool(8)
        val seen = ConcurrentHashMap<Long, Int>()
        try {
            repeat(64) {
                pool.submit {
                    repeat(100) {
                        parseOne(sql).walk().forEach { n -> seen.merge(n.objectId, 1, Int::plus) }
                    }
                }
            }
        } finally {
            pool.shutdown()
            check(pool.awaitTermination(120, TimeUnit.SECONDS)) { "worker pool did not drain" }
        }
        val duplicated = seen.filterValues { it > 1 }
        assertEquals(emptyMap(), duplicated, "objectId collisions across threads: ${duplicated.size}")
    }

    @Test
    fun concurrentTranspileAndAnnotateMatchSingleThreadedResult() {
        val expectedSql = transpile(sql, read = "postgres", write = "duckdb")
        val expectedTyped = annotateTypes(parseOne(sql, "postgres")).sql()
        val pool = Executors.newFixedThreadPool(8)
        val mismatches = ConcurrentHashMap.newKeySet<String>()
        try {
            repeat(64) {
                pool.submit {
                    repeat(50) {
                        val t = transpile(sql, read = "postgres", write = "duckdb")
                        if (t != expectedSql) mismatches.add("transpile: $t")
                        val a = annotateTypes(parseOne(sql, "postgres")).sql()
                        if (a != expectedTyped) mismatches.add("annotate: $a")
                    }
                }
            }
        } finally {
            pool.shutdown()
            check(pool.awaitTermination(120, TimeUnit.SECONDS)) { "worker pool did not drain" }
        }
        assertEquals(emptySet(), mismatches.toSet())
    }
}
