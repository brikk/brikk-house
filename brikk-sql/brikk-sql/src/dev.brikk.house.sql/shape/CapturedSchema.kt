package dev.brikk.house.sql.shape

import kotlinx.serialization.Serializable

/** Direct column metadata. [type] is the exact native SQL type, not a JDBC type name. */
@Serializable
data class CapturedColumn(
    val name: String,
    val type: String,
    val nullable: Boolean? = null,
)

/** A table or view captured without SQL text, defaults, connection URLs, or credentials. */
@Serializable
data class CapturedObject(
    val catalog: String,
    val schema: String,
    val name: String,
    val kind: String,
    val columns: List<CapturedColumn>,
    val dialect: String = "doris",
    val formatVersion: Int = 1,
)
