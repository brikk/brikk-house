package dev.brikk.house.sql

import dev.brikk.house.sql.ast.AggregateKeyProperty
import dev.brikk.house.sql.ast.AggregateTypeColumnConstraint
import dev.brikk.house.sql.ast.AutoPartitionProperty
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.PartitionByListProperty
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hand assertions for the Doris dialect wiring, each verified against the Python
 * oracle (reference/sqlglot v30.12.0-44-g93d16591): reserved-keyword quoting,
 * type mapping, mysql->doris and doris->base transpilation through the registry.
 */
class DorisDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "doris").sql("doris")

    @Test
    fun dorisReservedKeywordIsBacktickQuoted() {
        // "string" is Doris-reserved (not MySQL-reserved), so it stays quoted unforced
        assertEquals("SELECT `string` FROM t", roundTrip("SELECT `string` FROM t"))
    }

    @Test
    fun mysqlGroupConcatToDoris() {
        // Doris drops the GROUP_CONCAT function parser: SEPARATOR form becomes csv args
        assertEquals(
            "SELECT GROUP_CONCAT(x, ';') FROM t",
            transpile("SELECT GROUP_CONCAT(x SEPARATOR ';') FROM t", read = "mysql", write = "doris"),
        )
    }

    @Test
    fun baseToDorisTypeMapping() {
        // TEXT -> STRING and TIMESTAMPTZ -> DATETIME under Doris's TYPE_MAPPING
        assertEquals(
            "CREATE TABLE t (c STRING, d DATETIME)",
            transpile("CREATE TABLE t (c TEXT, d TIMESTAMPTZ)", read = "", write = "doris"),
        )
    }

    @Test
    fun dorisDateTruncToBase() {
        // Doris DATE_TRUNC(datetime, unit) parses to exp.TimestampTrunc
        assertEquals(
            "TIMESTAMP_TRUNC('2010-12-02 19:28:30', HOUR)",
            transpile("DATE_TRUNC('2010-12-02 19:28:30', 'HOUR')", read = "doris", write = ""),
        )
    }

    @Test
    fun currentDateRendersWithParens() {
        // Doris removes CURRENT_DATE from NO_PAREN_FUNCTIONS: always CURRENT_DATE()
        assertEquals("SELECT CURRENT_DATE()", transpile("SELECT CURRENT_DATE", read = "mysql", write = "doris"))
    }

    @Test
    fun renameTableStripsDb() {
        // RENAME_TABLE_WITH_DB = False drops the db qualifier from the rename target
        assertEquals(
            "ALTER TABLE db.t1 RENAME t2",
            transpile("ALTER TABLE db.t1 RENAME TO db.t2", read = "mysql", write = "doris"),
        )
    }

    @Test
    fun lagGetsExplicitOffsetAndDefault() {
        // Doris always renders LAG/LEAD with explicit offset and default arguments
        assertEquals(
            "SELECT LAG(a, 1, NULL) OVER (ORDER BY b) FROM t",
            transpile("SELECT LAG(a) OVER (ORDER BY b) FROM t", read = "", write = "doris"),
        )
    }

    // ------------------------------------------------------------------
    // brikk extension (NOT sqlglot parity): FILTER -> CASE rewrite for Doris.
    // Doris has no FILTER clause; sqlglot passes it through (invalid Doris SQL).
    // ------------------------------------------------------------------

    @Test
    fun filterCountStarRewritesToCaseOne() {
        assertEquals(
            "SELECT COUNT(CASE WHEN `status` = 'ok' THEN 1 END) FROM `events`",
            transpile("SELECT COUNT(*) FILTER(WHERE status = 'ok') FROM events", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun filterSumRewritesToCaseExpr() {
        assertEquals(
            "SELECT SUM(CASE WHEN region = 'EU' THEN amount END) AS eu_total FROM sales",
            transpile(
                "SELECT SUM(amount) FILTER(WHERE region = 'EU') AS eu_total FROM sales",
                read = "duckdb",
                write = "doris",
            ),
        )
    }

    @Test
    fun filterCountDistinctRewritesInsideDistinct() {
        assertEquals(
            "SELECT COUNT(DISTINCT CASE WHEN amount > 100 THEN user_id END) FROM sales",
            transpile(
                "SELECT COUNT(DISTINCT user_id) FILTER(WHERE amount > 100) FROM sales",
                read = "duckdb",
                write = "doris",
            ),
        )
    }

    @Test
    fun filterArrayAggSimpleRewritesThroughCollectList() {
        // ARRAY_AGG -> COLLECT_LIST is the normal Doris mapping; the CASE lands inside it
        assertEquals(
            "SELECT COLLECT_LIST(CASE WHEN x > 0 THEN x END) FROM t",
            transpile("SELECT ARRAY_AGG(x) FILTER(WHERE x > 0) FROM t", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun filterOnNonAllowlistedAggregateRaisesUnsupported() {
        // GROUP_CONCAT separators are not result-identical under the CASE rewrite
        val error = assertFailsWith<UnsupportedError> {
            transpile("SELECT STRING_AGG(x, ',') FILTER(WHERE y) FROM t", read = "duckdb", write = "doris")
        }
        assertTrue(
            error.message!!.contains("Doris has no FILTER clause"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun filterOnOrderedArrayAggRaisesUnsupported() {
        assertFailsWith<UnsupportedError> {
            transpile("SELECT ARRAY_AGG(x ORDER BY y) FILTER(WHERE z) FROM t", read = "duckdb", write = "doris")
        }
    }

    // brikk extension #9 (docs/brikk-extensions.md): Doris CREATE TABLE requires a
    // partition-definition list after PARTITION BY (cols); sqlglot emits the bare form,
    // which the FE parser rejects. Engine-side acceptance is pinned in
    // SqlVerifierTest.dorisAcceptsBrikkPartitionByRenderings (brikk-sql-verify).
    @Test
    fun createTablePartitionByColumnsGetsEmptyPartitionDefList() {
        assertEquals(
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2) ()",
            roundTrip("CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c2)"),
        )
        assertEquals(
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c1, c2) ()",
            roundTrip("CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (c1, c2)"),
        )
    }

    @Test
    fun createTablePartitionByFunctionRendersAutoRangeForm() {
        // A function partition key is only analyzer-valid as (auto) RANGE in Doris's
        // internal catalog; the FE infers AUTO from the function expression itself.
        assertEquals(
            "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY RANGE (DATE_TRUNC(c2, 'MONTH')) ()",
            roundTrip("CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY (DATE_TRUNC(c2, 'MONTH'))"),
        )
    }

    @Test
    fun explicitPartitionKindsAreUntouchedByTheCompletion() {
        // PartitionByRangeProperty (explicit RANGE/LIST + definitions) is a different
        // node and must keep sqlglot-parity rendering.
        val rangeSql = "CREATE TABLE test_table (c1 INT, c2 DATE) PARTITION BY RANGE (`c2`) " +
            "(PARTITION `p201701` VALUES LESS THAN ('2017-02-01'))"
        assertEquals(rangeSql, roundTrip(rangeSql))
    }

    // brikk extension #10 (docs/brikk-extensions.md): Doris MV column lists take bare
    // column names (types derive from the query); sqlglot re-emits typed column defs.
    @Test
    fun materializedViewColumnListDropsColumnTypes() {
        assertEquals(
            "CREATE MATERIALIZED VIEW test_table (c1, c2) KEY (c1)",
            roundTrip("CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT) KEY (c1)"),
        )
    }

    @Test
    fun createTableColumnTypesAreKeptOutsideMaterializedViews() {
        assertEquals(
            "CREATE TABLE test_table (c1 INT, c2 INT) UNIQUE KEY (c1)",
            roundTrip("CREATE TABLE test_table (c1 INT, c2 INT) UNIQUE KEY (c1)"),
        )
    }

    // ------------------------------------------------------------------
    // brikk extension #19 (docs/brikk-extensions.md, NOT sqlglot parity): Doris DDL as
    // emitted by `SHOW CREATE TABLE`. sqlglot's Doris dialect fails or falls back to an
    // opaque Command on every case below. Engine-side acceptance of each rendering is
    // pinned in SqlVerifierTest.dorisAcceptsBrikkDdlRenderings (brikk-sql-verify).
    // ------------------------------------------------------------------

    /** Parses as a real Create (not a Command fallback) and is stable across a re-parse. */
    private fun assertDdlRoundTrip(expected: String, input: String = expected) {
        val parsed = parseOne(input, "doris")
        assertTrue(parsed is Create, "expected Create, got ${parsed::class.simpleName}: $input")
        val rendered = parsed.sql("doris")
        assertEquals(expected, rendered)
        assertEquals(expected, roundTrip(rendered), "unstable re-parse")
    }

    @Test
    fun dorisStorageTypesParse() {
        // LARGEINT <-> INT128 (StarRocks' mapping), IPV4/IPV6 (existing kinds),
        // DECIMALV3 folds into DECIMAL, BITMAP/HLL/QUANTILE_STATE are brikk-native kinds.
        assertDdlRoundTrip(
            "CREATE TABLE t (a DECIMAL(10, 2), b LARGEINT, c IPV4, d IPV6, e BITMAP, f HLL, g QUANTILE_STATE)",
            "CREATE TABLE t (a DECIMALV3(10, 2), b LARGEINT, c IPV4, d IPV6, e BITMAP, f HLL, g QUANTILE_STATE)",
        )
        val kinds = (parseOne("CREATE TABLE t (b LARGEINT, e BITMAP)", "doris") as Create)
            .find(Schema::class)!!.expressionsArg
            .map { ((it as ColumnDef).args["kind"] as DataType).thisArg }
        assertEquals(listOf(DType.INT128, DType.BITMAP), kinds)
    }

    @Test
    fun aggregateKeyTableWithColumnAggregators() {
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v BIGINT SUM, b BITMAP BITMAP_UNION, h HLL HLL_UNION, m INT MAX, " +
                "r INT REPLACE_IF_NOT_NULL, q QUANTILE_STATE QUANTILE_UNION) AGGREGATE KEY (k)",
        )
        // Aggregator sits in the column's constraint list as its own kind; other
        // constraints (NULL / DEFAULT / COMMENT) keep their order around it.
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, `pv` BIGINT SUM NULL DEFAULT '0' COMMENT 'pv') AGGREGATE KEY (k)",
            "CREATE TABLE t (k INT, `pv` BIGINT SUM NULL DEFAULT \"0\" COMMENT \"pv\") AGGREGATE KEY (k)",
        )
        val create = parseOne("CREATE TABLE t (k INT, v BIGINT SUM) AGGREGATE KEY (k)", "doris") as Create
        val agg = create.find(AggregateTypeColumnConstraint::class)
        assertEquals("SUM", agg?.name)
        assertTrue(create.find(AggregateKeyProperty::class) != null)
    }

    @Test
    fun emptyPartitionDefinitionListParsesForRangeAndList() {
        // What dynamic partitioning emits: the RANGE/LIST kind must survive (not fold into
        // the kind-less PartitionedByProperty of extension #9).
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) UNIQUE KEY (d, k) PARTITION BY RANGE (d) () " +
                "DISTRIBUTED BY HASH (k) BUCKETS 10",
        )
        assertDdlRoundTrip(
            "CREATE TABLE t (c1 INT, c2 DATE) PARTITION BY LIST (c2) () DISTRIBUTED BY HASH (c1) BUCKETS 1",
        )
        val range = parseOne("CREATE TABLE t (d DATE) PARTITION BY RANGE (d) ()", "doris")
            .find(PartitionByRangeProperty::class)!!
        assertEquals(emptyList<Expression>(), range.args["create_expressions"])
        assertTrue(
            parseOne("CREATE TABLE t (d DATE) PARTITION BY LIST (d) ()", "doris")
                .find(PartitionByListProperty::class) != null,
        )
    }

    @Test
    fun autoPartitionByRangeAndList() {
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATETIME, k INT) AUTO PARTITION BY RANGE (DATE_TRUNC(d, 'DAY')) () " +
                "DISTRIBUTED BY HASH (k)",
            "CREATE TABLE t (d DATETIME, k INT) AUTO PARTITION BY RANGE (date_trunc(d, 'day')) () " +
                "DISTRIBUTED BY HASH (k) BUCKETS AUTO",
        )
        assertDdlRoundTrip(
            "CREATE TABLE t (c1 INT, c2 DATE) AUTO PARTITION BY LIST (c2) () DISTRIBUTED BY HASH (c1) BUCKETS 1",
        )
        val auto = parseOne(
            "CREATE TABLE t (d DATETIME) AUTO PARTITION BY RANGE (DATE_TRUNC(d, 'DAY')) ()", "doris",
        ).find(AutoPartitionProperty::class)!!
        assertTrue(auto.thisArg is PartitionByRangeProperty)
        // AUTO not followed by PARTITION BY is left for the next property parser.
        assertDdlRoundTrip("CREATE TABLE t (k INT) AUTO_INCREMENT=5")
    }

    @Test
    fun invertedIndexWithPropertiesAndComment() {
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, s VARCHAR(10), INDEX idx_s (s) USING INVERTED " +
                "PROPERTIES ('parser'='english') COMMENT 'c') DUPLICATE KEY (k)",
            "CREATE TABLE t (k INT, s VARCHAR(10), INDEX idx_s (s) USING INVERTED " +
                "PROPERTIES(\"parser\" = \"english\") COMMENT 'c') DUPLICATE KEY (k)",
        )
        // MySQL-inherited option set is untouched.
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, s VARCHAR(10), INDEX idx_s (s) USING INVERTED) DUPLICATE KEY (k)",
        )
    }

    @Test
    fun rollupClauseUsesDorisEntryGrammar() {
        // Doris rollupDef: name (cols) [DUPLICATE KEY (cols)] [PROPERTIES (...)] — not
        // StarRocks' `FROM base` form, hence the DorisRollupIndex node.
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v INT) DUPLICATE KEY (k) DISTRIBUTED BY HASH (k) BUCKETS 1 ROLLUP (r1(k, v))",
            "CREATE TABLE t (k INT, v INT) DUPLICATE KEY (k) DISTRIBUTED BY HASH (k) BUCKETS 1 ROLLUP (r1 (k, v))",
        )
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v INT) ROLLUP (r1(k, v), r2(k, v) DUPLICATE KEY (k) PROPERTIES ('a'='b'))",
            "CREATE TABLE t (k INT, v INT) ROLLUP (r1 (k, v), r2 (k, v) DUPLICATE KEY (k) PROPERTIES (\"a\" = \"b\"))",
        )
    }

    @Test
    fun realisticShowCreateTableOutputParses() {
        // Unique-key merge-on-write table with auto partitioning: hits DECIMALV3, IPV4, an
        // inverted index, AUTO PARTITION and an empty partition list in one statement.
        val showCreate = """
            CREATE TABLE `orders` (
              `order_id` BIGINT NOT NULL COMMENT 'id',
              `order_date` DATE NOT NULL,
              `amount` DECIMALV3(18, 4) NULL DEFAULT "0",
              `tags` ARRAY<VARCHAR(20)> NULL,
              `ip` IPV4 NULL,
              INDEX idx_tags (`tags`) USING INVERTED COMMENT 'tags'
            ) ENGINE=OLAP
            UNIQUE KEY(`order_id`, `order_date`)
            COMMENT 'orders'
            AUTO PARTITION BY RANGE (date_trunc(`order_date`, 'month'))
            ()
            DISTRIBUTED BY HASH(`order_id`) BUCKETS AUTO
            PROPERTIES (
            "replication_allocation" = "tag.location.default: 1",
            "enable_unique_key_merge_on_write" = "true"
            )
        """.trimIndent()
        assertDdlRoundTrip(
            "CREATE TABLE `orders` (`order_id` BIGINT NOT NULL COMMENT 'id', `order_date` DATE NOT NULL, " +
                "`amount` DECIMAL(18, 4) NULL DEFAULT '0', `tags` ARRAY<VARCHAR(20)> NULL, `ip` IPV4 NULL, " +
                "INDEX idx_tags (`tags`) USING INVERTED COMMENT 'tags') ENGINE=OLAP " +
                "UNIQUE KEY (`order_id`, `order_date`) COMMENT 'orders' " +
                "AUTO PARTITION BY RANGE (DATE_TRUNC(`order_date`, 'MONTH')) () " +
                "DISTRIBUTED BY HASH (`order_id`) " +
                "PROPERTIES ('replication_allocation'='tag.location.default: 1', " +
                "'enable_unique_key_merge_on_write'='true')",
            showCreate,
        )
    }
}
