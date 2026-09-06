package dev.brikk.house.sql

import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmCompatibilityTest {
    @Test
    fun coreTargetsJava21() {
        val stream = assertNotNull(SqlFragment::class.java.getResourceAsStream("/dev/brikk/house/sql/shape/SqlFragment.class"))
        val header = stream.use { it.readNBytes(8) }
        val major = (header[6].toInt() and 0xff) shl 8 or (header[7].toInt() and 0xff)

        assertEquals(65, major)
    }

    @Test
    fun existingPipeDesugarBinaryEntryPointRemainsAvailable() {
        val method = Class.forName("dev.brikk.house.sql.ast.PipeDesugarKt")
            .getMethod("desugarPipes", Expression::class.java, Boolean::class.javaPrimitiveType)
        assertNotNull(method.invoke(null, parseOne("FROM t |> SELECT id"), true))
    }
}
