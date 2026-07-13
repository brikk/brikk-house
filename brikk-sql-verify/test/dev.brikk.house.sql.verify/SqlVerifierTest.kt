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
