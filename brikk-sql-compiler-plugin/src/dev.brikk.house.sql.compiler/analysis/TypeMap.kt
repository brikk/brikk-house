package dev.brikk.house.sql.compiler.analysis

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/** A Kotlin type as the plugin sees it: class + nullability. Generics are not modelled. */
data class KType(val classId: ClassId, val nullable: Boolean) {
    val shortName: String get() = classId.shortClassName.asString()
    override fun toString(): String = classId.asFqNameString() + (if (nullable) "?" else "")
}

/**
 * Fixed SQL <-> Kotlin type table (JVM). SQL side uses brikk-sql base-dialect renderings
 * (what `ColumnShape.type` holds); Kotlin side is by ClassId, and — for reading raw FIR
 * `FirUserTypeRef`s in trait declarations — by short name.
 */
object TypeMap {
    private val JAVA_TIME = FqName("java.time")
    private val JAVA_MATH = FqName("java.math")

    val INSTANT = ClassId(JAVA_TIME, Name.identifier("Instant"))
    val LOCAL_DATE = ClassId(JAVA_TIME, Name.identifier("LocalDate"))
    val BIG_DECIMAL = ClassId(JAVA_MATH, Name.identifier("BigDecimal"))

    /** Base-dialect SQL type string (possibly with parameters) -> Kotlin type. */
    fun sqlToKotlin(sqlType: String, nullable: Boolean?): KType {
        val head = sqlType.substringBefore('(').trim().uppercase()
        val classId = when (head) {
            "BIGINT", "INT64", "INT128", "UBIGINT" -> StandardClassIds.Long
            "INT", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT", "INT32", "UINT", "USMALLINT", "UTINYINT" -> StandardClassIds.Int
            "BOOLEAN", "BOOL", "BIT" -> StandardClassIds.Boolean
            "DOUBLE", "FLOAT", "REAL", "FLOAT64" -> StandardClassIds.Double
            "DECIMAL", "NUMERIC", "BIGDECIMAL", "MONEY", "SMALLMONEY" -> BIG_DECIMAL
            "TEXT", "VARCHAR", "CHAR", "NCHAR", "NVARCHAR", "STRING", "JSON", "JSONB", "UUID", "VARIANT" -> StandardClassIds.String
            "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMPLTZ", "TIMESTAMPNTZ", "DATETIME", "DATETIME64", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> INSTANT
            "DATE" -> LOCAL_DATE
            "UNKNOWN", "NULL" -> return KType(StandardClassIds.Any, nullable = true)
            else -> return KType(StandardClassIds.Any, nullable = true)
        }
        return KType(classId, nullable = nullable == true)
    }

    /**
     * Kotlin short type name as written in a raw `FirUserTypeRef` (`String`, `Long`,
     * `Instant`, ...) -> base-dialect SQL type. Null if unknown.
     */
    fun kotlinShortNameToSql(shortName: String): String? = when (shortName) {
        "String" -> "TEXT"
        "Long" -> "BIGINT"
        "Int" -> "INT"
        "Short" -> "SMALLINT"
        "Boolean" -> "BOOLEAN"
        "Double" -> "DOUBLE"
        "Float" -> "FLOAT"
        "BigDecimal" -> "DECIMAL"
        "Instant" -> "TIMESTAMPTZ"
        "LocalDate" -> "DATE"
        "Any" -> "UNKNOWN"
        else -> null
    }

    fun kotlinShortNameToClassId(shortName: String): ClassId? = when (shortName) {
        "String" -> StandardClassIds.String
        "Long" -> StandardClassIds.Long
        "Int" -> StandardClassIds.Int
        "Short" -> StandardClassIds.Short
        "Boolean" -> StandardClassIds.Boolean
        "Double" -> StandardClassIds.Double
        "Float" -> StandardClassIds.Float
        "BigDecimal" -> BIG_DECIMAL
        "Instant" -> INSTANT
        "LocalDate" -> LOCAL_DATE
        "Any" -> StandardClassIds.Any
        else -> null
    }

    /** Resolved Kotlin ClassId -> base-dialect SQL type (for reading resolved user interfaces). */
    fun kotlinClassIdToSql(classId: ClassId): String? = kotlinShortNameToSql(classId.shortClassName.asString())

    /**
     * Whether a column of [actual] Kotlin type can satisfy a trait property of [required]
     * type. Nullability is ignored for now (see RESEARCH doc: output shapes do not surface
     * nullability yet). `Any` on the required side accepts anything; `Any` on the actual
     * side (UNKNOWN SQL type) satisfies only `Any`.
     */
    fun satisfies(actual: KType, required: KType): Boolean =
        required.classId == StandardClassIds.Any || actual.classId == required.classId
}
