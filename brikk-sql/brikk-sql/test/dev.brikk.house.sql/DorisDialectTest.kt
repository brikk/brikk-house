package dev.brikk.house.sql

import dev.brikk.house.sql.ast.AggregateKeyProperty
import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Describe
import dev.brikk.house.sql.ast.DorisBuildIndex
import dev.brikk.house.sql.ast.DorisCancelMaterializedViewTask
import dev.brikk.house.sql.ast.DorisMaterializedViewJob
import dev.brikk.house.sql.ast.DorisModifyPartition
import dev.brikk.house.sql.ast.DorisRecover
import dev.brikk.house.sql.ast.DorisRefresh
import dev.brikk.house.sql.ast.DorisVariantField
import dev.brikk.house.sql.ast.Drop
import dev.brikk.house.sql.ast.Refresh
import dev.brikk.house.sql.ast.Show
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
    fun legacyTypeSpellingsFoldIntoCurrentTypes() {
        assertDdlRoundTrip(
            "CREATE TABLE t (a DATETIME(3), b DATE, c DATETIME(0))",
            "CREATE TABLE t (a DATETIMEV2(3), b DATEV2, c datetimev2(0))",
        )
    }

    @Test
    fun aggStateTypeKeepsFunctionSignatureAndArgNullability() {
        assertDdlRoundTrip(
            "CREATE TABLE t (k INT, v AGG_STATE<sum(INT)> GENERIC, " +
                "w AGG_STATE<max_by(INT NOT NULL, VARCHAR(10) NULL)> GENERIC) AGGREGATE KEY (k)",
            "CREATE TABLE t (k INT, v AGG_STATE<sum(INT)> GENERIC, " +
                "w agg_state<max_by(int not null, varchar(10) null)> GENERIC) AGGREGATE KEY (k)",
        )
        val kind = (parseOne("CREATE TABLE t (v AGG_STATE<sum(INT)>)", "doris") as Create)
            .find(ColumnDef::class)!!.args["kind"] as DataType
        assertEquals(DType.AGG_STATE, kind.thisArg)
    }

    @Test
    fun typedVariantWithFieldsAndProperties() {
        // Field names are string literals, field types are full Doris types, and a
        // trailing properties(...) entry is allowed; a bare VARIANT is untouched.
        assertDdlRoundTrip(
            "CREATE TABLE t (v VARIANT<'x':LARGEINT, 'ip4':IPV4, 'ts':DATETIME(0), 'arr':ARRAY<STRING>, 'flag':BOOLEAN>, " +
                "w VARIANT<'a':INT, properties('variant_max_subcolumns_count'='10')>, " +
                "p VARIANT<properties('variant_max_subcolumns_count'='10')>, b VARIANT NULL)",
            "CREATE TABLE t (v variant<'x':largeint, 'ip4':ipv4, 'ts':datetimev2(0), 'arr':array<text>, 'flag':boolean>, " +
                "w VARIANT<'a':INT, properties(\"variant_max_subcolumns_count\" = \"10\")>, " +
                "p VARIANT<properties(\"variant_max_subcolumns_count\" = \"10\")>, b VARIANT NULL)",
        )
    }

    @Test
    fun partitionDefinitionListsMixAllDorisEntryForms() {
        // bracket range + LESS THAN + bare MAXVALUE in one list
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01'), PARTITION p3 VALUES LESS THAN (MAXVALUE))",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01'), PARTITION p3 VALUES LESS THAN MAXVALUE)",
        )
        // dynamic FROM/TO entry mixed with an explicit one; numeric bounds without a unit
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (FROM ('2020-01-01') TO ('2021-01-01') INTERVAL 1 MONTH, " +
                "PARTITION p_max VALUES LESS THAN (MAXVALUE))",
        )
        assertDdlRoundTrip("CREATE TABLE t (d INT, k INT) PARTITION BY RANGE (d) (FROM (1) TO (100) INTERVAL 10)")
        // per-partition property lists (RANGE and LIST)
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES LESS THAN ('2020-01-01') " +
                "('replication_num'='1'), PARTITION p2 VALUES LESS THAN ('2020-02-01'))",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) (PARTITION p1 VALUES LESS THAN ('2020-01-01') " +
                "(\"replication_num\" = \"1\"), PARTITION p2 VALUES LESS THAN ('2020-02-01'))",
        )
        assertDdlRoundTrip(
            "CREATE TABLE t (c VARCHAR(10)) PARTITION BY LIST (c) (PARTITION p1 VALUES IN ('a') ('replication_num'='1'))",
        )
        // multi-column LESS THAN must not be mistaken for a bracket range; MAXVALUE per column
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d, k) (PARTITION p1 VALUES LESS THAN ('2020-01-01', 100), " +
                "PARTITION p2 VALUES LESS THAN ('2020-02-01', MAXVALUE))",
        )
    }

    @Test
    fun uniqueKeyTableWithSortKeyAndFunctionRangePartition() {
        // Representative of our partitioned event tables: MoW unique key with ORDER BY sort
        // key, a function RANGE partition without the AUTO keyword (the FE infers it), an
        // inverted index, typed and untyped VARIANT columns, LARGEINT, IPV4/IPV6, DATETIMEV2.
        val showCreate = """
            CREATE TABLE import_db.events (
                event_key LARGEINT NOT NULL,
                event_at datetime NOT NULL,
                landed_at datetime NOT NULL,
                event_type VARCHAR(20) NOT NULL,
                event_sub_type VARCHAR(20),
                user_id LARGEINT,
                user_is_premium boolean,
                payload variant<
                    'item_id':largeint,
                    'start':bigint,
                    'autoplay':boolean,
                    'correlator':text
                >,
                experiments array<text>,
                referer_info variant,
                connect_info variant<
                    'client':text,
                    'ip_v4':ipv4,
                    'ip_v6':ipv6
                >,
                ingest_info variant<
                    'source_file':text,
                    'ingested_at':datetimev2(0)
                >,
                possible_bot boolean NOT NULL,
                INDEX idx_experiments(experiments) USING INVERTED
            )
            UNIQUE KEY(event_key, event_at)
            ORDER BY (event_type, event_at)
            PARTITION BY RANGE(date_trunc(event_at, 'day')) ()
            DISTRIBUTED BY HASH(event_key) BUCKETS 16
            PROPERTIES (
                'replication_num' = '1',
                'storage_format' = 'V3',
                'inverted_index_storage_format' = 'V3',
                'function_column.sequence_col' = 'landed_at'
            )
        """.trimIndent()
        assertDdlRoundTrip(
            "CREATE TABLE import_db.`events` (event_key LARGEINT NOT NULL, event_at DATETIME NOT NULL, " +
                "landed_at DATETIME NOT NULL, event_type VARCHAR(20) NOT NULL, event_sub_type VARCHAR(20), " +
                "user_id LARGEINT, user_is_premium BOOLEAN, " +
                "payload VARIANT<'item_id':LARGEINT, 'start':BIGINT, 'autoplay':BOOLEAN, 'correlator':STRING>, " +
                "experiments ARRAY<STRING>, referer_info VARIANT, " +
                "connect_info VARIANT<'client':STRING, 'ip_v4':IPV4, 'ip_v6':IPV6>, " +
                "ingest_info VARIANT<'source_file':STRING, 'ingested_at':DATETIME(0)>, " +
                "possible_bot BOOLEAN NOT NULL, INDEX idx_experiments (experiments) USING INVERTED) " +
                "UNIQUE KEY (event_key, event_at) ORDER BY (event_type, event_at) " +
                "PARTITION BY RANGE (DATE_TRUNC(event_at, 'DAY')) () DISTRIBUTED BY HASH (event_key) BUCKETS 16 " +
                "PROPERTIES ('replication_num'='1', 'storage_format'='V3', 'inverted_index_storage_format'='V3', " +
                "'function_column.sequence_col'='landed_at')",
            showCreate,
        )
        val create = parseOne(showCreate, "doris") as Create
        val range = create.find(PartitionByRangeProperty::class)!!
        assertEquals(emptyList<Expression>(), range.args["create_expressions"])
        assertTrue(create.find(AutoPartitionProperty::class) == null, "no AUTO keyword in the source")
    }

    @Test
    fun variantFieldNamePatternPrefixes() {
        assertDdlRoundTrip("CREATE TABLE t (v VARIANT<MATCH_NAME 'a*':INT, MATCH_NAME_GLOB 'b?':STRING, 'c':INT>)")
        val field = parseOne("CREATE TABLE t (v VARIANT<MATCH_NAME 'a*':INT>)", "doris").find(DorisVariantField::class)!!
        assertEquals("MATCH_NAME", field.args["match"])
    }

    @Test
    fun partitionIfNotExistsInsideCreateTableIsAcceptedAndDropped() {
        // Grammar-legal but a no-op for a new table, so the flag is not modeled.
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE) PARTITION BY RANGE (d) (PARTITION p1 VALUES LESS THAN ('2020-01-01'), " +
                "PARTITION p2 VALUES LESS THAN (MAXVALUE))",
            "CREATE TABLE t (d DATE) PARTITION BY RANGE (d) (PARTITION IF NOT EXISTS p1 VALUES LESS THAN ('2020-01-01'), " +
                "PARTITION IF NOT EXISTS p2 VALUES LESS THAN MAXVALUE)",
        )
        assertDdlRoundTrip(
            "CREATE TABLE t (d DATE) PARTITION BY LIST (d) (PARTITION p1 VALUES IN ('2020-01-01'))",
            "CREATE TABLE t (d DATE) PARTITION BY LIST (d) (PARTITION IF NOT EXISTS p1 VALUES IN ('2020-01-01'))",
        )
    }

    // ------------------------------------------------------------------
    // Statement-level Doris DDL a pipeline runtime issues (TODO-doris-ddl.md C1/C3).
    // ------------------------------------------------------------------

    /** Parses to the given node class (never Command) and re-parses stably. */
    private inline fun <reified T : Expression> assertStatementRoundTrip(expected: String, input: String = expected): T {
        val parsed = parseOne(input, "doris")
        assertTrue(parsed is T, "expected ${T::class.simpleName}, got ${parsed::class.simpleName}: $input")
        val rendered = parsed.sql("doris")
        assertEquals(expected, rendered)
        assertEquals(expected, roundTrip(rendered), "unstable re-parse")
        return parsed as T
    }

    @Test
    fun refreshMaterializedViewCatalogAndDatabase() {
        // REFRESH is a sqlglot statement token the Doris tokenizer never received, so
        // every REFRESH statement used to be a hard parse error.
        assertStatementRoundTrip<DorisRefresh>("REFRESH MATERIALIZED VIEW db.mv AUTO")
        assertStatementRoundTrip<DorisRefresh>("REFRESH MATERIALIZED VIEW mv COMPLETE")
        val r = assertStatementRoundTrip<DorisRefresh>(
            "REFRESH MATERIALIZED VIEW mv PARTITIONS (p1, p2)",
            "REFRESH MATERIALIZED VIEW mv PARTITION (p1, p2)",
        )
        assertEquals(listOf("p1", "p2"), (r.args["partitions"] as List<*>).map { (it as Expression).name })
        assertStatementRoundTrip<DorisRefresh>("REFRESH CATALOG c PROPERTIES ('invalid_cache'='true')")
        assertStatementRoundTrip<DorisRefresh>("REFRESH DATABASE c.db")
        // REFRESH TABLE stays on sqlglot's node
        assertStatementRoundTrip<Refresh>("REFRESH TABLE c.db.t")
        // ... and the keyword is still usable as a column name
        assertEquals("SELECT `refresh`, `resume` FROM t", roundTrip("SELECT refresh, resume FROM t"))
    }

    @Test
    fun createAndDropIndexStatements() {
        assertStatementRoundTrip<Create>(
            "CREATE INDEX IF NOT EXISTS idx ON db.t (s, k) USING INVERTED PROPERTIES ('parser'='english') COMMENT 'c'",
            "CREATE INDEX IF NOT EXISTS idx ON db.t (s, k) USING INVERTED PROPERTIES(\"parser\" = \"english\") COMMENT 'c'",
        )
        assertStatementRoundTrip<Create>("CREATE INDEX idx ON t (s) USING NGRAM_BF PROPERTIES ('gram_size'='3')")
        assertStatementRoundTrip<Create>("CREATE INDEX idx ON t (s) COMMENT 'c'")
        assertStatementRoundTrip<Create>("CREATE INDEX idx ON t (s)")
        assertStatementRoundTrip<Drop>("DROP INDEX IF EXISTS idx ON db.t")
    }

    @Test
    fun alterTablePartitionActions() {
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t ADD PARTITION IF NOT EXISTS p3 VALUES LESS THAN ('2020-04-01') ('replication_num'='1') " +
                "DISTRIBUTED BY HASH (k) BUCKETS 4",
            "ALTER TABLE t ADD PARTITION IF NOT EXISTS p3 VALUES LESS THAN ('2020-04-01') (\"replication_num\" = \"1\") " +
                "DISTRIBUTED BY HASH (k) BUCKETS 4",
        )
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t ADD PARTITION p3 VALUES LESS THAN ('2020-04-01') DISTRIBUTED BY HASH (k) BUCKETS 4 " +
                "PROPERTIES ('replication_num'='1')",
        )
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t ADD PARTITION p3 VALUES LESS THAN (MAXVALUE)",
            "ALTER TABLE t ADD PARTITION p3 VALUES LESS THAN MAXVALUE",
        )
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD TEMPORARY PARTITION tp1 VALUES [('2020-04-01'), ('2020-05-01'))")
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD PARTITION p3 VALUES IN ('x', 'y')")
        assertStatementRoundTrip<Alter>("ALTER TABLE t DROP PARTITION IF EXISTS p1 FORCE")
        assertStatementRoundTrip<Alter>("ALTER TABLE t DROP TEMPORARY PARTITION tp1")
        assertStatementRoundTrip<Alter>("ALTER TABLE t DROP PARTITION p1 FROM INDEX r1")
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t REPLACE PARTITION (p1, p2) WITH TEMPORARY PARTITION (tp1, tp2) FORCE PROPERTIES ('strict_range'='false')",
            "ALTER TABLE t REPLACE PARTITION (p1, p2) WITH TEMPORARY PARTITION (tp1, tp2) PROPERTIES ('strict_range' = 'false') FORCE",
        )
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY PARTITION p1 SET ('replication_num'='1')")
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY PARTITION (p1, p2) SET ('replication_num'='1')")
        val all = assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY PARTITION (*) SET ('replication_num'='1')")
        assertEquals(true, all.find(DorisModifyPartition::class)!!.args["all"])
        assertStatementRoundTrip<Alter>("ALTER TABLE t RENAME PARTITION p1 p2")
        assertStatementRoundTrip<Alter>("ALTER TABLE t RENAME ROLLUP r1 r2")
        assertStatementRoundTrip<Alter>("ALTER TABLE t RENAME COLUMN a b")
        // MySQL-inherited actions still take their own path
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY COLUMN c BIGINT NULL COMMENT 'x'")
        assertStatementRoundTrip<Alter>("ALTER TABLE t DROP COLUMN c")
        assertStatementRoundTrip<Alter>("ALTER TABLE t RENAME t2")
    }

    @Test
    fun alterTableRollupSwapAndSet() {
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t ADD ROLLUP r1(k, v) DUPLICATE KEY (k) FROM base PROPERTIES ('storage_type'='column')",
            "ALTER TABLE t ADD ROLLUP r1 (k, v) DUPLICATE KEY (k) FROM base PROPERTIES ('storage_type' = 'column')",
        )
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD ROLLUP r1(k, v), r2(k)", "ALTER TABLE t ADD ROLLUP r1 (k, v), r2 (k)")
        assertStatementRoundTrip<Alter>("ALTER TABLE t REPLACE WITH TABLE t2 PROPERTIES ('swap'='false')")
        // SET keeps its parens (the FE rejects the bare form) and is stable across a re-parse
        assertStatementRoundTrip<Alter>("ALTER TABLE t SET ('replication_num' = '1', 'dynamic_partition.enable' = 'true')")
    }

    @Test
    fun alterMaterializedView() {
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW mv REFRESH COMPLETE ON SCHEDULE EVERY 1 DAY")
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW mv REFRESH AUTO ON MANUAL")
        // optional method (sqlglot's port took ON as the method)
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW mv REFRESH ON COMMIT")
        assertStatementRoundTrip<Create>(
            "CREATE MATERIALIZED VIEW `mtmv` BUILD IMMEDIATE REFRESH ON COMMIT DISTRIBUTED BY RANDOM BUCKETS 2 AS SELECT k FROM t",
            "CREATE MATERIALIZED VIEW mtmv BUILD IMMEDIATE REFRESH ON COMMIT DISTRIBUTED BY RANDOM BUCKETS 2 AS SELECT k FROM t",
        )
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW mv RENAME mv2")
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW db.mv SET ('grace_period' = '10')")
        assertStatementRoundTrip<Alter>("ALTER MATERIALIZED VIEW mv REPLACE WITH MATERIALIZED VIEW mv2 PROPERTIES ('swap'='false')")
    }

    @Test
    fun describeAllAndCreateTableLikeWithRollup() {
        assertStatementRoundTrip<Describe>("DESCRIBE db.t ALL", "DESC db.t ALL")
        assertStatementRoundTrip<Create>("CREATE TABLE IF NOT EXISTS t2 LIKE db.t WITH ROLLUP (r1, r2)")
        assertStatementRoundTrip<Create>("CREATE TABLE t2 LIKE t WITH ROLLUP")
        assertStatementRoundTrip<Create>("CREATE TABLE t2 LIKE t")
    }

    @Test
    fun commandWordStatementsParseStructurally() {
        // BUILD / PAUSE / RESUME / CANCEL / RECOVER are COMMAND tokens (the tokenizer folds
        // the rest of the statement into one STRING); DorisParser.parseCommand re-tokenizes
        // the body into a node, and keeps the opaque Command for shapes it does not model.
        assertStatementRoundTrip<DorisBuildIndex>("BUILD INDEX idx ON db.t PARTITIONS (p1, p2)")
        assertStatementRoundTrip<DorisBuildIndex>("BUILD INDEX idx ON t")
        val job = assertStatementRoundTrip<DorisMaterializedViewJob>("PAUSE MATERIALIZED VIEW JOB ON db.mv")
        assertEquals("PAUSE", job.args["kind"])
        assertStatementRoundTrip<DorisMaterializedViewJob>("RESUME MATERIALIZED VIEW JOB ON mv")
        assertStatementRoundTrip<DorisCancelMaterializedViewTask>("CANCEL MATERIALIZED VIEW TASK 123 ON db.mv")
        assertStatementRoundTrip<DorisRecover>("RECOVER TABLE db.t")
        assertStatementRoundTrip<DorisRecover>("RECOVER TABLE t 12345 AS t2")
        assertStatementRoundTrip<DorisRecover>("RECOVER DATABASE db AS db2")
        assertStatementRoundTrip<DorisRecover>("RECOVER PARTITION p1 999 AS p2 FROM db.t")
        val other = parseOne("BUILD SOMETHING ELSE", "doris")
        assertTrue(other is Command, "unmodeled shape must stay a Command")
        assertEquals("BUILD SOMETHING ELSE", other.sql("doris"))
        assertEquals("SELECT `build`, `cancel`, `recover` FROM t", roundTrip("SELECT build, cancel, recover FROM t"))
    }

    @Test
    fun remainingAlterTableActions() {
        // TODO-doris-ddl.md C2/C3: ADD COLUMN with a rollup target (was mis-parsed into a bogus
        // second action), the parenthesized multi-column form, ORDER BY, ENABLE FEATURE,
        // MODIFY DISTRIBUTION | ENGINE | COMMENT.
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD COLUMN (c1 INT, c2 STRING) TO r1")
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD COLUMN (c1 INT, c2 STRING)")
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD COLUMN c INT NULL AFTER k TO r1 PROPERTIES ('timeout'='10')")
        // plain ADD COLUMN keeps sqlglot's bare ColumnDef action
        val plain = assertStatementRoundTrip<Alter>("ALTER TABLE t ADD COLUMN c INT NULL AFTER k")
        assertTrue((plain.args["actions"] as List<*>)[0] is ColumnDef)
        assertStatementRoundTrip<Alter>("ALTER TABLE t ADD COLUMN c1 INT, ADD COLUMN c2 INT")
        assertStatementRoundTrip<Alter>("ALTER TABLE t ORDER BY (k, v) FROM r1")
        assertStatementRoundTrip<Alter>(
            "ALTER TABLE t ENABLE FEATURE 'SEQUENCE_LOAD' WITH PROPERTIES ('function_column.sequence_type'='int')",
            "ALTER TABLE t ENABLE FEATURE \"SEQUENCE_LOAD\" WITH PROPERTIES (\"function_column.sequence_type\" = \"int\")",
        )
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY DISTRIBUTION DISTRIBUTED BY HASH (k) BUCKETS 16")
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY ENGINE TO odbc PROPERTIES ('driver'='x')")
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY COMMENT 'x'")
        assertStatementRoundTrip<Alter>("ALTER TABLE t MODIFY COLUMN k COMMENT 'kk'")
    }

    @Test
    fun dorisShowStatements() {
        // SHOW CREATE <kind> keeps the db-qualified name (MySQL's `t FROM db` is rejected)
        assertStatementRoundTrip<Show>("SHOW CREATE TABLE db.t")
        assertStatementRoundTrip<Show>("SHOW CREATE VIEW db.v")
        assertStatementRoundTrip<Show>("SHOW CREATE MATERIALIZED VIEW db.mv")
        assertStatementRoundTrip<Show>("SHOW CREATE MATERIALIZED VIEW mv ON t")
        assertStatementRoundTrip<Show>("SHOW PARTITIONS FROM db.t")
        assertStatementRoundTrip<Show>("SHOW PARTITIONS FROM t WHERE PartitionName = 'p1' LIMIT 10")
        assertStatementRoundTrip<Show>("SHOW TEMPORARY PARTITIONS FROM t")
        assertStatementRoundTrip<Show>("SHOW DATA FROM db.t")
        assertStatementRoundTrip<Show>("SHOW DATA")
        // MySQL forms are untouched
        assertStatementRoundTrip<Show>("SHOW FULL COLUMNS FROM t FROM db")
    }

    // ------------------------------------------------------------------
    // TODO-doris-ddl.md group B: renderings the FE rejected when inherited from MySQL.
    // ------------------------------------------------------------------

    @Test
    fun columnDefinitionRenderingsAreDorisNotMysql() {
        assertDdlRoundTrip("CREATE TABLE t (k INT KEY, v INT SUM) AGGREGATE KEY (k)")
        assertDdlRoundTrip("CREATE TABLE t (a MAP<STRING, INT>, b STRUCT<x:INT, y:STRING>, c ARRAY<STRUCT<x:INT>>)")
        assertDdlRoundTrip(
            "CREATE TABLE t (a DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                "b DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3), c DATE DEFAULT CURRENT_DATE)",
        )
        // ... while CURRENT_TIMESTAMP outside DEFAULT keeps sqlglot's NOW() mapping
        assertEquals("SELECT NOW()", roundTrip("SELECT CURRENT_TIMESTAMP"))
        assertDdlRoundTrip("CREATE TABLE t (a INT, b INT AS (a + 1))")
        assertDdlRoundTrip("CREATE TABLE t (id BIGINT NOT NULL AUTO_INCREMENT(100), k INT, j BIGINT AUTO_INCREMENT)")
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
