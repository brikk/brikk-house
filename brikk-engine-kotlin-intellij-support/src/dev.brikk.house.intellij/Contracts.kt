package dev.brikk.house.intellij

/** Immutable editing facts. No Kotlin analysis session, symbol, runtime value or SQL AST escapes here. */
public enum class BrikkDialect(public val sqlName: String, public val languageId: String) {
    DUCKDB("duckdb", "DuckDBSQL"), DORIS("doris", "DorisSQL"),
}

public enum class EntryRole { BIND, RELATION_SLOT, SQL_CONSTANT, LITERAL, PENDING, UNSUPPORTED }
public enum class ShapeKnowledge { FULL, PARTIAL }
public enum class ColumnNullability { NON_NULL, NULLABLE, UNKNOWN }
public enum class SqlFamily { INTEGER, WIDE_INTEGER, LARGE_INTEGER, FLOATING, DECIMAL, BOOLEAN, TEXT, TIMESTAMP, DATE, UNKNOWN }
public enum class ShapeOrigin { DECLARED, GENERATED, TYPE_BOUND, UNKNOWN }
public enum class NavigationKind { PROPERTY, RELATION_FALLBACK }

/** UTF-16 offsets relative to the authored host string, not the injected SQL document. */
public data class HostRange(public val start: Int, public val end: Int) {
    init { require(start >= 0 && end >= start) }
}

public data class TemplateEntry(
    public val range: HostRange,
    public val role: EntryRole,
    public val name: String? = null,
    /** Replacement text is for inspections only. A null replacement retains the literal host range. */
    public val replacement: String? = null,
    public val problem: String? = null,
)

public data class BrikkContext(
    public val dialect: BrikkDialect,
    public val trims: List<String>,
    public val entries: List<TemplateEntry>,
    public val relations: Map<String, ShapeResult>,
) {
    public val pipeEditingEnabled: Boolean get() = true
    public val executable: Boolean get() = false
    public val uncertain: Boolean get() = entries.any { it.problem != null || it.role == EntryRole.PENDING || it.role == EntryRole.UNSUPPORTED }
    public val binds: List<String> get() = entries.filter { it.role == EntryRole.BIND }.mapNotNull { it.name }.distinct()
    public val slots: List<String> get() = entries.filter { it.role == EntryRole.RELATION_SLOT }.mapNotNull { it.name }.distinct()
}

public sealed interface ContextResult {
    public data class Available(public val context: BrikkContext) : ContextResult
    public data class Unavailable(public val reason: String) : ContextResult
    public data object NotBrikk : ContextResult
}

public sealed interface ShapeResult {
    public data class Available(public val shape: RelationShape) : ShapeResult
    public data class Unavailable(public val reason: String) : ShapeResult
    public data class Unsupported(public val reason: String) : ShapeResult
    public data object NotRelation : ShapeResult
}

/** Implementations hold supported smart pointers, never a KaSymbol or KaType. */
public interface NavigationTarget {
    public val kind: NavigationKind
    /** Current Kotlin source location when one exists. Never an invented upstream SQL origin. */
    public val location: SourceLocation? get() = null
    public fun navigate(requestFocus: Boolean = true)
}

public data class SourceLocation(public val fileUrl: String, public val start: Int, public val end: Int)

public data class ColumnContract(
    public val name: String,
    public val kotlinClassId: String?,
    public val nullability: ColumnNullability,
    public val sqlFamily: SqlFamily,
    public val navigation: NavigationTarget? = null,
) {
    /** Kotlin types cannot recover decimal parameters, native SQL variants or SQL quote identity. */
    public val exactSqlType: String? get() = null
    public val quotedSqlIdentity: Boolean? get() = null
}

public data class RelationShape(
    public val knowledge: ShapeKnowledge,
    /** Enumeration order is not a SQL ordinal guarantee. */
    public val columns: List<ColumnContract>,
    public val origin: ShapeOrigin,
    public val navigation: NavigationTarget? = null,
)

public object BrikkTypeFamilies {
    public fun forClassId(classId: String?): SqlFamily = when (classId) {
        "kotlin.Int", "kotlin.Short" -> SqlFamily.INTEGER
        "kotlin.Long" -> SqlFamily.WIDE_INTEGER
        "java.math.BigInteger" -> SqlFamily.LARGE_INTEGER
        "kotlin.Float", "kotlin.Double" -> SqlFamily.FLOATING
        "java.math.BigDecimal" -> SqlFamily.DECIMAL
        "kotlin.Boolean" -> SqlFamily.BOOLEAN
        "kotlin.String" -> SqlFamily.TEXT
        "java.time.Instant" -> SqlFamily.TIMESTAMP
        "java.time.LocalDate" -> SqlFamily.DATE
        else -> SqlFamily.UNKNOWN
    }
}
