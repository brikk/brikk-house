package dev.brikk.house.sql.smoke

import dev.brikk.house.sql.runtime.BrikkSql
import dev.brikk.house.sql.runtime.BrikkTrait
import dev.brikk.house.sql.runtime.Partial
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.runtime.Sql
import java.time.Instant

/*
 * The three-step demo pipeline, compiled by the real toolchain with the plugin attached:
 *
 *   1. a catalog-bound source with a date-range parameter,
 *   2. a reusable generic trait pipe (anything with a `payload` gets JSON fields extracted),
 *   3. a terminating pipe over a Partial input that closes the shape.
 *
 * `Rel<T>` parameters are table inputs. The SQL refers to each one as a table-valued call
 * named after the parameter (`FROM src() |> ...`, `JOIN other() ON ...`); the plugin checks
 * both directions (every Rel parameter is used as a source, every such call names a Rel
 * parameter) and the runtime binds the calls to CTEs when rendering.
 *
 * No return types are written (option C); the plugin infers `Rel<EventsInRangeOut>`,
 * `Rel<ExtractEventOut>` (a Partial), `Rel<LoginDailyOut>`, and at the call site
 * `extractEvent(eventsInRange(..))` a local full Shape that satisfies LoginInput.
 */

@BrikkTrait
interface HasPayload : Partial { val payload: String }

@BrikkTrait
interface LoginInput : Partial {
    val user_id: String
    val action: String
    val event_at: Instant
}

@BrikkSql
fun eventsInRange(start: Instant, end: Instant) = Sql.doris("""
    FROM public.events
    |> WHERE event_at >= :start AND event_at < :end
""")

@BrikkSql
fun <T : HasPayload> extractEvent(src: Rel<T>) = Sql.doris("""
    FROM src()
    |> EXTEND payload->>'user_id' AS user_id,
              payload->>'action' AS action,
              (payload->>'duration_ms')::BIGINT AS duration_ms
""")

@BrikkSql
fun loginDaily(logins: Rel<LoginInput>) = Sql.doris("""
    FROM logins()
    |> WHERE action = 'login'
    |> AGGREGATE count(*) AS logins, max(event_at) AS last_login
       GROUP BY user_id, CAST(event_at AS DATE) AS day
""")

fun report(start: Instant, end: Instant) = loginDaily(extractEvent(eventsInRange(start, end)))

/** Column access through generated shapes type-checks. */
fun describe(row: LoginDailyOut, src: EventsInRangeOut): String =
    "${row.user_id} ${row.day} ${row.logins} ${src.tenant}"
