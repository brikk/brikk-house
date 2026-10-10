package dev.brikk.house.samples.duckdb

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset

val SAMPLE_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
val SAMPLE_END: Instant = Instant.parse("2026-01-03T00:00:00Z")

/** Always a fresh in-memory database; no production connection or persistent file. */
fun sampleDatabase(): Connection = DriverManager.getConnection("jdbc:duckdb:").also { connection ->
    try {
        connection.createStatement().use { statement ->
            statement.execute("SET TimeZone = 'UTC'")
            val ddl = checkNotNull(object {}.javaClass.getResourceAsStream("/schema.sql")) { "Missing sample schema resource" }
                .bufferedReader().use { it.readText() }
            statement.execute(ddl)
        }
        seedCustomers(connection)
        seedEvents(connection)
    } catch (e: Throwable) {
        connection.close()
        throw e
    }
}

private fun seedCustomers(connection: Connection) {
    connection.prepareStatement("INSERT INTO sample.customers VALUES (?, ?, ?)").use { statement ->
        for ((tenant, id, segment) in listOf(
            Triple("acme", " c-001 ", " Gold "), Triple("acme", "C-002", null), Triple("beta", "c-001", "platinum"),
        )) {
            statement.setString(1, tenant)
            statement.setString(2, id)
            if (segment == null) statement.setNull(3, Types.VARCHAR) else statement.setString(3, segment)
            statement.executeUpdate()
        }
    }
}

private data class Event(val id: Long, val tenant: String, val at: String, val payload: String?)

private fun seedEvents(connection: Connection) {
    val events = listOf(
        Event(1, "acme", "2026-01-01T00:00:00Z", """{"customer_id":" C-001 ","kind":" ORDER ","amount":" 100.00 ","channel":" Web "}"""),
        Event(2, "acme", "2026-01-01T10:00:00Z", """{"customer_id":"c-001","kind":"order","amount":25.50,"channel":"web"}"""),
        Event(3, "acme", "2026-01-01T11:00:00Z", """{"customer_id":"C-002","kind":"Order","amount":"40.00","channel":" Mobile "}"""),
        Event(4, "acme", "2026-01-01T12:00:00Z", """{"customer_id":"C-999","kind":"order","amount":"12.00","channel":""}"""),
        Event(5, "acme", "2026-01-01T13:00:00Z", """{"customer_id":"c-001","kind":"refund","amount":"10.00","channel":"web"}"""),
        Event(6, "acme", "2026-01-02T09:00:00Z", """{"customer_id":"c-001","kind":"ORDER","amount":"80.00","channel":"WEB"}"""),
        Event(7, "acme", "2026-01-02T10:00:00Z", """{"customer_id":"c-002","kind":"refund","amount":"5.00","channel":"mobile"}"""),
        Event(8, "acme", "2026-01-02T11:00:00Z", """{"customer_id":"c-002","kind":" order ","amount":"not-money","channel":"Mobile"}"""),
        Event(9, "acme", "2026-01-02T12:00:00Z", """{}"""),
        Event(10, "acme", "2026-01-02T13:00:00Z", null),
        Event(11, "acme", "2026-01-02T14:00:00Z", """{"kind":"order","amount":"7.00"}"""),
        Event(12, "acme", "2026-01-02T15:00:00Z", """{"customer_id":" C-002 ","kind":"refund"}"""),
        Event(13, "acme", "2026-01-03T00:00:00Z", """{"customer_id":"c-001","kind":"order","amount":"999.00"}"""),
        Event(14, "acme", "2025-12-31T23:59:59.999Z", """{"customer_id":"c-001","kind":"order","amount":"333.00"}"""),
        Event(15, "beta", "2026-01-01T10:00:00Z", """{"customer_id":"c-001","kind":"order","amount":"500.00"}"""),
        Event(16, "acme", "2026-01-02T16:00:00Z", """{"customer_id":"c-001","kind":"ping","channel":"web"}"""),
        Event(17, "acme", "2026-01-02T17:00:00Z", """{"customer_id":null,"kind":"refund","amount":"2.50","channel":null}"""),
        Event(18, "acme", "2026-01-02T18:00:00Z", """{"customer_id":"   ","kind":"   ","amount":"","channel":"   "}"""),
    )
    connection.prepareStatement("INSERT INTO sample.raw_events VALUES (?, ?, ?, CAST(? AS JSON))").use { statement ->
        for (event in events) {
            statement.setLong(1, event.id)
            statement.setString(2, event.tenant)
            statement.setObject(3, Instant.parse(event.at).atOffset(ZoneOffset.UTC))
            if (event.payload == null) statement.setNull(4, Types.VARCHAR) else statement.setString(4, event.payload)
            statement.executeUpdate()
        }
    }
}
