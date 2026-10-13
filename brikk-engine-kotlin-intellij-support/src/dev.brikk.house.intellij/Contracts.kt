package dev.brikk.house.intellij

/** Immutable editing facts. No Kotlin analysis session, symbol, runtime value or SQL AST escapes here. */
/**
 * A Brikk entry point's dialect, identified by its runtime name (`Sql.<sqlName>`). [entries] are
 * the dialects with a known plugin language; any other dialect the runtime declares with
 * `@BrikkSqlDialect` is still Brikk, with the language its SQL parameter declares. Not a closed
 * set: compare with `==`, and give `when` an `else` branch.
 */
public class BrikkDialect private constructor(public val sqlName: String, public val languageId: String) {
    /** Constant-style name, `DUCKDB` for `duckdb`. */
    public val name: String get() = sqlName.uppercase(java.util.Locale.ROOT)

    /** Whether a plugin language is known for this dialect, not just the runtime's declaration. */
    public val registered: Boolean get() = entries.any { it.sqlName == sqlName }

    override fun equals(other: Any?): Boolean = other is BrikkDialect && other.sqlName == sqlName
    override fun hashCode(): Int = sqlName.hashCode()
    override fun toString(): String = name

    public companion object {
        /** The runtime's language for Brikk SQL that no dialect plugin owns. */
        public const val GENERIC_LANGUAGE_ID: String = "BrikkSQL"

        @JvmField public val DUCKDB: BrikkDialect = BrikkDialect("duckdb", "DuckDBSQL")
        @JvmField public val DORIS: BrikkDialect = BrikkDialect("doris", "DorisSQL")
        @JvmField public val TRINO: BrikkDialect = BrikkDialect("trino", "TrinoSQL")
        @JvmField public val CLICKHOUSE: BrikkDialect = BrikkDialect("clickhouse", "ClickHouseSQL")

        /** The registered dialects. */
        @JvmStatic public val entries: List<BrikkDialect> = listOf(DUCKDB, DORIS, TRINO, CLICKHOUSE)

        /** The registered dialect named [sqlName]; otherwise one in [languageId], the runtime's declaration, or [GENERIC_LANGUAGE_ID]. */
        @JvmStatic public fun of(sqlName: String, languageId: String? = null): BrikkDialect =
            entries.firstOrNull { it.sqlName == sqlName } ?: BrikkDialect(sqlName, languageId ?: GENERIC_LANGUAGE_ID)
    }
}

/**
 * Why Kotlin facts are not available yet, for tooltips without parsing text. Only
 * [RUNTIME_ENTRY_MISSING] and [COMPILER_PLUGIN_MISSING] need the user (see `BrikkHosts.actionNeeded`);
 * the others resolve by themselves.
 */
public enum class BrikkPendingCause {
    /** Kotlin indexing is in progress. */
    INDEXING,
    /** Kotlin analysis is not allowed here (a write action, such as a rename): retried after it. */
    ANALYSIS_PROHIBITED,
    /** A Kotlin symbol or type does not resolve (yet). */
    UNRESOLVED,
    /** The call's receiver is the runtime's `Sql`, which has no such entry point: the runtime is older than the code. */
    RUNTIME_ENTRY_MISSING,
    /** A generated row type exists but its members are not generated yet. */
    GENERATED_SHAPES,
    /** A row type does not resolve and the Brikk compiler plugin, which generates row types, is not active for the module. */
    COMPILER_PLUGIN_MISSING,
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
) {
    /**
     * Whether [replacement] is a fabricated, non-executable placeholder (pending or unsupported
     * Kotlin): pass it as support's `HostSqlEntry` placeholder, for its marker and error filtering.
     */
    public val placeholder: Boolean get() = role == EntryRole.PENDING || role == EntryRole.UNSUPPORTED
}

/**
 * One stdlib trim applied to the host, in call order. [marginPrefix] is set for `trimMargin`,
 * `|` unless the call passes a literal prefix, and null for `trimIndent`; map it to support's
 * `HostSqlTrim.Margin(prefix)`.
 */
public data class BrikkTrim(public val function: String, public val marginPrefix: String?) {
    internal companion object {
        fun of(function: String, prefix: String? = null): BrikkTrim =
            BrikkTrim(function, if (function == "trimMargin") prefix ?: "|" else null)
    }
}

public data class BrikkContext(
    public val dialect: BrikkDialect,
    public val trims: List<String>,
    public val entries: List<TemplateEntry>,
    public val relations: Map<String, ShapeResult>,
    /** [trims] with their margin prefixes. */
    public val trimSteps: List<BrikkTrim> = trims.map { BrikkTrim.of(it) },
) {
    public val pipeEditingEnabled: Boolean get() = true
    public val executable: Boolean get() = false
    public val uncertain: Boolean get() = entries.any { it.problem != null || it.role == EntryRole.PENDING || it.role == EntryRole.UNSUPPORTED }
    public val binds: List<String> get() = entries.filter { it.role == EntryRole.BIND }.mapNotNull { it.name }.distinct()
    public val slots: List<String> get() = entries.filter { it.role == EntryRole.RELATION_SLOT }.mapNotNull { it.name }.distinct()
}

public sealed interface ContextResult {
    public data class Available(public val context: BrikkContext) : ContextResult
    public data class Unavailable(public val reason: String, public val cause: BrikkPendingCause = BrikkPendingCause.UNRESOLVED) : ContextResult
    public data object NotBrikk : ContextResult
}

public sealed interface ShapeResult {
    public data class Available(public val shape: RelationShape) : ShapeResult
    public data class Unavailable(public val reason: String, public val cause: BrikkPendingCause = BrikkPendingCause.UNRESOLVED) : ShapeResult
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

    /** The Kotlin type's simple name for display (`EventKind`), without nullability; null when it is no class. */
    public val kotlinTypeName: String? get() = kotlinClassId?.substringAfterLast('.')

    /** Three-state nullability: true, false, or null when unknown. */
    public fun nullableOrNull(): Boolean? = when (nullability) {
        ColumnNullability.NON_NULL -> false
        ColumnNullability.NULLABLE -> true
        ColumnNullability.UNKNOWN -> null
    }

    /** `relation contract` when only the relation is navigable (a generated column), otherwise `brikk column`. */
    public fun originLabel(): String = if (relationLevel) RELATION_CONTRACT_LABEL else COLUMN_LABEL

    /** Known only through its relation (a generated column): the one rule for labels and fact origins. */
    internal val relationLevel: Boolean get() = navigation?.kind == NavigationKind.RELATION_FALLBACK

    public companion object {
        public const val COLUMN_LABEL: String = "brikk column"
        public const val RELATION_CONTRACT_LABEL: String = "relation contract"
    }
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
