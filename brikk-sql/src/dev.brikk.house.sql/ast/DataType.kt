package dev.brikk.house.sql.ast

/**
 * sqlglot: datatypes.DType (aliased as DataType.Type in Python).
 *
 * NOTE: only the members needed for common casts are hand-ported here; the FULL enum
 * (~150 members, including value quirks like USERDEFINED = "USER-DEFINED") gets
 * generated later together with the node catalog.
 */
enum class DType {
    INT,
    BIGINT,
    SMALLINT,
    TINYINT,
    DECIMAL,
    FLOAT,
    DOUBLE,
    VARCHAR,
    CHAR,
    TEXT,
    BOOLEAN,
    DATE,
    DATETIME,
    TIMESTAMP,
    TIME,
    JSON,
    UUID,
    ARRAY,
    MAP,
    STRUCT,
    UNKNOWN, // Sentinel value, useful for type annotation
    ;

    /** sqlglot: AutoName — the enum's serialized value (equals the name for this subset). */
    val value: String get() = name

    companion object {
        fun fromValue(value: String): DType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown DataType.Type value: $value (full enum is generated later)")
    }
}

// sqlglot: datatypes.DataType(Expression) — is_data_type. The "this" arg holds a DType.
class DataType(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES
    override val isDataType get() = true

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expressions" to false, "nested" to false, "values" to false,
            "kind" to false, "nullable" to false, "collate" to false,
        )
    }
}

// sqlglot: datatypes.DataTypeParam(Expression) — precision/scale/size args of a DataType
class DataTypeParam(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to false)
    }
}
