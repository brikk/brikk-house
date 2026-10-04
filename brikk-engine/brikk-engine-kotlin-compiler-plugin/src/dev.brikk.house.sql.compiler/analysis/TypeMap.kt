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
 * (what `ColumnShape.type` holds); Kotlin-side analysis uses canonical ClassIds.
 * The short-name helpers below are explicit table utilities, not type resolution.
 */
object TypeMap {
    private val JAVA_TIME = FqName("java.time")
    private val JAVA_MATH = FqName("java.math")

    val INSTANT = ClassId(JAVA_TIME, Name.identifier("Instant"))
    val LOCAL_DATE = ClassId(JAVA_TIME, Name.identifier("LocalDate"))
    val BIG_DECIMAL = ClassId(JAVA_MATH, Name.identifier("BigDecimal"))
    val BIG_INTEGER = ClassId(JAVA_MATH, Name.identifier("BigInteger"))

    /** Base-dialect SQL type string (possibly with parameters) -> Kotlin type. */
    fun sqlToKotlin(sqlType: String, nullable: Boolean?): KType {
        val head = sqlType.substringBefore('(').trim().uppercase()
        val classId = when (head) {
            "INT128", "UBIGINT" -> BIG_INTEGER
            "BIGINT", "INT64", "UINT" -> StandardClassIds.Long
            "INT", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT", "INT32", "USMALLINT", "UTINYINT" -> StandardClassIds.Int
            "BOOLEAN", "BOOL", "BIT" -> StandardClassIds.Boolean
            "DOUBLE", "FLOAT", "REAL", "FLOAT64" -> StandardClassIds.Double
            "DECIMAL", "NUMERIC", "BIGDECIMAL", "MONEY", "SMALLMONEY" -> BIG_DECIMAL
            "TEXT", "VARCHAR", "CHAR", "NCHAR", "NVARCHAR", "STRING", "JSON", "JSONB", "UUID", "VARIANT" -> StandardClassIds.String
            "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMPLTZ", "TIMESTAMPNTZ", "DATETIME", "DATETIME64", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS" -> INSTANT
            "DATE" -> LOCAL_DATE
            "UNKNOWN", "NULL" -> return KType(StandardClassIds.Any, nullable = true)
            else -> return KType(StandardClassIds.Any, nullable = true)
        }
        // Unknown SQL nullability is not evidence of a non-null Kotlin getter.
        return KType(classId, nullable = nullable != false)
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
        "BigInteger" -> "INT128"
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
        "BigInteger" -> BIG_INTEGER
        "Instant" -> INSTANT
        "LocalDate" -> LOCAL_DATE
        "Any" -> StandardClassIds.Any
        else -> null
    }

    /** Resolved Kotlin ClassId -> base-dialect SQL type (for reading resolved user interfaces). */
    fun kotlinClassIdToSql(classId: ClassId): String? = when (classId) {
        StandardClassIds.String -> "TEXT"
        StandardClassIds.Long -> "BIGINT"
        StandardClassIds.Int -> "INT"
        StandardClassIds.Short -> "SMALLINT"
        StandardClassIds.Boolean -> "BOOLEAN"
        StandardClassIds.Double -> "DOUBLE"
        StandardClassIds.Float -> "FLOAT"
        BIG_DECIMAL -> "DECIMAL"
        BIG_INTEGER -> "INT128"
        INSTANT -> "TIMESTAMPTZ"
        LOCAL_DATE -> "DATE"
        StandardClassIds.Any -> "UNKNOWN"
        else -> null
    }

    /**
     * Whether a column of [actual] Kotlin type can satisfy a trait property of [required]
     * type. A nullable output cannot implement a non-null property. `Any` on the required
     * side accepts compatible nullability; `Any` on the actual
     * side (UNKNOWN SQL type) satisfies only `Any`.
     */
    fun satisfies(actual: KType, required: KType): Boolean =
        (!actual.nullable || required.nullable) &&
            (required.classId == StandardClassIds.Any || actual.classId == required.classId)
}
