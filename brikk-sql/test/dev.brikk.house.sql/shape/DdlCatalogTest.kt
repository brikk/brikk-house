package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals

class DdlCatalogTest {

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
