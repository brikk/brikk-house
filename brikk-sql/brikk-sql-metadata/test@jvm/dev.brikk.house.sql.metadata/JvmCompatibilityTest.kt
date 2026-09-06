package dev.brikk.house.sql.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmCompatibilityTest {
    @Test
    fun metadataTargetsJava21() {
        val stream = assertNotNull(FunctionDef::class.java.getResourceAsStream("/dev/brikk/house/sql/metadata/FunctionDef.class"))
        val header = stream.use { it.readNBytes(8) }
        val major = (header[6].toInt() and 0xff) shl 8 or (header[7].toInt() and 0xff)

        assertEquals(65, major)
    }
}
