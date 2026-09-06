package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.analysis.TypeMap
import org.jetbrains.kotlin.name.StandardClassIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeMapTest {
    @Test
    fun integerDomainsAreNotNarrowedToJvmPrimitivesThatCannotHoldThem() {
        for (sql in listOf("INT128", "UBIGINT")) {
            val type = TypeMap.sqlToKotlin(sql, nullable = false)
            assertEquals(TypeMap.BIG_INTEGER, type.classId, sql)
            assertFalse(type.nullable)
            assertTrue(TypeMap.sqlToKotlin(sql, nullable = true).nullable)
        }
        assertEquals(StandardClassIds.Long, TypeMap.sqlToKotlin("UINT", false).classId)
        assertEquals(StandardClassIds.Long, TypeMap.sqlToKotlin("BIGINT", false).classId)
        assertEquals(StandardClassIds.Int, TypeMap.sqlToKotlin("INT", false).classId)
        assertEquals(StandardClassIds.Int, TypeMap.sqlToKotlin("USMALLINT", false).classId)
    }

    @Test
    fun bigIntegerTraitsRetainAWideSqlType() {
        assertEquals("INT128", TypeMap.kotlinShortNameToSql("BigInteger"))
        assertEquals(TypeMap.BIG_INTEGER, TypeMap.kotlinShortNameToClassId("BigInteger"))
        assertEquals("INT128", TypeMap.kotlinClassIdToSql(TypeMap.BIG_INTEGER))
        assertFalse(TypeMap.satisfies(TypeMap.sqlToKotlin("INT128", false), TypeMap.sqlToKotlin("BIGINT", false)))
    }
}
