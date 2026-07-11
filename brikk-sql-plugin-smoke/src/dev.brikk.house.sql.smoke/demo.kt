package dev.brikk.house.sql.smoke

import dev.brikk.house.sql.BrikkSql

/**
 * Placeholder runtime surface (same contract the plugin tests use); will move to a real
 * brikk-sql runtime module once the surface syntax is designed.
 */
object Sql {
    @BrikkSql("doris")
    fun doris(sql: String): String = error("brikk-sql compiler plugin was not applied")
}

fun events(): String =
    Sql.doris(
        """
        FROM rumble_import.events
        |> WHERE event_at >= :start
        """.trimIndent()
    )
