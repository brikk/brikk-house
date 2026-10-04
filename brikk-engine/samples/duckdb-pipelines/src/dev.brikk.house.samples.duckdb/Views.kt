package dev.brikk.house.samples.duckdb

import dev.brikk.house.sql.runtime.BrikkSql
import dev.brikk.house.sql.runtime.BrikkTrait
import dev.brikk.house.sql.runtime.Partial
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.runtime.Sql
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@BrikkTrait
interface RawEvent : Partial {
    val event_id: Long
    val tenant: String
    val event_at: Instant
    val payload: String?
}

@BrikkTrait
interface ExtractedEvent : RawEvent {
    val customer_raw: String?
    val kind_raw: String?
    val amount_raw: String?
    val channel_raw: String?
}

@BrikkTrait
interface CleanEvent : RawEvent {
    val customer_key: String?
    val event_kind: String?
    val amount: BigDecimal?
    val channel: String?
}

/** Virtual view 1: half-open, tenant-scoped event source. Native SQL stays native. */
@BrikkSql
fun eventsInRange(tenant: String, start: Instant, end: Instant) = Sql.duckdb("""
    SELECT /* sample: event source */ event_id, tenant, event_at, payload
    FROM sample.raw_events
    WHERE tenant = $tenant AND event_at >= $start AND event_at < $end
""".trimIndent())

/** Shared pipe: JSON extraction is defined once for all four reports. */
@BrikkSql
fun <T : RawEvent> extractEventFields(src: Rel<T>) = Sql.duckdb("""
    FROM $src()
    |> EXTEND json_extract_string(payload, '$.customer_id') AS customer_raw,
              json_extract_string(payload, '$.kind') AS kind_raw,
              json_extract_string(payload, '$.amount') AS amount_raw,
              json_extract_string(payload, '$.channel') AS channel_raw
""".trimIndent())

/** Shared pipe: normalization does not silently discard bad/null events. */
@BrikkSql
fun <T : ExtractedEvent> cleanEventFields(src: Rel<T>) = Sql.duckdb("""
    FROM $src()
    |> EXTEND NULLIF(LOWER(TRIM(customer_raw)), '') AS customer_key,
              NULLIF(LOWER(TRIM(kind_raw)), '') AS event_kind,
              TRY_CAST(NULLIF(TRIM(amount_raw), '') AS DECIMAL(12, 2)) AS amount,
              COALESCE(NULLIF(LOWER(TRIM(channel_raw)), ''), 'unknown') AS channel
""".trimIndent())

/**
 * Explicit trait boundary for a reusable helper: do not let a local refined shape
 * escape through an inferred return type and lose the required columns.
 */
fun cleanedEvents(tenant: String, start: Instant, end: Instant): Rel<CleanEvent> =
    cleanEventFields(extractEventFields(eventsInRange(tenant, start, end)))

@BrikkSql
fun customerDirectory(tenant: String) = Sql.duckdb("""
    SELECT tenant,
           LOWER(TRIM(customer_id)) AS customer_key,
           NULLIF(LOWER(TRIM(segment)), '') AS segment
    FROM sample.customers
    WHERE tenant = $tenant
""".trimIndent())

/** Reporting view A: valid orders only; malformed amounts are counted by quality. */
@BrikkSql
fun dailyRevenue(events: Rel<CleanEvent>) = Sql.duckdb("""
    SELECT tenant, CAST(event_at AS DATE) AS day,
           COUNT(*) AS valid_orders, SUM(amount) AS revenue
    FROM $events()
    WHERE event_kind = 'order' AND amount IS NOT NULL
    GROUP BY tenant, CAST(event_at AS DATE)
    ORDER BY tenant, day
""".trimIndent())

/** Reporting view B: same cleaned events, with a pipe aggregate. */
@BrikkSql
fun dailyRefunds(events: Rel<CleanEvent>) = Sql.duckdb("""
    FROM $events()
    |> WHERE event_kind = 'refund' AND amount IS NOT NULL
    |> AGGREGATE COUNT(*) AS refunds, SUM(amount) AS refunded_amount
       GROUP BY tenant, CAST(event_at AS DATE) AS day
    |> ORDER BY tenant, day
""".trimIndent())

/** Reporting view C: explicit two-input wiring and outer-join preservation. */
@BrikkSql
fun customerRevenue(events: Rel<CleanEvent>, customers: Rel<CustomerDirectoryOut>) = Sql.duckdb("""
    SELECT events.tenant,
           CASE WHEN customers.customer_key IS NULL THEN 'unmatched'
                ELSE COALESCE(customers.segment, 'unclassified') END AS segment,
           COUNT(*) AS valid_orders, SUM(events.amount) AS revenue
    FROM $events() AS events
    LEFT JOIN $customers() AS customers
      ON events.tenant = customers.tenant AND events.customer_key = customers.customer_key
    WHERE events.event_kind = 'order' AND events.amount IS NOT NULL
    GROUP BY events.tenant,
             CASE WHEN customers.customer_key IS NULL THEN 'unmatched'
                  ELSE COALESCE(customers.segment, 'unclassified') END
    ORDER BY events.tenant, segment
""".trimIndent())

/** Reporting view D: deliberately observes the rows financial reports exclude. */
@BrikkSql
fun dataQuality(events: Rel<CleanEvent>) = Sql.duckdb("""
    SELECT tenant, COUNT(*) AS events,
           COUNT(CASE WHEN customer_key IS NULL THEN 1 END) AS missing_customer,
           COUNT(CASE WHEN event_kind IS NULL THEN 1 END) AS missing_kind,
           COUNT(CASE WHEN event_kind IN ('order', 'refund') AND amount IS NULL THEN 1 END) AS invalid_amount,
           COUNT(CASE WHEN payload IS NULL THEN 1 END) AS null_payloads,
           COUNT(CASE WHEN event_kind NOT IN ('order', 'refund') THEN 1 END) AS unknown_kind
    FROM $events()
    GROUP BY tenant
    ORDER BY tenant
""".trimIndent())

data class SampleReport(val name: String, val relation: Rel<*>)

/** Reusing this prefix shares graph code, not materialized computation across queries. */
fun reports(tenant: String, start: Instant, end: Instant): List<SampleReport> {
    val events = cleanedEvents(tenant, start, end)
    val customers = customerDirectory(tenant)
    return listOf(
        SampleReport("daily-revenue", dailyRevenue(events)),
        SampleReport("daily-refunds", dailyRefunds(events)),
        SampleReport("customer-revenue", customerRevenue(events = events, customers = customers)),
        SampleReport("data-quality", dataQuality(events)),
    )
}

// Compile-time acceptance: these are generated properties, not hand-written row types.
fun revenueShape(row: DailyRevenueOut): Triple<LocalDate, Long, BigDecimal?> =
    Triple(row.day, row.valid_orders, row.revenue)

fun refundShape(row: DailyRefundsOut): Pair<Long, BigDecimal?> =
    row.refunds to row.refunded_amount

fun qualityShape(row: DataQualityOut): Long = row.invalid_amount
