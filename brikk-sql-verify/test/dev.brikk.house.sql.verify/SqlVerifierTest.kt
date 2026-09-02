package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.certify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlVerifierTest {

    // -- registry --------------------------------------------------------------------------

    @Test
    fun forEngineReturnsVerifiersForSupportedEngines() {
        // Fidelity tier (native, non-advisory) + advisory tier (ShardingSphere). Postgres now
        // resolves to the advisory ShardingSphere oracle rather than embedded PG.
        for (engine in listOf("trino", "duckdb", "doris", "postgres", "mysql", "hive", "clickhouse")) {
            val verifier = SqlVerifiers.forEngine(engine)
            assertNotNull(verifier, "expected a verifier for $engine")
            assertEquals(engine, verifier.engine)
        }
        assertNotNull(SqlVerifiers.forEngine("TRINO"), "engine lookup is case-insensitive")
        assertNotNull(SqlVerifiers.forEngine("POSTGRES"), "engine lookup is case-insensitive")
    }

    @Test
    fun forEngineReturnsAdvisoryShardingSphereForPortableTier() {
        // postgres/mysql/hive/clickhouse resolve to the advisory ShardingSphere grammar oracle.
        for (engine in listOf("postgres", "mysql", "hive", "clickhouse")) {
            val verifier = SqlVerifiers.forEngine(engine)!!
            assertTrue(verifier is ShardingSphereVerifier, "$engine should be a ShardingSphereVerifier")
            assertTrue(
                verifier.verify("SELECT * FROM t").advisory,
                "$engine results should be advisory",
            )
        }
    }

    @Test
    fun forEngineReturnsNullForUnsupportedEngines() {
        assertNull(SqlVerifiers.forEngine("no-such-engine"))
    }

    // -- trino ----------------------------------------------------------------------------

    @Test
    fun trinoAcceptsValidSql() {
        val result = SqlVerifiers.forEngine("trino")!!.verify("SELECT a FROM t WHERE b = 1")
        assertTrue(result.accepted)
        assertNull(result.error)
    }

    @Test
    fun trinoRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("trino")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(8, result.col)
    }

    @Test
    fun trinoAcceptsGrammarBuiltinsThatCertifyClean() {
        // Belt-and-braces composition from Certify.kt's header: COALESCE is absent
        // from Trino's SHOW FUNCTIONS (parser special form) but grammar-accepted —
        // the catalog's grammarBuiltins clears certify(), and the real Trino parser
        // confirms the emitted SQL end to end.
        val report = SqlFragment("SELECT COALESCE(1, 2)", "duckdb").certify("trino")
        assertTrue(report.ok, "${report.findings}")
        assertEquals(emptyList(), report.findings)
        val result = SqlVerifiers.forEngine("trino")!!.verify(report.result.sql)
        assertTrue(result.accepted, "Trino parser rejected `${report.result.sql}`: ${result.error}")
    }

    @Test
    fun trinoVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("trino")!!
        assertTrue(verifier.verifyExpression("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)").accepted)
        assertFalse(verifier.verify("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)").accepted, "bare expression is not a statement")
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    @Test
    fun trinoAcceptsBrikkGrammarLegalityRenderings() {
        // brikk extension (docs/brikk-extensions.md entry 8): every rendering the Trino
        // generator emits for the grammar-legality fixes (asserted in brikk-sql's
        // TrinoDialectTest) must be accepted by the real Trino parser. Keep in sync with
        // TrinoDialectTest.
        val verifier = SqlVerifiers.forEngine("trino")!!

        // JSON_QUERY wrapper: WITHOUT [CONDITIONAL|UNCONDITIONAL] WRAPPER -> WITHOUT WRAPPER
        val expressions = listOf(
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITHOUT ARRAY WRAPPER)",
            "JSON_QUERY(content, 'strict $.HY.*' WITH CONDITIONAL ARRAY WRAPPER)",
        )
        for (sql in expressions) {
            val result = verifier.verifyExpression(sql)
            assertTrue(result.accepted, "Trino parser rejected `$sql`: ${result.error}")
        }
        // ... and the sqlglot-inherited form it replaces is indeed grammar-illegal.
        assertFalse(
            verifier.verifyExpression("JSON_QUERY(content, 'strict $.HY.*' WITHOUT CONDITIONAL WRAPPER)").accepted,
            "Trino unexpectedly accepts WITHOUT CONDITIONAL WRAPPER",
        )

        // SET PROPERTIES: string-literal keys are normalized to quoted identifiers.
        val statements = listOf(
            "ALTER TABLE people SET PROPERTIES foo = 123, \"foo bar\" = 456",
            "ALTER TABLE people SET PROPERTIES x = 'y'",
            "ALTER TABLE people SET PROPERTIES x = DEFAULT",
        )
        for (sql in statements) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Trino parser rejected `$sql`: ${result.error}")
        }
        assertFalse(
            verifier.verify("ALTER TABLE people SET PROPERTIES foo = 123, 'foo bar' = 456").accepted,
            "Trino unexpectedly accepts string-literal property names",
        )
    }

    @Test
    fun trinoAccepts483ParityRenderings() {
        val verifier = SqlVerifiers.forEngine("trino")!!

        for (sql in listOf(
            "ts AT LOCAL",
            "ROW(1) MATCH SIMPLE (SELECT 1)",
            "ROW(1) MATCH UNIQUE FULL (SELECT 1)",
            "UNIQUE (SELECT 1)",
            "CASE ROW(1) WHEN MATCH PARTIAL (SELECT 1) THEN TRUE ELSE FALSE END",
            "ROW(1 AS a, 2 AS b, 3)",
            "CAST(value AS NUMBER)",
            "CAST(value AS VARIANT)",
        )) {
            val result = verifier.verifyExpression(sql)
            assertTrue(result.accepted, "Trino parser rejected `$sql`: ${result.error}")
        }

        for (sql in listOf(
            "CREATE TABLE metrics (value INTEGER DEFAULT 0)",
            "ALTER TABLE metrics ADD COLUMN value INTEGER DEFAULT 0",
            "ALTER TABLE metrics ALTER COLUMN value SET DEFAULT 1",
            "ALTER TABLE metrics ALTER COLUMN value DROP DEFAULT",
            "INSERT INTO orders@dev SELECT 1",
            "DELETE FROM orders@dev WHERE id = 1",
            "UPDATE orders@dev SET value = 1",
            "MERGE INTO target@dev USING source ON target.id = source.id WHEN MATCHED THEN DELETE",
        )) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Trino parser rejected `$sql`: ${result.error}")
        }
    }

    // -- duckdb ---------------------------------------------------------------------------

    @Test
    fun duckdbAcceptsValidSql() {
        val verifier = SqlVerifiers.forEngine("duckdb")!!
        assertTrue(verifier.verify("SELECT a FROM t WHERE b = 1").accepted, "select goes through json_serialize_sql")
        assertTrue(verifier.verify("CREATE TABLE t (a INT)").accepted, "non-select goes through the prepare fallback")
        assertTrue(
            verifier.verify("SELECT * FROM completely_unknown_table").accepted,
            "binder errors (unknown table) are not grammar rejections",
        )
    }

    @Test
    fun duckdbRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("duckdb")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(13, result.col)
    }

    @Test
    fun duckdbVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("duckdb")!!
        assertTrue(verifier.verifyExpression("x -> '$.family'").accepted)
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    // -- doris ----------------------------------------------------------------------------

    @Test
    fun dorisAcceptsValidSql() {
        val result = SqlVerifiers.forEngine("doris")!!.verify("SELECT a FROM t WHERE b = 1")
        assertTrue(result.accepted)
    }

    @Test
    fun dorisRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("doris")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(8, result.col)
    }

    @Test
    fun dorisVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verifyExpression("DATE_TRUNC(c2, 'MONTH')").accepted)
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    @Test
    fun dorisParserSupportsArrays() {
        // Doris's real grammar accepts arrays; the Doris dialect now emits them first-class
        // (brikk extension, docs/brikk-extensions.md entry 7). This pins the engine-side truth.
        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verify("SELECT ARRAY(1, 2, 3)").accepted)
        assertTrue(verifier.verify("CREATE TABLE t (a ARRAY<INT>)").accepted)
    }

    @Test
    fun dorisAcceptsBrikkArrayRenderings() {
        // brikk extension (docs/brikk-extensions.md entry 7): every array rendering the
        // Doris generator emits (asserted in brikk-sql's DorisArraysTest) must be accepted
        // by the real Doris FE parser. Keep in sync with DorisArraysTest.
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            // array literals (canonical constructor form)
            "SELECT ARRAY(1, 2, 3)",
            "SELECT ARRAY()",
            // ARRAY<T> type mapping: casts + DDL, including nesting
            "SELECT CAST(x AS ARRAY<INT>)",
            "SELECT CAST(ARRAY(1, 2) AS ARRAY<BIGINT>)",
            "CREATE TABLE t (a ARRAY<INT>, b ARRAY<ARRAY<STRING>>)",
            // subscript access (1-based in Doris)
            "SELECT arr[1] FROM t",
            "SELECT ARRAY(1, 2, 3)[1]",
            // table-position UNNEST
            "SELECT * FROM UNNEST(ARRAY(1, 2, 3))",
            "SELECT * FROM UNNEST(arr) AS t(c)",
            // LATERAL VIEW EXPLODE pass-through
            "SELECT c FROM t LATERAL VIEW EXPLODE(arr) tt AS c",
            // scalar-position EXPLODE fallback (flagged by the generator, still emitted;
            // the FE grammar accepts the shape)
            "SELECT EXPLODE(ARRAY(1, 2, 3))",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
    }

    @Test
    fun dorisAcceptsBrikkPartitionByRenderings() {
        // brikk extension (docs/brikk-extensions.md entry 9): CREATE TABLE PARTITION BY
        // clauses are completed to the grammar-legal forms (DorisParser.g4 partitionTable
        // requires a parenthesized partition-definition list). Keep in sync with
        // DorisDialectTest.
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2) ()",
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c1, c2) ()",
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY RANGE (DATE_TRUNC(c2, 'MONTH')) ()",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
        // ... and the sqlglot-inherited bare form is indeed grammar-illegal.
        assertFalse(
            verifier.verify("CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2)").accepted,
            "Doris FE parser unexpectedly accepts a bare PARTITION BY (cols)",
        )
    }

    @Test
    fun dorisAcceptsBrikkDdlRenderings() {
        // brikk extension (docs/brikk-extensions.md entry 19): the Doris generator's
        // renderings of the `SHOW CREATE TABLE` clauses sqlglot cannot parse — storage types,
        // aggregate-key aggregators, empty partition lists, AUTO PARTITION, INDEX PROPERTIES,
        // ROLLUP. Keep in sync with DorisDialectTest (brikk-sql).
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            "CREATE TABLE t (a DECIMAL(10, 2), b LARGEINT, c IPV4, d IPV6, e BITMAP, f HLL, g QUANTILE_STATE)",
            "CREATE TABLE t (k INT, v BIGINT SUM, b BITMAP BITMAP_UNION, h HLL HLL_UNION, m INT MAX, " +
                "r INT REPLACE_IF_NOT_NULL, q QUANTILE_STATE QUANTILE_UNION) AGGREGATE KEY (k)",
            "CREATE TABLE t (d DATE, k INT) UNIQUE KEY (d, k) PARTITION BY RANGE (d) () " +
                "DISTRIBUTED BY HASH (k) BUCKETS 10",
            "CREATE TABLE t (c1 INT, c2 DATE) PARTITION BY LIST (c2) () DISTRIBUTED BY HASH (c1) BUCKETS 1",
            "CREATE TABLE t (d DATETIME, k INT) AUTO PARTITION BY RANGE (DATE_TRUNC(d, 'DAY')) () " +
                "DISTRIBUTED BY HASH (k)",
            "CREATE TABLE t (c1 INT, c2 DATE) AUTO PARTITION BY LIST (c2) () DISTRIBUTED BY HASH (c1) BUCKETS 1",
            "CREATE TABLE t (k INT, s VARCHAR(10), INDEX idx_s (s) USING INVERTED " +
                "PROPERTIES ('parser'='english') COMMENT 'c') DUPLICATE KEY (k)",
            "CREATE TABLE t (k INT, v INT) DUPLICATE KEY (k) DISTRIBUTED BY HASH (k) BUCKETS 1 ROLLUP (r1(k, v))",
            "CREATE TABLE t (k INT, v INT) ROLLUP (r1(k, v), r2(k, v) DUPLICATE KEY (k) PROPERTIES ('a'='b'))",
            // group A of TODO-doris-ddl.md: legacy spellings, AGG_STATE, typed VARIANT, partition lists
            "CREATE TABLE t (a DATETIME(3), b DATE, c DATETIME(0))",
            "CREATE TABLE t (k INT, v AGG_STATE<sum(INT)> GENERIC, w AGG_STATE<max_by(INT NOT NULL, VARCHAR(10) NULL)> GENERIC) " +
                "AGGREGATE KEY (k)",
            "CREATE TABLE t (v VARIANT<'x':LARGEINT, 'ip4':IPV4, 'ts':DATETIME(0), 'arr':ARRAY<STRING>, 'flag':BOOLEAN>, " +
                "w VARIANT<'a':INT, properties('variant_max_subcolumns_count'='10')>, " +
                "p VARIANT<properties('variant_max_subcolumns_count'='10')>)",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01'), PARTITION p3 VALUES LESS THAN (MAXVALUE))",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (FROM ('2020-01-01') TO ('2021-01-01') INTERVAL 1 MONTH, " +
                "PARTITION p_max VALUES LESS THAN (MAXVALUE))",
            "CREATE TABLE t (d INT, k INT) PARTITION BY RANGE (d) (FROM (1) TO (100) INTERVAL 10)",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES LESS THAN ('2020-01-01') " +
                "('replication_num'='1'), PARTITION p2 VALUES LESS THAN ('2020-02-01'))",
            "CREATE TABLE t (c VARCHAR(10)) PARTITION BY LIST (c) (PARTITION p1 VALUES IN ('a') ('replication_num'='1'))",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d, k) (PARTITION p1 VALUES LESS THAN ('2020-01-01', 100), " +
                "PARTITION p2 VALUES LESS THAN ('2020-02-01', MAXVALUE))",
            "CREATE TABLE t (k INT, a VARCHAR(20), d DATETIME) UNIQUE KEY (k, d) ORDER BY (a, d) " +
                "PARTITION BY RANGE (DATE_TRUNC(d, 'DAY')) () DISTRIBUTED BY HASH (k) BUCKETS 16",
            "CREATE TABLE t (v VARIANT<MATCH_NAME 'a*':INT, MATCH_NAME_GLOB 'b?':STRING, 'c':INT>)",
            // statement-level DDL (TODO-doris-ddl.md C1/C3): REFRESH, CREATE INDEX, ALTER TABLE
            // partition / rollup / swap actions, ALTER MATERIALIZED VIEW, DESC ALL, LIKE WITH ROLLUP
            "REFRESH MATERIALIZED VIEW db.mv AUTO",
            "REFRESH MATERIALIZED VIEW mv PARTITIONS (p1)",
            "REFRESH CATALOG c PROPERTIES ('invalid_cache'='true')",
            "REFRESH DATABASE c.db",
            "CREATE INDEX IF NOT EXISTS idx ON db.t (s, k) USING INVERTED PROPERTIES ('parser'='english') COMMENT 'c'",
            "CREATE INDEX idx ON t (s) USING NGRAM_BF PROPERTIES ('gram_size'='3')",
            "DROP INDEX IF EXISTS idx ON db.t",
            "ALTER TABLE t ADD PARTITION IF NOT EXISTS p3 VALUES LESS THAN ('2020-04-01') ('replication_num'='1') " +
                "DISTRIBUTED BY HASH (k) BUCKETS 4",
            "ALTER TABLE t ADD PARTITION p3 VALUES LESS THAN ('2020-04-01') DISTRIBUTED BY HASH (k) BUCKETS 4 " +
                "PROPERTIES ('replication_num'='1')",
            "ALTER TABLE t ADD PARTITION p3 VALUES LESS THAN (MAXVALUE)",
            "ALTER TABLE t ADD TEMPORARY PARTITION tp1 VALUES [('2020-04-01'), ('2020-05-01'))",
            "ALTER TABLE t ADD PARTITION p3 VALUES IN ('x', 'y')",
            "ALTER TABLE t DROP PARTITION IF EXISTS p1 FORCE",
            "ALTER TABLE t DROP TEMPORARY PARTITION tp1",
            "ALTER TABLE t DROP PARTITION p1 FROM INDEX r1",
            "ALTER TABLE t REPLACE PARTITION (p1, p2) WITH TEMPORARY PARTITION (tp1, tp2) FORCE PROPERTIES ('strict_range'='false')",
            "ALTER TABLE t MODIFY PARTITION p1 SET ('replication_num'='1')",
            "ALTER TABLE t MODIFY PARTITION (p1, p2) SET ('replication_num'='1')",
            "ALTER TABLE t MODIFY PARTITION (*) SET ('replication_num'='1')",
            "ALTER TABLE t RENAME PARTITION p1 p2",
            "ALTER TABLE t RENAME ROLLUP r1 r2",
            "ALTER TABLE t RENAME COLUMN a b",
            "ALTER TABLE t SET ('replication_num' = '1', 'dynamic_partition.enable' = 'true')",
            "ALTER TABLE t ADD ROLLUP r1(k, v) DUPLICATE KEY (k) FROM base PROPERTIES ('storage_type'='column')",
            "ALTER TABLE t ADD ROLLUP r1(k, v), r2(k)",
            "ALTER TABLE t REPLACE WITH TABLE t2 PROPERTIES ('swap'='false')",
            "ALTER MATERIALIZED VIEW mv REFRESH COMPLETE ON SCHEDULE EVERY 1 DAY",
            "ALTER MATERIALIZED VIEW mv REFRESH ON COMMIT",
            "ALTER MATERIALIZED VIEW mv RENAME mv2",
            "ALTER MATERIALIZED VIEW db.mv SET ('grace_period' = '10')",
            "ALTER MATERIALIZED VIEW mv REPLACE WITH MATERIALIZED VIEW mv2 PROPERTIES ('swap'='false')",
            "CREATE MATERIALIZED VIEW `mtmv` BUILD IMMEDIATE REFRESH ON COMMIT DISTRIBUTED BY RANDOM BUCKETS 2 AS SELECT k FROM t",
            "DESCRIBE db.t ALL",
            "CREATE TABLE IF NOT EXISTS t2 LIKE db.t WITH ROLLUP (r1, r2)",
            "CREATE TABLE t2 LIKE t WITH ROLLUP",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
    }

    // -- function-semantics renderings (brikk-sql FunctionSemanticsTest) -------------------

    @Test
    fun duckdbAcceptsFunctionSemanticsRenderings() {
        // Tier-1 fixes (docs/research/function-semantics-trino-duckdb.md contradictions 1+2,
        // ported sqlglot duckdb _greatest_least_sql / regexpreplace_sql) and the entry-15
        // TIME(x) rendering. Keep in sync with brikk-sql's FunctionSemanticsTest.
        val verifier = SqlVerifiers.forEngine("duckdb")!!
        val renderings = listOf(
            "SELECT CASE WHEN a IS NULL OR b IS NULL OR c IS NULL THEN NULL ELSE GREATEST(a, b, c) END FROM t",
            "SELECT CASE WHEN a IS NULL THEN NULL ELSE LEAST(a) END FROM t",
            "SELECT REGEXP_REPLACE(x, 'a', 'b', 'g') FROM t",
            "SELECT REGEXP_REPLACE(x, 'a', '', 'g') FROM t",
            "SELECT REGEXP_REPLACE(x, 'a', 'b', 'ims') FROM t",
            "SELECT CAST(CAST(x AS TIMESTAMP) AS TIME) FROM t",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "DuckDB parser rejected `$sql`: ${result.error}")
        }
        // ... and the Python-oracle rendering of a zone-less TIME(x) (empty AT TIME ZONE
        // operand) is indeed grammar-invalid — the reason entry 15 diverges.
        assertFalse(
            verifier.verify("SELECT CAST(CAST(x AS TIMESTAMPTZ) AT TIME ZONE  AS TIME) FROM t").accepted,
            "DuckDB unexpectedly accepts an empty AT TIME ZONE operand",
        )
    }

    @Test
    fun trinoAcceptsFunctionSemanticsRenderings() {
        // brikk extensions 12-14: grammar-legal Trino forms for the reverse-direction
        // REGEXP_REPLACE handling and the absent-name rename fixes. Keep in sync with
        // brikk-sql's FunctionSemanticsTest.
        val verifier = SqlVerifiers.forEngine("trino")!!
        val renderings = listOf(
            "SELECT REGEXP_REPLACE(x, 'a', 'b') FROM t",
            "SELECT IS_INFINITE(x) FROM t",
            "SELECT CURRENT_SCHEMA",
            "SELECT DATE_ADD('MONTH', 2, d) FROM t",
            "SELECT SPLIT(x, ',') FROM t",
            "SELECT GREATEST(a, b) FROM t",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Trino parser rejected `$sql`: ${result.error}")
        }
        // ... and the Python-oracle CURRENT_SCHEMA() call form is indeed grammar-illegal
        // (CURRENT_SCHEMA is a reserved parenthesis-less special form in SqlBase.g4).
        assertFalse(
            verifier.verify("SELECT CURRENT_SCHEMA()").accepted,
            "Trino unexpectedly accepts CURRENT_SCHEMA()",
        )
    }

    @Test
    fun dorisAcceptsFunctionSemanticsRenderings() {
        // brikk extension 14: catalog-backed absent-name fixes for Doris targets. Keep in
        // sync with brikk-sql's FunctionSemanticsTest.
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            "SELECT UNHEX(SHA2(x, 256)) FROM t",
            "SELECT UNHEX(SHA2(x, 512)) FROM t",
            "SELECT UNHEX(MD5(x)) FROM t",
            "SELECT GROUP_BIT_AND(x) FROM t",
            "SELECT GROUP_BIT_OR(x) FROM t",
            "SELECT GROUP_BIT_XOR(x) FROM t",
            "SELECT (WEEKDAY(x) + 1) FROM t",
            "SELECT ARRAY_FILTER(x -> x > 1, arr) FROM t",
            "SELECT ARRAY_MAP(x -> x + 1, arr) FROM t",
            "SELECT ARRAY_PUSHFRONT(arr, e) FROM t",
            "SELECT ARRAY_SORT(arr) FROM t",
            "SELECT ARRAY_REVERSE_SORT(arr) FROM t",
            "SELECT PERCENTILE_APPROX(x, 0.5) FROM t",
            "SELECT ARRAY_RANGE(0, 5)",
            "SELECT ARRAY_RANGE(1, 5 + 1)",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
    }

    @Test
    fun dorisAcceptsBrikkGeneratorMappingFixes() {
        // docs/research/BUGS-doris-generator-mappings-2026-07-13.md: the corrected
        // renderings the Doris generator now emits (asserted in brikk-sql's
        // DorisGeneratorMappingBugsTest) must be grammar-accepted by the real Doris FE
        // parser. (Grammar-only: the FE parser does not resolve function names, so this
        // pins syntax, not the semantic verdicts — those come from the live-probe REPORT.)
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            // P1
            "SELECT ARRAYS_OVERLAP(a, b)",
            "SELECT FROM_MILLISECOND(ms)",
            "SELECT SPLIT_BY_REGEXP(s, p)",
            "SELECT NAMED_STRUCT('a', 1, 'b', 'x')",
            "SELECT JSON_CONTAINS(j, v)",
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(j, p))",
            // P2
            "SELECT ARRAY_SIZE(a)",
            // P3
            "SELECT DATE_FORMAT(ts, '%Y')",
            "SELECT CAST(s AS DATETIME(6))",
            // enhancements
            "SELECT GCD(a, b)",
            "SELECT LCM(a, b)",
            "SELECT ARRAY_POSITION(a, b)",
            "SELECT ENDS_WITH(s, x)",
            "SELECT NOW()",
            "SELECT ST_ASBINARY(g)",
            "SELECT ISNAN(x)",
            "SELECT ARRAY_SLICE(a, 1, 3)",
            "SELECT ARRAY_SLICE(a, 2)",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
    }

    // -- doris (continued) ----------------------------------------------------------------

    @Test
    fun dorisAcceptsBrikkMaterializedViewColumnRendering() {
        // brikk extension (docs/brikk-extensions.md entry 10): MV column lists render as
        // bare names (simpleColumnDef). The remaining doris-verify ledger entry for
        // `CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1)` is inherent to
        // that corpus input (no AS <query>, which createMTMV requires); a complete
        // statement with brikk's column-list rendering is accepted.
        val verifier = SqlVerifiers.forEngine("doris")!!
        val accepted = verifier.verify(
            "CREATE MATERIALIZED VIEW test_table (c1, c2) KEY (c1) AS SELECT a, b FROM t"
        )
        assertTrue(accepted.accepted, "Doris FE parser rejected MV bare column list: ${accepted.error}")
        // ... typed MV column defs are indeed grammar-illegal.
        assertFalse(
            verifier.verify(
                "CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1) AS SELECT a, b FROM t"
            ).accepted,
            "Doris FE parser unexpectedly accepts typed MV column defs",
        )
    }
}
