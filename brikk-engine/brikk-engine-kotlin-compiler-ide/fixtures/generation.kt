// RUN: acceptance.GenerationKt
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkTrait
interface HasId : Partial { val id: Int }

@BrikkTrait
interface HasCode : Partial { val code: String }

@BrikkSql
fun source() = Sql.postgres("SELECT 1 AS id, 'x' AS code")

@BrikkSql
fun <T : HasId> stamp(n: Int = 3, src: Rel<T>) = Sql.postgres("FROM src() |> EXTEND $n AS n")

fun acceptCode(src: Rel<HasCode>) = src
val named = acceptCode(stamp(src = source(), n = 1))
val defaulted = acceptCode(stamp(src = source()))

// The public generated name must be available from the relocated artifact.
fun publicShape(value: SourceOut): Int = value.id

fun main() {
    val rel = stamp(src = source(), n = 9)
    check(rel.bindings().values.single() == 9)
    check(rel.render().contains(":n"))
    check(SourceOut::class.java.getMethod("getId").returnType == Int::class.javaPrimitiveType)
    check(HasCode::class.java.isAssignableFrom(SourceOut::class.java))
    println("generation, generic refinement, IR, bindings and execution OK")
}
