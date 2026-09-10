package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals

class DdlCatalogTest {

    @Test
    fun quotedAndLegacyRawTableNamesRemainUsable() {
        val ddl = "CREATE TABLE \"order-items\" (id BIGINT NOT NULL)"
        val captured = DdlCatalog.fromDdl(ddl, "postgres")
        assertEquals(setOf("\"order-items\""), captured.tables.keys)
        for (catalog in listOf(captured, ShapeCatalog(mapOf("order-items" to Shape.of("id" to "BIGINT"))))) {
            assertEquals("BIGINT", SqlFragment("SELECT id FROM \"order-items\"", "postgres").outputShape(catalog).columns.single().type)
        }
        val raw = ShapeCatalog(mapOf("ORDER" to Shape.of("id" to "BIGINT")))
        assertEquals("BIGINT", SqlFragment("SELECT id FROM \"order\"", "postgres").outputShape(raw).columns.single().type)
    }

    @Test
    fun catalogIdentifierQuotingDoesNotDependOnTheQueryDialect() {
        val doris = ShapeCatalog(mapOf("`catalog`.`schema`.`records`" to Shape.of("id" to "BIGINT")))
        assertEquals("INT", SqlFragment("SELECT 1 AS id", "postgres").outputShape(doris).columns.single().type)
        assertEquals("BIGINT", SqlFragment("SELECT id FROM catalog.schema.records", "postgres").outputShape(doris).columns.single().type)
        val postgres = ShapeCatalog(mapOf("\"catalog\".\"schema\".\"records\"" to Shape.of("id" to "BIGINT")))
        assertEquals("BIGINT", SqlFragment("SELECT id FROM catalog.schema.records", "doris").outputShape(postgres).columns.single().type)
    }

    @Test
    fun nestedStructFieldsAreNotTopLevelColumns() {
        val cat = DdlCatalog.fromDdl(
            "CREATE TABLE t (payload STRUCT(city VARCHAR), items ARRAY<STRUCT<sku VARCHAR>>)",
            "duckdb",
        )
        assertEquals(listOf("payload", "items"), cat.tables.getValue("t").names())
        assertEquals(listOf("payload", "items"), SqlFragment("SELECT * FROM t", "duckdb").outputShape(cat).names())
        assertEquals("UNKNOWN", SqlFragment("SELECT city FROM t", "duckdb").outputShape(cat).columns.single().type)
    }

    @Test
    fun quotedTableAndColumnCaseRemainDistinct() {
        val cat = DdlCatalog.fromDdl(
            """
            CREATE TABLE users (id INT, "Id" BIGINT);
            CREATE TABLE "Users" (id TEXT);
            """.trimIndent(),
            "postgres",
        )

        assertEquals("INT", SqlFragment("SELECT id FROM users", "postgres").outputShape(cat).columns.single().type)
        val quotedColumn = SqlFragment("SELECT \"Id\" FROM users", "postgres").outputShape(cat).columns.single()
        assertEquals("BIGINT", quotedColumn.type)
        assertEquals("TEXT", SqlFragment("SELECT id FROM \"Users\"", "postgres").outputShape(cat).columns.single().type)
        assertEquals(listOf(false, true), cat.tables.getValue("users").columns.map { it.quoted })
    }

    private val ddl = """
        CREATE TABLE public.events (
          event_id BIGINT PRIMARY KEY,
          event_at TIMESTAMPTZ NOT NULL,
          tenant TEXT NOT NULL,
          payload JSONB
        );
        CREATE INDEX idx_events_at ON public.events (event_at);
        CREATE TABLE tenants (id TEXT NOT NULL, name TEXT);
    """.trimIndent()

    @Test
    fun buildsCatalogFromCreateTableStatements() {
        val cat = DdlCatalog.fromDdl(ddl, "postgres", defaultSchema = "public")
        assertEquals(setOf("public.events", "public.tenants"), cat.tables.keys)
        assertEquals(
            listOf(
                ColumnShape("event_id", "BIGINT", nullable = false),
                ColumnShape("event_at", "TIMESTAMPTZ", nullable = false),
                ColumnShape("tenant", "TEXT", nullable = false),
                ColumnShape("payload", "JSONB", nullable = true),
            ),
            cat.tables.getValue("public.events").columns,
        )
        assertEquals(listOf("id", "name"), cat.tables.getValue("public.tenants").names())
    }

    @Test
    fun catalogFeedsFragmentShapesIncludingJsonExtraction() {
        val cat = DdlCatalog.fromDdl(ddl, "postgres", defaultSchema = "public")
        val src = SqlFragment("FROM public.events |> WHERE event_at >= :start", "postgres")
        assertEquals(listOf("event_id", "event_at", "tenant", "payload"), src.outputShape(cat).names())

        val ext = SqlFragment("FROM __src() |> EXTEND payload->>'user_id' AS user_id", "postgres")
        val out = ext.outputShape(ShapeCatalog(emptyMap(), slots = mapOf("__src" to src.outputShape(cat))))
        // brikk-native Postgres typing rule: ->> yields TEXT (sqlglot leaves it UNKNOWN)
        assertEquals(ColumnShape("user_id", "TEXT"), out.byName("user_id"))
    }

    @Test
    fun slotsCoexistWithQualifiedCatalogTables() {
        val cat = DdlCatalog.fromDdl(ddl, "postgres", defaultSchema = "public")
        val ext = SqlFragment("FROM __src() |> EXTEND payload->>'user_id' AS user_id", "postgres")
        val src = SqlFragment("FROM public.events", "postgres").outputShape(cat)
        val out = ext.outputShape(ShapeCatalog(cat.tables, slots = mapOf("__src" to src)))
        assertEquals(listOf("event_id", "event_at", "tenant", "payload", "user_id"), out.names())
    }
}
