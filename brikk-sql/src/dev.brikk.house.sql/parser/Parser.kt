package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.AddConstraint
import dev.brikk.house.sql.ast.AddPartition
import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Aliases
import dev.brikk.house.sql.ast.All
import dev.brikk.house.sql.ast.Alter
import dev.brikk.house.sql.ast.AlterColumn
import dev.brikk.house.sql.ast.AlterDistStyle
import dev.brikk.house.sql.ast.AlterRename
import dev.brikk.house.sql.ast.AlterSet
import dev.brikk.house.sql.ast.AlterSortKey
import dev.brikk.house.sql.ast.Analyze
import dev.brikk.house.sql.ast.AnalyzeColumns
import dev.brikk.house.sql.ast.AnalyzeDelete
import dev.brikk.house.sql.ast.AnalyzeHistogram
import dev.brikk.house.sql.ast.AnalyzeListChainedRows
import dev.brikk.house.sql.ast.AnalyzeSample
import dev.brikk.house.sql.ast.AnalyzeStatistics
import dev.brikk.house.sql.ast.AnalyzeValidate
import dev.brikk.house.sql.ast.AnalyzeWith
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Any
import dev.brikk.house.sql.ast.Args
import dev.brikk.house.sql.ast.Array
import dev.brikk.house.sql.ast.AtTimeZone
import dev.brikk.house.sql.ast.AutoIncrementColumnConstraint
import dev.brikk.house.sql.ast.AutoRefreshProperty
import dev.brikk.house.sql.ast.Between
import dev.brikk.house.sql.ast.BitwiseLeftShift
import dev.brikk.house.sql.ast.BitwiseRightShift
import dev.brikk.house.sql.ast.BlockCompressionProperty
import dev.brikk.house.sql.ast.Boolean
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Cache
import dev.brikk.house.sql.ast.CalledOnNullInputProperty
import dev.brikk.house.sql.ast.Case
import dev.brikk.house.sql.ast.CaseSpecificColumnConstraint
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.CharacterSetProperty
import dev.brikk.house.sql.ast.CheckColumnConstraint
import dev.brikk.house.sql.ast.ChecksumProperty
import dev.brikk.house.sql.ast.Chr
import dev.brikk.house.sql.ast.Clone
import dev.brikk.house.sql.ast.Cluster
import dev.brikk.house.sql.ast.ClusterProperty
import dev.brikk.house.sql.ast.ClusteredByProperty
import dev.brikk.house.sql.ast.Coalesce
import dev.brikk.house.sql.ast.Collate
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnConstraint
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Copy
import dev.brikk.house.sql.ast.CopyParameter
import dev.brikk.house.sql.ast.Credentials
import dev.brikk.house.sql.ast.ColumnPosition
import dev.brikk.house.sql.ast.Comprehension
import dev.brikk.house.sql.ast.Columns
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.Comment
import dev.brikk.house.sql.ast.Commit
import dev.brikk.house.sql.ast.CompressColumnConstraint
import dev.brikk.house.sql.ast.ComputedColumnConstraint
import dev.brikk.house.sql.ast.Connect
import dev.brikk.house.sql.ast.Constraint
import dev.brikk.house.sql.ast.CopyGrantsProperty
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Cube
import dev.brikk.house.sql.ast.DPipe
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataBlocksizeProperty
import dev.brikk.house.sql.ast.DataDeletionProperty
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DataTypeParam
import dev.brikk.house.sql.ast.Decode
import dev.brikk.house.sql.ast.DecodeCase
import dev.brikk.house.sql.ast.DefinerProperty
import dev.brikk.house.sql.ast.Delete
import dev.brikk.house.sql.ast.Describe
import dev.brikk.house.sql.ast.DictProperty
import dev.brikk.house.sql.ast.DictRange
import dev.brikk.house.sql.ast.DictSubProperty
import dev.brikk.house.sql.ast.Directory
import dev.brikk.house.sql.ast.DistKeyProperty
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.DistributedByProperty
import dev.brikk.house.sql.ast.Div
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Drop
import dev.brikk.house.sql.ast.DropPartition
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Escape
import dev.brikk.house.sql.ast.Except
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Extract
import dev.brikk.house.sql.ast.FallbackProperty
import dev.brikk.house.sql.ast.FileFormatProperty
import dev.brikk.house.sql.ast.Fetch
import dev.brikk.house.sql.ast.Filter
import dev.brikk.house.sql.ast.LimitOptions
import dev.brikk.house.sql.ast.ForeignKey
import dev.brikk.house.sql.ast.FormatJson
import dev.brikk.house.sql.ast.FreespaceProperty
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.GeneratedAsIdentityColumnConstraint
import dev.brikk.house.sql.ast.GeneratedAsRowColumnConstraint
import dev.brikk.house.sql.ast.Grant
import dev.brikk.house.sql.ast.GrantPrincipal
import dev.brikk.house.sql.ast.GrantPrivilege
import dev.brikk.house.sql.ast.Group
import dev.brikk.house.sql.ast.GroupConcat
import dev.brikk.house.sql.ast.GroupingSets
import dev.brikk.house.sql.ast.Having
import dev.brikk.house.sql.ast.HavingMax
import dev.brikk.house.sql.ast.Heredoc
import dev.brikk.house.sql.ast.Hint
import dev.brikk.house.sql.ast.HistoricalData
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.IgnoreNulls
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.InOutColumnConstraint
import dev.brikk.house.sql.ast.Index
import dev.brikk.house.sql.ast.IndexTableHint
import dev.brikk.house.sql.ast.IndexParameters
import dev.brikk.house.sql.ast.InlineLengthColumnConstraint
import dev.brikk.house.sql.ast.InputOutputFormat
import dev.brikk.house.sql.ast.Insert
import dev.brikk.house.sql.ast.Intersect
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.IntervalSpan
import dev.brikk.house.sql.ast.Is
import dev.brikk.house.sql.ast.IsolatedLoadingProperty
import dev.brikk.house.sql.ast.JSON
import dev.brikk.house.sql.ast.JSONColumnDef
import dev.brikk.house.sql.ast.JSONKeyValue
import dev.brikk.house.sql.ast.JSONObject
import dev.brikk.house.sql.ast.JSONObjectAgg
import dev.brikk.house.sql.ast.JSONSchema
import dev.brikk.house.sql.ast.JSONTable
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.JournalProperty
import dev.brikk.house.sql.ast.Kill
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.LikeProperty
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Introducer
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Lock
import dev.brikk.house.sql.ast.LoadData
import dev.brikk.house.sql.ast.LockingProperty
import dev.brikk.house.sql.ast.LogProperty
import dev.brikk.house.sql.ast.MacroOverload
import dev.brikk.house.sql.ast.MacroOverloads
import dev.brikk.house.sql.ast.MatchAgainst
import dev.brikk.house.sql.ast.MatchRecognize
import dev.brikk.house.sql.ast.MatchRecognizeMeasure
import dev.brikk.house.sql.ast.Merge
import dev.brikk.house.sql.ast.MergeBlockRatioProperty
import dev.brikk.house.sql.ast.MergeTreeTTL
import dev.brikk.house.sql.ast.MergeTreeTTLAction
import dev.brikk.house.sql.ast.Mod
import dev.brikk.house.sql.ast.Neg
import dev.brikk.house.sql.ast.NoPrimaryIndexProperty
import dev.brikk.house.sql.ast.Normalize
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.NotForReplicationColumnConstraint
import dev.brikk.house.sql.ast.NotNullColumnConstraint
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.NullSafeEQ
import dev.brikk.house.sql.ast.NullSafeNEQ
import dev.brikk.house.sql.ast.ObjectIdentifier
import dev.brikk.house.sql.ast.Overlay
import dev.brikk.house.sql.ast.XMLElement
import dev.brikk.house.sql.ast.XMLNamespace
import dev.brikk.house.sql.ast.XMLTable
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.OnCommitProperty
import dev.brikk.house.sql.ast.OnConflict
import dev.brikk.house.sql.ast.OnProperty
import dev.brikk.house.sql.ast.Opclass
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Ordered
import dev.brikk.house.sql.ast.OverflowTruncateBehavior
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.ParameterStyleProperty
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Partition
import dev.brikk.house.sql.ast.PartitionBoundSpec
import dev.brikk.house.sql.ast.PartitionByTruncate
import dev.brikk.house.sql.ast.PartitionedByBucket
import dev.brikk.house.sql.ast.PartitionedByProperty
import dev.brikk.house.sql.ast.PartitionedOfProperty
import dev.brikk.house.sql.ast.PeriodForSystemTimeConstraint
import dev.brikk.house.sql.ast.PipeAggregate
import dev.brikk.house.sql.ast.PipeAs
import dev.brikk.house.sql.ast.PipeDistinct
import dev.brikk.house.sql.ast.PipeExtend
import dev.brikk.house.sql.ast.PipeJoin
import dev.brikk.house.sql.ast.PipeLimit
import dev.brikk.house.sql.ast.PipeOrderBy
import dev.brikk.house.sql.ast.PipePivot
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeSelect
import dev.brikk.house.sql.ast.PipeSetOperation
import dev.brikk.house.sql.ast.PipeTableSample
import dev.brikk.house.sql.ast.PipeUnpivot
import dev.brikk.house.sql.ast.PipeWhere
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.PivotAlias
import dev.brikk.house.sql.ast.PivotAny
import dev.brikk.house.sql.ast.Pragma
import dev.brikk.house.sql.ast.PreWhere
import dev.brikk.house.sql.ast.Prior
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.PrimaryKey
import dev.brikk.house.sql.ast.PrimaryKeyColumnConstraint
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.Property
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.PseudoType
import dev.brikk.house.sql.ast.Reference
import dev.brikk.house.sql.ast.Refresh
import dev.brikk.house.sql.ast.RemoteWithConnectionModelProperty
import dev.brikk.house.sql.ast.RenameColumn
import dev.brikk.house.sql.ast.RespectNulls
import dev.brikk.house.sql.ast.Return
import dev.brikk.house.sql.ast.Returning
import dev.brikk.house.sql.ast.ReturnsProperty
import dev.brikk.house.sql.ast.Revoke
import dev.brikk.house.sql.ast.Rollback
import dev.brikk.house.sql.ast.Rollup
import dev.brikk.house.sql.ast.RowFormatDelimitedProperty
import dev.brikk.house.sql.ast.RowFormatSerdeProperty
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SequenceProperties
import dev.brikk.house.sql.ast.SerdeProperties
import dev.brikk.house.sql.ast.SessionParameter
import dev.brikk.house.sql.ast.SetItem
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.SettingsProperty
import dev.brikk.house.sql.ast.SkipJSONColumn
import dev.brikk.house.sql.ast.Slice
import dev.brikk.house.sql.ast.SortKeyProperty
import dev.brikk.house.sql.ast.SqlReadWriteProperty
import dev.brikk.house.sql.ast.SqlSecurityProperty
import dev.brikk.house.sql.ast.StabilityProperty
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.StorageHandlerProperty
import dev.brikk.house.sql.ast.StrPosition
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Summarize
import dev.brikk.house.sql.ast.Substring
import dev.brikk.house.sql.ast.SwapTable
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TableSample
import dev.brikk.house.sql.ast.ToTableProperty
import dev.brikk.house.sql.ast.Transaction
import dev.brikk.house.sql.ast.Trim
import dev.brikk.house.sql.ast.TriggerEvent
import dev.brikk.house.sql.ast.TriggerExecute
import dev.brikk.house.sql.ast.TriggerProperties
import dev.brikk.house.sql.ast.TriggerReferencing
import dev.brikk.house.sql.ast.TruncateTable
import dev.brikk.house.sql.ast.TryCast
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Uncache
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.UniqueColumnConstraint
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.UnpivotColumns
import dev.brikk.house.sql.ast.Update
import dev.brikk.house.sql.ast.Use
import dev.brikk.house.sql.ast.UserDefinedFunction
import dev.brikk.house.sql.ast.UsingData
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.Version
import dev.brikk.house.sql.ast.ViewAttributeProperty
import dev.brikk.house.sql.ast.VolatileProperty
import dev.brikk.house.sql.ast.When
import dev.brikk.house.sql.ast.Whens
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.WindowSpec
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.WithDataProperty
import dev.brikk.house.sql.ast.WithJournalTableProperty
import dev.brikk.house.sql.ast.WithOperator
import dev.brikk.house.sql.ast.WithSchemaBindingProperty
import dev.brikk.house.sql.ast.WithSystemVersioningProperty
import dev.brikk.house.sql.ast.WithinGroup
import dev.brikk.house.sql.ast.applyIndexOffset
import dev.brikk.house.sql.ast.args

/**
 * Faithful port of reference/sqlglot/sqlglot/parser.py `Parser`. Coverage now spans the
 * SELECT family (incl. pipe syntax), expression machinery (brackets/slices, intervals,
 * lambdas, windows incl. frame specs, values, lateral/unnest, grouping sets), DDL
 * (CREATE/DROP/ALTER with column defs, constraints and the property subsystem), DML
 * (INSERT/UPDATE/DELETE/MERGE) and auxiliary statements (USE/SET/transactions/CACHE/
 * DESCRIBE/COMMENT/GRANT/REVOKE/ANALYZE/KILL/LOAD/PRAGMA/TRUNCATE/REFRESH).
 * Remaining raise-gates: COPY, simplified PIVOT/UNPIVOT statements, triggers,
 * procedure blocks, macro overloads, locks and control-flow statements. The JSON path parser (sqlglot/jsonpath.py) is ported in JsonPath.kt.
 * Gate parity:
 * testResources/parser-corpus/known-failures.json (ParserIdentityCorpusTest).
 *
 * Conventions:
 *  - every ported method carries a `// sqlglot: Parser._parse_x` provenance comment;
 *  - navigation uses the SENTINEL token pattern (never nulls), like Python's
 *    SENTINEL_NONE (Token.__bool__ is False for SENTINEL);
 *  - class-level tables are exposed as open provider properties (defaults in
 *    [BaseParserTables]) so dialect subclasses can override them, mirroring Python's
 *    class-variable overrides;
 *  - node positions (Expression.update_positions -> meta) are NOT tracked yet: oracle
 *    comparisons strip meta on both sides. Comments ARE attached.
 *  - pipe syntax (PIPE_GT) is parsed into first-class PipeQuery/stage nodes instead of
 *    sqlglot's parse-time desugaring — see the "Pipe syntax" section and PipeDesugar.kt.
 */
open class Parser(
    errorLevel: ErrorLevel? = null,
    val errorMessageContext: Int = ERROR_MESSAGE_CONTEXT_DEFAULT,
    val maxErrors: Int = 3,
    val maxNodes: Int = -1,
    val tokenizerConfig: TokenizerConfig = TokenizerConfig.BASE,
) {

    companion object {
        // sqlglot: tokenizer_core.SENTINEL_NONE
        val SENTINEL: Token = Token(TokenType.SENTINEL, "")

        // sqlglot: expressions.SQLGLOT_ANONYMOUS
        const val SQLGLOT_ANONYMOUS: String = "sqlglot.anonymous"

        // sqlglot: expressions.SAFE_IDENTIFIER_RE
        private val SAFE_IDENTIFIER_RE = Regex("^[_a-zA-Z][\\w]*$")

        // sqlglot: expressions.INTERVAL_STRING_RE
        private val INTERVAL_STRING_RE = Regex("\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)\\s*")

        // sqlglot: parser.TIME_ZONE_RE
        private val TIME_ZONE_RE = Regex(":.*?[a-zA-Z\\+\\-]")
    }

    // -----------------------------------------------------------------------
    // Dialect-level flags (sqlglot: Dialect / Parser class vars; base defaults)
    // -----------------------------------------------------------------------

    // sqlglot: Parser.dialect (the umbrella Dialect object; used by annotate_types-
    // driven parse paths like apply_index_offset)
    open val dialect: dev.brikk.house.sql.dialects.Dialect
        get() = dev.brikk.house.sql.dialects.Dialects.BASE

    // sqlglot: Parser.STRICT_CAST
    open val strictCast: kotlin.Boolean get() = true

    // sqlglot: Dialect.NULL_ORDERING
    open val nullOrdering: String get() = "nulls_are_small"

    // sqlglot: Dialect.DPIPE_IS_STRING_CONCAT
    open val dpipeIsStringConcat: kotlin.Boolean get() = true

    // sqlglot: Dialect.STRICT_STRING_CONCAT
    open val strictStringConcat: kotlin.Boolean get() = false

    // sqlglot: Dialect.INDEX_OFFSET
    open val indexOffset: Int get() = 0

    // sqlglot: Dialect.TYPED_DIVISION
    open val typedDivision: kotlin.Boolean get() = false

    // sqlglot: Dialect.SAFE_DIVISION
    open val safeDivision: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_LIMIT_ALL
    open val supportsLimitAll: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_VALUES_DEFAULT
    open val supportsValuesDefault: kotlin.Boolean get() = true

    // sqlglot: Parser.ZONE_AWARE_TIMESTAMP_CONSTRUCTOR
    open val zoneAwareTimestampConstructor: kotlin.Boolean get() = false

    // sqlglot: Parser.MAP_KEYS_ARE_ARBITRARY_EXPRESSIONS
    open val mapKeysAreArbitraryExpressions: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_FIXED_SIZE_ARRAYS
    open val supportsFixedSizeArrays: kotlin.Boolean get() = false

    // sqlglot: Parser.TYPE_CONVERTERS
    open val typeConverters: Map<DType, (DataType) -> Expression> get() = emptyMap()

    // sqlglot: Dialect.SUPPORTS_ORDER_BY_ALL
    open val supportsOrderByAll: kotlin.Boolean get() = false

    // sqlglot: Parser.STRING_ALIASES
    open val stringAliases: kotlin.Boolean get() = false

    // sqlglot: Parser.OPTIONAL_ALIAS_TOKEN_CTE
    open val optionalAliasTokenCte: kotlin.Boolean get() = true

    // sqlglot: Parser.JOINS_HAVE_EQUAL_PRECEDENCE
    open val joinsHaveEqualPrecedence: kotlin.Boolean get() = false

    // sqlglot: Parser.ADD_JOIN_ON_TRUE
    open val addJoinOnTrue: kotlin.Boolean get() = false

    // sqlglot: Parser.MODIFIERS_ATTACHED_TO_SET_OP
    open val modifiersAttachedToSetOp: kotlin.Boolean get() = true

    // sqlglot: Parser.SET_OP_MODIFIERS
    open val setOpModifiers: Set<String> get() = setOf("order", "limit", "offset")

    // sqlglot: Dialect.SET_OP_DISTINCT_BY_DEFAULT (base: all True)
    open fun setOpDistinctByDefault(setOpToken: TokenType): kotlin.Boolean? = true

    // sqlglot: Dialect.PRESERVE_ORIGINAL_NAMES
    open val preserveOriginalNames: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_COLUMN_JOIN_MARKS
    open val supportsColumnJoinMarks: kotlin.Boolean get() = false

    // sqlglot: Parser.COLON_IS_VARIANT_EXTRACT
    open val colonIsVariantExtract: kotlin.Boolean get() = false

    // sqlglot: Parser.VALUES_FOLLOWED_BY_PAREN
    open val valuesFollowedByParen: kotlin.Boolean get() = true

    // sqlglot: Dialect.ALIAS_POST_TABLESAMPLE
    open val aliasPostTablesample: kotlin.Boolean get() = false

    // sqlglot: Dialect.ALIAS_POST_VERSION
    open val aliasPostVersion: kotlin.Boolean get() = true

    // sqlglot: Parser.TABLESAMPLE_CSV
    open val tablesampleCsv: kotlin.Boolean get() = false

    // sqlglot: Parser.DEFAULT_SAMPLING_METHOD
    open val defaultSamplingMethod: String? get() = null

    // sqlglot: Dialect.TABLESAMPLE_SIZE_IS_PERCENT
    open val tablesampleSizeIsPercent: kotlin.Boolean get() = false

    // sqlglot: Parser.PREFIXED_PIVOT_COLUMNS
    open val prefixedPivotColumns: kotlin.Boolean get() = false

    // sqlglot: Parser.IDENTIFY_PIVOT_STRINGS
    open val identifyPivotStrings: kotlin.Boolean get() = false

    // sqlglot: Parser.PIVOT_COLUMN_NAMING
    open val pivotColumnNaming: String get() = "agg_name_if_aliased"

    // sqlglot: Parser.SUPPORTS_IMPLICIT_UNNEST
    open val supportsImplicitUnnest: kotlin.Boolean get() = false

    // sqlglot: Parser.TYPED_LAMBDA_ARGS
    open val typedLambdaArgs: kotlin.Boolean get() = false

    // sqlglot: Parser.NO_PAREN_IF_COMMANDS
    open val noParenIfCommands: kotlin.Boolean get() = true

    // sqlglot: Dialect.SUPPORTS_USER_DEFINED_TYPES
    open val supportsUserDefinedTypes: kotlin.Boolean get() = true

    // sqlglot: Parser.JSON_ARROWS_REQUIRE_JSON_TYPE
    open val jsonArrowsRequireJsonType: kotlin.Boolean get() = false

    // sqlglot: Dialect.JSON_EXTRACT_SCALAR_SCALAR_ONLY
    open val jsonExtractScalarScalarOnly: kotlin.Boolean get() = false

    // sqlglot: Dialect.to_json_path (STRICT_JSON_PATH_SYNTAX only logs a warning)
    open fun toJsonPath(path: Expression?): Expression? {
        if (path is Literal) {
            var pathText = path.name
            if (path.isNumber) pathText = "[$pathText]"
            try {
                return parseJsonPath(pathText)
            } catch (e: Exception) {
                // sqlglot: logger.warning on invalid JSON path syntax
            }
        }
        return path
    }

    // sqlglot: Parser.SUPPORTS_PARTITION_SELECTION
    open val supportsPartitionSelection: kotlin.Boolean get() = false

    // sqlglot: Parser.INTERVAL_SPANS
    open val intervalSpans: kotlin.Boolean get() = true

    // sqlglot: Dialect.VALID_INTERVAL_UNITS
    open val validIntervalUnits: Set<String> get() = BaseParserTables.VALID_INTERVAL_UNITS

    // sqlglot: Parser.ARRAY_CONSTRUCTORS
    open val arrayConstructors: Map<String, NodeFactory> get() = BaseParserTables.ARRAY_CONSTRUCTORS

    // sqlglot: Parser.TRIM_TYPES
    open val trimTypes: Set<String> get() = setOf("LEADING", "TRAILING", "BOTH")

    // sqlglot: Parser.JSON_KEY_VALUE_SEPARATOR_TOKENS
    open val jsonKeyValueSeparatorTokens: Set<TokenType>
        get() = setOf(TokenType.COLON, TokenType.COMMA, TokenType.IS)

    // sqlglot: the **kwargs bag PROPERTY_PARSERS lambdas may receive (teradata prefixes)
    class PropertyKwargs(
        val no: kotlin.Boolean = false,
        val dual: kotlin.Boolean = false,
        val before: kotlin.Boolean = false,
        val default: kotlin.Boolean = false,
        val local: String? = null,
        val after: kotlin.Boolean = false,
        val minimum: kotlin.Boolean = false,
        val maximum: kotlin.Boolean = false,
    ) {
        /** sqlglot: `{k: v for k, v in kwargs.items() if v}` */
        fun toArgs(): Args {
            val m = LinkedHashMap<String, kotlin.Any?>()
            if (no) m["no"] = true
            if (dual) m["dual"] = true
            if (before) m["before"] = true
            if (default) m["default"] = true
            if (local != null) m["local"] = local
            if (after) m["after"] = true
            if (minimum) m["minimum"] = true
            if (maximum) m["maximum"] = true
            return m
        }
    }

    // sqlglot: Parser.PROPERTY_PARSERS
    open val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = BaseParserTables.PROPERTY_PARSERS

    // sqlglot: Parser.SET_PARSERS
    open val setParsers: Map<String, (Parser) -> Expression?>
        get() = mapOf(
            "GLOBAL" to { p -> p.parseSetItemAssignment("GLOBAL") },
            "LOCAL" to { p -> p.parseSetItemAssignment("LOCAL") },
            "SESSION" to { p -> p.parseSetItemAssignment("SESSION") },
            "TRANSACTION" to { p -> p.parseSetTransaction() },
        )

    // sqlglot: Parser.TRANSACTION_CHARACTERISTICS
    open val transactionCharacteristics: Map<String, List<List<String>>>
        get() = mapOf(
            "ISOLATION" to listOf(
                listOf("LEVEL", "REPEATABLE", "READ"),
                listOf("LEVEL", "READ", "COMMITTED"),
                listOf("LEVEL", "READ", "UNCOMITTED"),
                listOf("LEVEL", "SERIALIZABLE"),
            ),
            "READ" to listOf(listOf("WRITE"), listOf("ONLY")),
        )

    // sqlglot: Parser.VIEW_ATTRIBUTES
    open val viewAttributes: Set<String> get() = setOf("ENCRYPTION", "SCHEMABINDING", "VIEW_METADATA")

    // sqlglot: Parser.ISOLATED_LOADING_OPTIONS
    open val isolatedLoadingOptions: Map<String, List<List<String>>>
        get() = mapOf("FOR" to listOf(listOf("ALL"), listOf("INSERT"), listOf("NONE")))

    // sqlglot: Parser.SCHEMA_BINDING_OPTIONS
    open val schemaBindingOptions: Map<String, List<List<String>>>
        get() = mapOf(
            "TYPE" to listOf(listOf("EVOLUTION")),
            "BINDING" to emptyList(),
            "COMPENSATION" to emptyList(),
            "EVOLUTION" to emptyList(),
        )

    // sqlglot: Parser.CREATE_SEQUENCE
    open val createSequenceOptions: Map<String, List<List<String>>>
        get() = mapOf(
            "SCALE" to listOf(listOf("EXTEND"), listOf("NOEXTEND")),
            "SHARD" to listOf(listOf("EXTEND"), listOf("NOEXTEND")),
            "NO" to listOf(listOf("CYCLE"), listOf("CACHE"), listOf("MAXVALUE"), listOf("MINVALUE")),
            "SESSION" to emptyList(),
            "GLOBAL" to emptyList(),
            "KEEP" to emptyList(),
            "NOKEEP" to emptyList(),
            "ORDER" to emptyList(),
            "NOORDER" to emptyList(),
            "NOCACHE" to emptyList(),
            "CYCLE" to emptyList(),
            "NOCYCLE" to emptyList(),
            "NOMINVALUE" to emptyList(),
            "NOMAXVALUE" to emptyList(),
            "NOSCALE" to emptyList(),
            "NOSHARD" to emptyList(),
        )

    // sqlglot: Parser.COMMENT_TABLE_ALIAS_TOKENS
    open val commentTableAliasTokens: Set<TokenType>
        get() = BaseParserTables.TABLE_ALIAS_TOKENS - setOf(TokenType.IS)

    // sqlglot: Parser.UPDATE_ALIAS_TOKENS
    open val updateAliasTokens: Set<TokenType>
        get() = BaseParserTables.TABLE_ALIAS_TOKENS - setOf(TokenType.SET)

    // sqlglot: Parser.USABLES
    open val usables: Map<String, List<List<String>>>
        get() = mapOf(
            "ROLE" to emptyList(),
            "WAREHOUSE" to emptyList(),
            "DATABASE" to emptyList(),
            "SCHEMA" to emptyList(),
            "CATALOG" to emptyList(),
        )

    // sqlglot: Parser.TRIGGER_EVENTS
    open val triggerEvents: Set<TokenType>
        get() = setOf(TokenType.INSERT, TokenType.UPDATE, TokenType.DELETE, TokenType.TRUNCATE)

    // sqlglot: Parser.TRIGGER_TIMING
    open val triggerTiming: Map<String, List<List<String>>>
        get() = mapOf(
            "INSTEAD" to listOf(listOf("OF")),
            "BEFORE" to emptyList(),
            "AFTER" to emptyList(),
        )

    // sqlglot: Parser.TRIGGER_DEFERRABLE
    open val triggerDeferrable: Map<String, List<List<String>>>
        get() = mapOf(
            "NOT" to listOf(listOf("DEFERRABLE")),
            "DEFERRABLE" to emptyList(),
        )

    // sqlglot: Parser.PARTITION_KEYWORDS
    open val partitionKeywords: Set<String> get() = setOf("PARTITION", "SUBPARTITION")

    // sqlglot: Parser.DESCRIBE_STYLES
    open val describeStyles: Set<String> get() = setOf("ANALYZE", "EXTENDED", "FORMATTED", "HISTORY")

    // sqlglot: Parser.INSERT_ALTERNATIVES
    open val insertAlternatives: Set<String>
        get() = setOf("ABORT", "FAIL", "IGNORE", "REPLACE", "ROLLBACK")

    // sqlglot: Parser.ANALYZE_STYLES
    open val analyzeStyles: Set<String>
        get() = setOf(
            "BUFFER_USAGE_LIMIT", "FULL", "LOCAL", "NO_WRITE_TO_BINLOG", "SAMPLE",
            "SKIP_LOCKED", "VERBOSE",
        )

    // sqlglot: Parser.ANALYZE_EXPRESSION_PARSERS
    open val analyzeExpressionParsers: Map<String, (Parser) -> Expression?>
        get() = mapOf(
            "ALL" to { p -> p.parseAnalyzeColumns() },
            "COMPUTE" to { p -> p.parseAnalyzeStatistics() },
            "DELETE" to { p -> p.parseAnalyzeDelete() },
            "DROP" to { p -> p.parseAnalyzeHistogram() },
            "ESTIMATE" to { p -> p.parseAnalyzeStatistics() },
            "LIST" to { p -> p.parseAnalyzeList() },
            "PREDICATE" to { p -> p.parseAnalyzeColumns() },
            "UPDATE" to { p -> p.parseAnalyzeHistogram() },
            "VALIDATE" to { p -> p.parseAnalyzeValidate() },
        )

    // sqlglot: Parser.ADD_CONSTRAINT_TOKENS
    open val addConstraintTokens: Set<TokenType>
        get() = setOf(
            TokenType.CONSTRAINT, TokenType.FOREIGN_KEY, TokenType.INDEX, TokenType.KEY,
            TokenType.PRIMARY_KEY, TokenType.UNIQUE,
        )

    // sqlglot: Parser.ALTER_PARSERS
    open val alterParsers: Map<String, (Parser) -> kotlin.Any?>
        get() = mapOf(
            "ADD" to { p -> p.parseAlterTableAdd() },
            "AS" to { p -> p.parseSelect() },
            "ALTER" to { p -> p.parseAlterTableAlter() },
            "CLUSTER BY" to { p -> p.parseClusterProperty() },
            "DELETE" to { p -> p.expression(Delete(args("where" to p.parseWhere()))) },
            "DROP" to { p -> p.parseAlterTableDrop() },
            "RENAME" to { p -> p.parseAlterTableRename() },
            "SET" to { p -> p.parseAlterTableSet() },
            "SWAP" to { p ->
                p.expression(
                    SwapTable(
                        args("this" to (if (p.match(TokenType.WITH)) p.parseTable(schema = true) else false))
                    )
                )
            },
        )

    // sqlglot: Parser.ALTER_ALTER_PARSERS
    open val alterAlterParsers: Map<String, (Parser) -> Expression?>
        get() = mapOf(
            "DISTKEY" to { p -> p.parseAlterDiststyle() },
            "DISTSTYLE" to { p -> p.parseAlterDiststyle() },
            "SORTKEY" to { p -> p.parseAlterSortkey() },
            "COMPOUND" to { p -> p.parseAlterSortkey(compound = true) },
        )

    // sqlglot: Parser.DB_CREATABLES
    open val dbCreatables: Set<TokenType> get() = BaseParserTables.DB_CREATABLES

    // sqlglot: Parser.CREATABLES
    open val creatables: Set<TokenType> get() = BaseParserTables.CREATABLES

    // sqlglot: Parser.ALTERABLES
    open val alterables: Set<TokenType>
        get() = setOf(TokenType.INDEX, TokenType.TABLE, TokenType.VIEW)

    // sqlglot: Parser.DDL_SELECT_TOKENS
    open val ddlSelectTokens: Set<TokenType>
        get() = setOf(TokenType.SELECT, TokenType.WITH, TokenType.L_PAREN)

    // sqlglot: Parser.CLONE_KEYWORDS
    open val cloneKeywords: Set<String> get() = setOf("CLONE", "COPY")

    // sqlglot: Parser.CONSTRAINT_PARSERS
    open val constraintParsers: Map<String, (Parser) -> Expression?>
        get() = BaseParserTables.CONSTRAINT_PARSERS

    // sqlglot: Parser.SCHEMA_UNNAMED_CONSTRAINTS
    open val schemaUnnamedConstraints: Set<String>
        get() = BaseParserTables.SCHEMA_UNNAMED_CONSTRAINTS

    // sqlglot: Parser.KEY_CONSTRAINT_OPTIONS
    open val keyConstraintOptions: Map<String, List<List<String>>>
        get() = BaseParserTables.KEY_CONSTRAINT_OPTIONS

    // sqlglot: Parser.CONFLICT_ACTIONS
    open val conflictActions: Map<String, List<List<String>>>
        get() = BaseParserTables.CONFLICT_ACTIONS

    // sqlglot: Parser.OPCLASS_FOLLOW_KEYWORDS
    open val opclassFollowKeywords: Set<String>
        get() = setOf("ASC", "DESC", "NULLS", "WITH")

    // sqlglot: Parser.OPTYPE_FOLLOW_TOKENS
    open val optypeFollowTokens: Set<TokenType>
        get() = setOf(TokenType.COMMA, TokenType.R_PAREN)

    // sqlglot: Parser.UNNEST_OFFSET_ALIAS_TOKENS
    open val unnestOffsetAliasTokens: Set<TokenType>
        get() = BaseParserTables.TABLE_ALIAS_TOKENS - BaseParserTables.SET_OPERATIONS

    // sqlglot: Parser.WINDOW_EXCLUDE_OPTIONS
    open val windowExcludeOptions: Map<String, List<List<String>>>
        get() = mapOf(
            "NO" to listOf(listOf("OTHERS")),
            "CURRENT" to listOf(listOf("ROW")),
            "GROUP" to emptyList(),
            "TIES" to emptyList(),
        )

    // -----------------------------------------------------------------------
    // Table providers (sqlglot: Parser class-level tables; see BaseParserTables)
    // -----------------------------------------------------------------------

    open val functions: Map<String, (List<Expression?>) -> Expression> get() = BaseParserTables.FUNCTIONS
    open val noParenFunctions: Map<TokenType, () -> Expression> get() = BaseParserTables.NO_PAREN_FUNCTIONS
    open val structTypeTokens: Set<TokenType> get() = BaseParserTables.STRUCT_TYPE_TOKENS
    open val nestedTypeTokens: Set<TokenType> get() = BaseParserTables.NESTED_TYPE_TOKENS
    open val enumTypeTokens: Set<TokenType> get() = BaseParserTables.ENUM_TYPE_TOKENS
    open val aggregateTypeTokens: Set<TokenType> get() = BaseParserTables.AGGREGATE_TYPE_TOKENS
    open val typeTokens: Set<TokenType> get() = BaseParserTables.TYPE_TOKENS
    open val signedToUnsignedTypeToken: Map<TokenType, TokenType> get() = BaseParserTables.SIGNED_TO_UNSIGNED_TYPE_TOKEN
    open val subqueryPredicates: Map<TokenType, NodeFactory> get() = BaseParserTables.SUBQUERY_PREDICATES
    open val subqueryTokens: Set<TokenType> get() = BaseParserTables.SUBQUERY_TOKENS
    open val reservedTokens: Set<TokenType> get() = BaseParserTables.RESERVED_TOKENS
    open val textMatchExcludedTokens: Set<TokenType> get() = BaseParserTables.TEXT_MATCH_EXCLUDED_TOKENS
    open val idVarTokens: Set<TokenType> get() = BaseParserTables.ID_VAR_TOKENS
    open val tableAliasTokens: Set<TokenType> get() = BaseParserTables.TABLE_ALIAS_TOKENS
    open val aliasTokens: Set<TokenType> get() = BaseParserTables.ALIAS_TOKENS
    open val colonPlaceholderTokens: Set<TokenType> get() = BaseParserTables.COLON_PLACEHOLDER_TOKENS
    open val identifierTokens: Set<TokenType> get() = BaseParserTables.IDENTIFIER_TOKENS
    open val brackets: Set<TokenType> get() = BaseParserTables.BRACKETS
    open val columnPostfixTokens: Set<TokenType> get() = BaseParserTables.COLUMN_POSTFIX_TOKENS
    open val tablePostfixTokens: Set<TokenType> get() = BaseParserTables.TABLE_POSTFIX_TOKENS
    open val funcTokens: Set<TokenType> get() = BaseParserTables.FUNC_TOKENS
    open val conjunction: Map<TokenType, NodeFactory> get() = BaseParserTables.CONJUNCTION
    open val assignmentTable: Map<TokenType, NodeFactory> get() = BaseParserTables.ASSIGNMENT
    open val disjunction: Map<TokenType, NodeFactory> get() = BaseParserTables.DISJUNCTION
    open val equality: Map<TokenType, NodeFactory> get() = BaseParserTables.EQUALITY
    open val comparison: Map<TokenType, NodeFactory> get() = BaseParserTables.COMPARISON
    open val bitwise: Map<TokenType, NodeFactory> get() = BaseParserTables.BITWISE
    open val term: Map<TokenType, NodeFactory> get() = BaseParserTables.TERM
    open val factor: Map<TokenType, NodeFactory> get() = BaseParserTables.FACTOR
    open val exponent: Map<TokenType, NodeFactory> get() = BaseParserTables.EXPONENT
    open val times: Set<TokenType> get() = BaseParserTables.TIMES
    open val timestamps: Set<TokenType> get() = BaseParserTables.TIMESTAMPS
    open val setOperations: Set<TokenType> get() = BaseParserTables.SET_OPERATIONS
    open val joinMethods: Set<TokenType> get() = BaseParserTables.JOIN_METHODS
    open val joinSides: Set<TokenType> get() = BaseParserTables.JOIN_SIDES
    open val joinKinds: Set<TokenType> get() = BaseParserTables.JOIN_KINDS
    open val joinHints: Set<String> get() = BaseParserTables.JOIN_HINTS
    open val tableTerminators: Set<TokenType> get() = BaseParserTables.TABLE_TERMINATORS
    open val lambdas: Map<TokenType, (Parser, List<Expression?>) -> Expression?> get() = BaseParserTables.LAMBDAS
    open val lambdaArgTerminators: Set<TokenType> get() = BaseParserTables.LAMBDA_ARG_TERMINATORS
    open val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> get() = BaseParserTables.COLUMN_OPERATORS
    open val castColumnOperators: Set<TokenType> get() = BaseParserTables.CAST_COLUMN_OPERATORS
    open val statementParsers: Map<TokenType, (Parser) -> Expression> get() = BaseParserTables.STATEMENT_PARSERS
    open val unaryParsers: Map<TokenType, (Parser) -> Expression?> get() = BaseParserTables.UNARY_PARSERS
    open val stringParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.STRING_PARSERS
    open val numericParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.NUMERIC_PARSERS
    open val primaryParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.PRIMARY_PARSERS
    open val placeholderParsers: Map<TokenType, (Parser) -> Expression?> get() = BaseParserTables.PLACEHOLDER_PARSERS
    open val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?> get() = BaseParserTables.RANGE_PARSERS
    open val noParenFunctionParsers: Map<String, (Parser) -> Expression?> get() = BaseParserTables.NO_PAREN_FUNCTION_PARSERS
    open val invalidFuncNameTokens: Set<TokenType> get() = BaseParserTables.INVALID_FUNC_NAME_TOKENS
    open val functionsWithAliasedArgs: Set<String> get() = BaseParserTables.FUNCTIONS_WITH_ALIASED_ARGS
    open val functionParsers: Map<String, (Parser) -> Expression?> get() = BaseParserTables.FUNCTION_PARSERS
    open val queryModifierParsers: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>> get() = BaseParserTables.QUERY_MODIFIER_PARSERS
    open val queryModifierTokens: Set<TokenType> get() = queryModifierParsers.keys
    open val typeLiteralParsers: Map<DType, (Parser, Expression?, Expression) -> Expression?> get() = BaseParserTables.TYPE_LITERAL_PARSERS
    open val windowAliasTokens: Set<TokenType> get() = BaseParserTables.WINDOW_ALIAS_TOKENS
    open val windowBeforeParenTokens: Set<TokenType> get() = BaseParserTables.WINDOW_BEFORE_PAREN_TOKENS
    open val windowSides: Set<String> get() = BaseParserTables.WINDOW_SIDES
    open val fetchTokens: Set<TokenType> get() = BaseParserTables.FETCH_TOKENS
    open val distinctTokens: Set<TokenType> get() = BaseParserTables.DISTINCT_TOKENS
    open val selectStartTokens: Set<TokenType> get() = BaseParserTables.SELECT_START_TOKENS
    open val ambiguousAliasTokens: Set<TokenType> get() = BaseParserTables.AMBIGUOUS_ALIAS_TOKENS
    open val operationModifiers: Set<String> get() = BaseParserTables.OPERATION_MODIFIERS
    open val recursiveCteSearchKind: Set<String> get() = BaseParserTables.RECURSIVE_CTE_SEARCH_KIND
    open val castActions: Map<String, List<List<String>>> get() = BaseParserTables.CAST_ACTIONS
    open val historicalDataPrefix: Set<String> get() = BaseParserTables.HISTORICAL_DATA_PREFIX
    open val historicalDataKind: Set<String> get() = BaseParserTables.HISTORICAL_DATA_KIND

    // -----------------------------------------------------------------------
    // State (sqlglot: Parser.__init__ / Parser.reset)
    // -----------------------------------------------------------------------

    var errorLevel: ErrorLevel = errorLevel ?: ErrorLevel.IMMEDIATE

    var sql: String = ""
        protected set

    val errors: MutableList<ParseError> = mutableListOf()

    protected var tokens: List<Token> = emptyList()
    protected var tokensSize: Int = 0
    protected var index: Int = 0

    var currToken: Token = SENTINEL
        protected set
    var nextToken: Token = SENTINEL
        protected set
    var prevToken: Token = SENTINEL
        protected set
    var prevComments: List<String> = emptyList()
        protected set

    private var chunks: List<MutableList<Token>> = emptyList()
    private var chunkIndex: Int = 0
    private var nodeCount: Int = 0

    /** Python's `bool(token)` — SENTINEL tokens are falsy. */
    protected val Token.exists: kotlin.Boolean get() = tokenType != TokenType.SENTINEL

    // sqlglot: Parser.reset
    fun reset() {
        sql = ""
        errors.clear()
        tokens = emptyList()
        tokensSize = 0
        index = 0
        currToken = SENTINEL
        nextToken = SENTINEL
        prevToken = SENTINEL
        prevComments = emptyList()
        chunks = emptyList()
        chunkIndex = 0
        nodeCount = 0
    }

    // -----------------------------------------------------------------------
    // Navigation / matchers
    // -----------------------------------------------------------------------

    // sqlglot: Parser._advance
    protected fun advance(times: Int = 1) {
        val newIndex = index + times
        index = newIndex
        currToken = if (newIndex < tokensSize) tokens[newIndex] else SENTINEL
        nextToken = if (newIndex + 1 < tokensSize) tokens[newIndex + 1] else SENTINEL

        if (newIndex > 0) {
            val prev = tokens[newIndex - 1]
            prevToken = prev
            prevComments = prev.comments
        } else {
            prevToken = SENTINEL
            prevComments = emptyList()
        }
    }

    // sqlglot: Parser._advance_chunk
    private fun advanceChunk() {
        index = -1
        tokens = chunks[chunkIndex]
        tokensSize = tokens.size
        chunkIndex += 1
        advance()
    }

    // sqlglot: Parser._retreat
    protected fun retreat(index: Int) {
        if (index != this.index) advance(index - this.index)
    }

    // sqlglot: Parser._add_comments
    protected fun addTokenComments(expression: Expression?) {
        if (expression != null && prevComments.isNotEmpty()) {
            expression.addComments(prevComments)
            prevComments = emptyList()
        }
    }

    // sqlglot: Parser._match
    fun match(tokenType: TokenType, advance: kotlin.Boolean = true, expression: Expression? = null): kotlin.Boolean {
        if (currToken.tokenType == tokenType) {
            if (advance) advance()
            addTokenComments(expression)
            return true
        }
        return false
    }

    // sqlglot: Parser._match_set
    fun matchSet(types: Collection<TokenType>, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType in types) {
            if (advance) advance()
            return true
        }
        return false
    }

    // sqlglot: Parser._match_pair
    fun matchPair(tokenTypeA: TokenType, tokenTypeB: TokenType, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType == tokenTypeA && nextToken.tokenType == tokenTypeB) {
            if (advance) advance(2)
            return true
        }
        return false
    }

    // sqlglot: Parser._match_texts
    fun matchTexts(texts: Collection<String>, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType !in textMatchExcludedTokens && currToken.text.uppercase() in texts) {
            if (advance) advance()
            return true
        }
        return false
    }

    // sqlglot: Parser._match_text_seq
    fun matchTextSeq(vararg texts: String, advance: kotlin.Boolean = true): kotlin.Boolean {
        val startIndex = index
        for (text in texts) {
            if (currToken.tokenType !in textMatchExcludedTokens && currToken.text.uppercase() == text) {
                advance()
            } else {
                retreat(startIndex)
                return false
            }
        }
        if (!advance) retreat(startIndex)
        return true
    }

    // sqlglot: Parser._match_l_paren
    protected fun matchLParen(expression: Expression? = null) {
        if (!match(TokenType.L_PAREN, expression = expression)) raiseError("Expecting (")
    }

    // sqlglot: Parser._match_r_paren
    protected fun matchRParen(expression: Expression? = null) {
        if (!match(TokenType.R_PAREN, expression = expression)) raiseError("Expecting )")
    }

    // sqlglot: Parser._is_connected
    protected fun isConnected(): kotlin.Boolean =
        prevToken.exists && currToken.exists && prevToken.end + 1 == currToken.start

    // sqlglot: Parser._find_sql
    protected fun findSql(start: Token, end: Token): String = sql.substring(start.start, end.end + 1)

    // sqlglot: Parser._advance_any
    protected fun advanceAny(ignoreReserved: kotlin.Boolean = false): Token? {
        if (currToken.exists && (ignoreReserved || currToken.tokenType !in reservedTokens)) {
            advance()
            return prevToken
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Errors / validation
    // -----------------------------------------------------------------------

    // sqlglot: Parser.raise_error
    fun raiseError(message: String, token: Token = SENTINEL): Nothing? {
        val errorToken = when {
            token.exists -> token
            currToken.exists -> currToken
            prevToken.exists -> prevToken
            else -> Token.string("")
        }
        val highlighted = highlightSql(
            sql = sql,
            positions = listOf(errorToken.start to errorToken.end),
            contextLength = errorMessageContext,
        )
        val formattedMessage =
            "$message. Line ${errorToken.line}, Col: ${errorToken.col}.\n  ${highlighted.formattedSql}"

        val error = ParseError.new(
            formattedMessage,
            description = message,
            line = errorToken.line,
            col = errorToken.col,
            startContext = highlighted.startContext,
            highlight = highlighted.highlight,
            endContext = highlighted.endContext,
        )

        if (errorLevel == ErrorLevel.IMMEDIATE) throw error

        errors.add(error)
        return null
    }

    /**
     * sqlglot: Parser.validate_expression + Expression.error_messages — validates that
     * required args are present (None / empty list count as missing, false does not).
     */
    fun <E : Expression> validateExpression(expression: E): E {
        if (maxNodes > -1) {
            nodeCount += 1
            if (nodeCount > maxNodes) {
                raiseError("Maximum number of AST nodes ($maxNodes) exceeded")
            }
        }
        if (errorLevel != ErrorLevel.IGNORE) {
            for ((key, required) in expression.argTypes) {
                if (!required) continue
                val v = expression.args[key]
                if (v == null || (v is List<*> && v.isEmpty())) {
                    raiseError("Required keyword: '$key' missing for ${expression::class.simpleName}")
                }
            }
        }
        return expression
    }

    // sqlglot: Parser._try_parse
    protected fun <T> tryParse(parseMethod: () -> T?, retreat: kotlin.Boolean = false): T? {
        val startIndex = index
        val savedErrorLevel = errorLevel
        var result: T? = null

        errorLevel = ErrorLevel.IMMEDIATE
        try {
            result = parseMethod()
        } catch (_: ParseError) {
            result = null
        } finally {
            if (result == null || retreat) retreat(startIndex)
            errorLevel = savedErrorLevel
        }

        return result
    }

    // sqlglot: Parser.expression
    fun <E : Expression> expression(
        instance: E,
        token: Token? = null,
        comments: List<String>? = null,
    ): E {
        // sqlglot: instance.update_positions(token) — positions live in meta, which is
        // stripped for oracle comparisons; position tracking lands in a later phase.
        // NOTE: Python is `add_comments(comments) if comments else _add_comments(...)`,
        // i.e. an empty comments list falls through to the prev-token comments.
        if (!comments.isNullOrEmpty()) instance.addComments(comments) else addTokenComments(instance)
        if (!instance.isPrimitive) validateExpression(instance)
        return instance
    }

    // sqlglot: Parser.check_errors (WARN logging is dropped: no logger in scope yet)
    fun checkErrors() {
        if (errorLevel == ErrorLevel.RAISE && errors.isNotEmpty()) {
            throw ParseError(
                concatMessages(errors, maxErrors),
                errors = mergeErrors(errors),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------

    // sqlglot: Parser.parse
    fun parse(rawTokens: List<Token>, sql: String): List<Expression?> =
        parseInternal({ it.parseStatement() }, rawTokens, sql)

    // sqlglot: Parser.parse_into — subset limited to the EXPRESSION_PARSERS entries the
    // schema layer needs (exp.DataType, exp.Table, exp.Identifier).

    // sqlglot: EXPRESSION_PARSERS[exp.DataType]
    fun parseIntoDataType(rawTokens: List<Token>, sql: String): Expression? =
        parseInternal(
            { it.parseTypes(allowIdentifiers = false, schema = true) },
            rawTokens,
            sql,
        ).firstOrNull()

    // sqlglot: EXPRESSION_PARSERS[exp.Table]
    fun parseIntoTable(rawTokens: List<Token>, sql: String): Expression? =
        parseInternal({ it.parseTableParts() }, rawTokens, sql).firstOrNull()

    // sqlglot: EXPRESSION_PARSERS[exp.Identifier]
    fun parseIntoIdentifier(rawTokens: List<Token>, sql: String): Expression? =
        parseInternal({ it.parseIdVar() }, rawTokens, sql).firstOrNull()

    // sqlglot: Parser._parse
    private fun parseInternal(
        parseMethod: (Parser) -> Expression?,
        rawTokens: List<Token>,
        sql: String?,
    ): List<Expression?> {
        reset()
        this.sql = sql ?: ""

        val total = rawTokens.size
        val newChunks = mutableListOf(mutableListOf<Token>())

        for ((i, token) in rawTokens.withIndex()) {
            if (token.tokenType == TokenType.SEMICOLON) {
                if (token.comments.isNotEmpty()) {
                    newChunks.add(mutableListOf(token))
                }
                if (i < total - 1) {
                    newChunks.add(mutableListOf())
                }
            } else {
                newChunks.last().add(token)
            }
        }

        chunks = newChunks

        return parseBatchStatements(parseMethod = parseMethod, sepFirstStatement = false)
    }

    // sqlglot: Parser._parse_batch_statements
    protected fun parseBatchStatements(
        parseMethod: (Parser) -> Expression?,
        sepFirstStatement: kotlin.Boolean = true,
    ): List<Expression?> {
        val expressions = mutableListOf<Expression?>()

        // Chunkification binds if/while statements with the first statement of the body
        if (sepFirstStatement) {
            match(TokenType.BEGIN)
            expressions.add(parseMethod(this))
        }

        val chunksLength = chunks.size
        while (chunkIndex < chunksLength) {
            advanceChunk()

            if (match(TokenType.ELSE, advance = false)) {
                return expressions
            }

            if (expressions.isNotEmpty() && !nextToken.exists && match(TokenType.END)) {
                // sqlglot: exp.EndStatement() — control-flow statements not ported.
                raiseError("END statements are not supported yet")
                continue
            }

            expressions.add(parseMethod(this))

            if (index < tokensSize) {
                raiseError("Invalid expression / Unexpected token")
            }

            checkErrors()
        }

        return expressions
    }

    // sqlglot: Parser._warn_unsupported (logging dropped: no logger in scope yet)
    protected fun warnUnsupported() {}

    // -----------------------------------------------------------------------
    // CSV / wrapped combinators
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_csv (Kotlin deviation: sep comes first so that parseMethod
    // can be passed as a trailing lambda)
    fun <T : kotlin.Any> parseCsv(
        sep: TokenType = TokenType.COMMA,
        parseMethod: () -> T?,
    ): MutableList<T> {
        var parseResult = parseMethod()
        val items = mutableListOf<T>()
        if (parseResult != null) items.add(parseResult)

        while (match(sep)) {
            if (parseResult is Expression) addTokenComments(parseResult)
            parseResult = parseMethod()
            if (parseResult != null) items.add(parseResult)
        }

        return items
    }

    // sqlglot: Parser._parse_wrapped
    fun <T> parseWrapped(parseMethod: () -> T, optional: kotlin.Boolean = false): T {
        val wrapped = match(TokenType.L_PAREN)
        if (!wrapped && !optional) raiseError("Expecting (")
        val parseResult = parseMethod()
        if (wrapped) matchRParen()
        return parseResult
    }

    // sqlglot: Parser._parse_wrapped_csv
    fun <T : kotlin.Any> parseWrappedCsv(
        parseMethod: () -> T?,
        sep: TokenType = TokenType.COMMA,
        optional: kotlin.Boolean = false,
    ): MutableList<T> = parseWrapped({ parseCsv(sep, parseMethod) }, optional)

    // sqlglot: Parser._parse_wrapped_id_vars
    fun parseWrappedIdVars(optional: kotlin.Boolean = false): MutableList<Expression> =
        parseWrappedCsv({ parseIdVar() }, optional = optional)

    // -----------------------------------------------------------------------
    // Expression ladder (sqlglot: _parse_expression .. _parse_unary)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_expression
    open fun parseExpression(): Expression? = parseAlias(parseAssignment())

    // sqlglot: Parser._parse_expressions
    fun parseExpressions(): MutableList<Expression> = parseCsv { parseExpression() }

    // sqlglot: Parser._parse_assignment
    fun parseAssignment(): Expression? {
        var this_ = parseDisjunction()
        if (this_ == null && nextToken.tokenType in assignmentTable) {
            // This allows us to parse <non-identifier token> := <expr>
            advanceAny(ignoreReserved = true)
            this_ = expression(
                Column(args("this" to expression(Identifier(args("this" to prevToken.text)))))
            )
        }

        while (matchSet(assignmentTable.keys)) {
            if (this_ is Column && this_.parts.size == 1) {
                this_ = this_.thisArg as Expression
            }

            val comments = prevComments
            this_ = expression(
                assignmentTable.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseAssignment())
                ),
                comments = comments,
            )
        }

        return this_
    }

    // sqlglot: Parser._parse_disjunction
    fun parseDisjunction(): Expression? {
        var this_ = parseConjunction()
        while (matchSet(disjunction.keys)) {
            val comments = prevComments
            this_ = expression(
                disjunction.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseConjunction())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_conjunction
    fun parseConjunction(): Expression? {
        var this_ = parseEquality()
        while (matchSet(conjunction.keys)) {
            val comments = prevComments
            this_ = expression(
                conjunction.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseEquality())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_equality
    fun parseEquality(): Expression? {
        var this_ = parseComparison()
        while (matchSet(equality.keys)) {
            val comments = prevComments
            this_ = expression(
                equality.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseComparison())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_comparison
    fun parseComparison(): Expression? {
        var this_ = parseRange()
        while (matchSet(comparison.keys)) {
            val comments = prevComments
            this_ = expression(
                comparison.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseRange())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_range
    fun parseRange(this_: Expression? = null): Expression? {
        var current = this_ ?: parseBitwise()

        while (true) {
            val negate = match(TokenType.NOT)
            if (matchSet(rangeParsers.keys)) {
                val parsed = rangeParsers.getValue(prevToken.tokenType)(this, current)
                    ?: return current
                current = parsed
            } else if (match(TokenType.ISNULL) || (negate && match(TokenType.NULL))) {
                current = expression(Is(args("this" to current, "expression" to Null())))
            } else if (match(TokenType.NOTNULL)) {
                // Postgres supports ISNULL and NOTNULL for conditions.
                current = expression(Is(args("this" to current, "expression" to Null())))
                current = expression(Not(args("this" to current)))
            } else {
                if (negate) retreat(index - 1)
                break
            }

            if (negate) {
                current = negateRange(current)
                if (currToken.exists &&
                    (currToken.tokenType == TokenType.NOT || currToken.tokenType in rangeParsers)
                ) {
                    current = expression(Paren(args("this" to current)))
                }
            }
        }

        return current
    }

    // sqlglot: Parser._negate_range
    protected fun negateRange(this_: Expression?): Expression? {
        if (this_ == null) return this_

        val expr = if (this_ is Escape) this_.thisArg as Expression else this_
        if (expr is Like || expr is ILike) {
            expr.set("negate", true)
            return this_
        }

        return expression(Not(args("this" to this_)))
    }

    // sqlglot: Parser._parse_is
    fun parseIs(this_: Expression?): Expression? {
        val startIndex = index - 1
        val negate = match(TokenType.NOT)

        if (matchTextSeq("DISTINCT", "FROM")) {
            val factory: NodeFactory = if (negate) ::NullSafeEQ else ::NullSafeNEQ
            return expression(factory(args("this" to this_, "expression" to parseBitwise())))
        }

        val expr: Expression?
        if (match(TokenType.JSON)) {
            // sqlglot: IS JSON predicate (IS_JSON_PREDICATE_KIND); Python's
            // `_match_texts(...) and text` yields False (not None) on no match
            val kind: kotlin.Any = if (matchTexts(setOf("VALUE", "SCALAR", "ARRAY", "OBJECT"))) {
                prevToken.text.uppercase()
            } else false

            val with_: kotlin.Boolean? = when {
                matchTextSeq("WITH") -> true
                matchTextSeq("WITHOUT") -> false
                else -> null
            }

            val unique = match(TokenType.UNIQUE)
            matchTextSeq("KEYS")
            expr = expression(
                JSON(args("this" to kind, "with_" to with_, "unique" to unique))
            )
        } else {
            expr = parseNull() ?: parseBitwise()
            if (expr == null) {
                retreat(startIndex)
                return null
            }
        }

        var result: Expression = expression(Is(args("this" to this_, "expression" to expr)))
        if (negate) result = expression(Not(args("this" to result)))
        return parseColumnOps(result)
    }

    // sqlglot: Parser._parse_in
    fun parseIn(this_: Expression?, alias: kotlin.Boolean = false): Expression {
        var result: Expression
        val unnest = parseUnnest(withAlias = false)
        if (unnest != null) {
            result = expression(In(args("this" to this_, "unnest" to unnest)))
        } else if (matchSet(setOf(TokenType.L_PAREN, TokenType.L_BRACKET))) {
            val matchedLParen = prevToken.tokenType == TokenType.L_PAREN
            val expressions = parseCsv { parseSelectOrExpression(alias = alias) }

            val query = expressions.singleOrNull()
            if (query != null && isQuery(query)) {
                result = expression(
                    In(args("this" to this_, "query" to subqueryOf(parseQueryModifiers(query))))
                )
            } else {
                result = expression(In(args("this" to this_, "expressions" to expressions)))
            }

            if (matchedLParen) {
                matchRParen(result)
            } else if (!match(TokenType.R_BRACKET, expression = result)) {
                raiseError("Expecting ]")
            }
        } else {
            result = expression(In(args("this" to this_, "field" to parseColumn())))
        }

        return result
    }

    // sqlglot: Parser._parse_between
    fun parseBetween(this_: Expression?): Expression {
        var symmetric: kotlin.Boolean? = null
        if (matchTextSeq("SYMMETRIC")) {
            symmetric = true
        } else if (matchTextSeq("ASYMMETRIC")) {
            symmetric = false
        }

        val low = parseBitwise()
        match(TokenType.AND)
        val high = parseBitwise()

        return expression(
            Between(args("this" to this_, "low" to low, "high" to high, "symmetric" to symmetric))
        )
    }

    // sqlglot: Parser._parse_escape
    fun parseEscape(this_: Expression?): Expression? {
        if (!match(TokenType.ESCAPE)) return this_
        return expression(Escape(args("this" to this_, "expression" to (parseString() ?: parseNull()))))
    }

    // sqlglot: Parser._parse_bitwise
    fun parseBitwise(): Expression? {
        var this_ = parseTerm()

        while (true) {
            if (matchSet(bitwise.keys)) {
                this_ = expression(
                    bitwise.getValue(prevToken.tokenType)(
                        args("this" to this_, "expression" to parseTerm())
                    )
                )
            } else if (dpipeIsStringConcat && match(TokenType.DPIPE)) {
                this_ = expression(
                    DPipe(
                        args(
                            "this" to this_,
                            "expression" to parseTerm(),
                            "safe" to !strictStringConcat,
                        )
                    )
                )
            } else if (match(TokenType.DQMARK)) {
                this_ = expression(
                    Coalesce(
                        args("this" to this_, "expressions" to listOfNotNull(parseTerm()))
                    )
                )
            } else if (matchPair(TokenType.LT, TokenType.LT)) {
                this_ = expression(
                    BitwiseLeftShift(args("this" to this_, "expression" to parseTerm()))
                )
            } else if (matchPair(TokenType.GT, TokenType.GT)) {
                this_ = expression(
                    BitwiseRightShift(args("this" to this_, "expression" to parseTerm()))
                )
            } else {
                break
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_term
    fun parseTerm(): Expression? {
        var this_ = parseFactor()

        while (matchSet(term.keys)) {
            val factory = term.getValue(prevToken.tokenType)
            val comments = prevComments
            val expr = parseFactor()

            this_ = expression(factory(args("this" to this_, "expression" to expr)), comments = comments)

            if (this_ is Collate) {
                val collation = this_.expressionArg
                // Preserve collations such as pg_catalog."default" (Postgres) as columns,
                // otherwise fallback to Identifier / Var
                if (collation is Column && collation.parts.size == 1) {
                    val ident = collation.thisArg
                    if (ident is Identifier) {
                        this_.set(
                            "expression",
                            if (ident.quoted) ident else expression(Var(args("this" to ident.name))),
                        )
                    }
                }
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_factor
    fun parseFactor(): Expression? {
        val parseMethod: () -> Expression? =
            if (exponent.isNotEmpty()) ::parseExponent else ::parseUnary
        var this_ = parseAtTimeZone(parseMethod())

        while (matchSet(factor.keys)) {
            val factorToken = prevToken.tokenType
            val factory = factor.getValue(factorToken)
            val comments = prevComments
            val expr = parseMethod()

            if (expr == null && factorToken == TokenType.DIV && prevToken.text.all { it.isLetter() }) {
                retreat(index - 1)
                return this_
            }

            this_ = expression(factory(args("this" to this_, "expression" to expr)), comments = comments)

            if (this_ is Div) {
                this_.set("typed", typedDivision)
                this_.set("safe", safeDivision)
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_exponent
    fun parseExponent(): Expression? {
        var this_ = parseUnary()
        while (matchSet(exponent.keys)) {
            val comments = prevComments
            this_ = expression(
                exponent.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseUnary())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_unary
    fun parseUnary(): Expression? {
        if (matchSet(unaryParsers.keys)) {
            return unaryParsers.getValue(prevToken.tokenType)(this)
        }
        return parseType()
    }

    // sqlglot: Parser._parse_at_time_zone
    fun parseAtTimeZone(this_: Expression?): Expression? {
        if (!matchTextSeq("AT", "TIME", "ZONE")) return this_
        return parseAtTimeZone(
            expression(AtTimeZone(args("this" to this_, "zone" to parseUnary())))
        )
    }

    // -----------------------------------------------------------------------
    // Statement layer (sqlglot: _parse_statement + the SELECT machinery)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_statement
    fun parseStatement(): Expression? {
        if (!currToken.exists) return null

        if (matchSet(statementParsers.keys)) {
            val comments = prevComments
            val stmt = statementParsers.getValue(prevToken.tokenType)(this)
            stmt.addComments(comments, prepend = true)
            return stmt
        }

        if (matchSet(tokenizerConfig.commands)) {
            return parseCommand()
        }

        if (matchTextSeq("WHILE")) {
            // sqlglot: Parser._parse_whileblock — control-flow statements not ported.
            return raiseError("WHILE blocks are not supported yet")
        }

        var expr = parseExpression()
        expr = if (expr != null) parseSetOperations(expr) else parseSelect()

        // sqlglot: Parser._parse_statement (Subquery head followed by PIPE_GT)
        if (expr is Subquery && match(TokenType.PIPE_GT, advance = false)) {
            expr = parsePipeSyntaxQuery(expr)
        }

        return parseQueryModifiers(expr)
    }

    // sqlglot: Parser._parse_command
    fun parseCommand(): Expression {
        warnUnsupported()
        val comments = prevComments
        return expression(
            Command(args("this" to prevToken.text.uppercase(), "expression" to parseString())),
            comments = comments,
        )
    }

    /** Raise-gate helper for statement dispatch entries that are not ported yet. */
    fun parseAsCommandGate(message: String): Expression {
        raiseError(message)
        return parseAsCommand(prevToken)
    }

    // sqlglot: Parser._parse_as_command
    fun parseAsCommand(start: Token): Expression {
        while (currToken.exists) advance()
        val text = findSql(start, prevToken)
        val size = start.text.length
        warnUnsupported()
        return Command(args("this" to text.take(size), "expression" to text.drop(size)))
    }

    // sqlglot: Parser._parse_select
    fun parseSelect(
        nested: kotlin.Boolean = false,
        table: kotlin.Boolean = false,
        parseSubqueryAlias: kotlin.Boolean = true,
        parseSetOperation: kotlin.Boolean = true,
        consumePipe: kotlin.Boolean = true,
        from: From? = null,
    ): Expression? {
        var query = parseSelectQuery(
            nested = nested,
            table = table,
            parseSubqueryAlias = parseSubqueryAlias,
            parseSetOperation = parseSetOperation,
        )

        if (consumePipe && match(TokenType.PIPE_GT, advance = false)) {
            if (query == null && from != null) {
                query = expression(
                    Select(
                        args(
                            "expressions" to mutableListOf<Expression>(Star()),
                            "from_" to from,
                        )
                    )
                )
            }
            if (query != null && isQuery(query)) {
                query = parsePipeSyntaxQuery(query)
                if (query != null && table) query = subqueryOf(query)
            }
        }

        return query
    }

    // sqlglot: Parser._parse_select_query
    fun parseSelectQuery(
        nested: kotlin.Boolean = false,
        table: kotlin.Boolean = false,
        parseSubqueryAlias: kotlin.Boolean = true,
        parseSetOperation: kotlin.Boolean = true,
    ): Expression? {
        val cte = parseWith()

        if (cte != null) {
            var this_ = parseStatement()

            if (this_ == null) {
                raiseError("Failed to parse any statement following CTE")
                return cte
            }

            while (this_ is Subquery && this_.isWrapper) {
                this_ = this_.thisArg as Expression
            }

            // brikk: the WITH clause of a pipe query belongs to the pipeline head (sqlglot
            // attaches it to the desugared select; desugarPipes pops it back off the head).
            val cteTarget = if (this_ is PipeQuery) this_.thisArg as Expression else this_

            if ("with_" in cteTarget.argTypes) {
                val innerCte = cteTarget.args["with_"] as? With
                if (innerCte != null) {
                    cte.set("expressions", cte.expressionsArg + innerCte.expressionsArg)
                    if (innerCte.args["recursive"] == true) {
                        cte.set("recursive", true)
                    }
                }
                cteTarget.set("with_", cte)
            } else {
                raiseError("${cteTarget.key} does not support CTE")
                this_ = cte
            }

            return this_
        }

        // duckdb supports leading with FROM x
        var from = if (match(TokenType.FROM, advance = false)) {
            parseFrom(joins = true, consumePipe = true)
        } else {
            null
        }

        var this_: Expression?

        if (match(TokenType.SELECT)) {
            val comments = prevComments

            val hint = parseHint()

            var all: kotlin.Boolean
            var matchedDistinct: kotlin.Boolean
            if (nextToken.exists && nextToken.tokenType != TokenType.DOT) {
                all = match(TokenType.ALL)
                matchedDistinct = matchSet(distinctTokens)
            } else {
                all = false
                matchedDistinct = false
            }

            val kind = if (match(TokenType.ALIAS) && matchTexts(setOf("STRUCT", "VALUE"))) {
                prevToken.text.uppercase()
            } else {
                null
            }

            var distinct: Expression? = if (matchedDistinct) parseDistinctExpression() else null

            val opMods = mutableListOf<Expression>()
            while (currToken.exists && matchTexts(operationModifiers)) {
                opMods.add(Var(args("this" to prevToken.text.uppercase())))
            }

            val limit = parseLimit(top = true)

            // Some dialects (e.g. Redshift, T-SQL) allow SELECT TOP N DISTINCT ...
            if (limit != null && !matchedDistinct && !all) {
                matchedDistinct = matchSet(distinctTokens)
                if (matchedDistinct) {
                    distinct = parseDistinctExpression()
                } else {
                    all = match(TokenType.ALL)
                }
            }

            if (all && distinct != null) {
                raiseError("Cannot specify both ALL and DISTINCT after SELECT")
            }

            val projections = parseProjections()

            this_ = expression(
                Select(
                    args(
                        "kind" to kind,
                        "hint" to hint,
                        "distinct" to distinct,
                        "expressions" to projections,
                        "limit" to limit,
                        "exclude" to null,
                        "operation_modifiers" to opMods.ifEmpty { null },
                    )
                )
            )
            this_.comments = comments.toMutableList()

            val into = parseInto()
            if (into != null) this_.set("into", into)

            if (from == null) from = parseFrom()

            if (from != null) this_.set("from_", from)

            this_ = parseQueryModifiers(this_)
        } else if ((table || nested) && match(TokenType.L_PAREN)) {
            val comments = prevComments
            val wrapped = parseWrappedSelect(table = table)

            wrapped?.addComments(comments, prepend = true)

            // We return early here so that the UNION isn't attached to the subquery by the
            // following call to _parse_set_operations, but instead becomes the parent node
            matchRParen()
            return parseSubquery(wrapped, parseAlias = parseSubqueryAlias)
        } else if (match(TokenType.VALUES, advance = false)) {
            this_ = parseDerivedTableValues()
        } else if (from != null) {
            // sqlglot: exp.select("*").from_(from_.this, copy=False)
            this_ = expression(
                Select(
                    args(
                        "expressions" to mutableListOf<Expression>(Star()),
                        "from_" to expression(From(args("this" to from.thisArg))),
                    )
                )
            )
            this_ = parseQueryModifiers(this_)
        } else if (match(TokenType.SUMMARIZE)) {
            val table = match(TokenType.TABLE)
            val inner = parseSelect() ?: parseString() ?: parseTable()
            return expression(Summarize(args("this" to inner, "table" to table)))
        } else if (match(TokenType.DESCRIBE)) {
            this_ = parseDescribe()
        } else {
            this_ = null
        }

        return if (parseSetOperation) parseSetOperations(this_) else this_
    }

    /** sqlglot: `exp.Distinct(on=self._parse_value(values=False) if ... else None)`. */
    private fun parseDistinctExpression(): Expression {
        val on: Expression? = if (match(TokenType.ON)) parseValue(values = false) else null
        return expression(Distinct(args("on" to on)))
    }

    // sqlglot: Parser._parse_value (the `values` flag is only consulted by dialect overrides)
    fun parseValue(@Suppress("UNUSED_PARAMETER") values: kotlin.Boolean = true): Expression? {
        fun parseValueExpression(): Expression? {
            if (supportsValuesDefault && match(TokenType.DEFAULT)) {
                return Var(args("this" to prevToken.text.uppercase()))
            }
            return parseExpression()
        }

        if (match(TokenType.L_PAREN)) {
            val expressions = parseCsv { parseValueExpression() }
            matchRParen()
            return expression(Tuple(args("expressions" to expressions)))
        }

        // In some dialects we can have VALUES 1, 2 which results in 1 column & 2 rows.
        val single = parseExpression()
        if (single != null) {
            return expression(Tuple(args("expressions" to listOf(single))))
        }
        return null
    }

    // sqlglot: Parser._parse_wrapped_select
    protected fun parseWrappedSelect(table: kotlin.Boolean = false): Expression? {
        var this_: Expression?
        if (matchSet(setOf(TokenType.PIVOT, TokenType.UNPIVOT))) {
            this_ = parseSimplifiedPivot(
                isUnpivot = prevToken.tokenType == TokenType.UNPIVOT
            )
        } else if (match(TokenType.FROM)) {
            val from = parseFrom(joins = true, skipFromToken = true, consumePipe = true)
            // Support parentheses for duckdb FROM-first syntax
            val select = parseSelect(from = from)
            if (select != null) {
                // brikk: a PipeQuery keeps the leading FROM inside its head, never on itself
                if (select !is PipeQuery && select.args["from_"] == null) select.set("from_", from)
                this_ = select
            } else {
                this_ = expression(
                    Select(
                        args(
                            "expressions" to mutableListOf<Expression>(Star()),
                            "from_" to from,
                        )
                    )
                )
                this_ = parseQueryModifiers(parseSetOperations(this_))
            }
        } else {
            this_ = if (table) {
                parseTable(consumePipe = true)
            } else {
                parseSelect(nested = true, parseSetOperation = false)
            }

            // Transform exp.Values into a exp.Table to pass through parse_query_modifiers
            // in case a modifier (e.g. join) is following
            if (table && this_ is Values && this_.alias.isNotEmpty()) {
                val alias = (this_.args["alias"] as Expression).pop()
                this_ = expression(Table(args("this" to this_, "alias" to alias)))
            }

            this_ = parseQueryModifiers(parseSetOperations(this_))
        }

        return this_
    }

    // sqlglot: Parser._parse_projections (the exclude list is not ported — always null)
    protected fun parseProjections(): MutableList<Expression> = parseExpressions()

    // sqlglot: Parser._parse_with
    fun parseWith(skipWithToken: kotlin.Boolean = false): With? {
        if (!skipWithToken && !match(TokenType.WITH)) return null

        val comments = prevComments
        val recursive = match(TokenType.RECURSIVE)

        var lastComments: List<String>? = null
        val expressions = mutableListOf<Expression>()
        while (true) {
            val cte = parseCte()
            if (cte is CTE) {
                expressions.add(cte)
                if (!lastComments.isNullOrEmpty()) cte.addComments(lastComments)
            }

            if (!match(TokenType.COMMA) && !match(TokenType.WITH)) {
                break
            } else {
                match(TokenType.WITH)
            }

            lastComments = prevComments
        }

        return expression(
            With(
                args(
                    "expressions" to expressions,
                    "recursive" to if (recursive) true else null,
                    "search" to parseRecursiveWithSearch(),
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_recursive_with_search — exp.RecursiveWithSearch not ported.
    // Faithful quirk: SEARCH is consumed even when no search kind follows.
    protected fun parseRecursiveWithSearch(): Expression? {
        matchTextSeq("SEARCH")

        if (!matchTexts(recursiveCteSearchKind)) return null

        return raiseError("WITH ... SEARCH is not supported yet")
    }

    // sqlglot: Parser._parse_cte
    fun parseCte(): Expression? {
        val startIndex = index

        val alias = parseTableAlias(idVarTokens)
        if (alias == null || alias.thisArg == null) {
            raiseError("Expected CTE to have alias")
        }

        val keyExpressions = if (matchTextSeq("USING", "KEY")) parseWrappedIdVars() else null

        if (!match(TokenType.ALIAS) && !optionalAliasTokenCte) {
            retreat(startIndex)
            return null
        }

        val comments = prevComments

        val materialized: kotlin.Boolean? = when {
            matchTextSeq("NOT", "MATERIALIZED") -> false
            matchTextSeq("MATERIALIZED") -> true
            else -> null
        }

        val cte = expression(
            CTE(
                args(
                    "this" to parseWrapped({ parseStatement() }),
                    "alias" to alias,
                    "materialized" to materialized,
                    "key_expressions" to keyExpressions,
                )
            ),
            comments = comments,
        )

        // sqlglot: exp.Values CTE-body rewriting (SELECT * FROM <values>)
        val values = cte.args["this"]
        if (values is Values) {
            val body = if (values.alias.isNotEmpty()) {
                values
            } else {
                values.set(
                    "alias",
                    TableAlias(args("this" to Identifier(args("this" to "_values", "quoted" to false)))),
                )
                values
            }
            cte.set(
                "this",
                Select(
                    args(
                        "expressions" to mutableListOf<Expression>(Star()),
                        "from_" to From(args("this" to body)),
                    )
                ),
            )
        }

        return cte
    }

    // sqlglot: Parser._parse_table_alias
    fun parseTableAlias(aliasTokens: Collection<TokenType>? = null): TableAlias? {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) return null

        val anyToken = match(TokenType.ALIAS)
        val alias = parseIdVar(anyToken = anyToken, tokens = aliasTokens ?: tableAliasTokens)
            ?: parseStringAsIdentifier()

        val startIndex = index
        var columns: MutableList<Expression>? = null
        if (match(TokenType.L_PAREN)) {
            columns = parseCsv { parseFunctionParameter() }
            if (columns.isNotEmpty()) matchRParen() else retreat(startIndex)
        }

        if (alias == null && columns.isNullOrEmpty()) return null

        val tableAlias = expression(TableAlias(args("this" to alias, "columns" to columns)))

        // We bubble up comments from the Identifier to the TableAlias
        if (alias is Identifier) tableAlias.addComments(alias.popComments())

        return tableAlias
    }

    // sqlglot: Parser._parse_function_parameter
    protected open fun parseFunctionParameter(): Expression? =
        parseColumnDef(parseIdVar(), computedColumn = false)

    // sqlglot: Parser._parse_subquery
    fun parseSubquery(this_: Expression?, parseAlias: kotlin.Boolean = true): Expression? {
        if (this_ == null) return null

        return expression(
            Subquery(
                args(
                    "this" to this_,
                    "pivots" to parsePivots(),
                    "alias" to if (parseAlias) parseTableAlias() else null,
                    "sample" to parseTableSample(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_query_modifiers
    fun parseQueryModifiers(this_: Expression?): Expression? {
        // sqlglot: Parser.MODIFIABLES = (exp.Query, exp.Table, exp.TableFromRows, exp.Values)
        if (this_ != null && (isQuery(this_) || this_ is Table)) {
            for (join in parseJoins()) this_.append("joins", join)
            while (true) {
                val lateral = parseLateral() ?: break
                this_.append("laterals", lateral)
            }

            while (true) {
                if (matchSet(queryModifierParsers.keys, advance = false)) {
                    val modifierToken = currToken
                    val (key, value) = queryModifierParsers.getValue(modifierToken.tokenType)(this)

                    if (value != null) {
                        if (this_.args[key] != null) {
                            raiseError(
                                "Found multiple '${modifierToken.text.uppercase()}' clauses",
                                token = modifierToken,
                            )
                        }

                        this_.set(key, value)
                        if (key == "limit") {
                            val limitNode = value as Expression
                            val offset = limitNode.args["offset"]
                            limitNode.set("offset", null)

                            if (offset != null) {
                                val offsetNode = Offset(args("expression" to offset))
                                this_.set("offset", offsetNode)

                                val limitByExpressions = limitNode.expressionsArg
                                limitNode.set("expressions", null)
                                offsetNode.set("expressions", limitByExpressions)
                            }
                        }
                        continue
                    }
                }
                break
            }
        }

        // sqlglot: SUPPORTS_IMPLICIT_UNNEST — base: False.

        return this_
    }

    // sqlglot: Parser._parse_hint_fallback_to_string
    protected fun parseHintFallbackToString(): Expression? {
        val start = currToken
        while (currToken.exists) advance()

        val end = tokens[index - 1]
        return Hint(args("expressions" to listOf(findSql(start, end))))
    }

    // sqlglot: Parser._parse_hint_function_call
    protected fun parseHintFunctionCall(): Expression? = parseFunctionCall()

    // sqlglot: Parser._parse_hint_body
    protected fun parseHintBody(): Expression? {
        val startIndex = index
        var shouldFallbackToString = false

        val hints = mutableListOf<Expression>()
        try {
            while (true) {
                val hint = parseCsv { parseHintFunctionCall() ?: parseVar(upper = true) }
                if (hint.isEmpty()) break
                hints.addAll(hint)
            }
        } catch (e: ParseError) {
            shouldFallbackToString = true
        }

        if (shouldFallbackToString || currToken.exists) {
            retreat(startIndex)
            return parseHintFallbackToString()
        }

        return expression(Hint(args("expressions" to hints)))
    }

    // sqlglot: Parser._parse_hint — exp.maybe_parse(comment, into=exp.Hint) re-tokenizes
    // the hint comment and parses it with _parse_hint_body on a fresh parser.
    protected fun parseHint(): Expression? {
        if (match(TokenType.HINT) && prevComments.isNotEmpty()) {
            val hintSql = prevComments[0]
            val hintParser = Parser(tokenizerConfig = tokenizerConfig)
            val hintTokens = Tokenizer(tokenizerConfig).tokenize(hintSql)
            return hintParser.parseSingleInto({ it.parseHintBody() }, hintTokens, hintSql)
        }
        return null
    }

    /** sqlglot: Parser.parse_into (single-target variant used by maybe_parse). */
    fun parseSingleInto(
        parseMethod: (Parser) -> Expression?,
        rawTokens: List<Token>,
        sql: String,
    ): Expression? = parseInternal(parseMethod, rawTokens, sql).firstOrNull()

    // sqlglot: Parser._parse_into — exp.Into not ported.
    protected fun parseInto(): Expression? {
        if (!match(TokenType.INTO)) return null
        return raiseError("SELECT INTO is not supported yet")
    }

    // sqlglot: Parser._parse_from
    fun parseFrom(
        joins: kotlin.Boolean = false,
        skipFromToken: kotlin.Boolean = false,
        consumePipe: kotlin.Boolean = false,
    ): From? {
        if (!skipFromToken && !match(TokenType.FROM)) return null

        val comments = prevComments
        return expression(
            From(args("this" to parseTable(joins = joins, consumePipe = consumePipe))),
            comments = comments,
        )
    }

    // -----------------------------------------------------------------------
    // Joins / tables
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_join_parts
    protected fun parseJoinParts(): Triple<Token?, Token?, Token?> = Triple(
        if (matchSet(joinMethods)) prevToken else null,
        if (matchSet(joinSides)) prevToken else null,
        if (matchSet(joinKinds)) prevToken else null,
    )

    // sqlglot: Parser._parse_using_identifiers
    protected fun parseUsingIdentifiers(): MutableList<Expression> {
        // sqlglot: local _parse_column_as_identifier
        fun parseColumnAsIdentifier(): Expression? {
            val this_ = parseColumn()
            return if (this_ is Column) this_.thisArg as Expression? else this_
        }

        return parseWrappedCsv({ parseColumnAsIdentifier() }, optional = true)
    }

    // sqlglot: Parser._parse_join
    fun parseJoin(
        skipJoinToken: kotlin.Boolean = false,
        parseBracket: kotlin.Boolean = false,
        aliasTokens: Collection<TokenType>? = null,
    ): Expression? {
        if (match(TokenType.COMMA)) {
            val table = tryParse({ parseTable(aliasTokens = aliasTokens) })
            val crossJoin = if (table != null) expression(Join(args("this" to table))) else null

            if (crossJoin != null && joinsHaveEqualPrecedence) crossJoin.set("kind", "CROSS")

            return crossJoin
        }

        val startIndex = index
        var (method, side, kind) = parseJoinParts()
        val directed = matchTextSeq("DIRECTED")
        val hint = if (matchTexts(joinHints)) prevToken.text else null
        val join = match(TokenType.JOIN) ||
            (kind != null && kind.tokenType == TokenType.STRAIGHT_JOIN)
        val joinComments = prevComments

        if (!skipJoinToken && !join) {
            retreat(startIndex)
            kind = null
            method = null
            side = null
        }

        val outerApply = matchPair(TokenType.OUTER, TokenType.APPLY, advance = false)
        val crossApply = matchPair(TokenType.CROSS, TokenType.APPLY, advance = false)

        if (!skipJoinToken && !join && !outerApply && !crossApply) return null

        val kwargs = LinkedHashMap<String, kotlin.Any?>()
        kwargs["this"] = parseTable(parseBracket = parseBracket, aliasTokens = aliasTokens)

        if (kind != null && kind.tokenType == TokenType.ARRAY && match(TokenType.COMMA)) {
            kwargs["expressions"] = parseCsv {
                parseTable(parseBracket = parseBracket, aliasTokens = aliasTokens)
            }
        }

        if (method != null) kwargs["method"] = method.text.uppercase()
        if (side != null) kwargs["side"] = side.text.uppercase()
        if (kind != null) kwargs["kind"] = kind.text.uppercase()
        if (hint != null) kwargs["hint"] = hint

        if (match(TokenType.MATCH_CONDITION)) {
            kwargs["match_condition"] = parseWrapped({ parseComparison() })
        }

        if (match(TokenType.ON)) {
            kwargs["on"] = parseDisjunction()
        } else if (match(TokenType.USING)) {
            kwargs["using"] = parseUsingIdentifiers()
        } else if (
            method == null &&
            !(outerApply || crossApply) &&
            // sqlglot: `not isinstance(kwargs["this"], exp.Unnest)` — exp.Unnest not ported
            !(kind != null && (kind.tokenType == TokenType.CROSS || kind.tokenType == TokenType.ARRAY))
        ) {
            val innerIndex = index
            var joins: MutableList<Expression>? = parseJoins(aliasTokens = aliasTokens).toMutableList()

            if (!joins.isNullOrEmpty() && match(TokenType.ON)) {
                kwargs["on"] = parseDisjunction()
            } else if (!joins.isNullOrEmpty() && match(TokenType.USING)) {
                kwargs["using"] = parseUsingIdentifiers()
            } else {
                joins = null
                retreat(innerIndex)
            }

            (kwargs["this"] as? Expression)?.set("joins", if (joins.isNullOrEmpty()) null else joins)
        }

        kwargs["pivots"] = parsePivots()

        val tokenComments = mutableListOf<String>()
        for (token in listOfNotNull(method, side, kind)) tokenComments.addAll(token.comments)
        val comments = joinComments + tokenComments

        if (addJoinOnTrue &&
            kwargs["on"] == null &&
            kwargs["using"] == null &&
            kwargs["method"] == null &&
            kwargs["kind"] in setOf(null, "INNER", "OUTER")
        ) {
            // sqlglot: exp.true()
            kwargs["on"] = Boolean(args("this" to true))
        }

        if (directed) kwargs["directed"] = true

        return expression(Join(kwargs), comments = comments)
    }

    // sqlglot: Parser._parse_joins
    fun parseJoins(aliasTokens: Collection<TokenType>? = null): List<Expression> {
        val joins = mutableListOf<Expression>()
        while (true) {
            val join = parseJoin(aliasTokens = aliasTokens) ?: break
            joins.add(join)
        }
        return joins
    }

    // sqlglot: Parser._parse_table_part
    protected fun parseTablePart(schema: kotlin.Boolean = false): Expression? =
        (if (!schema) parseFunction(optionalParens = false) else null)
            ?: parseIdVar(anyToken = false)
            ?: parseStringAsIdentifier()
            ?: parsePlaceholder()

    // sqlglot: Parser._parse_table_parts_fast
    protected fun parseTablePartsFast(): Expression? {
        val startIndex = index
        var parts: MutableList<Expression>? = null
        var allComments: MutableList<String>? = null

        while (matchSet(identifierTokens)) {
            val token = prevToken
            val comments = prevComments

            val hasDot = match(TokenType.DOT)
            val currTt = currToken.tokenType

            if (!hasDot) {
                if (currTt in tablePostfixTokens) {
                    retreat(startIndex)
                    return null
                }
            } else if (currTt !in identifierTokens) {
                retreat(startIndex)
                return null
            }

            if (parts == null) parts = mutableListOf()

            if (comments.isNotEmpty()) {
                if (allComments == null) allComments = mutableListOf()
                allComments.addAll(comments)
                prevComments = emptyList()
            }

            parts.add(
                expression(
                    Identifier(
                        args("this" to token.text, "quoted" to (token.tokenType == TokenType.IDENTIFIER))
                    ),
                    token,
                )
            )

            if (!hasDot) break
        }

        if (parts == null) return null

        val n = parts.size

        val table: Table = when {
            n == 1 -> Table(args("this" to parts[0]))
            n == 2 -> Table(args("this" to parts[1], "db" to parts[0]))
            else -> {
                var this_: Expression = parts[2]
                for (i in 3 until n) {
                    this_ = Dot(args("this" to this_, "expression" to parts[i]))
                }
                Table(args("this" to this_, "db" to parts[1], "catalog" to parts[0]))
            }
        }

        if (!allComments.isNullOrEmpty()) table.addComments(allComments)
        return table
    }

    // sqlglot: Parser._parse_table_parts
    fun parseTableParts(
        schema: kotlin.Boolean = false,
        isDbReference: kotlin.Boolean = false,
        wildcard: kotlin.Boolean = false,
        fast: kotlin.Boolean = false,
    ): Expression? {
        if (fast) return parseTablePartsFast()

        var catalog: kotlin.Any? = null
        var db: kotlin.Any? = null
        var table: kotlin.Any? = parseTablePart(schema = schema)

        while (match(TokenType.DOT)) {
            if (catalog != null) {
                // This allows nesting the table in arbitrarily many dot expressions if needed
                table = expression(
                    Dot(args("this" to table, "expression" to parseTablePart(schema = schema)))
                )
            } else {
                catalog = db
                db = table
                // "" used for tsql FROM a..b case
                table = parseTablePart(schema = schema) ?: ""
            }
        }

        if (wildcard && isConnected() && (table is Identifier || table == null) && match(TokenType.STAR)) {
            if (table is Identifier) {
                table.args["this"] = (table.args["this"] as String) + "*"
            } else {
                table = Identifier(args("this" to "*"))
            }
        }

        if (isDbReference) {
            catalog = db
            db = table
            table = null
        }

        if (table == null && !isDbReference) raiseError("Expected table name but got $currToken")
        if (db == null && isDbReference) raiseError("Expected database name but got $currToken")

        val tableExp = expression(Table(args("this" to table, "db" to db, "catalog" to catalog)))

        // Bubble up comments from identifier parts to the Table
        val comments = mutableListOf<String>()
        for (part in tableExp.parts) comments.addAll(part.popComments())
        if (comments.isNotEmpty()) tableExp.addComments(comments)

        val changes = parseChanges()
        if (changes != null) tableExp.set("changes", changes)

        val atBefore = parseHistoricalData()
        if (atBefore != null) tableExp.set("when", atBefore)

        val pivots = parsePivots()
        if (pivots != null) tableExp.set("pivots", pivots)

        return tableExp
    }

    // sqlglot: Parser._parse_table
    open fun parseTable(
        schema: kotlin.Boolean = false,
        joins: kotlin.Boolean = false,
        aliasTokens: Collection<TokenType>? = null,
        parseBracket: kotlin.Boolean = false,
        isDbReference: kotlin.Boolean = false,
        parsePartition: kotlin.Boolean = false,
        consumePipe: kotlin.Boolean = false,
    ): Expression? {
        if (!schema && !isDbReference && !consumePipe && !joins) {
            val startIndex = index
            val table = parseTableParts(fast = true)

            if (table != null) {
                val currTt = currToken.tokenType
                val nextTt = nextToken.tokenType

                val fastTerminators = tableTerminators

                // only return the table if we're sure there are no other operators
                // MATCH_CONDITION is a special case because it accepts any alias before it like LIMIT
                if (currTt in fastTerminators && nextTt != TokenType.MATCH_CONDITION) {
                    return table
                }

                val postfixTokens = tablePostfixTokens

                if (currTt !in postfixTokens && nextTt !in postfixTokens) {
                    val alias = parseTableAlias(aliasTokens = aliasTokens ?: tableAliasTokens)
                    if (alias != null) table.set("alias", alias)

                    if (currToken.tokenType in fastTerminators) return table
                }

                retreat(startIndex)
            }
        }

        val stream = parseStream()
        if (stream != null) return stream

        val lateral = parseLateral()
        if (lateral != null) return lateral

        val unnest = parseUnnest()
        if (unnest != null) return unnest

        val values = parseDerivedTableValues()
        if (values != null) return values

        val subquery = parseSelect(table = true, consumePipe = consumePipe)
        if (subquery != null) {
            if (subquery.args["pivots"] == null) subquery.set("pivots", parsePivots())
            if (joins) {
                for (join in parseJoins()) subquery.append("joins", join)
            }
            return subquery
        }

        var bracketTable: Expression? = null
        if (parseBracket) {
            val bracket = this.parseBracket(null)
            if (bracket != null) bracketTable = expression(Table(args("this" to bracket)))
        }

        val rowsFrom: Expression? = if (matchTextSeq("ROWS", "FROM", advance = false)) {
            // sqlglot: exp.Table(rows_from=...) — not ported.
            raiseError("ROWS FROM is not supported yet")
        } else {
            null
        }

        val only = match(TokenType.ONLY)

        val this_: Expression = bracketTable
            ?: rowsFrom
            ?: this.parseBracket(parseTableParts(schema = schema, isDbReference = isDbReference))
            ?: return null

        if (only) this_.set("only", true)

        // Postgres supports a wildcard (table) suffix operator, which is a no-op in this context
        match(TokenType.STAR)

        // sqlglot: parse_partition or SUPPORTS_PARTITION_SELECTION (base: false)
        if ((parsePartition || supportsPartitionSelection) && match(TokenType.PARTITION, advance = false)) {
            this_.set("partition", this.parsePartition())
        }

        if (schema) {
            return parseSchema(this_ = this_)
        }

        if (aliasPostVersion) this_.set("version", parseVersion())

        if (aliasPostTablesample) this_.set("sample", parseTableSample())

        val alias = parseTableAlias(aliasTokens = aliasTokens ?: tableAliasTokens)
        if (alias != null) this_.set("alias", alias)

        if (match(TokenType.INDEXED_BY)) {
            this_.set("indexed", parseTableParts())
        } else if (matchTextSeq("NOT", "INDEXED")) {
            this_.set("indexed", false)
        }

        if (this_ is Table && matchTextSeq("AT")) {
            // sqlglot: exp.AtIndex — not ported.
            return raiseError("AT index expressions are not supported yet")
        }

        this_.set("hints", parseTableHints())

        if (this_.args["pivots"] == null) this_.set("pivots", parsePivots())

        if (!aliasPostTablesample) this_.set("sample", parseTableSample())

        if (!aliasPostVersion) this_.set("version", parseVersion())

        if (joins) {
            for (join in parseJoins(aliasTokens = aliasTokens)) this_.append("joins", join)
        }

        if (matchPair(TokenType.WITH, TokenType.ORDINALITY)) {
            this_.set("ordinality", true)
            this_.set("alias", parseTableAlias())
        }

        return this_
    }

    // sqlglot: Parser._parse_stream — exp.Stream not ported; the retreat keeps STREAM
    // usable as a plain identifier, like Python.
    protected fun parseStream(): Expression? {
        val startIndex = index
        if (match(TokenType.STREAM)) {
            val this_ = tryParse({ parseTable() })
            if (this_ != null) {
                return raiseError("STREAM tables are not supported yet")
            }
            retreat(startIndex)
        }
        return null
    }

    // sqlglot: Parser._parse_lateral — exp.Lateral not ported.
    protected fun parseLateral(): Expression? {
        var crossApply: kotlin.Boolean? = null
        if (matchPair(TokenType.CROSS, TokenType.APPLY)) {
            crossApply = true
        } else if (matchPair(TokenType.OUTER, TokenType.APPLY)) {
            crossApply = false
        }

        var this_: Expression?
        var view = false
        var outer = false
        if (crossApply != null) {
            this_ = parseSelect(table = true)
        } else if (match(TokenType.LATERAL)) {
            this_ = parseSelect(table = true)
            view = match(TokenType.VIEW)
            outer = match(TokenType.OUTER)
        } else {
            return null
        }

        if (this_ == null) {
            this_ = parseUnnest()
                ?: parseFunction()
                ?: parseIdVar(anyToken = false)

            while (match(TokenType.DOT)) {
                this_ = Dot(
                    args(
                        "this" to this_,
                        "expression" to (parseFunction() ?: parseIdVar(anyToken = false)),
                    )
                )
            }
        }

        var ordinality: kotlin.Boolean? = null
        val tableAlias: Expression?

        if (view) {
            val table = parseIdVar(anyToken = false)
            val columns: List<Expression> =
                if (match(TokenType.ALIAS)) parseCsv { parseIdVar() } else emptyList()
            tableAlias = expression(TableAlias(args("this" to table, "columns" to columns)))
        } else if ((this_ is Subquery || this_ is Unnest) && this_.alias.isNotEmpty()) {
            // We move the alias from the lateral's child node to the lateral itself
            tableAlias = (this_.args["alias"] as Expression).pop()
        } else {
            ordinality = matchPair(TokenType.WITH, TokenType.ORDINALITY)
            tableAlias = parseTableAlias()
        }

        return expression(
            Lateral(
                args(
                    "this" to this_,
                    "view" to view,
                    "outer" to outer,
                    "alias" to tableAlias,
                    "cross_apply" to crossApply,
                    "ordinality" to ordinality,
                )
            )
        )
    }

    // sqlglot: Parser._parse_unnest (UNNEST_COLUMN_ONLY=false in the base dialect)
    fun parseUnnest(withAlias: kotlin.Boolean = true): Expression? {
        if (!matchPair(TokenType.UNNEST, TokenType.L_PAREN, advance = false)) return null

        advance()

        val expressions = parseWrappedCsv({ parseEquality() })
        var offset: kotlin.Any? = matchPair(TokenType.WITH, TokenType.ORDINALITY)

        val alias = if (withAlias) parseTableAlias() else null

        if (alias != null) {
            @Suppress("UNCHECKED_CAST")
            val columns = alias.args["columns"] as? MutableList<Expression> ?: mutableListOf()
            if (offset == true && expressions.size < columns.size) {
                offset = columns.removeAt(columns.size - 1)
            }
        }

        if (offset == false && matchPair(TokenType.WITH, TokenType.OFFSET)) {
            match(TokenType.ALIAS)
            offset = parseIdVar(anyToken = false, tokens = unnestOffsetAliasTokens)
                ?: toIdentifier("offset")
        }

        return expression(
            Unnest(args("expressions" to expressions, "alias" to alias, "offset" to offset))
        )
    }

    // sqlglot: Parser._parse_derived_table_values
    protected fun parseDerivedTableValues(): Expression? {
        val isDerived = matchPair(TokenType.L_PAREN, TokenType.VALUES)
        if (!isDerived &&
            // ClickHouse's `FORMAT Values` is equivalent to `VALUES`
            !matchTextSeq("VALUES") && !matchTextSeq("FORMAT", "VALUES")
        ) {
            return null
        }

        val expressions = parseCsv { parseValue() }
        val alias = parseTableAlias()

        if (isDerived) matchRParen()

        return expression(
            Values(
                args("expressions" to expressions, "alias" to (alias ?: parseTableAlias()))
            )
        )
    }

    // sqlglot: Parser._parse_match_recognize_measure
    protected fun parseMatchRecognizeMeasure(): Expression {
        // sqlglot: `self._match_texts(...) and self._prev.text.upper()` — a failed
        // match stores the Python falsy False, not None
        val windowFrame: kotlin.Any =
            if (matchTexts(setOf("FINAL", "RUNNING"))) prevToken.text.uppercase() else false
        return expression(
            MatchRecognizeMeasure(
                args("window_frame" to windowFrame, "this" to parseExpression())
            )
        )
    }

    // sqlglot: Parser._parse_match_recognize
    fun parseMatchRecognize(): Expression? {
        if (!match(TokenType.MATCH_RECOGNIZE)) return null

        matchLParen()

        val partition = parsePartitionBy()
        val order = parseOrder()

        val measures = if (matchTextSeq("MEASURES")) {
            parseCsv { parseMatchRecognizeMeasure() }
        } else null

        val rows: Expression? = if (matchTextSeq("ONE", "ROW", "PER", "MATCH")) {
            Var(args("this" to "ONE ROW PER MATCH"))
        } else if (matchTextSeq("ALL", "ROWS", "PER", "MATCH")) {
            var text = "ALL ROWS PER MATCH"
            if (matchTextSeq("SHOW", "EMPTY", "MATCHES")) {
                text += " SHOW EMPTY MATCHES"
            } else if (matchTextSeq("OMIT", "EMPTY", "MATCHES")) {
                text += " OMIT EMPTY MATCHES"
            } else if (matchTextSeq("WITH", "UNMATCHED", "ROWS")) {
                text += " WITH UNMATCHED ROWS"
            }
            Var(args("this" to text))
        } else null

        val after: Expression? = if (matchTextSeq("AFTER", "MATCH", "SKIP")) {
            var text = "AFTER MATCH SKIP"
            if (matchTextSeq("PAST", "LAST", "ROW")) {
                text += " PAST LAST ROW"
            } else if (matchTextSeq("TO", "NEXT", "ROW")) {
                text += " TO NEXT ROW"
            } else if (matchTextSeq("TO", "FIRST")) {
                text += " TO FIRST ${advanceAny()?.text}"
            } else if (matchTextSeq("TO", "LAST")) {
                text += " TO LAST ${advanceAny()?.text}"
            }
            Var(args("this" to text))
        } else null

        val pattern: Expression? = if (matchTextSeq("PATTERN")) {
            matchLParen()

            if (!currToken.exists) raiseError("Expecting )")

            var paren = 1
            val start = currToken
            var end = prevToken

            while (currToken.exists && paren > 0) {
                if (currToken.tokenType == TokenType.L_PAREN) paren += 1
                if (currToken.tokenType == TokenType.R_PAREN) paren -= 1

                end = prevToken
                advance()
            }

            if (paren > 0) raiseError("Expecting )")

            Var(args("this" to findSql(start, end)))
        } else null

        val define = if (matchTextSeq("DEFINE")) {
            parseCsv { parseNameAsExpression() }
        } else null

        matchRParen()

        return expression(
            MatchRecognize(
                args(
                    "partition_by" to partition,
                    "order" to order,
                    "measures" to measures,
                    "rows" to rows,
                    "after" to after,
                    "pattern" to pattern,
                    "define" to define,
                    "alias" to parseTableAlias(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_name_as_expression
    protected fun parseNameAsExpression(): Expression? {
        var this_ = parseIdVar(anyToken = true)
        if (match(TokenType.ALIAS)) {
            this_ = expression(Alias(args("alias" to this_, "this" to parseDisjunction())))
        }
        return this_
    }

    // sqlglot: Parser._parse_version
    protected fun parseVersion(): Expression? {
        val this_ = when {
            match(TokenType.TIMESTAMP_SNAPSHOT) -> "TIMESTAMP"
            match(TokenType.VERSION_SNAPSHOT) -> "VERSION"
            else -> return null
        }

        val kind: String
        var expr: Expression?
        if (matchSet(setOf(TokenType.FROM, TokenType.BETWEEN))) {
            kind = prevToken.text.uppercase()
            val start = parseBitwise()
            matchTexts(setOf("TO", "AND"))
            val end = parseBitwise()
            expr = expression(Tuple(args("expressions" to listOf(start, end))))
        } else if (matchTextSeq("CONTAINED", "IN")) {
            kind = "CONTAINED IN"
            expr = expression(
                Tuple(args("expressions" to parseWrappedCsv({ parseBitwise() })))
            )
        } else if (match(TokenType.ALL)) {
            kind = "ALL"
            expr = null
        } else {
            matchTextSeq("AS", "OF")
            kind = "AS OF"
            expr = parseType()
        }

        return expression(Version(args("this" to this_, "expression" to expr, "kind" to kind)))
    }

    // sqlglot: Parser._parse_table_sample
    protected open fun parseTableSample(asModifier: kotlin.Boolean = false): Expression? {
        if (!match(TokenType.TABLE_SAMPLE) &&
            !(asModifier && matchTextSeq("USING", "SAMPLE"))
        ) {
            return null
        }

        var bucketNumerator: Expression? = null
        var bucketDenominator: Expression? = null
        var bucketField: Expression? = null
        var percent: Expression? = null
        var size: Expression? = null
        var seed: kotlin.Any? = null

        var method = parseVar(tokens = setOf(TokenType.ROW), upper = true)
        val matchedLParen = match(TokenType.L_PAREN)

        val expressions: MutableList<Expression>?
        val num: Expression?
        if (tablesampleCsv) {
            num = null
            expressions = parseCsv { parsePrimary() }
        } else {
            expressions = null
            num = if (match(TokenType.NUMBER, advance = false)) {
                parseFactor()
            } else {
                parsePrimary() ?: parsePlaceholder()
            }
        }

        if (matchTextSeq("BUCKET")) {
            bucketNumerator = parseNumber()
            matchTextSeq("OUT", "OF")
            bucketDenominator = parseNumber()
            match(TokenType.ON)
            bucketField = parseField()
        } else if (matchSet(setOf(TokenType.PERCENT, TokenType.MOD))) {
            percent = num
        } else if (match(TokenType.ROWS) || !tablesampleSizeIsPercent) {
            size = num
        } else {
            percent = num
        }

        if (matchedLParen) matchRParen()

        if (match(TokenType.L_PAREN)) {
            method = parseVar(upper = true)
            seed = if (match(TokenType.COMMA)) parseNumber() else false
            matchRParen()
        } else if (matchTexts(setOf("SEED", "REPEATABLE"))) {
            seed = parseWrapped({ parseNumber() })
        }

        if (method == null && defaultSamplingMethod != null) {
            method = Var(args("this" to defaultSamplingMethod))
        }

        return expression(
            TableSample(
                args(
                    "expressions" to expressions,
                    "method" to method,
                    "bucket_numerator" to bucketNumerator,
                    "bucket_denominator" to bucketDenominator,
                    "bucket_field" to bucketField,
                    "percent" to percent,
                    "size" to size,
                    "seed" to seed,
                )
            )
        )
    }

    // sqlglot: Parser.TABLE_INDEX_HINT_TOKENS
    open val tableIndexHintTokens: Set<TokenType>
        get() = setOf(TokenType.FORCE, TokenType.IGNORE, TokenType.USE)

    // sqlglot: Parser._parse_table_hints (exp.WithTableHint branch still raise-gated)
    protected open fun parseTableHints(): List<Expression>? {
        val hints = mutableListOf<Expression>()
        if (matchPair(TokenType.WITH, TokenType.L_PAREN, advance = false)) {
            raiseError("Table hints are not supported yet")
            return null
        }
        // https://dev.mysql.com/doc/refman/8.0/en/index-hints.html
        while (matchSet(tableIndexHintTokens)) {
            val hint = expression(IndexTableHint(args("this" to prevToken.text.uppercase())))

            matchSet(setOf(TokenType.INDEX, TokenType.KEY))
            if (match(TokenType.FOR)) {
                hint.set("target", if (advanceAny() != null) prevToken.text.uppercase() else null)
            }

            hint.set("expressions", parseWrappedIdVars())
            hints.add(hint)
        }

        return hints.ifEmpty { null }
    }

    // sqlglot: Parser._parse_changes — exp.Changes not ported (Snowflake CHANGES clause).
    protected fun parseChanges(): Expression? {
        if (matchTextSeq("CHANGES", "(", "INFORMATION", "=>", advance = false)) {
            return raiseError("CHANGES(...) is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_historical_data — exp.HistoricalData not ported (Snowflake
    // AT/BEFORE); the retreat keeps AT/BEFORE usable as identifiers, like Python.
    protected fun parseHistoricalData(): Expression? {
        // https://docs.snowflake.com/en/sql-reference/constructs/at-before
        val startIndex = index
        if (matchTexts(historicalDataPrefix)) {
            val this_ = prevToken.text.uppercase()
            val kind = if (match(TokenType.L_PAREN) && matchTexts(historicalDataKind)) {
                prevToken.text.uppercase()
            } else null
            val expr = if (kind != null && match(TokenType.FARROW)) parseBitwise() else null

            if (expr != null) {
                matchRParen()
                return expression(
                    HistoricalData(args("this" to this_, "kind" to kind, "expression" to expr))
                )
            }
            retreat(startIndex)
        }
        return null
    }

    // sqlglot: Parser._parse_pivots
    protected fun parsePivots(): MutableList<Expression>? {
        if (currToken.tokenType != TokenType.PIVOT && currToken.tokenType != TokenType.UNPIVOT) {
            return null
        }
        val pivots = mutableListOf<Expression>()
        while (true) {
            val pivot = parsePivot() ?: break
            pivots.add(pivot)
        }
        return pivots.ifEmpty { null }
    }

    // sqlglot: parser._unpivot_target
    private fun unpivotTarget(expr: Expression): Expression {
        // UNPIVOT's pre-FOR values and FOR field are new output names, not column references.
        if (expr is Column && expr.text("table").isEmpty()) return expr.thisArg as Expression
        if (expr is Tuple) {
            expr.set("expressions", expr.expressionsArg.map { unpivotTarget(it as Expression) })
        }
        return expr
    }

    // sqlglot: Parser._parse_pivot_in
    protected fun parsePivotIn(): Expression {
        // sqlglot: local _parse_aliased_expression
        fun parseAliasedExpression(): Expression? {
            val this_ = parseSelectOrExpression()

            match(TokenType.ALIAS)
            var alias = parseBitwise()
            if (alias != null) {
                if (alias is Column && alias.text("db").isEmpty()) {
                    alias = alias.thisArg as Expression
                }
                return expression(PivotAlias(args("this" to this_, "alias" to alias)))
            }

            return this_
        }

        val value = parseColumn()

        if (!match(TokenType.IN)) {
            raiseError("Expecting IN")
        }

        if (match(TokenType.L_PAREN)) {
            val exprs: MutableList<Expression> = if (match(TokenType.ANY)) {
                mutableListOf(expression(PivotAny(args("this" to parseOrder()))))
            } else {
                parseCsv { parseAliasedExpression() }
            }
            matchRParen()
            return expression(In(args("this" to value, "expressions" to exprs)))
        }

        return expression(In(args("this" to value, "field" to parseIdVar())))
    }

    // sqlglot: Parser._parse_pivot_aggregation
    protected fun parsePivotAggregation(): Expression? {
        val func = parseFunction()
        if (func == null) {
            if (prevToken.tokenType == TokenType.COMMA) return null
            raiseError("Expecting an aggregation function in PIVOT")
        }

        return parseAlias(func)
    }

    // sqlglot: Parser._parse_pivot
    protected fun parsePivot(): Expression? {
        val startIndex = index
        var includeNulls: kotlin.Boolean? = null

        val unpivot: kotlin.Boolean
        if (match(TokenType.PIVOT)) {
            unpivot = false
        } else if (match(TokenType.UNPIVOT)) {
            unpivot = true

            // https://docs.databricks.com/en/sql/language-manual/sql-ref-syntax-qry-select-unpivot.html#syntax
            if (matchTextSeq("INCLUDE", "NULLS")) {
                includeNulls = true
            } else if (matchTextSeq("EXCLUDE", "NULLS")) {
                includeNulls = false
            }
        } else {
            return null
        }

        if (!match(TokenType.L_PAREN)) {
            retreat(startIndex)
            return null
        }

        val expressions: MutableList<Expression> = if (unpivot) {
            parseCsv { parseColumn() }
        } else {
            parseCsv { parsePivotAggregation() }
        }

        if (expressions.isEmpty()) {
            raiseError("Failed to parse PIVOT's aggregation list")
        }

        if (!match(TokenType.FOR)) {
            raiseError("Expecting FOR")
        }

        val fields = mutableListOf<Expression>()
        while (true) {
            val field = tryParse({ parsePivotIn() }) ?: break
            fields.add(field)
        }

        val defaultOnNull: kotlin.Any? = if (matchTextSeq("DEFAULT", "ON", "NULL")) {
            parseWrapped({ parseBitwise() })
        } else {
            false
        }

        val group = parseGroup()

        matchRParen()

        val pivot = expression(
            Pivot(
                args(
                    "expressions" to expressions,
                    "fields" to fields,
                    "unpivot" to unpivot,
                    "include_nulls" to includeNulls,
                    "default_on_null" to defaultOnNull,
                    "group" to group,
                )
            )
        )

        if (unpivot) {
            pivot.set("expressions", pivot.expressionsArg.map { unpivotTarget(it as Expression) })
            for (pivotField in (pivot.args["fields"] as? List<*>).orEmpty()) {
                if (pivotField is In) {
                    pivotField.set("this", unpivotTarget(pivotField.thisArg as Expression))
                }
            }
        }

        if (!matchSet(setOf(TokenType.PIVOT, TokenType.UNPIVOT), advance = false)) {
            pivot.set("alias", parseTableAlias())
        }

        if (!unpivot) {
            val names = pivotColumnNames(expressions)

            val columns = mutableListOf<Expression>()
            val allFields = mutableListOf<List<String>>()
            for (pivotField in (pivot.args["fields"] as? List<*>).orEmpty()) {
                val pivotFieldExpressions = (pivotField as Expression).expressionsArg

                // The `PivotAny` expression corresponds to `ANY ORDER BY <column>`; we can't infer in this case.
                if (pivotFieldExpressions.firstOrNull() is PivotAny) continue

                allFields.add(
                    pivotFieldExpressions.map { fld ->
                        fld as Expression
                        // sqlglot: fld.sql() if IDENTIFY_PIVOT_STRINGS else fld.alias_or_name
                        // (base: IDENTIFY_PIVOT_STRINGS=false)
                        fld.aliasOrName
                    }
                )
            }

            if (allFields.isNotEmpty()) {
                if (names.isNotEmpty()) allFields.add(names)

                // Generate all possible combinations of the pivot columns
                // e.g PIVOT(sum(...) as total FOR year IN (2000, 2010) FOR country IN ('NL', 'US'))
                // generates the product between [[2000, 2010], ['NL', 'US'], ['total']]
                for (fldPartsTuple in cartesianProduct(allFields)) {
                    val fldParts = fldPartsTuple.toMutableList()

                    if (names.isNotEmpty() && prefixedPivotColumns) {
                        // Move the "name" to the front of the list
                        fldParts.add(0, fldParts.removeAt(fldParts.size - 1))
                    }

                    // sqlglot: exp.to_identifier("_".join(fld_parts))
                    columns.add(toIdentifier(fldParts.joinToString("_")))
                }
            }

            pivot.set("columns", columns)
            pivot.set("identify_pivot_strings", identifyPivotStrings)
            pivot.set("prefixed_pivot_columns", prefixedPivotColumns)
            pivot.set("pivot_column_naming", pivotColumnNaming)
        }

        return pivot
    }

    // sqlglot: Parser._parse_unpivot_columns
    protected fun parseUnpivotColumns(): Expression? {
        if (!match(TokenType.INTO)) return null

        return expression(
            UnpivotColumns(
                args(
                    "this" to if (matchTextSeq("NAME")) parseColumn() else null,
                    "expressions" to
                        if (matchTextSeq("VALUE")) parseCsv { parseColumn() } else null,
                )
            )
        )
    }

    // sqlglot: Parser._parse_simplified_pivot (https://duckdb.org/docs/sql/statements/pivot)
    fun parseSimplifiedPivot(isUnpivot: kotlin.Boolean? = null): Expression {
        fun parseOn(): Expression? {
            val this_ = parseBitwise()

            if (match(TokenType.IN)) {
                // PIVOT ... ON col IN (row_val1, row_val2)
                return parseIn(this_)
            }
            if (match(TokenType.ALIAS, advance = false)) {
                // UNPIVOT ... ON (col1, col2, col3) AS row_val
                return parseAlias(this_)
            }

            return this_
        }

        val this_ = parseTable()
        // sqlglot: `self._match(...) and self._parse_csv(...)` — False when unmatched
        val expressions: kotlin.Any = if (match(TokenType.ON)) parseCsv { parseOn() } else false
        val into = parseUnpivotColumns()
        val using: kotlin.Any = if (match(TokenType.USING)) {
            parseCsv { parseAlias(parseColumn()) }
        } else false
        val group = parseGroup()

        return expression(
            Pivot(
                args(
                    "this" to this_,
                    "expressions" to expressions,
                    "using" to using,
                    "group" to group,
                    "unpivot" to isUnpivot,
                    "into" to into,
                )
            )
        )
    }

    // sqlglot: Parser._pivot_column_names
    protected open fun pivotColumnNames(aggregations: List<Expression>): List<String> =
        aggregations.map { it.alias }.filter { it.isNotEmpty() }

    // itertools.product over a list of string lists (for _parse_pivot's column inference)
    private fun cartesianProduct(lists: List<List<String>>): List<List<String>> {
        var acc = listOf(listOf<String>())
        for (list in lists) {
            acc = acc.flatMap { prefix -> list.map { prefix + it } }
        }
        return acc
    }

    // -----------------------------------------------------------------------
    // Clause layer (sqlglot: _parse_where .. _parse_offset, set operations)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_where
    fun parseWhere(skipWhereToken: kotlin.Boolean = false): Expression? {
        if (!skipWhereToken && !match(TokenType.WHERE)) return null

        val comments = prevComments
        return expression(
            Where(args("this" to parseDisjunction())),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_group
    fun parseGroup(skipGroupByToken: kotlin.Boolean = false): Expression? {
        if (!skipGroupByToken && !match(TokenType.GROUP_BY)) return null
        val comments = prevComments

        // sqlglot: elements = defaultdict(list) — keys appear in first-touch order
        val elements = LinkedHashMap<String, kotlin.Any?>()

        if (match(TokenType.ALL)) {
            elements["all"] = true
        } else if (match(TokenType.DISTINCT)) {
            elements["all"] = false
        }

        if (matchSet(queryModifierTokens, advance = false)) {
            return expression(Group(elements), comments = comments)
        }

        val expressions = mutableListOf<Expression>()

        while (true) {
            val iterationIndex = index

            elements["expressions"] = expressions
            expressions.addAll(
                parseCsv {
                    if (matchSet(setOf(TokenType.CUBE, TokenType.ROLLUP), advance = false)) {
                        null
                    } else {
                        parseDisjunction()
                    }
                }
            )

            val beforeWithIndex = index
            val withPrefix = match(TokenType.WITH)

            val cubeOrRollup = parseCubeOrRollup(withPrefix = withPrefix)
            if (cubeOrRollup != null) {
                val key = if (cubeOrRollup is Rollup) "rollup" else "cube"
                @Suppress("UNCHECKED_CAST")
                val list = elements.getOrPut(key) { mutableListOf<Expression>() } as MutableList<Expression>
                list.add(cubeOrRollup)
            } else {
                val groupingSets = parseGroupingSets()
                if (groupingSets != null) {
                    @Suppress("UNCHECKED_CAST")
                    val list = elements.getOrPut("grouping_sets") { mutableListOf<Expression>() } as MutableList<Expression>
                    list.add(groupingSets)
                } else if (matchTextSeq("TOTALS")) {
                    elements["totals"] = true
                }
            }

            if (index in beforeWithIndex..(beforeWithIndex + 1)) {
                retreat(beforeWithIndex)
                break
            }

            if (iterationIndex == index) break
        }

        return expression(Group(elements), comments = comments)
    }

    // sqlglot: Parser._parse_cube_or_rollup
    protected fun parseCubeOrRollup(withPrefix: kotlin.Boolean = false): Expression? {
        val factory: NodeFactory = when {
            match(TokenType.CUBE) -> { a -> Cube(a) }
            match(TokenType.ROLLUP) -> { a -> Rollup(a) }
            else -> return null
        }

        return expression(
            factory(
                args(
                    "expressions" to if (withPrefix) emptyList() else parseWrappedCsv({ parseBitwise() }),
                )
            )
        )
    }

    // sqlglot: Parser._parse_grouping_sets
    protected fun parseGroupingSets(): Expression? {
        if (match(TokenType.GROUPING_SETS)) {
            return expression(
                GroupingSets(args("expressions" to parseWrappedCsv({ parseGroupingSet() })))
            )
        }
        return null
    }

    // sqlglot: Parser._parse_grouping_set
    protected fun parseGroupingSet(): Expression? =
        parseGroupingSets() ?: parseCubeOrRollup() ?: parseBitwise()

    // sqlglot: Parser._parse_having
    fun parseHaving(skipHavingToken: kotlin.Boolean = false): Expression? {
        if (!skipHavingToken && !match(TokenType.HAVING)) return null
        val comments = prevComments
        return expression(
            Having(args("this" to parseDisjunction())),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_order
    fun parseOrder(this_: Expression? = null, skipOrderToken: kotlin.Boolean = false): Expression? {
        var siblings: kotlin.Boolean? = null
        if (!skipOrderToken && !match(TokenType.ORDER_BY)) {
            if (!match(TokenType.ORDER_SIBLINGS_BY)) return this_

            siblings = true
        }

        val comments = prevComments
        return expression(
            Order(
                args(
                    "this" to this_,
                    "expressions" to parseCsv { parseOrdered() },
                    "siblings" to siblings,
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_ordered
    fun parseOrdered(parseMethod: (() -> Expression?)? = null): Expression? {
        var this_ = if (parseMethod != null) parseMethod() else parseDisjunction()
        if (this_ == null) return null

        if (this_.name.uppercase() == "ALL" && supportsOrderByAll) {
            this_ = Var(args("this" to "ALL"))
        }

        val asc = match(TokenType.ASC)
        val desc: kotlin.Boolean? = if (match(TokenType.DESC)) true else (if (asc) false else null)

        val isNullsFirst = matchTextSeq("NULLS", "FIRST")
        val isNullsLast = matchTextSeq("NULLS", "LAST")

        var nullsFirst = isNullsFirst
        val explicitlyNullOrdered = isNullsFirst || isNullsLast

        if (!explicitlyNullOrdered &&
            ((desc != true && nullOrdering == "nulls_are_small") ||
                (desc == true && nullOrdering != "nulls_are_small")) &&
            nullOrdering != "nulls_are_last"
        ) {
            nullsFirst = true
        }

        if (matchTextSeq("WITH", "FILL")) {
            // sqlglot: exp.WithFill — not ported.
            return raiseError("WITH FILL is not supported yet")
        }

        return expression(
            Ordered(
                args("this" to this_, "desc" to desc, "nulls_first" to nullsFirst, "with_fill" to null)
            )
        )
    }

    // sqlglot: Parser._parse_limit_options — exp.LimitOptions not ported.
    protected fun parseLimitOptions(): Expression? {
        val percent = matchSet(setOf(TokenType.PERCENT, TokenType.MOD))
        val rows = matchSet(setOf(TokenType.ROW, TokenType.ROWS))
        matchTextSeq("ONLY")
        val withTies = matchTextSeq("WITH", "TIES")

        if (!(percent || rows || withTies)) return null

        return expression(
            LimitOptions(args("percent" to percent, "rows" to rows, "with_ties" to withTies))
        )
    }

    // sqlglot: Parser._parse_limit
    fun parseLimit(
        this_: Expression? = null,
        top: kotlin.Boolean = false,
        skipLimitToken: kotlin.Boolean = false,
    ): Expression? {
        if (skipLimitToken || match(if (top) TokenType.TOP else TokenType.LIMIT)) {
            val comments = prevComments
            var expr: Expression?
            if (top) {
                val limitParen = match(TokenType.L_PAREN)
                expr = if (limitParen) (parseTerm() ?: parseSelect()) else parseNumber()

                if (limitParen) matchRParen()
            } else {
                if (supportsLimitAll && match(TokenType.ALL)) return this_

                // Parsing LIMIT x% (i.e x PERCENT) as a term leads to an error, since
                // we try to build an exp.Mod expr. For that matter, we backtrack and instead
                // consume the factor plus parse the percentage separately
                val startIndex = index
                expr = tryParse({ parseTerm() })
                if (expr is Mod) {
                    retreat(startIndex)
                    expr = parseFactor()
                } else if (expr == null) {
                    expr = parseFactor()
                }
            }
            val limitOptions = parseLimitOptions()

            var offset: Expression? = null
            if (match(TokenType.COMMA)) {
                offset = expr
                expr = parseTerm()
            }

            val limitExp = expression(
                Limit(
                    args(
                        "this" to this_,
                        "expression" to expr,
                        "offset" to offset,
                        "limit_options" to limitOptions,
                        "expressions" to parseLimitBy(),
                    )
                ),
                comments = comments,
            )

            return limitExp
        }

        if (match(TokenType.FETCH)) {
            val direction = if (matchSet(setOf(TokenType.FIRST, TokenType.NEXT))) {
                prevToken.text.uppercase()
            } else {
                "FIRST"
            }

            val count = parseField(tokens = fetchTokens)

            return expression(
                Fetch(
                    args(
                        "direction" to direction,
                        "count" to count,
                        "limit_options" to parseLimitOptions(),
                    )
                )
            )
        }

        return this_
    }

    // sqlglot: Parser._parse_limit_by
    protected fun parseLimitBy(): List<Expression>? =
        if (matchTextSeq("BY")) parseCsv { parseBitwise() } else null

    // sqlglot: Parser._parse_offset
    fun parseOffset(this_: Expression? = null): Expression? {
        if (!match(TokenType.OFFSET)) return this_

        val count = parseTerm()
        matchSet(setOf(TokenType.ROW, TokenType.ROWS))

        return expression(
            Offset(args("this" to this_, "expression" to count, "expressions" to parseLimitBy()))
        )
    }

    // sqlglot: Parser._can_parse_limit_or_offset
    protected fun canParseLimitOrOffset(): kotlin.Boolean {
        if (!matchSet(ambiguousAliasTokens, advance = false)) return false

        val startIndex = index
        var result = tryParse({ parseLimit() }, retreat = true) != null ||
            tryParse({ parseOffset() }, retreat = true) != null
        retreat(startIndex)

        // MATCH_CONDITION (...) is a special construct that should not be consumed by limit/offset
        if (nextToken.tokenType == TokenType.MATCH_CONDITION) result = false

        return result
    }

    // sqlglot: Parser._can_parse_named_window
    protected fun canParseNamedWindow(): kotlin.Boolean {
        // `WINDOW` is in ID_VAR_TOKENS so it could be mistakenly consumed as an implicit alias.
        // Refuse only when the following tokens look like a named-window clause: `WINDOW <id> AS (`.
        if (!match(TokenType.WINDOW, advance = false)) return false

        val name = tokens.getOrNull(index + 1) ?: return false
        if (name.tokenType !in idVarTokens) return false

        val aliasTok = tokens.getOrNull(index + 2) ?: return false
        if (aliasTok.tokenType != TokenType.ALIAS) return false

        val body = tokens.getOrNull(index + 3) ?: return false
        return body.tokenType == TokenType.L_PAREN
    }

    // sqlglot: Parser.parse_set_operation
    fun parseSetOperation(this_: Expression?, consumePipe: kotlin.Boolean = false): Expression? {
        val start = index
        val (_, sideToken, kindToken) = parseJoinParts()

        val side = sideToken?.text
        var kind = kindToken?.text

        if (!matchSet(setOperations)) {
            retreat(start)
            return null
        }

        val tokenType = prevToken.tokenType

        val operationName: String
        val operation: NodeFactory
        when (tokenType) {
            TokenType.UNION -> {
                operationName = "Union"
                operation = { Union(it) }
            }
            TokenType.EXCEPT -> {
                operationName = "Except"
                operation = { Except(it) }
            }
            else -> {
                operationName = "Intersect"
                operation = { Intersect(it) }
            }
        }

        val comments = prevToken.comments

        val distinct: kotlin.Boolean? = when {
            match(TokenType.DISTINCT) -> true
            match(TokenType.ALL) -> false
            else -> setOpDistinctByDefault(tokenType)
                ?: raiseError("Expected DISTINCT or ALL for $operationName")
        }

        var byName = if (matchTextSeq("BY", "NAME") || matchTextSeq("STRICT", "CORRESPONDING")) {
            true
        } else {
            null
        }
        if (matchTextSeq("CORRESPONDING")) {
            byName = true
            if (side == null && kind == null) kind = "INNER"
        }

        var onColumnList: MutableList<Expression>? = null
        if (byName == true && matchTexts(setOf("ON", "BY"))) {
            onColumnList = parseWrappedCsv({ parseColumn() })
        }

        val expr = parseSelect(nested = true, parseSetOperation = false, consumePipe = consumePipe)

        return expression(
            operation(
                args(
                    "this" to this_,
                    "distinct" to distinct,
                    "by_name" to byName,
                    "expression" to expr,
                    "side" to side,
                    "kind" to kind,
                    "on" to onColumnList,
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_set_operations
    fun parseSetOperations(this_: Expression?): Expression? {
        var current = this_
        while (current != null) {
            val setop = parseSetOperation(current) ?: break
            current = setop
        }

        if (current is SetOperation && modifiersAttachedToSetOp) {
            val expr = current.args["expression"] as? Expression

            if (expr != null) {
                for (arg in setOpModifiers) {
                    val modifier = expr.args[arg] as? Expression
                    if (modifier != null) {
                        current.set(arg, modifier.pop())
                    }
                }
            }
        }

        return current
    }

    // -----------------------------------------------------------------------
    // Types (sqlglot: _parse_type / _parse_types)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_type
    open fun parseType(
        parseInterval: kotlin.Boolean = true,
        fallbackToIdentifier: kotlin.Boolean = false,
    ): Expression? {
        if (!fallbackToIdentifier) {
            val atom = parseAtom()
            if (atom != null) return atom
        }

        if (parseInterval) {
            val interval = parseInterval()
            if (interval != null) return parseColumnOps(interval)
        }

        val startIndex = index
        val dataType = parseTypes(checkFunc = true, allowIdentifiers = false)

        // sqlglot: BigQuery inline-constructor Cast handling — struct types not ported.

        if (dataType != null) {
            val index2 = index
            val primary = parsePrimary()

            if (primary is Literal) {
                val literal = primary.name
                val this_ = parseColumnOps(primary)

                val literalParser = typeLiteralParsers[dataType.thisArg as? DType]
                if (literalParser != null) return literalParser(this, this_, dataType)

                // sqlglot: ZONE_AWARE_TIMESTAMP_CONSTRUCTOR (parser.py TIME_ZONE_RE)
                var toType = dataType
                if (zoneAwareTimestampConstructor &&
                    dataType.thisArg == DType.TIMESTAMP &&
                    TIME_ZONE_RE.containsMatchIn(literal)
                ) {
                    toType = expression(DataType(args("this" to DType.TIMESTAMPTZ)))
                }

                return expression(Cast(args("this" to this_, "to" to toType)))
            }

            // The expressions arg gets set by the parser when we have something like DECIMAL(38, 0)
            // in the input SQL. In that case, we'll produce these tokens: DECIMAL ( 38 , 0 )
            if (dataType.expressionsArg.isNotEmpty() && index2 - startIndex > 1) {
                retreat(index2)
                return parseColumnOps(dataType)
            }

            retreat(startIndex)
        }

        if (fallbackToIdentifier) return parseIdVar()

        return parseColumn()
    }

    // sqlglot: Parser._parse_atom
    protected fun parseAtom(): Expression? {
        if (currToken.tokenType in identifierTokens) {
            val column = parseColumn()
            if (column != null) return column
        }

        val token = currToken
        val tokenType = token.tokenType

        val primaryParser = primaryParsers[tokenType] ?: return null

        val nextType = nextToken.tokenType

        if (nextType in columnOperators ||
            nextType in columnPostfixTokens ||
            (tokenType == TokenType.STRING && nextType == TokenType.STRING)
        ) {
            return null
        }

        advance()
        return primaryParser(this, token)
    }

    // sqlglot: Parser.SUPPORTS_OMITTED_INTERVAL_SPAN_UNIT
    open val supportsOmittedIntervalSpanUnit: kotlin.Boolean get() = false

    // sqlglot: exp.INTERVAL_DAY_TIME_RE
    private val INTERVAL_DAY_TIME_RE =
        Regex("^\\s*-?\\s*\\d+(?:\\.\\d+)?\\s+(?:-?(?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?|-?(?:\\d+:){1,2}|:)\\s*")

    // sqlglot: Parser._parse_interval_span
    protected fun parseIntervalSpan(thisIn: Expression?): Expression {
        var this_ = thisIn

        // handle day-time format interval span with omitted units:
        //   INTERVAL '<number days> hh[:][mm[:ss[.ff]]]' <maybe `unit TO unit`>
        var intervalSpanUnitsOmitted: kotlin.Boolean? = null
        if (this_ != null &&
            this_.isString &&
            supportsOmittedIntervalSpanUnit &&
            INTERVAL_DAY_TIME_RE.containsMatchIn(this_.name)
        ) {
            val spanIndex = index

            // Var "TO" Var
            val firstUnit = parseVar(anyToken = true, upper = true)
            var secondUnit: Expression? = null
            if (firstUnit != null && matchTextSeq("TO")) {
                secondUnit = parseVar(anyToken = true, upper = true)
            }

            intervalSpanUnitsOmitted = !(firstUnit != null && secondUnit != null)

            retreat(spanIndex)
        }

        var unit: Expression?
        if (intervalSpanUnitsOmitted == true) {
            unit = null
        } else {
            unit = parseFunction()
            if (unit == null &&
                (currToken.tokenType == TokenType.VAR ||
                    currToken.text.uppercase() in validIntervalUnits)
            ) {
                unit = parseVar(anyToken = true, upper = true)
            }
        }

        // Most dialects support, e.g., the form INTERVAL '5' day, thus we try to parse
        // each INTERVAL expression into this canonical form so it's easy to transpile
        if (this_ != null && this_.isNumber) {
            this_ = Literal.string(literalNumberToPy(this_))
        } else if (this_ != null && this_.isString) {
            val parts = INTERVAL_STRING_RE.findAll(this_.name).toList()
            if (parts.isNotEmpty() && unit != null) {
                // Unconsume the eagerly-parsed unit, since the real unit was part of the string
                unit = null
                retreat(index - 1)
            }
            if (parts.size == 1) {
                this_ = Literal.string(parts[0].groupValues[1])
                unit = expression(Var(args("this" to parts[0].groupValues[2].uppercase())))
            }
        }

        if (intervalSpans && matchTextSeq("TO")) {
            unit = expression(
                IntervalSpan(
                    args(
                        "this" to unit,
                        "expression" to (parseFunction() ?: parseVar(anyToken = true, upper = true)),
                    )
                )
            )
        }

        return expression(Interval(args("this" to this_, "unit" to unit)))
    }

    // sqlglot: Literal.to_py (only used for number literals via _parse_interval_span;
    // Neg.to_py negates its operand)
    private fun literalNumberToPy(literal: Expression): String {
        if (literal is Neg) {
            val inner = literalNumberToPy(literal.thisArg as Expression)
            return if (inner.startsWith("-")) inner.drop(1) else "-$inner"
        }
        val text = literal.name
        return text.toLongOrNull()?.toString() ?: text
    }

    // sqlglot: Parser._parse_interval
    protected fun parseInterval(requireInterval: kotlin.Boolean = true): Expression? {
        val startIndex = index

        if (!match(TokenType.INTERVAL) && requireInterval) return null

        val this_: Expression? = if (match(TokenType.STRING, advance = false)) {
            parsePrimary()
        } else {
            parseTerm()
        }

        if (this_ == null ||
            (this_ is Column &&
                this_.table.isEmpty() &&
                (this_.args["this"] as? Identifier)?.args?.get("quoted") != true &&
                currToken.exists &&
                currToken.text.uppercase() !in validIntervalUnits)
        ) {
            retreat(startIndex)
            return null
        }

        val interval = parseIntervalSpan(this_)

        val plusIndex = index
        match(TokenType.PLUS)

        // Convert INTERVAL 'val_1' unit_1 [+] ... [+] 'val_n' unit_n into a sum of intervals
        if (matchSet(setOf(TokenType.STRING, TokenType.NUMBER), advance = false)) {
            return expression(
                Add(args("this" to interval, "expression" to parseInterval(false)))
            )
        }

        retreat(plusIndex)
        return interval
    }

    // sqlglot: Parser._parse_user_defined_type (base keeps a dotted string name)
    protected open fun parseUserDefinedType(identifier: Identifier): Expression? {
        var typeName = identifier.name
        while (match(TokenType.DOT)) {
            typeName = "$typeName.${if (advanceAny() != null) prevToken.text else ""}"
        }
        return DataType(args("this" to DType.USERDEFINED, "kind" to typeName))
    }

    // sqlglot: Parser._parse_types (TYPE_CONVERTERS={} and
    // SUPPORTS_FIXED_SIZE_ARRAYS=false in the base dialect; the MAP[K=>V] Materialize
    // branch, ClickHouse JSON type args and VECTOR expressions are ported faithfully)
    fun parseTypes(
        checkFunc: kotlin.Boolean = false,
        schema: kotlin.Boolean = false,
        allowIdentifiers: kotlin.Boolean = true,
        withCollation: kotlin.Boolean = false,
    ): Expression? {
        val startIndex = index
        var this_: Expression? = null

        var typeToken: TokenType? = null
        if (matchSet(typeTokens)) {
            typeToken = prevToken.tokenType
        } else {
            val identifier = if (allowIdentifiers) {
                parseIdVar(anyToken = false, tokens = setOf(TokenType.VAR))
            } else {
                null
            }
            if (identifier is Identifier) {
                val identTokens = try {
                    Tokenizer(tokenizerConfig).tokenize(identifier.name)
                } catch (e: Exception) {
                    null
                }

                val firstType = identTokens?.firstOrNull()?.tokenType
                if (firstType != null && firstType in typeTokens) {
                    typeToken = firstType
                    if (identTokens.size > 1) {
                        // sqlglot: exp.DataType.from_str(identifier.name) — multi-token
                        // type strings inside identifiers are not ported.
                        return raiseError("Composite type identifiers are not supported yet")
                    }
                } else if (supportsUserDefinedTypes) {
                    return parseUserDefinedType(identifier)
                } else {
                    // sqlglot: `self._retreat(self._index - 1); return None`
                    retreat(index - 1)
                    return null
                }
            } else {
                return null
            }
        }

        if (typeToken == TokenType.PSEUDO_TYPE) {
            return expression(PseudoType(args("this" to prevToken.text.uppercase())))
        }

        if (typeToken == TokenType.OBJECT_IDENTIFIER) {
            return expression(ObjectIdentifier(args("this" to prevToken.text.uppercase())))
        }

        // https://materialize.com/docs/sql/types/map/
        if (typeToken == TokenType.MAP && match(TokenType.L_BRACKET)) {
            val keyType = parseTypes(checkFunc = checkFunc, schema = schema, allowIdentifiers = allowIdentifiers)
            if (!match(TokenType.FARROW)) {
                retreat(startIndex)
                return null
            }
            val valueType = parseTypes(checkFunc = checkFunc, schema = schema, allowIdentifiers = allowIdentifiers)
            if (!match(TokenType.R_BRACKET)) {
                retreat(startIndex)
                return null
            }
            return DataType(
                args(
                    "this" to DType.MAP,
                    "expressions" to listOf(keyType, valueType),
                    "nested" to true,
                )
            )
        }

        val nested = typeToken in nestedTypeTokens
        val isStruct = typeToken in structTypeTokens
        val isAggregate = typeToken in aggregateTypeTokens
        var expressions: MutableList<Expression>? = null
        var maybeFunc = false

        if (match(TokenType.L_PAREN)) {
            if (isStruct) {
                expressions = parseCsv { parseStructTypes(typeRequired = true) }
            } else if (nested) {
                expressions = parseCsv {
                    parseTypes(checkFunc = checkFunc, schema = schema, allowIdentifiers = allowIdentifiers)
                }
                if (typeToken == TokenType.NULLABLE && expressions.size == 1) {
                    val inner = expressions[0]
                    inner.set("nullable", true)
                    matchRParen()
                    return inner
                }
            } else if (typeToken in enumTypeTokens) {
                expressions = parseCsv { parseEquality() }
            } else if (typeToken == TokenType.JSON) {
                // ClickHouse JSON type supports arguments: JSON(col Type, SKIP col, param=value)
                expressions = parseCsv { parseJsonTypeArg() }
            } else if (isAggregate) {
                val funcOrIdent = parseFunction(anonymous = true)
                    ?: parseIdVar(anyToken = false, tokens = setOf(TokenType.VAR, TokenType.ANY))
                    ?: return null
                expressions = mutableListOf(funcOrIdent)
                if (match(TokenType.COMMA)) {
                    expressions.addAll(
                        parseCsv {
                            parseTypes(checkFunc = checkFunc, schema = schema, allowIdentifiers = allowIdentifiers)
                        }
                    )
                }
            } else {
                expressions = parseCsv { parseTypeSize() }

                // https://docs.snowflake.com/en/sql-reference/data-types-vector
                if (typeToken == TokenType.VECTOR && expressions.size == 2) {
                    // sqlglot: _parse_vector_expressions — DataType.from_str over the
                    // first param name.
                    val first = expressions[0]
                    val firstDtype = DType.entries.firstOrNull { it.name == first.name.uppercase() }
                    if (firstDtype != null) {
                        expressions[0] = DataType(args("this" to firstDtype, "nested" to false))
                    }
                }
            }

            if (!match(TokenType.R_PAREN)) {
                retreat(startIndex)
                return null
            }

            maybeFunc = true
        }

        var values: MutableList<Expression>? = null

        if (nested && match(TokenType.LT)) {
            if (isStruct) {
                expressions = parseCsv { parseStructTypes(typeRequired = true) }
            } else {
                expressions = parseCsv {
                    parseTypes(
                        checkFunc = checkFunc,
                        schema = schema,
                        allowIdentifiers = allowIdentifiers,
                        withCollation = true,
                    )
                }
            }

            if (!match(TokenType.GT)) {
                raiseError("Expecting >")
            }

            if (matchSet(setOf(TokenType.L_BRACKET, TokenType.L_PAREN))) {
                values = parseCsv { parseDisjunction() }
                if (values.isEmpty() && isStruct) {
                    values = null
                    retreat(index - 1)
                } else {
                    matchSet(setOf(TokenType.R_BRACKET, TokenType.R_PAREN))
                }
            }
        }

        if (typeToken in timestamps) {
            if (matchTextSeq("WITH", "TIME", "ZONE")) {
                maybeFunc = false
                val tzType = if (typeToken in times) DType.TIMETZ else DType.TIMESTAMPTZ
                this_ = DataType(args("this" to tzType, "expressions" to expressions))
            } else if (matchTextSeq("WITH", "LOCAL", "TIME", "ZONE")) {
                maybeFunc = false
                this_ = DataType(args("this" to DType.TIMESTAMPLTZ, "expressions" to expressions))
            } else if (matchTextSeq("WITHOUT", "TIME", "ZONE")) {
                maybeFunc = false
            }
        } else if (typeToken == TokenType.INTERVAL) {
            if (currToken.text.uppercase() in validIntervalUnits) {
                var unit: Expression? = parseVar(upper = true)
                if (matchTextSeq("TO")) {
                    unit = IntervalSpan(args("this" to unit, "expression" to parseVar(upper = true)))
                }
                this_ = expression(DataType(args("this" to expression(Interval(args("unit" to unit))))))
            } else {
                this_ = expression(DataType(args("this" to DType.INTERVAL)))
            }
        } else if (typeToken == TokenType.VOID) {
            this_ = DataType(args("this" to DType.NULL))
        }

        if (maybeFunc && checkFunc) {
            val index2 = index
            val peek = parseString()

            if (peek == null) {
                retreat(startIndex)
                return null
            }

            retreat(index2)
        }

        if (this_ == null) {
            var finalTypeToken = typeToken!!
            if (matchTextSeq("UNSIGNED")) {
                val unsignedTypeToken = signedToUnsignedTypeToken[finalTypeToken]
                if (unsignedTypeToken == null) {
                    raiseError("Cannot convert ${finalTypeToken.name} to unsigned.")
                } else {
                    finalTypeToken = unsignedTypeToken
                }
            }

            // NULLABLE without parentheses can be a column (Presto/Trino)
            if (finalTypeToken == TokenType.NULLABLE && expressions == null) {
                retreat(startIndex)
                return null
            }

            val dtype = DType.entries.firstOrNull { it.name == finalTypeToken.name }
                ?: return raiseError("Unknown data type token: ${finalTypeToken.name}")

            this_ = DataType(
                args("this" to dtype, "expressions" to expressions, "nested" to nested)
            )

            // Empty arrays/structs are allowed
            if (values != null) {
                val inner: Expression = if (isStruct) {
                    Struct(args("expressions" to values))
                } else {
                    Array(args("expressions" to values))
                }
                this_ = Cast(args("this" to inner, "to" to this_))
            }
        } else if (!expressions.isNullOrEmpty()) {
            this_.set("expressions", expressions)
        }

        // https://materialize.com/docs/sql/types/list/#type-name
        while (match(TokenType.LIST)) {
            this_ = DataType(
                args("this" to DType.LIST, "expressions" to listOf(this_), "nested" to true)
            )
        }

        val arrayIndex = index

        // Postgres supports the INT ARRAY[3] syntax as a synonym for INT[3]
        var matchedArray = match(TokenType.ARRAY)

        while (currToken.exists) {
            val datatypeToken = prevToken.tokenType
            val matchedLBracket = match(TokenType.L_BRACKET)

            if ((!matchedLBracket && !matchedArray) ||
                (datatypeToken == TokenType.ARRAY && match(TokenType.R_BRACKET))
            ) {
                // Postgres allows casting empty arrays such as ARRAY[]::INT[],
                // not to be confused with the fixed size array parsing
                break
            }

            matchedArray = false
            val arrayValues = parseCsv { parseDisjunction() }.takeIf { it.isNotEmpty() }
            if (arrayValues != null && !schema &&
                // sqlglot: dialect.SUPPORTS_FIXED_SIZE_ARRAYS gate — e.g. in DuckDB
                // ARRAY[1] should retreat and be parsed into exp.Array, in contrast to
                // INT[x][y] which denotes a fixed-size array data type
                (
                    !supportsFixedSizeArrays ||
                        datatypeToken == TokenType.ARRAY ||
                        !match(TokenType.R_BRACKET, advance = false)
                    )
            ) {
                retreat(arrayIndex)
                break
            }

            this_ = DataType(
                args(
                    "this" to DType.ARRAY,
                    "expressions" to listOf(this_),
                    "values" to arrayValues,
                    "nested" to true,
                )
            )
            match(TokenType.R_BRACKET)
        }

        // sqlglot: TYPE_CONVERTERS
        if (typeConverters.isNotEmpty() && this_ is DataType) {
            val converter = typeConverters[this_.thisArg as? DType]
            if (converter != null) this_ = converter(this_)
        }

        if (withCollation && this_ is DataType && match(TokenType.COLLATE)) {
            this_.set("collate", parseIdentifier() ?: parseColumn())
        }

        return this_
    }

    // sqlglot: Parser._parse_json_type_arg (ClickHouse JSON type arguments)
    protected fun parseJsonTypeArg(): Expression? {
        // SKIP col or SKIP REGEXP 'pattern'
        if (matchTextSeq("SKIP")) {
            val regexp = match(TokenType.RLIKE)
            var arg = parseColumn()
            if (arg is Column) {
                arg = columnToDot(arg)
            }
            return expression(SkipJSONColumn(args("regexp" to regexp, "expression" to arg)))
        }

        val paramOrCol = parseColumn()
        if (paramOrCol !is Column) return null

        // Parameter: name=value (e.g., max_dynamic_paths=2)
        if (columnParts(paramOrCol).size == 1 && match(TokenType.EQ)) {
            val param = paramOrCol.name
            val value = parsePrimary()
            return expression(EQ(args("this" to Var(args("this" to param)), "expression" to value)))
        }

        // Column type hint: col_name Type
        val col = columnToDot(paramOrCol)
        val kind = parseTypes(checkFunc = false, allowIdentifiers = false)
        return expression(ColumnDef(args("this" to col, "kind" to kind)))
    }

    // sqlglot: Column.parts — the ordered catalog/db/table/this parts of a column
    protected fun columnParts(column: Column): List<Expression> =
        listOf("catalog", "db", "table", "this")
            .mapNotNull { column.args[it] as? Expression }

    // sqlglot: Column.to_dot — converts a column to a Dot chain (single-part columns
    // collapse to their identifier); include_dots appends the enclosing Dot chain
    protected fun columnToDot(column: Column, includeDots: kotlin.Boolean = true): Expression {
        val parts = columnParts(column).toMutableList()

        if (includeDots) {
            var parent = column.parent
            while (parent is Dot) {
                (parent.args["expression"] as? Expression)?.let { parts.add(it) }
                parent = parent.parent
            }
        }

        if (parts.size == 1) return parts[0].copy()
        var result: Expression = parts[0].copy()
        for (i in 1 until parts.size) {
            result = Dot(args("this" to result, "expression" to parts[i].copy()))
        }
        return result
    }

    /** sqlglot: `self._parse_table_sample(as_modifier=True)` (query modifier form). */
    fun parseTableSampleModifier(): Expression? = parseTableSample(asModifier = true)

    // sqlglot: Parser._parse_prewhere
    fun parsePrewhere(skipWhereToken: kotlin.Boolean = false): Expression? {
        if (!skipWhereToken && !match(TokenType.PREWHERE)) return null
        val comments = prevComments
        return expression(PreWhere(args("this" to parseDisjunction())), comments = comments)
    }

    // sqlglot: Parser._parse_qualify
    fun parseQualify(): Expression? {
        if (!match(TokenType.QUALIFY)) return null
        return expression(Qualify(args("this" to parseDisjunction())))
    }

    // sqlglot: Parser._parse_sort
    fun parseSort(factory: NodeFactory, token: TokenType): Expression? {
        if (!match(token)) return null
        return expression(factory(args("expressions" to parseCsv { parseOrdered() })))
    }

    /** sqlglot: NO_PAREN_FUNCTION_PARSERS["PRIOR"] toggle used by _parse_connect_with_prior. */
    protected var connectPriorEnabled: kotlin.Boolean = false

    // sqlglot: Parser._parse_connect_with_prior
    protected fun parseConnectWithPrior(): Expression? {
        connectPriorEnabled = true
        val connect = parseDisjunction()
        connectPriorEnabled = false
        return connect
    }

    // sqlglot: Parser._parse_connect
    fun parseConnect(skipStartToken: kotlin.Boolean = false): Expression? {
        var start: Expression? = if (skipStartToken) {
            null
        } else if (match(TokenType.START_WITH)) {
            parseDisjunction()
        } else {
            return null
        }

        match(TokenType.CONNECT_BY)
        val nocycle = matchTextSeq("NOCYCLE")
        val connect = parseConnectWithPrior()

        if (start == null && match(TokenType.START_WITH)) {
            start = parseDisjunction()
        }

        return expression(
            Connect(args("start" to start, "connect" to connect, "nocycle" to nocycle))
        )
    }

    // sqlglot: Parser._parse_window_clause
    fun parseWindowClause(): List<Expression>? =
        if (match(TokenType.WINDOW)) parseCsv { parseNamedWindow() } else null

    // sqlglot: Parser._parse_named_window
    fun parseNamedWindow(): Expression? = parseWindow(parseIdVar(), alias = true)

    // sqlglot: Parser._parse_exists (Python's boolean chain yields False — which is
    // serialized — rather than None when the sequence is absent)
    fun parseExists(not_: kotlin.Boolean = false): kotlin.Boolean =
        matchTextSeq("IF") && (!not_ || match(TokenType.NOT)) && match(TokenType.EXISTS)

    // sqlglot: Parser._parse_heredoc
    fun parseHeredoc(): Expression? {
        if (match(TokenType.HEREDOC_STRING)) {
            return expression(Heredoc(args("this" to prevToken.text)))
        }

        if (!matchTextSeq("$")) return null

        val tags = mutableListOf("$")
        var tagText: String? = null

        if (isConnected()) {
            advance()
            tags.add(prevToken.text.uppercase())
        } else {
            raiseError("No closing $ found")
        }

        if (tags.last() != "$") {
            if (isConnected() && matchTextSeq("$")) {
                tagText = tags.last()
                tags.add("$")
            } else {
                raiseError("No closing $ found")
            }
        }

        val heredocStart = currToken

        while (currToken.exists) {
            if (matchTextSeq(*tags.toTypedArray(), advance = false)) {
                val this_ = findSql(heredocStart, prevToken)
                advance(tags.size)
                return expression(Heredoc(args("this" to this_, "tag" to tagText)))
            }
            advance()
        }

        raiseError("No closing ${tags.joinToString("")} found")
        return null
    }

    // sqlglot: Parser._parse_user_defined_function_expression
    protected fun parseUserDefinedFunctionExpression(): Expression? = parseStatement()

    // sqlglot: Parser._parse_macro_overloads
    protected fun parseMacroOverloads(
        this_: UserDefinedFunction,
        firstBody: Expression,
        firstIsTable: kotlin.Boolean = false,
    ): Expression {
        val overloads = mutableListOf<Expression>(
            expression(
                MacroOverload(
                    args(
                        "this" to firstBody,
                        "expressions" to (this_.args["expressions"] as? List<*>)?.takeIf { it.isNotEmpty() },
                        "is_table" to firstIsTable,
                    )
                )
            )
        )
        this_.set("expressions", null)
        this_.set("wrapped", false)

        while (match(TokenType.COMMA)) {
            if (!match(TokenType.L_PAREN)) break

            val params = parseCsv { parseFunctionParameter() }
            matchRParen()

            if (!match(TokenType.ALIAS)) break

            val isTable = match(TokenType.TABLE)
            val body = parseExpression()
            overloads.add(
                expression(
                    MacroOverload(
                        args("this" to body, "expressions" to params, "is_table" to isTable)
                    )
                )
            )
        }

        return expression(MacroOverloads(args("expressions" to overloads)))
    }

    // sqlglot: Parser._parse_user_defined_function
    fun parseUserDefinedFunction(kind: TokenType? = null): Expression? {
        val this_ = parseTableParts(schema = true)

        if (!match(TokenType.L_PAREN)) return this_

        val expressions = parseCsv { parseFunctionParameter() }
        matchRParen()
        return expression(
            UserDefinedFunction(
                args("this" to this_, "expressions" to expressions, "wrapped" to true)
            )
        )
    }

    // sqlglot: Parser._parse_function_properties
    open fun parseFunctionProperties(): Expression? {
        // Skip the generic `key = value` fallback in _parse_property since this
        // runs post-AS where a function body like `name = expr` can be misread
        // as a property.
        val properties = mutableListOf<Expression>()
        while (true) {
            val prop: kotlin.Any? = if (matchTexts(propertyParsers.keys)) {
                propertyParsers.getValue(prevToken.text.uppercase())(this, PropertyKwargs())
            } else if (match(TokenType.DEFAULT) && matchTexts(propertyParsers.keys)) {
                propertyParsers.getValue(prevToken.text.uppercase())(this, PropertyKwargs(default = true))
            } else {
                break
            }
            when (prop) {
                is Expression -> properties.add(prop)
                is kotlin.collections.List<*> -> properties.addAll(prop.filterIsInstance<Expression>())
            }
        }

        return if (properties.isNotEmpty()) {
            expression(Properties(args("expressions" to properties)))
        } else {
            null
        }
    }

    // sqlglot: Parser._parse_ddl_select
    fun parseDdlSelect(): Expression? =
        parseQueryModifiers(
            parseSetOperations(parseSelect(nested = true, parseSubqueryAlias = false))
        )

    // sqlglot: Parser._parse_index
    fun parseIndex(indexIn: Expression? = null, anonymous: kotlin.Boolean = false): Expression? {
        var index_ = indexIn
        val unique: kotlin.Boolean?
        val primary: kotlin.Boolean?
        val amp: kotlin.Boolean?
        val table: Expression?

        if (index_ != null || anonymous) {
            unique = null
            primary = null
            amp = null

            match(TokenType.ON)
            match(TokenType.TABLE) // hive
            table = parseTableParts(schema = true)
        } else {
            unique = match(TokenType.UNIQUE)
            primary = matchTextSeq("PRIMARY")
            amp = matchTextSeq("AMP")

            if (!match(TokenType.INDEX)) return null

            index_ = parseIdVar()
            table = null
        }

        val params = parseIndexParams()

        return expression(
            Index(
                args(
                    "this" to index_,
                    "table" to table,
                    "unique" to unique,
                    "primary" to primary,
                    "amp" to amp,
                    "params" to params,
                )
            )
        )
    }

    // sqlglot: Parser._parse_trigger_events
    protected fun parseTriggerEvents(): List<Expression> {
        val events = mutableListOf<Expression>()

        while (true) {
            val eventType = if (matchSet(triggerEvents)) prevToken.text.uppercase() else null
            if (eventType == null) {
                raiseError("Expected trigger event (INSERT, UPDATE, DELETE, TRUNCATE)")
                break
            }

            val columns = if (eventType == "UPDATE" && matchTextSeq("OF")) {
                parseCsv { parseColumn() }
            } else {
                null
            }

            events.add(expression(TriggerEvent(args("this" to eventType, "columns" to columns))))

            if (!match(TokenType.OR)) break
        }

        return events
    }

    // sqlglot: Parser._parse_trigger_deferrable
    protected fun parseTriggerDeferrable(): Pair<String?, String?> {
        val deferrableVar = parseVarFromOptions(triggerDeferrable, raiseUnmatched = false)
        val deferrable = deferrableVar?.name

        var initially: String? = null
        if (deferrable != null && matchTextSeq("INITIALLY")) {
            initially = if (matchTexts(setOf("IMMEDIATE", "DEFERRED"))) {
                prevToken.text.uppercase()
            } else {
                null
            }
        }

        return deferrable to initially
    }

    // sqlglot: Parser._parse_trigger_referencing_clause
    protected fun parseTriggerReferencingClause(keyword: String): Expression? {
        if (!matchTextSeq(keyword)) return null
        if (!matchTextSeq("TABLE")) {
            raiseError("Expected TABLE after $keyword in REFERENCING clause")
        }
        matchTextSeq("AS")
        return parseIdVar()
    }

    // sqlglot: Parser._parse_trigger_referencing
    protected fun parseTriggerReferencing(): Expression? {
        if (!matchTextSeq("REFERENCING")) return null

        var oldAlias: Expression? = null
        var newAlias: Expression? = null

        while (true) {
            val old = parseTriggerReferencingClause("OLD")
            if (old != null) {
                if (oldAlias != null) raiseError("Duplicate OLD clause in REFERENCING")
                oldAlias = old
                continue
            }
            val new = parseTriggerReferencingClause("NEW")
            if (new != null) {
                if (newAlias != null) raiseError("Duplicate NEW clause in REFERENCING")
                newAlias = new
                continue
            }
            break
        }

        if (oldAlias == null && newAlias == null) {
            raiseError("REFERENCING clause requires at least OLD TABLE or NEW TABLE")
        }

        return expression(TriggerReferencing(args("old" to oldAlias, "new" to newAlias)))
    }

    // sqlglot: Parser._parse_trigger_for_each
    protected fun parseTriggerForEach(): String? {
        if (!matchTextSeq("FOR", "EACH")) return null

        return if (matchTexts(setOf("ROW", "STATEMENT"))) prevToken.text.uppercase() else null
    }

    // sqlglot: Parser._parse_trigger_execute
    protected fun parseTriggerExecute(): Expression? {
        if (!match(TokenType.EXECUTE)) return null

        if (!matchSet(setOf(TokenType.FUNCTION, TokenType.PROCEDURE))) {
            raiseError("Expected FUNCTION or PROCEDURE after EXECUTE")
        }

        val funcCall = parseColumn()
        return expression(TriggerExecute(args("this" to funcCall)))
    }

    // sqlglot: Parser._parse_create (macro-overload branch gated:
    // no base-corpus coverage; CREATABLE_KIND_MAPPING={} in the base dialect)
    fun parseCreate(): Expression {
        // Note: this can't be None because we've matched a statement parser
        val start = prevToken

        val replace = start.tokenType == TokenType.REPLACE ||
            matchPair(TokenType.OR, TokenType.REPLACE) ||
            matchPair(TokenType.OR, TokenType.ALTER)
        val refresh = matchPair(TokenType.OR, TokenType.REFRESH)

        val unique = match(TokenType.UNIQUE)

        val clustered: kotlin.Boolean? = if (matchTextSeq("CLUSTERED", "COLUMNSTORE")) {
            true
        } else if (matchTextSeq("NONCLUSTERED", "COLUMNSTORE") || matchTextSeq("COLUMNSTORE")) {
            false
        } else {
            null
        }

        if (matchPair(TokenType.TABLE, TokenType.FUNCTION, advance = false)) {
            advance()
        }

        var properties: Expression? = null
        var createToken: Token? = if (matchSet(creatables)) prevToken else null

        if (createToken == null) {
            // exp.Properties.Location.POST_CREATE
            properties = parseProperties()
            createToken = if (matchSet(creatables)) prevToken else null

            if (properties == null || createToken == null) {
                return parseAsCommand(start)
            }
        }

        val createTokenType = createToken.tokenType

        val concurrently = matchTextSeq("CONCURRENTLY")
        val exists = parseExists(not_ = true)
        var this_: Expression? = null
        var expressionArg: Expression? = null
        var indexes: MutableList<Expression>? = null
        var noSchemaBinding: kotlin.Boolean? = null
        var begin: kotlin.Boolean? = null
        var clone: Expression? = null

        fun extendProps(tempProps: Expression?) {
            if (properties != null && tempProps != null) {
                for (e in tempProps.expressionsArg) properties!!.append("expressions", e)
            } else if (tempProps != null) {
                properties = tempProps
            }
        }

        if (createTokenType == TokenType.FUNCTION || createTokenType == TokenType.PROCEDURE) {
            this_ = parseUserDefinedFunction(kind = createTokenType)

            // exp.Properties.Location.POST_SCHEMA
            extendProps(parseProperties())

            expressionArg = if (match(TokenType.ALIAS)) parseHeredoc() else null

            var isTable = false
            var overloadMode = false
            if (expressionArg == null &&
                createTokenType == TokenType.FUNCTION &&
                this_ is UserDefinedFunction &&
                this_.args["wrapped"] == true
            ) {
                val preTableIndex = index
                isTable = match(TokenType.TABLE)

                expressionArg = parseExpression()
                overloadMode = expressionArg != null &&
                    currToken.tokenType == TokenType.COMMA &&
                    nextToken.tokenType == TokenType.L_PAREN
                if (!overloadMode) {
                    retreat(preTableIndex)
                    isTable = false
                    expressionArg = null
                }
            }

            extendProps(parseFunctionProperties())

            if (expressionArg == null) {
                if (match(TokenType.COMMAND)) {
                    expressionArg = parseAsCommand(prevToken)
                } else {
                    begin = match(TokenType.BEGIN)
                    val return_ = matchTextSeq("RETURN")

                    if (match(TokenType.STRING, advance = false)) {
                        // BigQuery's JavaScript UDF definitions that end in an OPTIONS property
                        expressionArg = parseString()
                        extendProps(parseProperties())
                    } else {
                        expressionArg = if (createTokenType == TokenType.FUNCTION) {
                            parseUserDefinedFunctionExpression()
                        } else {
                            // sqlglot: Parser._parse_block — exp.Block not ported.
                            raiseError("CREATE PROCEDURE bodies are not supported yet")
                return parseAsCommand(start)
                        }
                    }

                    if (return_) {
                        expressionArg = expression(Return(args("this" to expressionArg)))
                    }
                }
            }

            if (overloadMode && expressionArg != null) {
                expressionArg = parseMacroOverloads(
                    this_ as UserDefinedFunction, expressionArg!!, isTable
                )
            }
        } else if (createTokenType == TokenType.INDEX) {
            // Postgres allows anonymous indexes, eg. CREATE INDEX IF NOT EXISTS ON t(c)
            if (!match(TokenType.ON)) {
                this_ = parseIndex(indexIn = parseIdVar(), anonymous = false)
            } else {
                this_ = parseIndex(indexIn = null, anonymous = true)
            }
        } else if ((createTokenType == TokenType.CONSTRAINT && match(TokenType.TRIGGER)) ||
            createTokenType == TokenType.TRIGGER
        ) {
            val isConstraint = createTokenType == TokenType.CONSTRAINT
            if (isConstraint) {
                createToken = prevToken
            }

            val triggerName = parseIdVar() ?: return parseAsCommand(start)

            val timingVar = parseVarFromOptions(triggerTiming, raiseUnmatched = false)
            val timing = timingVar?.name ?: return parseAsCommand(start)

            val events = parseTriggerEvents()
            if (!match(TokenType.ON)) {
                raiseError("Expected ON in trigger definition")
            }

            val table = parseTableParts()
            val referencedTable = if (match(TokenType.FROM)) parseTableParts() else null
            val (deferrable, initially) = parseTriggerDeferrable()
            val referencing = parseTriggerReferencing()
            val forEach = parseTriggerForEach()
            val when_ = if (matchTextSeq("WHEN")) {
                parseWrapped({ parseDisjunction() }, optional = true)
            } else {
                false
            }
            val execute = parseTriggerExecute() ?: return parseAsCommand(start)

            val triggerProps = expression(
                TriggerProperties(
                    args(
                        "table" to table,
                        "timing" to timing,
                        "events" to events,
                        "execute" to execute,
                        "constraint" to isConstraint,
                        "referenced_table" to referencedTable,
                        "deferrable" to deferrable,
                        "initially" to initially,
                        "referencing" to referencing,
                        "for_each" to forEach,
                        "when" to when_,
                    )
                )
            )

            this_ = triggerName
            extendProps(expression(Properties(args("expressions" to listOf(triggerProps)))))
        } else if (createTokenType == TokenType.TYPE) {
            this_ = parseTableParts(schema = true)
            if (this_ == null || !match(TokenType.ALIAS)) {
                return parseAsCommand(start)
            }

            if (match(TokenType.ENUM)) {
                expressionArg = DataType(
                    args(
                        "this" to DType.ENUM,
                        "expressions" to parseWrappedCsv({ parseString() }),
                    )
                )
            } else if (match(TokenType.L_PAREN, advance = false)) {
                expressionArg = parseSchema()
            } else {
                return parseAsCommand(start)
            }
        } else if (createTokenType in dbCreatables) {
            val tableParts = parseTableParts(
                schema = true,
                isDbReference = createTokenType == TokenType.SCHEMA,
            )

            // exp.Properties.Location.POST_NAME
            match(TokenType.COMMA)
            extendProps(parseProperties(before = true))

            this_ = parseSchema(this_ = tableParts)

            // exp.Properties.Location.POST_SCHEMA and POST_WITH
            extendProps(parseProperties())

            val hasAlias = match(TokenType.ALIAS)
            if (!matchSet(ddlSelectTokens, advance = false)) {
                // exp.Properties.Location.POST_ALIAS
                extendProps(parseProperties())
            }

            if (createTokenType == TokenType.SEQUENCE) {
                expressionArg = parseTypes()
                val props = parseProperties()
                if (props != null) {
                    val sequenceProps = SequenceProperties()
                    val options = mutableListOf<kotlin.Any?>()
                    for (prop in props.expressionsArg.toList()) {
                        if (prop is SequenceProperties) {
                            for ((argKey, value) in prop.args.toList()) {
                                if (argKey == "options") {
                                    options.addAll(value as? kotlin.collections.List<*> ?: emptyList<kotlin.Any?>())
                                } else {
                                    sequenceProps.set(argKey, value)
                                }
                            }
                            prop.pop()
                        }
                    }

                    if (options.isNotEmpty()) sequenceProps.set("options", options)

                    props.append("expressions", sequenceProps)
                    extendProps(props)
                }
            } else {
                expressionArg = parseDdlSelect()

                // Some dialects also support using a table as an alias instead of a SELECT.
                if (expressionArg == null && hasAlias) {
                    expressionArg = tryParse({ parseTableParts() })
                }
            }

            if (createTokenType == TokenType.TABLE) {
                // exp.Properties.Location.POST_EXPRESSION
                extendProps(parseProperties())

                indexes = mutableListOf()
                while (true) {
                    val index_ = parseIndex()

                    // exp.Properties.Location.POST_INDEX
                    extendProps(parseProperties())
                    if (index_ == null) {
                        break
                    } else {
                        match(TokenType.COMMA)
                        indexes.add(index_)
                    }
                }
            } else if (createTokenType == TokenType.VIEW) {
                if (matchTextSeq("WITH", "NO", "SCHEMA", "BINDING")) {
                    noSchemaBinding = true
                }
            } else if (createTokenType == TokenType.SINK || createTokenType == TokenType.SOURCE) {
                extendProps(parseProperties())
            }

            val shallow = matchTextSeq("SHALLOW")

            if (matchTexts(cloneKeywords)) {
                val copy = prevToken.text.lowercase() == "copy"
                clone = expression(
                    Clone(
                        args(
                            "this" to parseTable(schema = true),
                            "shallow" to shallow,
                            "copy" to copy,
                        )
                    )
                )
            }
        }

        if (currToken.exists &&
            !matchSet(setOf(TokenType.R_PAREN, TokenType.COMMA), advance = false)
        ) {
            return parseAsCommand(start)
        }

        val createKindText = createToken.text.uppercase()
        return expression(
            Create(
                args(
                    "this" to this_,
                    "kind" to createKindText,
                    "replace" to replace,
                    "refresh" to refresh,
                    "unique" to unique,
                    "expression" to expressionArg,
                    "exists" to exists,
                    "properties" to properties,
                    "indexes" to indexes,
                    "no_schema_binding" to noSchemaBinding,
                    "begin" to begin,
                    "clone" to clone,
                    "concurrently" to concurrently,
                    "clustered" to clustered,
                )
            )
        )
    }

    // sqlglot: Parser._parse_column_def_with_exists
    protected fun parseColumnDefWithExists(): Expression? {
        val start = index
        match(TokenType.COLUMN)

        val existsColumn = parseExists(not_ = true)
        val expressionArg = parseFieldDef()

        if (expressionArg !is ColumnDef) {
            retreat(start)
            return null
        }

        expressionArg.set("exists", existsColumn)

        return expressionArg
    }

    // sqlglot: Parser._parse_add_column
    protected fun parseAddColumn(): Expression? {
        if (prevToken.text.uppercase() != "ADD") return null
        return parseColumnDefWithExists()
    }

    // sqlglot: Parser._parse_drop_column
    protected fun parseDropColumn(): Expression? {
        val drop = if (match(TokenType.DROP)) parseDrop() else null
        if (drop != null && drop !is Command) {
            drop.set("kind", drop.args["kind"] ?: "COLUMN")
        }
        return drop
    }

    // sqlglot: Parser._parse_alter_drop_action
    protected open fun parseAlterDropAction(): Expression? = parseDropColumn()

    // sqlglot: Parser._parse_drop_partition
    fun parseDropPartition(exists: kotlin.Boolean? = null): Expression =
        expression(
            DropPartition(
                args("expressions" to parseCsv { parsePartition() }, "exists" to exists)
            )
        )

    // sqlglot: Parser._parse_alter_table_add
    // (ALTER_TABLE_ADD_REQUIRED_FOR_EACH_COLUMN=true in the base dialect)
    fun parseAlterTableAdd(): List<Expression> {
        fun parseAddAlteration(): Expression? {
            matchTextSeq("ADD")
            if (matchSet(addConstraintTokens, advance = false)) {
                return expression(
                    AddConstraint(args("expressions" to parseCsv { parseConstraint() }))
                )
            }

            val columnDef = parseAddColumn()
            if (columnDef is ColumnDef) return columnDef

            val exists = parseExists(not_ = true)
            if (matchPair(TokenType.PARTITION, TokenType.L_PAREN, advance = false)) {
                return expression(
                    AddPartition(
                        args(
                            "exists" to exists,
                            "this" to parseField(anyToken = true),
                            "location" to (if (matchTextSeq("LOCATION", advance = false)) {
                                parseProperty()
                            } else false),
                        )
                    )
                )
            }

            return null
        }

        if (!matchSet(addConstraintTokens, advance = false) && matchTextSeq("COLUMNS")) {
            val schema = parseSchema()
            return if (schema != null) {
                listOf(schema)
            } else {
                parseCsv { parseColumnDefWithExists() }
            }
        }

        return parseCsv { parseAddAlteration() }
    }

    // sqlglot: Parser._parse_alter_table_alter
    fun parseAlterTableAlter(): Expression? {
        if (matchTexts(alterAlterParsers.keys)) {
            return alterAlterParsers.getValue(prevToken.text.uppercase())(this)
        }

        // Many dialects support the ALTER [COLUMN] syntax, so if there is no
        // keyword after ALTER we default to parsing this statement
        match(TokenType.COLUMN)
        val column = parseField(anyToken = true)

        if (matchPair(TokenType.DROP, TokenType.DEFAULT)) {
            return expression(AlterColumn(args("this" to column, "drop" to true)))
        }
        if (matchPair(TokenType.SET, TokenType.DEFAULT)) {
            return expression(
                AlterColumn(args("this" to column, "default" to parseDisjunction()))
            )
        }
        if (match(TokenType.COMMENT)) {
            return expression(AlterColumn(args("this" to column, "comment" to parseString())))
        }
        if (matchTextSeq("DROP", "NOT", "NULL")) {
            return expression(
                AlterColumn(args("this" to column, "drop" to true, "allow_null" to true))
            )
        }
        if (matchTextSeq("SET", "NOT", "NULL")) {
            return expression(AlterColumn(args("this" to column, "allow_null" to false)))
        }

        if (matchTextSeq("SET", "VISIBLE")) {
            return expression(AlterColumn(args("this" to column, "visible" to "VISIBLE")))
        }
        if (matchTextSeq("SET", "INVISIBLE")) {
            return expression(AlterColumn(args("this" to column, "visible" to "INVISIBLE")))
        }

        matchTextSeq("SET", "DATA")
        matchTextSeq("TYPE")
        return expression(
            AlterColumn(
                args(
                    "this" to column,
                    "dtype" to parseTypes(),
                    "collate" to (if (match(TokenType.COLLATE)) parseTerm() else false),
                    "using" to (if (match(TokenType.USING)) parseDisjunction() else false),
                )
            )
        )
    }

    // sqlglot: Parser._parse_alter_diststyle
    fun parseAlterDiststyle(): Expression {
        if (matchTexts(setOf("ALL", "EVEN", "AUTO"))) {
            return expression(
                AlterDistStyle(args("this" to Var(args("this" to prevToken.text.uppercase()))))
            )
        }

        matchTextSeq("KEY", "DISTKEY")
        return expression(AlterDistStyle(args("this" to parseColumn())))
    }

    // sqlglot: Parser._parse_alter_sortkey
    fun parseAlterSortkey(compound: kotlin.Boolean? = null): Expression {
        if (compound == true) {
            matchTextSeq("SORTKEY")
        }

        if (match(TokenType.L_PAREN, advance = false)) {
            return expression(
                AlterSortKey(args("expressions" to parseWrappedIdVars(), "compound" to compound))
            )
        }

        matchTexts(setOf("AUTO", "NONE"))
        return expression(
            AlterSortKey(
                args(
                    "this" to Var(args("this" to prevToken.text.uppercase())),
                    "compound" to compound,
                )
            )
        )
    }

    // sqlglot: Parser._parse_alter_table_drop
    fun parseAlterTableDrop(): List<Expression> {
        val startIndex = index - 1

        val partitionExists = parseExists()
        if (match(TokenType.PARTITION, advance = false)) {
            return parseCsv { parseDropPartition(exists = partitionExists) }
        }

        retreat(startIndex)
        return parseCsv { parseAlterDropAction() }
    }

    // sqlglot: Parser._parse_alter_table_rename (ALTER_RENAME_REQUIRES_COLUMN=true)
    open fun parseAlterTableRename(): Expression? {
        if (match(TokenType.COLUMN)) {
            val exists = parseExists()
            val oldColumn = parseColumn()
            val to = matchTextSeq("TO")
            val newColumn = parseColumn()

            if (oldColumn == null || !to || newColumn == null) return null

            return expression(
                RenameColumn(args("this" to oldColumn, "to" to newColumn, "exists" to exists))
            )
        }

        matchTextSeq("TO")
        return expression(AlterRename(args("this" to parseTable(schema = true))))
    }

    // sqlglot: Parser._parse_wrapped_options
    fun parseWrappedOptions(): List<Expression> {
        match(TokenType.EQ)
        match(TokenType.L_PAREN)

        val opts = mutableListOf<Expression>()
        while (currToken.exists && !match(TokenType.R_PAREN)) {
            // sqlglot: FORMAT_NAME= is Snowflake/T-SQL specific (_parse_format_name)
            val option = parseProperty()
            if (option == null) {
                raiseError("Unable to parse option")
                break
            }
            when (option) {
                is Expression -> opts.add(option)
                is kotlin.collections.List<*> -> opts.addAll(option.filterIsInstance<Expression>())
            }
        }

        return opts
    }

    // sqlglot: Parser._parse_alter_table_set
    fun parseAlterTableSet(): Expression {
        val alterSet = expression(AlterSet())

        if (match(TokenType.L_PAREN, advance = false) || matchTextSeq("TABLE", "PROPERTIES")) {
            alterSet.set("expressions", parseWrappedCsv({ parseAssignment() }))
        } else if (matchTextSeq("FILESTREAM_ON", advance = false)) {
            alterSet.set("expressions", listOf(parseAssignment()))
        } else if (matchTexts(setOf("LOGGED", "UNLOGGED"))) {
            alterSet.set("option", Var(args("this" to prevToken.text.uppercase())))
        } else if (matchTextSeq("WITHOUT") && matchTexts(setOf("CLUSTER", "OIDS"))) {
            alterSet.set("option", Var(args("this" to "WITHOUT ${prevToken.text.uppercase()}")))
        } else if (matchTextSeq("LOCATION")) {
            alterSet.set("location", parseField())
        } else if (matchTextSeq("ACCESS", "METHOD")) {
            alterSet.set("access_method", parseField())
        } else if (matchTextSeq("TABLESPACE")) {
            alterSet.set("tablespace", parseField())
        } else if (matchTextSeq("FILE", "FORMAT") || matchTextSeq("FILEFORMAT")) {
            alterSet.set("file_format", listOf(parseField()))
        } else if (matchTextSeq("STAGE_FILE_FORMAT")) {
            alterSet.set("file_format", parseWrappedOptions())
        } else if (matchTextSeq("STAGE_COPY_OPTIONS")) {
            alterSet.set("copy_options", parseWrappedOptions())
        } else if (matchTextSeq("TAG") || matchTextSeq("TAGS")) {
            alterSet.set("tag", parseCsv { parseAssignment() })
        } else {
            if (matchTextSeq("SERDE")) {
                alterSet.set("serde", parseField())
            }

            val properties = parseWrapped(parseMethod = { parseProperties() }, optional = true)
            alterSet.set("expressions", listOf(properties))
        }

        return alterSet
    }

    // sqlglot: Parser._parse_alter (ALTER_TABLE_PARTITIONS=false,
    // ALTER_TABLE_SUPPORTS_CASCADE=false in the base dialect; ALTER SESSION is a
    // Snowflake-only token and unreachable here)
    fun parseAlter(): Expression {
        val start = prevToken

        val iceberg = matchTextSeq("ICEBERG")

        val alterToken = if (matchSet(alterables)) prevToken else null
        if (alterToken == null) {
            return parseAsCommand(start)
        }
        if (iceberg && alterToken.tokenType != TokenType.TABLE) {
            return parseAsCommand(start)
        }

        val exists = parseExists()
        val only = matchTextSeq("ONLY")

        val this_ = parseTable(schema = true)
        val check = matchTextSeq("WITH", "CHECK")
        val cluster = if (match(TokenType.ON)) parseOnProperty() else null

        if (nextToken.exists) advance()

        val parser = if (prevToken.exists) alterParsers[prevToken.text.uppercase()] else null
        if (parser != null) {
            val actions: List<Expression> = when (val parsed = parser(this)) {
                is Expression -> listOf(parsed)
                is kotlin.collections.List<*> -> parsed.filterIsInstance<Expression>()
                else -> emptyList()
            }
            val notValid = matchTextSeq("NOT", "VALID")
            val options = mutableListOf<Expression>()
            while (true) {
                val prop = parseProperty()
                when (prop) {
                    is Expression -> options.add(prop)
                    is kotlin.collections.List<*> -> options.addAll(prop.filterIsInstance<Expression>())
                    else -> break
                }
                if (!match(TokenType.COMMA)) break
            }

            if (!currToken.exists && actions.isNotEmpty()) {
                return expression(
                    Alter(
                        args(
                            "this" to this_,
                            "kind" to alterToken.text.uppercase(),
                            "exists" to exists,
                            "actions" to actions,
                            "only" to only,
                            "options" to options,
                            "cluster" to cluster,
                            "not_valid" to notValid,
                            "check" to check,
                            "cascade" to false,
                            "iceberg" to iceberg,
                        )
                    )
                )
            }
        }

        return parseAsCommand(start)
    }

    // sqlglot: Parser._parse_comment (statement form)
    fun parseCommentStatement(allowExists: kotlin.Boolean = true): Expression {
        val start = prevToken
        val exists = if (allowExists) parseExists() else null

        match(TokenType.ON)

        val materialized = matchTextSeq("MATERIALIZED")
        val kind = (if (matchSet(creatables)) prevToken else null)
            ?: return parseAsCommand(start)

        val this_: Expression? = when {
            kind.tokenType == TokenType.FUNCTION || kind.tokenType == TokenType.PROCEDURE ->
                parseUserDefinedFunction(kind = kind.tokenType)
            kind.tokenType == TokenType.TABLE ->
                parseTable(aliasTokens = commentTableAliasTokens)
            kind.tokenType == TokenType.COLUMN -> parseColumn()
            else -> parseTableParts(schema = true)
        }

        match(TokenType.IS)

        return expression(
            Comment(
                args(
                    "this" to this_,
                    "kind" to kind.text,
                    "expression" to parseString(),
                    "exists" to exists,
                    "materialized" to materialized,
                )
            )
        )
    }

    // sqlglot: Parser._parse_describe
    fun parseDescribe(): Expression {
        val kind = if (matchSet(creatables)) prevToken.text else null
        var style: String? = if (matchTexts(describeStyles)) prevToken.text.uppercase() else null
        if (match(TokenType.DOT)) {
            style = null
            retreat(index - 2)
        }

        val format = if (match(TokenType.FORMAT, advance = false)) parseProperty() else null

        val this_: Expression? = if (matchSet(statementParsers.keys, advance = false)) {
            parseStatement()
        } else {
            parseTable(schema = true)
        }

        val properties = parseProperties()
        val expressions = properties?.expressionsArg
        val partition = parsePartition()
        return expression(
            Describe(
                args(
                    "this" to this_,
                    "style" to style,
                    "kind" to kind,
                    "expressions" to expressions,
                    "partition" to partition,
                    "format" to format,
                    "as_json" to matchTextSeq("AS", "JSON"),
                )
            )
        )
    }

    // sqlglot: Parser.COPY_INTO_VARLEN_OPTIONS
    open val copyIntoVarlenOptions: Set<String>
        get() = setOf("FILE_FORMAT", "COPY_OPTIONS", "FORMAT_OPTIONS", "CREDENTIAL")

    // sqlglot: Dialect.COPY_PARAMS_ARE_CSV
    open val copyParamsAreCsv: kotlin.Boolean get() = true

    // sqlglot: Parser._parse_copy_parameters
    protected fun parseCopyParameters(): List<Expression> {
        val sep = if (copyParamsAreCsv) TokenType.COMMA else null

        val options = mutableListOf<Expression>()
        while (currToken.exists && !match(TokenType.R_PAREN, advance = false)) {
            val option = parseVar(anyToken = true)
            val prev = prevToken.text.uppercase()

            // Different dialects might separate options and values by white space, "=" and "AS"
            match(TokenType.EQ)
            match(TokenType.ALIAS)

            val param = expression(CopyParameter(args("this" to option)))

            if (prev in copyIntoVarlenOptions && match(TokenType.L_PAREN, advance = false)) {
                // Snowflake FILE_FORMAT case, Databricks COPY & FORMAT options
                param.set("expressions", parseWrappedOptions())
            } else if (prev == "FILE_FORMAT") {
                // T-SQL's external file format case
                param.set("expression", parseField())
            } else if (prev == "FORMAT" &&
                prevToken.tokenType == TokenType.ALIAS &&
                matchTexts(setOf("AVRO", "JSON"))
            ) {
                param.set("this", Var(args("this" to "FORMAT AS ${prevToken.text.uppercase()}")))
                param.set("expression", parseField())
            } else {
                param.set("expression", parseUnquotedField() ?: parseBracket(null))
            }

            options.add(param)

            if (sep != null) match(sep)
        }

        return options
    }

    // sqlglot: Parser._parse_credentials
    protected fun parseCredentials(): Expression? {
        val expr = expression(Credentials())

        if (matchTextSeq("STORAGE_INTEGRATION", "=")) {
            expr.set("storage", parseField())
        }
        if (matchTextSeq("CREDENTIALS")) {
            // Snowflake case: CREDENTIALS = (...), Redshift case: CREDENTIALS <string>
            val creds: kotlin.Any? = if (match(TokenType.EQ)) parseWrappedOptions() else parseField()
            expr.set("credentials", creds)
        }
        if (matchTextSeq("ENCRYPTION")) {
            expr.set("encryption", parseWrappedOptions())
        }
        if (matchTextSeq("IAM_ROLE")) {
            expr.set(
                "iam_role",
                if (match(TokenType.DEFAULT)) Var(args("this" to prevToken.text)) else parseField(),
            )
        }
        if (matchTextSeq("REGION")) {
            expr.set("region", parseField())
        }

        return expr
    }

    // sqlglot: Parser._parse_file_location
    protected open fun parseFileLocation(): Expression? = parseField()

    // sqlglot: Parser._parse_copy
    fun parseCopy(): Expression {
        val start = prevToken

        match(TokenType.INTO)

        val this_ = if (match(TokenType.L_PAREN, advance = false)) {
            parseSelect(nested = true, parseSubqueryAlias = false)
        } else {
            parseTable(schema = true)
        }

        val kind = match(TokenType.FROM) || !matchTextSeq("TO")

        var files: List<Expression?> = parseCsv { parseFileLocation() }
        if (match(TokenType.EQ, advance = false)) {
            // Backtrack one token since we've consumed the lhs of a parameter assignment here.
            // This can happen for Snowflake dialect. Instead, we'd like to parse the parameter
            // list via `_parse_wrapped(..)` below.
            advance(-1)
            files = emptyList()
        }

        val credentials = parseCredentials()

        matchTextSeq("WITH")

        val params = parseWrapped({ parseCopyParameters() }, optional = true)

        // Fallback case
        if (currToken.exists) {
            return parseAsCommand(start)
        }

        return expression(
            Copy(
                args(
                    "this" to this_,
                    "kind" to kind,
                    "credentials" to credentials,
                    "files" to files,
                    "params" to params,
                )
            )
        )
    }

    // sqlglot: Parser._parse_insert
    fun parseInsert(): Expression {
        val comments = mutableListOf<String>()
        val hint = parseHint()
        val overwrite = match(TokenType.OVERWRITE)
        val ignore = match(TokenType.IGNORE)
        val local = matchTextSeq("LOCAL")
        var alternative: String? = null
        var isFunction: kotlin.Boolean? = null

        val this_: Expression?
        if (matchTextSeq("DIRECTORY")) {
            this_ = expression(
                Directory(
                    args(
                        "this" to parseVarOrString(),
                        "local" to local,
                        "row_format" to parseRowFormat(matchRow = true),
                    )
                )
            )
        } else {
            if (matchSet(setOf(TokenType.FIRST, TokenType.ALL))) {
                // sqlglot: Parser._parse_multitable_inserts — exp.MultitableInserts not ported.
                raiseError("Multitable INSERT statements are not supported yet")
                return parseAsCommand(prevToken)
            }

            if (match(TokenType.OR)) {
                alternative = if (matchTexts(insertAlternatives)) prevToken.text else null
            }

            match(TokenType.INTO)
            prevComments?.let { comments.addAll(it) }
            match(TokenType.TABLE)
            isFunction = match(TokenType.FUNCTION)

            this_ = if (isFunction) parseFunction() else parseInsertTable()
        }

        val returning = parseReturning() // TSQL allows RETURNING before source

        return expression(
            Insert(
                args(
                    "hint" to hint,
                    "is_function" to isFunction,
                    "this" to this_,
                    "stored" to (if (matchTextSeq("STORED")) parseStored() else false),
                    "by_name" to matchTextSeq("BY", "NAME"),
                    "exists" to parseExists(),
                    "where" to (if (matchPair(TokenType.REPLACE, TokenType.WHERE)) parseDisjunction() else false),
                    "partition" to (if (match(TokenType.PARTITION_BY)) parsePartitionedBy() else false),
                    "settings" to (if (matchTextSeq("SETTINGS")) parseSettingsProperty() else false),
                    "default" to matchTextSeq("DEFAULT", "VALUES"),
                    "expression" to (parseDerivedTableValues() ?: parseDdlSelect()),
                    "conflict" to parseOnConflict(),
                    "returning" to (returning ?: parseReturning()),
                    "overwrite" to overwrite,
                    "alternative" to alternative,
                    "ignore" to ignore,
                    "source" to (if (match(TokenType.TABLE)) parseTable() else false),
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_insert_table
    protected fun parseInsertTable(): Expression? {
        val this_ = parseTable(schema = true, parsePartition = true)
        if (this_ is Table && match(TokenType.ALIAS, advance = false)) {
            this_.set("alias", parseTableAlias())
        }
        return this_
    }

    // sqlglot: Parser._parse_returning
    fun parseReturning(): Expression? {
        if (!match(TokenType.RETURNING)) return null
        return expression(
            Returning(
                args(
                    "expressions" to parseCsv { parseExpression() },
                    "into" to (if (match(TokenType.INTO)) parseTablePart() else false),
                )
            )
        )
    }

    // sqlglot: Parser._parse_kill
    fun parseKill(): Expression {
        val kind = if (matchTexts(setOf("CONNECTION", "QUERY"))) {
            Var(args("this" to prevToken.text))
        } else {
            null
        }

        return expression(Kill(args("this" to parsePrimary(), "kind" to kind)))
    }

    // sqlglot: Parser._parse_load
    fun parseLoad(): Expression {
        if (matchTextSeq("DATA")) {
            val local = matchTextSeq("LOCAL")
            matchTextSeq("INPATH")
            val inpath = parseString()
            val overwrite = match(TokenType.OVERWRITE)
            var temp: kotlin.Boolean? = null
            if (match(TokenType.INTO)) {
                temp = match(TokenType.TEMPORARY)
                match(TokenType.TABLE)
            }

            return expression(
                LoadData(
                    args(
                        "this" to parseTable(schema = true),
                        "local" to local,
                        "overwrite" to overwrite,
                        "temp" to temp,
                        "inpath" to inpath,
                        "files" to (if (matchTextSeq("FROM", "FILES")) {
                            Properties(args("expressions" to parseWrappedProperties()))
                        } else false),
                        "partition" to parsePartition(),
                        "input_format" to (if (matchTextSeq("INPUTFORMAT")) parseString() else false),
                        "serde" to (if (matchTextSeq("SERDE")) parseString() else false),
                    )
                )
            )
        }
        return parseAsCommand(prevToken)
    }

    // sqlglot: Parser._parse_delete
    fun parseDelete(): Expression {
        val hint = parseHint()

        // This handles MySQL's "Multiple-Table Syntax"
        var tables: List<Expression>? = null
        if (!match(TokenType.FROM, advance = false)) {
            tables = parseCsv { parseTable() }.takeIf { it.isNotEmpty() }
        }

        val returning = parseReturning()

        return expression(
            Delete(
                args(
                    "hint" to hint,
                    "tables" to tables,
                    "this" to (if (match(TokenType.FROM)) parseTable(joins = true) else false),
                    "using" to (if (match(TokenType.USING)) parseCsv { parseTable(joins = true) } else false),
                    "cluster" to (if (match(TokenType.ON)) parseOnProperty() else false),
                    "where" to parseWhere(),
                    "returning" to (returning ?: parseReturning()),
                    "order" to parseOrder(),
                    "limit" to parseLimit(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_update
    fun parseUpdate(): Expression {
        val hint = parseHint()
        val kwargs = args(
            "hint" to hint,
            "this" to parseTable(joins = true, aliasTokens = updateAliasTokens),
        ).toMutableMap()
        while (currToken.exists) {
            if (match(TokenType.SET)) {
                kwargs["expressions"] = parseCsv { parseEquality() }
            } else if (match(TokenType.RETURNING, advance = false)) {
                kwargs["returning"] = parseReturning()
            } else if (match(TokenType.FROM, advance = false)) {
                val from = parseFrom(joins = true)
                val table = from?.args?.get("this")
                if (table is Subquery && match(TokenType.JOIN, advance = false)) {
                    table.set("joins", parseJoins().toMutableList().takeIf { it.isNotEmpty() })
                }
                kwargs["from_"] = from
            } else if (match(TokenType.WHERE, advance = false)) {
                kwargs["where"] = parseWhere()
            } else if (match(TokenType.ORDER_BY, advance = false)) {
                kwargs["order"] = parseOrder()
            } else if (match(TokenType.LIMIT, advance = false)) {
                kwargs["limit"] = parseLimit()
            } else {
                break
            }
        }

        return expression(Update(kwargs))
    }

    // sqlglot: Parser._parse_use
    fun parseUse(): Expression =
        expression(
            Use(
                args(
                    "kind" to parseVarFromOptions(usables, raiseUnmatched = false),
                    "this" to parseTable(schema = false),
                )
            )
        )

    // sqlglot: Parser._parse_uncache
    fun parseUncache(): Expression {
        if (!match(TokenType.TABLE)) {
            raiseError("Expecting TABLE after UNCACHE")
        }

        return expression(
            Uncache(args("exists" to parseExists(), "this" to parseTable(schema = true)))
        )
    }

    // sqlglot: Parser._parse_cache
    fun parseCache(): Expression {
        val lazy = matchTextSeq("LAZY")
        match(TokenType.TABLE)
        val table = parseTable(schema = true)

        var options = listOf<Expression?>()
        if (matchTextSeq("OPTIONS")) {
            matchLParen()
            val k = parseString()
            match(TokenType.EQ)
            val v = parseString()
            options = listOf(k, v)
            matchRParen()
        }

        match(TokenType.ALIAS)
        return expression(
            Cache(
                args(
                    "this" to table,
                    "lazy" to lazy,
                    "options" to options,
                    "expression" to parseSelect(nested = true),
                )
            )
        )
    }

    // sqlglot: Parser._parse_partition
    fun parsePartition(): Expression? {
        if (!matchTexts(partitionKeywords)) return null

        return expression(
            Partition(
                args(
                    "subpartition" to (prevToken.text.uppercase() == "SUBPARTITION"),
                    "expressions" to parseWrappedCsv({ parseDisjunction() }),
                )
            )
        )
    }

    // sqlglot: Parser._parse_transaction (TRANSACTION_KIND = DEFERRED/IMMEDIATE/EXCLUSIVE)
    fun parseTransaction(): Expression {
        var this_: String? = null
        if (matchTexts(setOf("DEFERRED", "IMMEDIATE", "EXCLUSIVE"))) {
            this_ = prevToken.text
        }

        matchTexts(setOf("TRANSACTION", "WORK"))

        val modes = mutableListOf<String>()
        while (true) {
            val mode = mutableListOf<String>()
            while (match(TokenType.VAR) || match(TokenType.NOT)) {
                mode.add(prevToken.text)
            }

            if (mode.isNotEmpty()) modes.add(mode.joinToString(" "))
            if (!match(TokenType.COMMA)) break
        }

        return expression(Transaction(args("this" to this_, "modes" to modes)))
    }

    // sqlglot: Parser._parse_commit_or_rollback
    fun parseCommitOrRollback(): Expression {
        var chain: kotlin.Boolean? = null
        var savepoint: Expression? = null
        val isRollback = prevToken.tokenType == TokenType.ROLLBACK

        matchTexts(setOf("TRANSACTION", "WORK"))

        if (matchTextSeq("TO")) {
            matchTextSeq("SAVEPOINT")
            savepoint = parseIdVar()
        }

        if (match(TokenType.AND)) {
            chain = !matchTextSeq("NO")
            matchTextSeq("CHAIN")
        }

        if (isRollback) {
            return expression(Rollback(args("savepoint" to savepoint)))
        }

        return expression(Commit(args("chain" to chain)))
    }

    // sqlglot: Parser._parse_refresh
    fun parseRefresh(): Expression {
        val kind = when {
            match(TokenType.TABLE) -> "TABLE"
            matchTextSeq("MATERIALIZED", "VIEW") -> "MATERIALIZED VIEW"
            else -> ""
        }

        val this_ = parseString() ?: parseTable()
        if (kind.isEmpty() && this_ !is Literal) {
            return parseAsCommand(prevToken)
        }

        return expression(Refresh(args("this" to this_, "kind" to kind)))
    }

    // sqlglot: Parser._parse_drop
    fun parseDrop(exists: kotlin.Boolean = false): Expression {
        val start = prevToken
        val temporary = match(TokenType.TEMPORARY)
        val materialized = matchTextSeq("MATERIALIZED")
        val iceberg = matchTextSeq("ICEBERG")

        val kind = if (matchSet(creatables)) prevToken.text.uppercase() else null
        if (kind == null || (iceberg && kind != "TABLE")) {
            return parseAsCommand(start)
        }

        val concurrently = matchTextSeq("CONCURRENTLY")
        val ifExists: kotlin.Any? = if (exists) true else parseExists()

        val this_ = if (kind == "COLUMN") {
            parseColumn()
        } else {
            parseTableParts(schema = true, isDbReference = kind == "SCHEMA")
        }

        val cluster = if (match(TokenType.ON)) parseOnProperty() else null

        val expressions = if (match(TokenType.L_PAREN, advance = false)) {
            parseWrappedCsv({ parseTypes() })
        } else {
            null
        }

        val cascadeOrRestrict =
            if (matchTexts(setOf("CASCADE", "RESTRICT"))) prevToken.text.uppercase() else null

        return expression(
            Drop(
                args(
                    "exists" to ifExists,
                    "this" to this_,
                    "expressions" to expressions,
                    "kind" to kind,
                    "temporary" to temporary,
                    "materialized" to materialized,
                    "cascade" to (cascadeOrRestrict == "CASCADE"),
                    "restrict" to (cascadeOrRestrict == "RESTRICT"),
                    "constraints" to matchTextSeq("CONSTRAINTS"),
                    "purge" to matchTextSeq("PURGE"),
                    "cluster" to cluster,
                    "concurrently" to concurrently,
                    "sync" to matchTextSeq("SYNC"),
                    "iceberg" to iceberg,
                )
            )
        )
    }

    // sqlglot: Parser._parse_truncate_table
    fun parseTruncateTable(): Expression? {
        val start = prevToken

        // Not to be confused with TRUNCATE(number, decimals) function call
        if (match(TokenType.L_PAREN)) {
            retreat(index - 2)
            return parseFunction()
        }

        // Clickhouse supports TRUNCATE DATABASE as well
        val isDatabase = match(TokenType.DATABASE)

        match(TokenType.TABLE)

        val exists = parseExists(not_ = false)

        val expressions = parseCsv { parseTable(schema = true, isDbReference = isDatabase) }

        val cluster = if (match(TokenType.ON)) parseOnProperty() else null

        val identity: String? = if (matchTextSeq("RESTART", "IDENTITY")) {
            "RESTART"
        } else if (matchTextSeq("CONTINUE", "IDENTITY")) {
            "CONTINUE"
        } else {
            null
        }

        val option = if (matchTextSeq("CASCADE") || matchTextSeq("RESTRICT")) {
            prevToken.text
        } else {
            null
        }

        val partition = parsePartition()

        // Fallback case
        if (currToken.exists) {
            return parseAsCommand(start)
        }

        return expression(
            TruncateTable(
                args(
                    "expressions" to expressions,
                    "is_database" to isDatabase,
                    "exists" to exists,
                    "cluster" to cluster,
                    "identity" to identity,
                    "option" to option,
                    "partition" to partition,
                )
            )
        )
    }

    // sqlglot: Parser._parse_merge
    fun parseMerge(): Expression {
        match(TokenType.INTO)
        val target = parseTable()

        if (target != null && match(TokenType.ALIAS, advance = false)) {
            target.set("alias", parseTableAlias())
        }

        match(TokenType.USING)
        val using = parseTable()

        return expression(
            Merge(
                args(
                    "this" to target,
                    "using" to using,
                    "on" to (if (match(TokenType.ON)) parseDisjunction() else false),
                    "using_cond" to (if (match(TokenType.USING)) parseUsingIdentifiers() else false),
                    "whens" to parseWhenMatched(),
                    "returning" to parseReturning(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_when_matched
    fun parseWhenMatched(): Expression {
        val whens = mutableListOf<Expression>()

        while (match(TokenType.WHEN)) {
            val matched = !match(TokenType.NOT)
            matchTextSeq("MATCHED")
            val source: kotlin.Boolean = if (matchTextSeq("BY", "TARGET")) {
                false
            } else {
                matchTextSeq("BY", "SOURCE")
            }
            val condition = if (match(TokenType.AND)) parseDisjunction() else null

            match(TokenType.THEN)

            val then: Expression? = if (match(TokenType.INSERT)) {
                val star = parseStar()
                if (star != null) {
                    expression(Insert(args("this" to star)))
                } else {
                    expression(
                        Insert(
                            args(
                                "this" to (if (matchTextSeq("ROW")) {
                                    Var(args("this" to "ROW"))
                                } else {
                                    parseValue(values = false)
                                }),
                                "expression" to (if (matchTextSeq("VALUES")) parseValue() else false),
                                "where" to parseWhere(),
                            )
                        )
                    )
                }
            } else if (match(TokenType.UPDATE)) {
                val star = parseStar()
                if (star != null) {
                    expression(Update(args("expressions" to star)))
                } else {
                    expression(
                        Update(
                            args(
                                "expressions" to (if (match(TokenType.SET)) parseCsv { parseEquality() } else false),
                                "where" to parseWhere(),
                            )
                        )
                    )
                }
            } else if (match(TokenType.DELETE)) {
                expression(Var(args("this" to prevToken.text)))
            } else {
                parseVarFromOptions(conflictActions)
            }

            whens.add(
                expression(
                    When(
                        args(
                            "matched" to matched,
                            "source" to source,
                            "condition" to condition,
                            "then" to then,
                        )
                    )
                )
            )
        }
        return expression(Whens(args("expressions" to whens)))
    }

    // sqlglot: Parser._parse_analyze
    fun parseAnalyze(): Expression {
        val start = prevToken
        if (!currToken.exists) {
            return expression(Analyze())
        }

        val options = mutableListOf<String>()
        while (matchTexts(analyzeStyles)) {
            if (prevToken.text.uppercase() == "BUFFER_USAGE_LIMIT") {
                options.add("BUFFER_USAGE_LIMIT ${(parseNumber() as? Literal)?.name ?: ""}")
            } else {
                options.add(prevToken.text.uppercase())
            }
        }

        var this_: Expression? = null
        var innerExpression: Expression? = null

        var kind: String? = if (currToken.exists) currToken.text.uppercase() else null

        if (match(TokenType.TABLE) || match(TokenType.INDEX)) {
            this_ = parseTableParts()
        } else if (matchTextSeq("TABLES")) {
            if (matchSet(setOf(TokenType.FROM, TokenType.IN))) {
                kind = "$kind ${prevToken.text.uppercase()}"
                this_ = parseTable(schema = true, isDbReference = true)
            }
        } else if (matchTextSeq("DATABASE")) {
            this_ = parseTable(schema = true, isDbReference = true)
        } else if (matchTextSeq("CLUSTER")) {
            this_ = parseTable()
        } else if (matchTexts(analyzeExpressionParsers.keys)) {
            kind = null
            innerExpression = analyzeExpressionParsers.getValue(prevToken.text.uppercase())(this)
        } else {
            // Empty kind (Presto)
            kind = null
            this_ = parseTableParts()
        }

        val partition = tryParse({ parsePartition() })
        if (partition == null && matchTexts(partitionKeywords)) {
            return parseAsCommand(start)
        }

        val mode: String? = if (matchTextSeq("WITH", "SYNC", "MODE") ||
            matchTextSeq("WITH", "ASYNC", "MODE")
        ) {
            "WITH ${tokens[index - 2].text.uppercase()} MODE"
        } else {
            null
        }

        if (matchTexts(analyzeExpressionParsers.keys)) {
            innerExpression = analyzeExpressionParsers.getValue(prevToken.text.uppercase())(this)
        }

        val properties = parseProperties()
        return expression(
            Analyze(
                args(
                    "kind" to kind,
                    "this" to this_,
                    "mode" to mode,
                    "partition" to partition,
                    "properties" to properties,
                    "expression" to innerExpression,
                    "options" to options,
                )
            )
        )
    }

    // sqlglot: Parser._parse_analyze_statistics
    fun parseAnalyzeStatistics(): Expression {
        var this_: String? = null
        val kind = prevToken.text.uppercase()
        val option = if (matchTextSeq("DELTA")) prevToken.text.uppercase() else null
        var expressions = listOf<Expression>()

        if (!matchTextSeq("STATISTICS")) {
            raiseError("Expecting token STATISTICS")
        }

        if (matchTextSeq("NOSCAN")) {
            this_ = "NOSCAN"
        } else if (match(TokenType.FOR)) {
            if (matchTextSeq("ALL", "COLUMNS")) {
                this_ = "FOR ALL COLUMNS"
            }
            if (matchTextSeq("COLUMNS")) {
                this_ = "FOR COLUMNS"
                expressions = parseCsv { parseColumnReference() }
            }
        } else if (matchTextSeq("SAMPLE")) {
            val sample = parseNumber()
            expressions = listOf(
                expression(
                    AnalyzeSample(
                        args(
                            "sample" to sample,
                            "kind" to (if (match(TokenType.PERCENT)) prevToken.text.uppercase() else null),
                        )
                    )
                )
            )
        }

        return expression(
            AnalyzeStatistics(
                args(
                    "kind" to kind,
                    "option" to option,
                    "this" to this_,
                    "expressions" to expressions,
                )
            )
        )
    }

    // sqlglot: Parser._parse_analyze_validate (the INTO sub-clause is unsupported like
    // Parser._parse_into in the base SELECT path)
    fun parseAnalyzeValidate(): Expression {
        var kind: String? = null
        var this_: String? = null
        var expressionArg: Expression? = null
        if (matchTextSeq("REF", "UPDATE")) {
            kind = "REF"
            this_ = "UPDATE"
            if (matchTextSeq("SET", "DANGLING", "TO", "NULL")) {
                this_ = "UPDATE SET DANGLING TO NULL"
            }
        } else if (matchTextSeq("STRUCTURE")) {
            kind = "STRUCTURE"
            if (matchTextSeq("CASCADE", "FAST")) {
                this_ = "CASCADE FAST"
            } else if (matchTextSeq("CASCADE", "COMPLETE") &&
                matchTexts(setOf("ONLINE", "OFFLINE"))
            ) {
                this_ = "CASCADE COMPLETE ${prevToken.text.uppercase()}"
                expressionArg = parseInto()
            }
        }

        return expression(
            AnalyzeValidate(args("kind" to kind, "this" to this_, "expression" to expressionArg))
        )
    }

    // sqlglot: Parser._parse_analyze_columns
    fun parseAnalyzeColumns(): Expression? {
        val this_ = prevToken.text.uppercase()
        if (matchTextSeq("COLUMNS")) {
            return expression(AnalyzeColumns(args("this" to "$this_ ${prevToken.text.uppercase()}")))
        }
        return null
    }

    // sqlglot: Parser._parse_analyze_delete
    fun parseAnalyzeDelete(): Expression? {
        val kind = if (matchTextSeq("SYSTEM")) prevToken.text.uppercase() else null
        if (matchTextSeq("STATISTICS")) {
            return expression(AnalyzeDelete(args("kind" to kind)))
        }
        return null
    }

    // sqlglot: Parser._parse_analyze_list
    fun parseAnalyzeList(): Expression? {
        if (matchTextSeq("CHAINED", "ROWS")) {
            return expression(AnalyzeListChainedRows(args("expression" to parseInto())))
        }
        return null
    }

    // sqlglot: Parser._parse_analyze_histogram
    fun parseAnalyzeHistogram(): Expression {
        val this_ = prevToken.text.uppercase()
        var expressionArg: Expression? = null
        var expressions = listOf<Expression>()
        var updateOptions: String? = null

        if (matchTextSeq("HISTOGRAM", "ON")) {
            expressions = parseCsv { parseColumnReference() }
            val withExpressions = mutableListOf<String>()
            while (match(TokenType.WITH)) {
                if (matchTexts(setOf("SYNC", "ASYNC"))) {
                    if (matchTextSeq("MODE", advance = false)) {
                        withExpressions.add("${prevToken.text.uppercase()} MODE")
                        advance()
                    }
                } else {
                    val buckets = parseNumber()
                    if (matchTextSeq("BUCKETS")) {
                        withExpressions.add("${(buckets as? Literal)?.name ?: ""} BUCKETS")
                    }
                }
            }
            if (withExpressions.isNotEmpty()) {
                expressionArg = expression(AnalyzeWith(args("expressions" to withExpressions)))
            }

            if (matchTexts(setOf("MANUAL", "AUTO")) &&
                match(TokenType.UPDATE, advance = false)
            ) {
                updateOptions = prevToken.text.uppercase()
                advance()
            } else if (matchTextSeq("USING", "DATA")) {
                expressionArg = expression(UsingData(args("this" to parseString())))
            }
        }

        return expression(
            AnalyzeHistogram(
                args(
                    "this" to this_,
                    "expressions" to expressions,
                    "expression" to expressionArg,
                    "update_options" to updateOptions,
                )
            )
        )
    }

    // sqlglot: Parser._parse_grant_privilege (PRIVILEGE_FOLLOW_TOKENS = ON/COMMA/L_PAREN)
    fun parseGrantPrivilege(): Expression? {
        val privilegeParts = mutableListOf<String>()

        // Keep consuming consecutive keywords until comma (end of this privilege) or ON
        // (end of privilege list) or L_PAREN (start of column list) are met
        while (currToken.exists &&
            !matchSet(setOf(TokenType.ON, TokenType.COMMA, TokenType.L_PAREN), advance = false)
        ) {
            privilegeParts.add(currToken.text.uppercase())
            advance()
        }

        val this_ = Var(args("this" to privilegeParts.joinToString(" ")))
        val expressions = if (match(TokenType.L_PAREN, advance = false)) {
            parseWrappedCsv({ parseColumn() })
        } else {
            null
        }

        return expression(GrantPrivilege(args("this" to this_, "expressions" to expressions)))
    }

    // sqlglot: Parser._parse_grant_principal
    fun parseGrantPrincipal(): Expression? {
        val kind = if (matchTexts(setOf("ROLE", "GROUP"))) prevToken.text.uppercase() else false
        val principal = parseIdVar() ?: return null

        return expression(GrantPrincipal(args("this" to principal, "kind" to kind)))
    }

    // sqlglot: Parser._parse_grant_revoke_common
    protected fun parseGrantRevokeCommon(): Triple<List<Expression>?, String?, Expression?> {
        val privileges = parseCsv { parseGrantPrivilege() }

        match(TokenType.ON)
        val kind = if (matchSet(creatables)) prevToken.text.uppercase() else null

        // Attempt to parse the securable e.g. MySQL allows names
        // such as "foo.*", "*.*" which are not easily parseable yet
        val securable = tryParse({ parseTableParts() })

        return Triple(privileges, kind, securable)
    }

    // sqlglot: Parser._parse_grant
    fun parseGrant(): Expression {
        val start = prevToken

        val (privileges, kind, securable) = parseGrantRevokeCommon()

        if (securable == null || !matchTextSeq("TO")) {
            return parseAsCommand(start)
        }

        val principals = parseCsv { parseGrantPrincipal() }

        val grantOption = matchTextSeq("WITH", "GRANT", "OPTION")

        if (currToken.exists) {
            return parseAsCommand(start)
        }

        return expression(
            Grant(
                args(
                    "privileges" to privileges,
                    "kind" to kind,
                    "securable" to securable,
                    "principals" to principals,
                    "grant_option" to grantOption,
                )
            )
        )
    }

    // sqlglot: Parser._parse_revoke
    fun parseRevoke(): Expression {
        val start = prevToken

        val grantOption = matchTextSeq("GRANT", "OPTION", "FOR")

        val (privileges, kind, securable) = parseGrantRevokeCommon()

        if (securable == null || !matchTextSeq("FROM")) {
            return parseAsCommand(start)
        }

        val principals = parseCsv { parseGrantPrincipal() }

        var cascade: String? = null
        if (matchTexts(setOf("CASCADE", "RESTRICT"))) {
            cascade = prevToken.text.uppercase()
        }

        if (currToken.exists) {
            return parseAsCommand(start)
        }

        return expression(
            Revoke(
                args(
                    "privileges" to privileges,
                    "kind" to kind,
                    "securable" to securable,
                    "principals" to principals,
                    "grant_option" to grantOption,
                    "cascade" to cascade,
                )
            )
        )
    }

    // sqlglot: Parser._parse_set_item_assignment (SET_REQUIRES_ASSIGNMENT_DELIMITER=true,
    // SET_ASSIGNMENT_DELIMITERS = {"=", ":=", "TO"})
    fun parseSetItemAssignment(kind: String? = null): Expression? {
        val startIndex = index

        if (kind in setOf("GLOBAL", "SESSION") && matchTextSeq("TRANSACTION")) {
            return parseSetTransaction(global_ = kind == "GLOBAL")
        }

        val left = parsePrimary() ?: parseColumn()
        val assignmentDelimiter = matchTexts(setOf("=", ":=", "TO"))

        if (left == null || !assignmentDelimiter) {
            retreat(startIndex)
            return null
        }

        var right: Expression? = parseStatement() ?: parseIdVar()
        if (right is Column || right is Identifier) {
            right = Var(args("this" to right.name))
        }

        val eq = expression(EQ(args("this" to left, "expression" to right)))
        return expression(SetItem(args("this" to eq, "kind" to kind)))
    }

    // sqlglot: Parser._parse_set_transaction
    fun parseSetTransaction(global_: kotlin.Boolean = false): Expression {
        matchTextSeq("TRANSACTION")
        val characteristics = parseCsv {
            parseVarFromOptions(transactionCharacteristics)
        }
        return expression(
            SetItem(
                args(
                    "expressions" to characteristics,
                    "kind" to "TRANSACTION",
                    "global_" to global_,
                )
            )
        )
    }

    // sqlglot: Parser._find_parser — greedy longest-key match over word sequences.
    // Tokens may carry multi-word text (e.g. "CHARACTER SET"), matching Python's
    // `key = curr.split(" ")` trie walk.
    protected fun <T> findParser(parsers: Map<String, T>): T? {
        if (!currToken.exists) return null
        val startIndex = index
        val words = mutableListOf<String>()
        while (currToken.exists) {
            words.add(currToken.text.uppercase())
            advance()
            val joined = words.joinToString(" ")
            if (parsers.containsKey(joined)) return parsers.getValue(joined)
            if (parsers.keys.none { it.startsWith("$joined ") }) break
        }
        retreat(startIndex)
        return null
    }

    // sqlglot: Parser._parse_set_item (dispatch through SET_PARSERS via _find_parser)
    fun parseSetItem(): Expression? {
        val parser = findParser(setParsers)
        if (parser != null) {
            return parser(this)
        }
        return parseSetItemAssignment(kind = null)
    }

    // sqlglot: Parser._parse_set
    fun parseSet(unset: kotlin.Boolean = false, tag: kotlin.Boolean = false): Expression {
        val startIndex = index
        val set_ = expression(
            dev.brikk.house.sql.ast.Set(
                args(
                    "expressions" to parseCsv { parseSetItem() },
                    "unset" to unset,
                    "tag" to tag,
                )
            )
        )

        if (currToken.exists) {
            retreat(startIndex)
            return parseAsCommand(prevToken)
        }

        return set_
    }

    // -----------------------------------------------------------------------
    // Properties (sqlglot: _parse_property and friends)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_property
    fun parseProperty(): kotlin.Any? {
        if (matchTexts(propertyParsers.keys)) {
            return propertyParsers.getValue(prevToken.text.uppercase())(this, PropertyKwargs())
        }

        if (match(TokenType.DEFAULT) && matchTexts(propertyParsers.keys)) {
            return propertyParsers.getValue(prevToken.text.uppercase())(
                this, PropertyKwargs(default = true)
            )
        }

        if (matchTextSeq("COMPOUND", "SORTKEY")) {
            return parseSortkey(compound = true)
        }

        if (matchTextSeq("PARAMETER", "STYLE", "PANDAS")) {
            return expression(ParameterStyleProperty(args("this" to "PANDAS")))
        }

        val startIndex = index

        val seqProps = parseSequenceProperties()
        if (seqProps != null) return seqProps

        retreat(startIndex)
        return parseKeyValueProperty()
    }

    // sqlglot: Parser._parse_property_before (teradata prefix keywords)
    fun parsePropertyBefore(): kotlin.Any? {
        match(TokenType.COMMA)

        val kwargs = PropertyKwargs(
            no = matchTextSeq("NO"),
            dual = matchTextSeq("DUAL"),
            before = matchTextSeq("BEFORE"),
            default = matchTextSeq("DEFAULT"),
            local = if (matchTextSeq("LOCAL")) "LOCAL"
                else if (matchTextSeq("NOT", "LOCAL")) "NOT LOCAL" else null,
            after = matchTextSeq("AFTER"),
            minimum = matchTexts(setOf("MIN", "MINIMUM")),
            maximum = matchTexts(setOf("MAX", "MAXIMUM")),
        )

        if (matchTexts(propertyParsers.keys)) {
            return propertyParsers.getValue(prevToken.text.uppercase())(this, kwargs)
        }

        return null
    }

    // sqlglot: Parser._parse_key_value_property
    fun parseKeyValueProperty(parseValue: (() -> Expression?)? = null): Expression? {
        val startIndex = index
        var key = parseColumn()

        if (!match(TokenType.EQ)) {
            retreat(startIndex)
            return null
        }

        // Transform the key to exp.Dot for dotted identifiers or exp.Var otherwise
        if (key is Column) {
            key = if (columnParts(key).size > 1) columnToDot(key) else Var(args("this" to key.name))
        }

        var value: Expression? = if (parseValue != null) {
            parseValue()
        } else {
            parseBitwise() ?: parseVar(anyToken = true)
        }

        // Transform the value to exp.Var if it was parsed as exp.Column(exp.Identifier())
        if (value is Column) value = Var(args("this" to value.name))

        return expression(Property(args("this" to key, "value" to value)))
    }

    // sqlglot: Parser._parse_stored
    fun parseStored(): Expression {
        if (matchTextSeq("BY")) {
            return expression(StorageHandlerProperty(args("this" to parseVarOrString())))
        }

        match(TokenType.ALIAS)
        val inputFormat = if (matchTextSeq("INPUTFORMAT")) parseString() else null
        val outputFormat = if (matchTextSeq("OUTPUTFORMAT")) parseString() else null

        return expression(
            FileFormatProperty(
                args(
                    "this" to if (inputFormat != null || outputFormat != null) {
                        expression(
                            InputOutputFormat(
                                args("input_format" to inputFormat, "output_format" to outputFormat)
                            )
                        )
                    } else {
                        parseVarOrString() ?: parseNumber() ?: parseIdVar()
                    },
                    "hive_format" to true,
                )
            )
        )
    }

    // sqlglot: Parser._parse_unquoted_field
    protected fun parseUnquotedField(): Expression? {
        var field = parseField()
        if (field is Identifier && field.args["quoted"] != true) {
            field = Var(args("this" to field.name))
        }
        return field
    }

    // sqlglot: Parser._parse_property_assignment
    fun parsePropertyAssignment(factory: NodeFactory, kwargs: Args = emptyMap()): Expression {
        match(TokenType.EQ)
        match(TokenType.ALIAS)

        val propArgs = args("this" to parseUnquotedField()).toMutableMap()
        propArgs.putAll(kwargs)
        return expression(factory(propArgs))
    }

    // sqlglot: Parser._parse_properties
    fun parseProperties(before: kotlin.Boolean? = null): Expression? {
        val properties = mutableListOf<Expression>()
        while (true) {
            val prop = if (before == true) parsePropertyBefore() else parseProperty()
            if (prop == null) break
            when (prop) {
                is Expression -> properties.add(prop)
                is kotlin.collections.List<*> -> properties.addAll(prop.filterIsInstance<Expression>())
            }
        }

        if (properties.isNotEmpty()) {
            return expression(Properties(args("expressions" to properties)))
        }

        return null
    }

    // sqlglot: Parser._parse_fallback
    fun parseFallback(no: kotlin.Boolean = false): Expression =
        expression(FallbackProperty(args("no" to no, "protection" to matchTextSeq("PROTECTION"))))

    // sqlglot: Parser._parse_sql_security
    fun parseSqlSecurity(): Expression =
        expression(
            SqlSecurityProperty(
                args(
                    "this" to (if (matchTexts(setOf("DEFINER", "INVOKER", "NONE"))) prevToken.text.uppercase() else false),
                )
            )
        )

    // sqlglot: Parser._parse_settings_property
    fun parseSettingsProperty(): Expression =
        expression(SettingsProperty(args("expressions" to parseCsv { parseAssignment() })))

    // sqlglot: Parser._parse_called_on_null_input_property
    fun parseCalledOnNullInputProperty(): Expression? {
        if (!matchTextSeq("ON", "NULL", "INPUT")) {
            retreat(index - 1)
            return null
        }
        return expression(CalledOnNullInputProperty())
    }

    // sqlglot: Parser._parse_volatile_property (PRE_VOLATILE_TOKENS = CREATE/REPLACE/UNIQUE)
    fun parseVolatileProperty(): Expression {
        val preVolatileToken = if (index >= 2) tokens.getOrNull(index - 2) else null

        if (preVolatileToken != null &&
            preVolatileToken.tokenType in setOf(TokenType.CREATE, TokenType.REPLACE, TokenType.UNIQUE)
        ) {
            return VolatileProperty()
        }

        return expression(StabilityProperty(args("this" to Literal.string("VOLATILE"))))
    }

    // sqlglot: Parser._parse_retention_period
    fun parseRetentionPeriod(): Expression {
        val number = parseNumber()
        val numberStr = if (number != null) "${(number as Literal).name} " else ""
        val unit = parseVar(anyToken = true)
        return Var(args("this" to "$numberStr${(unit as? Var)?.name ?: ""}"))
    }

    // sqlglot: Parser._parse_system_versioning_property
    fun parseSystemVersioningProperty(with_: kotlin.Boolean = false): Expression {
        match(TokenType.EQ)
        val prop = expression(
            WithSystemVersioningProperty(args("on" to true, "with_" to with_))
        )

        if (matchTextSeq("OFF")) {
            prop.set("on", false)
            return prop
        }

        match(TokenType.ON)
        if (match(TokenType.L_PAREN)) {
            while (currToken.exists && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("HISTORY_TABLE", "=")) {
                    prop.set("this", parseTableParts())
                } else if (matchTextSeq("DATA_CONSISTENCY_CHECK", "=")) {
                    prop.set("data_consistency", if (advanceAny() != null) prevToken.text.uppercase() else null)
                } else if (matchTextSeq("HISTORY_RETENTION_PERIOD", "=")) {
                    prop.set("retention_period", parseRetentionPeriod())
                }

                match(TokenType.COMMA)
            }
        }

        return prop
    }

    // sqlglot: Parser._parse_data_deletion_property
    fun parseDataDeletionProperty(): Expression {
        match(TokenType.EQ)
        val on = matchTextSeq("ON") || !matchTextSeq("OFF")
        val prop = expression(DataDeletionProperty(args("on" to on)))

        if (match(TokenType.L_PAREN)) {
            while (currToken.exists && !match(TokenType.R_PAREN)) {
                if (matchTextSeq("FILTER_COLUMN", "=")) {
                    prop.set("filter_column", parseColumn())
                } else if (matchTextSeq("RETENTION_PERIOD", "=")) {
                    prop.set("retention_period", parseRetentionPeriod())
                }

                match(TokenType.COMMA)
            }
        }

        return prop
    }

    // sqlglot: Parser._parse_distributed_property
    fun parseDistributedProperty(): Expression {
        var kind = "HASH"
        var expressions: List<Expression>? = null
        if (matchTextSeq("BY", "HASH")) {
            expressions = parseWrappedCsv({ parseIdVar() })
        } else if (matchTextSeq("BY", "RANDOM")) {
            kind = "RANDOM"
        }

        // If the BUCKETS keyword is not present, the number of buckets is AUTO
        var buckets: Expression? = null
        if (matchTextSeq("BUCKETS") && !matchTextSeq("AUTO")) {
            buckets = parseNumber()
        }

        return expression(
            DistributedByProperty(
                args(
                    "expressions" to expressions,
                    "kind" to kind,
                    "buckets" to buckets,
                    "order" to parseOrder(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_composite_key_property
    fun parseCompositeKeyProperty(factory: NodeFactory): Expression {
        matchTextSeq("KEY")
        return expression(factory(args("expressions" to parseWrappedIdVars())))
    }

    // sqlglot: Parser._parse_with_property (SERDE_PROPERTIES/PROCEDURE_OPTIONS paths
    // gated: no base-corpus coverage)
    fun parseWithProperty(): kotlin.Any? {
        if (matchTextSeq("(", "SYSTEM_VERSIONING")) {
            val prop = parseSystemVersioningProperty(with_ = true)
            matchRParen()
            return prop
        }

        if (match(TokenType.L_PAREN, advance = false)) {
            return parseWrappedProperties()
        }

        if (matchTextSeq("JOURNAL")) {
            return parseWithJournalTable()
        }

        if (matchTexts(viewAttributes)) {
            return expression(ViewAttributeProperty(args("this" to prevToken.text.uppercase())))
        }

        if (matchTextSeq("DATA")) {
            return parseWithData(no = false)
        } else if (matchTextSeq("NO", "DATA")) {
            return parseWithData(no = true)
        }

        if (match(TokenType.SERDE_PROPERTIES, advance = false)) {
            return parseSerdeProperties(with_ = true)
        }

        if (match(TokenType.SCHEMA)) {
            return expression(
                WithSchemaBindingProperty(
                    args("this" to parseVarFromOptions(schemaBindingOptions))
                )
            )
        }

        if (!nextToken.exists) return null

        return parseWithIsolatedLoading()
    }

    // sqlglot: Parser._parse_definer
    fun parseDefiner(): Expression? {
        match(TokenType.EQ)

        val user = parseIdVar()
        match(TokenType.PARAMETER)
        val host = parseIdVar()
            ?: (if (match(TokenType.MOD)) identifierExpression() else null)

        if (user == null || host == null) return null

        return DefinerProperty(args("this" to "${user.name}@${host.name}"))
    }

    // sqlglot: Parser._parse_withjournaltable
    fun parseWithJournalTable(): Expression {
        match(TokenType.TABLE)
        match(TokenType.EQ)
        return expression(WithJournalTableProperty(args("this" to parseTableParts())))
    }

    // sqlglot: Parser._parse_log
    fun parseLog(no: kotlin.Boolean = false): Expression =
        expression(LogProperty(args("no" to no)))

    // sqlglot: Parser._parse_journal
    fun parseJournal(kwargs: PropertyKwargs): Expression =
        expression(JournalProperty(kwargs.toArgs()))

    // sqlglot: Parser._parse_checksum
    fun parseChecksum(): Expression {
        match(TokenType.EQ)

        var on: kotlin.Boolean? = null
        if (match(TokenType.ON)) {
            on = true
        } else if (matchTextSeq("OFF")) {
            on = false
        }

        return expression(ChecksumProperty(args("on" to on, "default" to match(TokenType.DEFAULT))))
    }

    // sqlglot: Parser._parse_cluster
    fun parseCluster(): Expression {
        match(TokenType.CLUSTER_BY)
        return expression(Cluster(args("expressions" to parseCsv { parseColumn() })))
    }

    // sqlglot: Parser._parse_cluster_property
    fun parseClusterProperty(): Expression =
        expression(ClusterProperty(args("expressions" to parseWrappedCsv({ parseColumn() }))))

    // sqlglot: Parser._parse_clustered_by
    fun parseClusteredBy(): Expression {
        matchTextSeq("BY")

        matchLParen()
        val expressions = parseCsv { parseColumn() }
        matchRParen()

        var sortedBy: List<Expression>? = null
        if (matchTextSeq("SORTED", "BY")) {
            matchLParen()
            sortedBy = parseCsv { parseOrdered() }
            matchRParen()
        }

        match(TokenType.INTO)
        val buckets = parseNumber()
        matchTextSeq("BUCKETS")

        return expression(
            ClusteredByProperty(
                args("expressions" to expressions, "sorted_by" to sortedBy, "buckets" to buckets)
            )
        )
    }

    // sqlglot: Parser._parse_copy_property
    fun parseCopyProperty(): Expression? {
        if (!matchTextSeq("GRANTS")) {
            retreat(index - 1)
            return null
        }
        return expression(CopyGrantsProperty())
    }

    // sqlglot: Parser._parse_freespace
    fun parseFreespace(): Expression {
        match(TokenType.EQ)
        return expression(
            FreespaceProperty(args("this" to parseNumber(), "percent" to match(TokenType.PERCENT)))
        )
    }

    // sqlglot: Parser._parse_mergeblockratio
    fun parseMergeblockratio(no: kotlin.Boolean = false, default: kotlin.Boolean = false): Expression {
        if (match(TokenType.EQ)) {
            return expression(
                MergeBlockRatioProperty(
                    args("this" to parseNumber(), "percent" to match(TokenType.PERCENT))
                )
            )
        }
        return expression(MergeBlockRatioProperty(args("no" to no, "default" to default)))
    }

    // sqlglot: Parser._parse_datablocksize
    fun parseDatablocksize(
        default: kotlin.Boolean? = null,
        minimum: kotlin.Boolean? = null,
        maximum: kotlin.Boolean? = null,
    ): Expression {
        match(TokenType.EQ)
        val size = parseNumber()

        var units: String? = null
        if (matchTexts(setOf("BYTES", "KBYTES", "KILOBYTES"))) {
            units = prevToken.text
        }

        return expression(
            DataBlocksizeProperty(
                args(
                    "size" to size,
                    "units" to units,
                    "default" to default,
                    "minimum" to minimum,
                    "maximum" to maximum,
                )
            )
        )
    }

    // sqlglot: Parser._parse_blockcompression
    fun parseBlockcompression(): Expression {
        match(TokenType.EQ)
        val always = matchTextSeq("ALWAYS")
        val manual = matchTextSeq("MANUAL")
        val never = matchTextSeq("NEVER")
        val default = matchTextSeq("DEFAULT")

        var autotemp: Expression? = null
        if (matchTextSeq("AUTOTEMP")) {
            autotemp = parseSchema()
        }

        return expression(
            BlockCompressionProperty(
                args(
                    "always" to always,
                    "manual" to manual,
                    "never" to never,
                    "default" to default,
                    "autotemp" to autotemp,
                )
            )
        )
    }

    // sqlglot: Parser._parse_withisolatedloading
    fun parseWithIsolatedLoading(): Expression? {
        val startIndex = index
        val no = matchTextSeq("NO")
        val concurrent = matchTextSeq("CONCURRENT")

        if (!matchTextSeq("ISOLATED", "LOADING")) {
            retreat(startIndex)
            return null
        }

        val target = parseVarFromOptions(isolatedLoadingOptions, raiseUnmatched = false)
        return expression(
            IsolatedLoadingProperty(
                args("no" to no, "concurrent" to concurrent, "target" to target)
            )
        )
    }

    // sqlglot: Parser._parse_locking
    fun parseLocking(): Expression {
        val kind: String? = when {
            match(TokenType.TABLE) -> "TABLE"
            match(TokenType.VIEW) -> "VIEW"
            match(TokenType.ROW) -> "ROW"
            matchTextSeq("DATABASE") -> "DATABASE"
            else -> null
        }

        val this_ = if (kind in setOf("DATABASE", "TABLE", "VIEW")) parseTableParts() else null

        val forOrIn: String? = when {
            match(TokenType.FOR) -> "FOR"
            match(TokenType.IN) -> "IN"
            else -> null
        }

        val lockType: String? = when {
            matchTextSeq("ACCESS") -> "ACCESS"
            matchTexts(setOf("EXCL", "EXCLUSIVE")) -> "EXCLUSIVE"
            matchTextSeq("SHARE") -> "SHARE"
            matchTextSeq("READ") -> "READ"
            matchTextSeq("WRITE") -> "WRITE"
            matchTextSeq("CHECKSUM") -> "CHECKSUM"
            else -> null
        }

        val override = matchTextSeq("OVERRIDE")

        return expression(
            LockingProperty(
                args(
                    "this" to this_,
                    "kind" to kind,
                    "for_or_in" to forOrIn,
                    "lock_type" to lockType,
                    "override" to override,
                )
            )
        )
    }

    // sqlglot: Parser._parse_partition_bound_spec
    fun parsePartitionBoundSpec(): Expression {
        fun parsePartitionBoundExpr(): Expression? {
            if (matchTextSeq("MINVALUE")) return Var(args("this" to "MINVALUE"))
            if (matchTextSeq("MAXVALUE")) return Var(args("this" to "MAXVALUE"))
            return parseBitwise()
        }

        var this_: kotlin.Any? = null
        var expressionArg: Expression? = null
        var fromExpressions: List<Expression>? = null
        var toExpressions: List<Expression>? = null

        if (match(TokenType.IN)) {
            this_ = parseWrappedCsv({ parseBitwise() })
        } else if (match(TokenType.FROM)) {
            fromExpressions = parseWrappedCsv({ parsePartitionBoundExpr() })
            matchTextSeq("TO")
            toExpressions = parseWrappedCsv({ parsePartitionBoundExpr() })
        } else if (matchTextSeq("WITH", "(", "MODULUS")) {
            this_ = parseNumber()
            matchTextSeq(",", "REMAINDER")
            expressionArg = parseNumber()
            matchRParen()
        } else {
            raiseError("Failed to parse partition bound spec.")
        }

        return expression(
            PartitionBoundSpec(
                args(
                    "this" to this_,
                    "expression" to expressionArg,
                    "from_expressions" to fromExpressions,
                    "to_expressions" to toExpressions,
                )
            )
        )
    }

    // sqlglot: Parser._parse_partitioned_of
    fun parsePartitionedOf(): Expression? {
        if (!matchTextSeq("OF")) {
            retreat(index - 1)
            return null
        }

        val this_ = parseTable(schema = true)

        val expressionArg: Expression = if (match(TokenType.DEFAULT)) {
            Var(args("this" to "DEFAULT"))
        } else if (matchTextSeq("FOR", "VALUES")) {
            parsePartitionBoundSpec()
        } else {
            raiseError("Expecting either DEFAULT or FOR VALUES clause.")
            return null
        }

        return expression(PartitionedOfProperty(args("this" to this_, "expression" to expressionArg)))
    }

    // sqlglot: Parser._parse_partitioned_by
    fun parsePartitionedBy(): Expression {
        match(TokenType.EQ)
        return expression(
            PartitionedByProperty(
                args("this" to (parseSchema() ?: parseBracket(parseField())))
            )
        )
    }

    // sqlglot: Parser._parse_withdata
    fun parseWithData(no: kotlin.Boolean = false): Expression {
        val statistics: kotlin.Boolean? = if (matchTextSeq("AND", "STATISTICS")) {
            true
        } else if (matchTextSeq("AND", "NO", "STATISTICS")) {
            false
        } else {
            null
        }

        return expression(WithDataProperty(args("no" to no, "statistics" to statistics)))
    }

    // sqlglot: Parser._parse_contains_property
    fun parseContainsProperty(): Expression? {
        if (matchTextSeq("SQL")) {
            return expression(SqlReadWriteProperty(args("this" to "CONTAINS SQL")))
        }
        return null
    }

    // sqlglot: Parser._parse_modifies_property
    fun parseModifiesProperty(): Expression? {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(SqlReadWriteProperty(args("this" to "MODIFIES SQL DATA")))
        }
        return null
    }

    // sqlglot: Parser._parse_no_property
    fun parseNoProperty(): Expression? {
        if (matchTextSeq("PRIMARY", "INDEX")) return NoPrimaryIndexProperty()
        if (matchTextSeq("SQL")) {
            return expression(SqlReadWriteProperty(args("this" to "NO SQL")))
        }
        return null
    }

    // sqlglot: Parser._parse_on_property
    fun parseOnProperty(): Expression? {
        if (matchTextSeq("COMMIT", "PRESERVE", "ROWS")) return OnCommitProperty()
        if (matchTextSeq("COMMIT", "DELETE", "ROWS")) {
            return OnCommitProperty(args("delete" to true))
        }
        return expression(OnProperty(args("this" to parseSchema(parseIdVar()))))
    }

    // sqlglot: Parser._parse_reads_property
    fun parseReadsProperty(): Expression? {
        if (matchTextSeq("SQL", "DATA")) {
            return expression(SqlReadWriteProperty(args("this" to "READS SQL DATA")))
        }
        return null
    }

    // sqlglot: Parser._parse_distkey
    fun parseDistkey(): Expression =
        expression(DistKeyProperty(args("this" to parseWrapped(parseMethod = { parseIdVar() }))))

    // sqlglot: Parser._parse_sortkey
    fun parseSortkey(compound: kotlin.Boolean = false): Expression =
        expression(
            SortKeyProperty(args("this" to parseWrappedIdVars(), "compound" to compound))
        )

    // sqlglot: Parser._parse_character_set (property form)
    fun parseCharacterSet(default: kotlin.Boolean = false): Expression {
        match(TokenType.EQ)
        return expression(
            CharacterSetProperty(args("this" to parseVarOrString(), "default" to default))
        )
    }

    // sqlglot: Parser._parse_remote_with_connection
    fun parseRemoteWithConnection(): Expression {
        matchTextSeq("WITH", "CONNECTION")
        return expression(RemoteWithConnectionModelProperty(args("this" to parseTableParts())))
    }

    // sqlglot: Parser._parse_returns
    fun parseReturns(): Expression {
        var value: Expression?
        var nullArg: kotlin.Boolean? = null
        val isTable = match(TokenType.TABLE)

        if (isTable) {
            if (match(TokenType.LT)) {
                value = expression(
                    Schema(
                        args(
                            "this" to "TABLE",
                            "expressions" to parseCsv { parseStructTypes() },
                        )
                    )
                )
                if (!match(TokenType.GT)) {
                    raiseError("Expecting >")
                }
            } else {
                value = parseSchema(Var(args("this" to "TABLE")))
            }
        } else if (matchTextSeq("NULL", "ON", "NULL", "INPUT")) {
            nullArg = true
            value = null
        } else {
            value = parseTypes()
        }

        return expression(
            ReturnsProperty(args("this" to value, "is_table" to isTable, "null" to nullArg))
        )
    }

    // sqlglot: Parser._parse_to_table
    fun parseToTable(): Expression =
        expression(ToTableProperty(args("this" to parseTableParts(schema = true))))

    // sqlglot: Parser._parse_ttl
    fun parseTtl(): Expression {
        fun parseTtlAction(): Expression? {
            val this_ = parseBitwise()

            if (matchTextSeq("DELETE")) {
                return expression(MergeTreeTTLAction(args("this" to this_, "delete" to true)))
            }
            if (matchTextSeq("RECOMPRESS")) {
                return expression(
                    MergeTreeTTLAction(args("this" to this_, "recompress" to parseBitwise()))
                )
            }
            if (matchTextSeq("TO", "DISK")) {
                return expression(
                    MergeTreeTTLAction(args("this" to this_, "to_disk" to parseString()))
                )
            }
            if (matchTextSeq("TO", "VOLUME")) {
                return expression(
                    MergeTreeTTLAction(args("this" to this_, "to_volume" to parseString()))
                )
            }

            return this_
        }

        val expressions = parseCsv { parseTtlAction() }
        val where = parseWhere()
        val group = parseGroup()

        var aggregates: List<Expression>? = null
        if (group != null && match(TokenType.SET)) {
            aggregates = parseCsv { parseSetItem() }
        }

        return expression(
            MergeTreeTTL(
                args(
                    "expressions" to expressions,
                    "where" to where,
                    "group" to group,
                    "aggregates" to aggregates,
                )
            )
        )
    }

    // sqlglot: Parser._parse_row
    fun parseRow(): Expression? {
        if (!match(TokenType.FORMAT)) return null
        return parseRowFormat()
    }

    // sqlglot: Parser._parse_serde_properties
    fun parseSerdeProperties(with_: kotlin.Boolean = false): Expression? {
        val startIndex = index
        val withFlag = with_ || matchTextSeq("WITH")

        if (!match(TokenType.SERDE_PROPERTIES)) {
            retreat(startIndex)
            return null
        }
        return expression(
            SerdeProperties(args("expressions" to parseWrappedProperties(), "with_" to withFlag))
        )
    }

    // sqlglot: Parser._parse_row_format
    fun parseRowFormat(matchRow: kotlin.Boolean = false): Expression? {
        if (matchRow && !matchPair(TokenType.ROW, TokenType.FORMAT)) return null

        if (matchTextSeq("SERDE")) {
            val this_ = parseString()
            val serdeProperties = parseSerdeProperties()
            return expression(
                RowFormatSerdeProperty(args("this" to this_, "serde_properties" to serdeProperties))
            )
        }

        matchTextSeq("DELIMITED")

        val kwargs = mutableMapOf<String, kotlin.Any?>()

        if (matchTextSeq("FIELDS", "TERMINATED", "BY")) {
            kwargs["fields"] = parseString()
            if (matchTextSeq("ESCAPED", "BY")) kwargs["escaped"] = parseString()
        }
        if (matchTextSeq("COLLECTION", "ITEMS", "TERMINATED", "BY")) {
            kwargs["collection_items"] = parseString()
        }
        if (matchTextSeq("MAP", "KEYS", "TERMINATED", "BY")) kwargs["map_keys"] = parseString()
        if (matchTextSeq("LINES", "TERMINATED", "BY")) kwargs["lines"] = parseString()
        if (matchTextSeq("NULL", "DEFINED", "AS")) kwargs["null"] = parseString()

        return expression(RowFormatDelimitedProperty(kwargs))
    }

    // sqlglot: Parser._parse_dict_property
    fun parseDictProperty(this_: String): Expression {
        val settings = mutableListOf<Expression>()

        matchLParen()
        val kind = parseIdVar()

        if (match(TokenType.L_PAREN)) {
            while (true) {
                val key = parseIdVar()
                val value = parseFunction() ?: parsePrimaryOrVar()
                if (key == null && value == null) break
                settings.add(expression(DictSubProperty(args("this" to key, "value" to value))))
            }
            match(TokenType.R_PAREN)
        }

        matchRParen()

        return expression(
            DictProperty(
                args("this" to this_, "kind" to kind?.name, "settings" to settings)
            )
        )
    }

    // sqlglot: Parser._parse_dict_range
    fun parseDictRange(this_: String): Expression {
        matchLParen()
        val hasMin = matchTextSeq("MIN")
        val min: Expression?
        val max: Expression?
        if (hasMin) {
            min = parseVar() ?: parsePrimary()
            matchTextSeq("MAX")
            max = parseVar() ?: parsePrimary()
        } else {
            max = parseVar() ?: parsePrimary()
            min = Literal.number("0")
        }
        matchRParen()
        return expression(DictRange(args("this" to this_, "min" to min, "max" to max)))
    }

    // sqlglot: Parser._parse_sequence_properties
    fun parseSequenceProperties(): Expression? {
        val seq = SequenceProperties()

        val options = mutableListOf<Expression>()
        val startIndex = index

        while (currToken.exists) {
            match(TokenType.COMMA)
            if (matchTextSeq("INCREMENT")) {
                matchTextSeq("BY")
                matchTextSeq("=")
                seq.set("increment", parseTerm())
            } else if (matchTextSeq("MINVALUE")) {
                seq.set("minvalue", parseTerm())
            } else if (matchTextSeq("MAXVALUE")) {
                seq.set("maxvalue", parseTerm())
            } else if (match(TokenType.START_WITH) || matchTextSeq("START")) {
                matchTextSeq("=")
                seq.set("start", parseTerm())
            } else if (matchTextSeq("CACHE")) {
                // T-SQL allows empty CACHE which is initialized dynamically
                seq.set("cache", parseNumber() ?: true)
            } else if (matchTextSeq("OWNED", "BY")) {
                // "OWNED BY NONE" is the default
                seq.set("owned", if (matchTextSeq("NONE")) null else parseColumn())
            } else {
                val opt = parseVarFromOptions(createSequenceOptions, raiseUnmatched = false)
                if (opt != null) {
                    options.add(opt)
                } else {
                    break
                }
            }
        }

        seq.set("options", options.takeIf { it.isNotEmpty() })
        return if (index == startIndex) null else seq
    }

    // sqlglot: Parser._parse_auto_property
    fun parseAutoProperty(): Expression? {
        if (!matchTextSeq("REFRESH")) {
            retreat(index - 1)
            return null
        }
        return expression(AutoRefreshProperty(args("this" to parseVar(upper = true))))
    }

    // sqlglot: Parser._parse_partitioned_by_bucket_or_truncate
    fun parsePartitionedByBucketOrTruncate(): Expression? {
        if (!match(TokenType.L_PAREN, advance = false)) {
            // Partitioning by bucket or truncate follows the syntax:
            // PARTITION BY (BUCKET(..) | TRUNCATE(..))
            // Without parenthesis after each keyword, parse this as an identifier instead
            retreat(index - 1)
            return null
        }

        val isBucket = prevToken.text.uppercase() == "BUCKET"

        val wrapped = parseWrappedCsv({ parsePrimary() ?: parseColumn() })
        var this_ = wrapped.getOrNull(0)
        var expressionArg = wrapped.getOrNull(1)

        if (this_ is Literal) {
            // Iceberg partition transforms: canonicalize to `bucket(<col name>, <num buckets>)`
            val tmp = this_
            this_ = expressionArg
            expressionArg = tmp
        }

        val factory: NodeFactory = if (isBucket) {
            { a -> PartitionedByBucket(a) }
        } else {
            { a -> PartitionByTruncate(a) }
        }
        return expression(factory(args("this" to this_, "expression" to expressionArg)))
    }

    // sqlglot: Parser._parse_wrapped_properties (list-valued property results are
    // flattened; Python keeps them nested but every consumer flattens)
    fun parseWrappedProperties(): MutableList<Expression> {
        val result = mutableListOf<Expression>()
        parseWrapped(parseMethod = {
            do {
                when (val prop = parseProperty()) {
                    is Expression -> result.add(prop)
                    is kotlin.collections.List<*> -> result.addAll(prop.filterIsInstance<Expression>())
                }
            } while (match(TokenType.COMMA))
        })
        return result
    }

    // sqlglot: Parser._parse_schema
    fun parseSchema(this_: Expression? = null): Expression? {
        val startIndex = index
        if (!match(TokenType.L_PAREN)) return this_

        // Disambiguate between schema and subquery/CTE, e.g. in INSERT INTO table (<expr>),
        // expr can be of both types
        if (matchSet(selectStartTokens)) {
            retreat(startIndex)
            return this_
        }
        val schemaArgs = parseCsv { parseConstraint() ?: parseFieldDef() }
        matchRParen()
        return expression(Schema(args("this" to this_, "expressions" to schemaArgs)))
    }

    // sqlglot: Parser._parse_field_def
    fun parseFieldDef(): Expression? = parseColumnDef(parseField(anyToken = true))

    // sqlglot: Parser._parse_column_def (WRAPPED_TRANSFORM_COLUMN_CONSTRAINT=true in base)
    fun parseColumnDef(thisIn: Expression?, computedColumn: kotlin.Boolean = true): Expression? {
        // column defs are not really columns, they're identifiers
        var this_ = thisIn
        if (this_ is Column) this_ = this_.args["this"] as? Expression

        if (!computedColumn) match(TokenType.ALIAS)

        var kind = parseTypes(schema = true)

        if (matchTextSeq("FOR", "ORDINALITY")) {
            return expression(ColumnDef(args("this" to this_, "ordinality" to true)))
        }

        val constraints = mutableListOf<Expression>()

        if ((kind == null && match(TokenType.ALIAS)) || matchTexts(setOf("ALIAS", "MATERIALIZED"))) {
            val persisted = prevToken.text.uppercase() == "MATERIALIZED"
            val constraintKind = ComputedColumnConstraint(
                args(
                    "this" to parseDisjunction(),
                    "persisted" to (persisted || matchTextSeq("PERSISTED")),
                    "data_type" to if (matchTextSeq("AUTO")) Var(args("this" to "AUTO")) else parseTypes(),
                    "not_null" to matchPair(TokenType.NOT, TokenType.NULL),
                )
            )
            constraints.add(expression(ColumnConstraint(args("kind" to constraintKind))))
        } else if (kind == null && matchSet(setOf(TokenType.IN, TokenType.OUT), advance = false)) {
            val inOutConstraint = expression(
                InOutColumnConstraint(
                    args("input_" to match(TokenType.IN), "output" to match(TokenType.OUT))
                )
            )
            constraints.add(inOutConstraint)
            kind = parseTypes()
        } else if (kind != null &&
            match(TokenType.ALIAS, advance = false) &&
            nextToken.tokenType == TokenType.L_PAREN
        ) {
            advance()
            constraints.add(
                expression(
                    ColumnConstraint(
                        args(
                            "kind" to ComputedColumnConstraint(
                                args(
                                    "this" to parseDisjunction(),
                                    "persisted" to (matchTexts(setOf("STORED", "VIRTUAL")) &&
                                        prevToken.text.uppercase() == "STORED"),
                                )
                            )
                        )
                    )
                )
            )
        }

        while (true) {
            val constraint = parseColumnConstraint() ?: break
            constraints.add(constraint)
        }

        if (kind == null && constraints.isEmpty()) return this_

        var position: Expression? = null
        if (matchTexts(setOf("FIRST", "AFTER"))) {
            val pos = prevToken.text
            position = expression(ColumnPosition(args("this" to parseColumn(), "position" to pos)))
        }

        return expression(
            ColumnDef(
                args(
                    "this" to this_,
                    "kind" to kind,
                    "constraints" to constraints,
                    "position" to position,
                )
            )
        )
    }

    // sqlglot: Parser._parse_auto_increment
    fun parseAutoIncrement(): Expression {
        var start: Expression? = null
        var increment: Expression? = null
        var order: kotlin.Boolean? = null

        if (match(TokenType.L_PAREN, advance = false)) {
            val wrapped = parseWrappedCsv({ parseBitwise() })
            start = wrapped.getOrNull(0)
            increment = wrapped.getOrNull(1)
        } else if (matchTextSeq("START")) {
            start = parseBitwise()
            matchTextSeq("INCREMENT")
            increment = parseBitwise()
            if (matchTextSeq("ORDER")) {
                order = true
            } else if (matchTextSeq("NOORDER")) {
                order = false
            }
        }

        if (start != null && increment != null) {
            return GeneratedAsIdentityColumnConstraint(
                args("start" to start, "increment" to increment, "this" to false, "order" to order)
            )
        }

        return AutoIncrementColumnConstraint()
    }

    // sqlglot: Parser._parse_check_constraint
    fun parseCheckConstraint(): Expression? {
        if (!match(TokenType.L_PAREN, advance = false)) return null

        return expression(
            CheckColumnConstraint(
                args(
                    "this" to parseWrapped(parseMethod = { parseAssignment() }),
                    "enforced" to matchTextSeq("ENFORCED"),
                )
            )
        )
    }

    // sqlglot: Parser._parse_compress
    fun parseCompress(): Expression {
        if (match(TokenType.L_PAREN, advance = false)) {
            return expression(
                CompressColumnConstraint(args("this" to parseWrappedCsv({ parseBitwise() })))
            )
        }
        return expression(CompressColumnConstraint(args("this" to parseBitwise())))
    }

    // sqlglot: Parser._parse_generated_as_identity
    open fun parseGeneratedAsIdentity(): Expression {
        var this_: Expression
        if (matchTextSeq("BY", "DEFAULT")) {
            val onNull = matchPair(TokenType.ON, TokenType.NULL)
            this_ = expression(
                GeneratedAsIdentityColumnConstraint(args("this" to false, "on_null" to onNull))
            )
        } else {
            matchTextSeq("ALWAYS")
            this_ = expression(GeneratedAsIdentityColumnConstraint(args("this" to true)))
        }

        match(TokenType.ALIAS)

        if (matchTextSeq("ROW")) {
            val start = matchTextSeq("START")
            if (!start) match(TokenType.END)
            val hidden = matchTextSeq("HIDDEN")
            return expression(
                GeneratedAsRowColumnConstraint(args("start" to start, "hidden" to hidden))
            )
        }

        val identity = matchTextSeq("IDENTITY")

        if (match(TokenType.L_PAREN)) {
            if (match(TokenType.START_WITH)) this_.set("start", parseBitwise())
            if (matchTextSeq("INCREMENT", "BY")) this_.set("increment", parseBitwise())
            if (matchTextSeq("MINVALUE")) this_.set("minvalue", parseBitwise())
            if (matchTextSeq("MAXVALUE")) this_.set("maxvalue", parseBitwise())

            if (matchTextSeq("CYCLE")) {
                this_.set("cycle", true)
            } else if (matchTextSeq("NO", "CYCLE")) {
                this_.set("cycle", false)
            }

            if (!identity) {
                this_.set("expression", parseRange())
            } else if (this_.args["start"] == null && match(TokenType.NUMBER, advance = false)) {
                val genArgs = parseCsv { parseBitwise() }
                this_.set("start", genArgs.getOrNull(0))
                this_.set("increment", genArgs.getOrNull(1))
            }

            matchRParen()
        }

        return this_
    }

    // sqlglot: Parser._parse_inline
    fun parseInline(): Expression {
        matchTextSeq("LENGTH")
        return expression(InlineLengthColumnConstraint(args("this" to parseBitwise())))
    }

    // sqlglot: Parser._parse_not_constraint
    fun parseNotConstraint(): Expression? {
        if (matchTextSeq("NULL")) {
            return expression(NotNullColumnConstraint())
        }
        if (matchTextSeq("CASESPECIFIC")) {
            return expression(CaseSpecificColumnConstraint(args("not_" to true)))
        }
        if (matchTextSeq("FOR", "REPLICATION")) {
            return expression(NotForReplicationColumnConstraint())
        }

        // Unconsume the `NOT` token
        retreat(index - 1)
        return null
    }

    // sqlglot: Parser._parse_column_constraint (PROCEDURE_OPTIONS={} in the base parser)
    fun parseColumnConstraint(): Expression? {
        val this_ = if (match(TokenType.CONSTRAINT)) parseIdVar() else null

        if (matchTexts(constraintParsers.keys)) {
            val constraint = constraintParsers.getValue(prevToken.text.uppercase())(this)
            if (constraint == null) {
                retreat(index - 1)
                return null
            }

            return expression(ColumnConstraint(args("this" to this_, "kind" to constraint)))
        }

        return this_
    }

    // sqlglot: Parser._parse_constraint
    fun parseConstraint(): Expression? {
        if (!match(TokenType.CONSTRAINT)) {
            return parseUnnamedConstraint(constraints = schemaUnnamedConstraints)
        }

        return expression(
            Constraint(args("this" to parseIdVar(), "expressions" to parseUnnamedConstraints()))
        )
    }

    // sqlglot: Parser._parse_unnamed_constraints
    fun parseUnnamedConstraints(): MutableList<Expression> {
        val constraints = mutableListOf<Expression>()
        while (true) {
            val constraint = parseUnnamedConstraint() ?: parseFunction() ?: break
            constraints.add(constraint)
        }
        return constraints
    }

    // sqlglot: Parser._parse_unnamed_constraint
    fun parseUnnamedConstraint(constraints: Collection<String>? = null): Expression? {
        val startIndex = index

        if (match(TokenType.IDENTIFIER, advance = false) ||
            !matchTexts(constraints ?: constraintParsers.keys)
        ) {
            return null
        }

        val constraintKey = prevToken.text.uppercase()
        val parser = constraintParsers[constraintKey]
            ?: return raiseError("No parser found for schema constraint $constraintKey.")

        val result = parser(this)
        if (result == null) retreat(startIndex)

        return result
    }

    // sqlglot: Parser._parse_unique_key
    open fun parseUniqueKey(): Expression? {
        if (currToken.exists &&
            currToken.tokenType != TokenType.IDENTIFIER &&
            currToken.text.uppercase() in constraintParsers.keys
        ) {
            return null
        }
        return parseIdVar(anyToken = false)
    }

    // sqlglot: Parser._parse_unique
    fun parseUnique(): Expression {
        matchTexts(setOf("KEY", "INDEX"))
        return expression(
            UniqueColumnConstraint(
                args(
                    "nulls" to matchTextSeq("NULLS", "NOT", "DISTINCT"),
                    "this" to parseSchema(parseUniqueKey()),
                    "index_type" to (if (match(TokenType.USING) && advanceAny() != null) prevToken.text else false),
                    "on_conflict" to parseOnConflict(),
                    "options" to parseKeyConstraintOptions(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_key_constraint_options
    fun parseKeyConstraintOptions(): List<String> {
        val options = mutableListOf<String>()
        while (true) {
            if (!currToken.exists) break

            if (match(TokenType.ON)) {
                val on = if (advanceAny() != null) prevToken.text else ""

                val action: String = if (matchTextSeq("NO", "ACTION")) {
                    "NO ACTION"
                } else if (matchTextSeq("CASCADE")) {
                    "CASCADE"
                } else if (matchTextSeq("RESTRICT")) {
                    "RESTRICT"
                } else if (matchPair(TokenType.SET, TokenType.NULL)) {
                    "SET NULL"
                } else if (matchPair(TokenType.SET, TokenType.DEFAULT)) {
                    "SET DEFAULT"
                } else {
                    raiseError("Invalid key constraint")
                    ""
                }

                options.add("ON $on $action")
            } else {
                val varOption = parseVarFromOptions(keyConstraintOptions, raiseUnmatched = false)
                    ?: break
                options.add(varOption.name)
            }
        }

        return options
    }

    // sqlglot: Parser._parse_references
    fun parseReferences(matchToken: kotlin.Boolean = true): Expression? {
        if (matchToken && !match(TokenType.REFERENCES)) return null

        val this_ = parseTable(schema = true)
        val options = parseKeyConstraintOptions()
        return expression(
            Reference(args("this" to this_, "expressions" to null, "options" to options))
        )
    }

    // sqlglot: Parser._parse_foreign_key
    fun parseForeignKey(): Expression {
        val expressions = if (!match(TokenType.REFERENCES, advance = false)) {
            parseWrappedIdVars()
        } else {
            null
        }
        val reference = parseReferences()
        val onOptions = LinkedHashMap<String, String>()

        while (match(TokenType.ON)) {
            if (!matchSet(setOf(TokenType.DELETE, TokenType.UPDATE))) {
                raiseError("Expected DELETE or UPDATE")
            }

            val kind = prevToken.text.lowercase()

            val action: String = if (matchTextSeq("NO", "ACTION")) {
                "NO ACTION"
            } else if (match(TokenType.SET)) {
                matchSet(setOf(TokenType.NULL, TokenType.DEFAULT))
                "SET " + prevToken.text.uppercase()
            } else {
                advance()
                prevToken.text.uppercase()
            }

            onOptions[kind] = action
        }

        val fkArgs = args(
            "expressions" to expressions,
            "reference" to reference,
            "options" to parseKeyConstraintOptions(),
        ).toMutableMap()
        for ((k, v) in onOptions) fkArgs[k] = v

        return expression(ForeignKey(fkArgs))
    }

    // sqlglot: Parser._parse_primary_key_part
    protected open fun parsePrimaryKeyPart(): Expression? = parseField()

    // sqlglot: Parser._parse_period_for_system_time
    fun parsePeriodForSystemTime(): Expression? {
        if (!match(TokenType.TIMESTAMP_SNAPSHOT)) {
            retreat(index - 1)
            return null
        }

        val idVars = parseWrappedIdVars()
        return expression(
            PeriodForSystemTimeConstraint(
                args("this" to idVars.getOrNull(0), "expression" to idVars.getOrNull(1))
            )
        )
    }

    // sqlglot: Parser._parse_primary_key
    open fun parsePrimaryKey(
        wrappedOptional: kotlin.Boolean = false,
        inProps: kotlin.Boolean = false,
        namedPrimaryKey: kotlin.Boolean = false,
    ): Expression {
        val desc: kotlin.Boolean? = if (matchSet(setOf(TokenType.ASC, TokenType.DESC))) {
            prevToken.tokenType == TokenType.DESC
        } else {
            null
        }

        var this_: Expression? = null
        if (namedPrimaryKey &&
            currToken.text.uppercase() !in constraintParsers.keys &&
            nextToken.exists &&
            nextToken.tokenType == TokenType.L_PAREN
        ) {
            this_ = parseIdVar()
        }

        if (!inProps && !match(TokenType.L_PAREN, advance = false)) {
            return expression(
                PrimaryKeyColumnConstraint(
                    args("desc" to desc, "options" to parseKeyConstraintOptions())
                )
            )
        }

        val expressions = parseWrappedCsv({ parsePrimaryKeyPart() }, optional = wrappedOptional)

        return expression(
            PrimaryKey(
                args(
                    "this" to this_,
                    "expressions" to expressions,
                    "include" to parseIndexParams(),
                    "options" to parseKeyConstraintOptions(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_opclass
    protected fun parseOpclass(): Expression? {
        val this_ = parseDisjunction()

        if (matchTexts(opclassFollowKeywords, advance = false)) return this_

        if (!matchSet(optypeFollowTokens, advance = false)) {
            return expression(Opclass(args("this" to this_, "expression" to parseTableParts())))
        }

        return this_
    }

    // sqlglot: Parser._parse_indexed_column
    protected fun parseIndexedColumn(): Expression? = parseOrdered { parseOpclass() }

    // sqlglot: Parser._parse_with_operator
    protected fun parseWithOperator(): Expression? {
        val this_ = parseIndexedColumn()

        if (!match(TokenType.WITH)) return this_

        val op = parseVar(anyToken = true, tokens = reservedTokens)

        return expression(WithOperator(args("this" to this_, "op" to op)))
    }

    // sqlglot: Parser._parse_index_params
    fun parseIndexParams(): Expression {
        val using = if (match(TokenType.USING)) parseVar(anyToken = true) else null

        val columns = if (match(TokenType.L_PAREN, advance = false)) {
            parseWrappedCsv({ parseWithOperator() })
        } else {
            null
        }

        val include = if (matchTextSeq("INCLUDE")) parseWrappedIdVars() else null
        val partitionBy = parsePartitionBy()
        val withStorage: kotlin.Any? = if (match(TokenType.WITH)) parseWrappedProperties() else false
        val tablespace = if (matchTextSeq("USING", "INDEX", "TABLESPACE")) {
            parseVar(anyToken = true)
        } else {
            null
        }
        val where = parseWhere()

        val on = if (match(TokenType.ON)) parseField() else null

        return expression(
            IndexParameters(
                args(
                    "using" to using,
                    "columns" to columns,
                    "include" to include,
                    "partition_by" to partitionBy,
                    "where" to where,
                    "with_storage" to withStorage,
                    "tablespace" to tablespace,
                    "on" to on,
                )
            )
        )
    }

    // sqlglot: Parser._parse_on_conflict
    fun parseOnConflict(): Expression? {
        val conflict = matchTextSeq("ON", "CONFLICT")
        val duplicate = matchTextSeq("ON", "DUPLICATE", "KEY")

        if (!conflict && !duplicate) return null

        var conflictKeys: List<Expression>? = null
        var constraint: Expression? = null

        if (conflict) {
            if (matchTextSeq("ON", "CONSTRAINT")) {
                constraint = parseIdVar()
            } else if (match(TokenType.L_PAREN)) {
                conflictKeys = parseCsv { parseIndexedColumn() }
                matchRParen()
            }
        }

        val indexPredicate = parseWhere()

        val action = parseVarFromOptions(conflictActions)
        val expressions: List<Expression>? = if (prevToken.tokenType == TokenType.UPDATE) {
            match(TokenType.SET)
            parseCsv { parseEquality() }
        } else {
            null
        }

        return expression(
            OnConflict(
                args(
                    "duplicate" to duplicate,
                    "expressions" to expressions,
                    "action" to action,
                    "conflict_keys" to conflictKeys,
                    "index_predicate" to indexPredicate,
                    "constraint" to constraint,
                    "where" to parseWhere(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_create_like
    fun parseCreateLike(): Expression? {
        val table = parseTable(schema = true)

        val options = mutableListOf<Expression>()
        while (matchTexts(setOf("INCLUDING", "EXCLUDING"))) {
            val optionKind = prevToken.text.uppercase()

            val idVar = parseIdVar() ?: return null

            options.add(
                expression(
                    Property(
                        args(
                            "this" to optionKind,
                            "value" to Var(args("this" to idVar.name.uppercase())),
                        )
                    )
                )
            )
        }

        return expression(LikeProperty(args("this" to table, "expressions" to options)))
    }

    // sqlglot: Parser._parse_struct_types
    protected open fun parseStructTypes(typeRequired: kotlin.Boolean = false): Expression? {
        val startIndex = index

        val this_: Expression?
        if (currToken.exists && nextToken.exists &&
            currToken.tokenType in typeTokens &&
            nextToken.tokenType in typeTokens
        ) {
            // Takes care of special cases like `STRUCT<list ARRAY<...>>` where the identifier
            // is also a type token. Without this, the list will be parsed as a type.
            this_ = parseIdVar()
        } else {
            this_ = parseType(parseInterval = false, fallbackToIdentifier = true) ?: parseIdVar()
        }

        match(TokenType.COLON)

        if (typeRequired && this_ !is DataType && !matchSet(typeTokens, advance = false)) {
            retreat(startIndex)
            return parseTypes()
        }

        return parseColumnDef(this_)
    }

    // sqlglot: Parser._parse_type_size
    protected fun parseTypeSize(): Expression? {
        var this_ = parseType()
        if (this_ == null) return null

        if (this_ is Column && this_.table.isEmpty()) {
            this_ = Var(args("this" to this_.name.uppercase()))
        }

        return expression(
            DataTypeParam(args("this" to this_, "expression" to parseVar(anyToken = true)))
        )
    }

    // -----------------------------------------------------------------------
    // Columns (sqlglot: _parse_column / _parse_column_ops)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_column
    fun parseColumn(): Expression? {
        var column: Expression? = parseColumnPartsFast()
        if (column == null) {
            var this_ = parseColumnReference()
            if (this_ == null) this_ = parseBracket(this_)
            column = if (this_ != null) parseColumnOps(this_) else this_
        }

        if (column != null) {
            if (supportsColumnJoinMarks) column.set("join_mark", match(TokenType.JOIN_MARKER))
            // sqlglot: COLON_IS_VARIANT_EXTRACT — base: False.
        }

        return column
    }

    // sqlglot: Parser._parse_column_parts_fast
    protected fun parseColumnPartsFast(): Expression? {
        val startIndex = index
        var parts: MutableList<Expression>? = null
        var allComments: MutableList<String>? = null

        while (matchSet(identifierTokens)) {
            val token = prevToken
            val comments = prevComments

            if (parts == null && token.text.uppercase() in noParenFunctionParsers) {
                retreat(startIndex)
                return null
            }

            val hasDot = match(TokenType.DOT)
            val currTt = currToken.tokenType

            if (!hasDot) {
                if (currTt in columnOperators || currTt in columnPostfixTokens) {
                    retreat(startIndex)
                    return null
                }
            } else if (currTt !in identifierTokens) {
                retreat(startIndex)
                return null
            }

            if (parts == null) parts = mutableListOf()

            if (comments.isNotEmpty()) {
                if (allComments == null) allComments = mutableListOf()
                allComments.addAll(comments)
                prevComments = emptyList()
            }

            parts.add(
                expression(
                    Identifier(
                        args("this" to token.text, "quoted" to (token.tokenType == TokenType.IDENTIFIER))
                    ),
                    token,
                )
            )

            if (!hasDot) break
        }

        if (parts == null) return null

        val n = parts.size

        var column: Expression = when {
            n == 1 -> Column(args("this" to parts[0]))
            n == 2 -> Column(args("this" to parts[1], "table" to parts[0]))
            n == 3 -> Column(args("this" to parts[2], "table" to parts[1], "db" to parts[0]))
            else -> Column(
                args(
                    "this" to parts[3], "table" to parts[2], "db" to parts[1], "catalog" to parts[0]
                )
            )
        }
        for (i in 4 until n) {
            column = Dot(args("this" to column, "expression" to parts[i]))
        }

        if (!allComments.isNullOrEmpty()) column.addComments(allComments)

        return column
    }

    // sqlglot: Parser._parse_column_reference
    protected fun parseColumnReference(): Expression? {
        var this_ = parseField()
        if (this_ == null &&
            match(TokenType.VALUES, advance = false) &&
            valuesFollowedByParen &&
            (!nextToken.exists || nextToken.tokenType != TokenType.L_PAREN)
        ) {
            this_ = parseIdVar()
        }

        if (this_ is Identifier) {
            // We bubble up comments from the Identifier to the Column
            this_ = expression(Column(args("this" to this_)), comments = this_.popComments())
        }

        return this_
    }

    // sqlglot: Parser._parse_dcolon
    protected fun parseDcolon(): Expression? = parseTypes()

    // sqlglot: Parser._parse_column_ops
    fun parseColumnOps(this_: Expression?): Expression? {
        var current = this_
        while (currToken.tokenType in brackets) {
            current = parseBracket(current)
        }

        while (currToken.exists) {
            val opToken = currToken.tokenType

            if (opToken !in columnOperators) break
            val op = columnOperators[opToken]
            advance()

            var field: Expression?
            if (opToken in castColumnOperators) {
                field = parseDcolon()
                if (field == null) raiseError("Expected type")
            } else if (op != null && currToken.exists) {
                field = parseColumnReference() ?: parseBitwise()
                if (field is Column && match(TokenType.DOT, advance = false)) {
                    field = parseColumnOps(field)
                }
            } else {
                val dot = isConnected() && prevToken.tokenType == TokenType.DOT
                field = parseField(anyToken = true, anonymousFunc = true)

                // In t.true, t.null we should produce an Identifier node
                if (dot && (field is Null || field is Boolean)) {
                    field = expression(
                        Identifier(args("this" to prevToken.text)),
                        comments = field.comments,
                    )
                }
            }

            // Function calls can be qualified, e.g., x.y.FOO()
            // This converts the final AST to a series of Dots leading to the function call
            if ((field is Func || field is Window) && current != null) {
                current = current.transform { n -> if (n is Column) n.toDot(includeDots = false) else n }
            }

            if (op != null) {
                current = op(this, current, field)
            } else if (current is Column && current.args["catalog"] == null) {
                current = expression(
                    Column(
                        args(
                            "this" to field,
                            "table" to current.thisArg,
                            "db" to current.args["table"],
                            "catalog" to current.args["db"],
                        )
                    ),
                    comments = current.comments,
                )
            } else if (field is Window) {
                // Move the exp.Dot's to the window's function
                val windowFunc = expression(Dot(args("this" to current, "expression" to field.thisArg)))
                field.set("this", windowFunc)
                current = field
            } else {
                current = expression(Dot(args("this" to current, "expression" to field)))
            }

            if (field != null && !field.comments.isNullOrEmpty()) {
                current?.addComments(field.popComments())
            }

            current = parseBracket(current)
        }

        return current
    }

    // sqlglot: Parser._parse_operator (OPERATOR(schema.op) custom-operator syntax)
    open fun parseOperator(thisIn: Expression?): Expression? {
        var this_ = thisIn
        while (true) {
            if (!match(TokenType.L_PAREN)) break

            var op = ""
            while (currToken.exists && !match(TokenType.R_PAREN)) {
                op += currToken.text
                advance()
            }

            val comments = prevComments.toMutableList()
            this_ = expression(
                dev.brikk.house.sql.ast.Operator(
                    args("this" to this_, "operator" to op, "expression" to parseBitwise())
                ),
                comments = comments,
            )

            if (!match(TokenType.OPERATOR)) break
        }

        return this_
    }

    // sqlglot: Parser._parse_bracket_key_value
    protected fun parseBracketKeyValue(isMap: kotlin.Boolean = false): Expression? =
        parseSlice(parseAlias(parseDisjunction(), explicit = true))

    // sqlglot: Parser._parse_bracket (ODBC_DATETIME_LITERALS={} in the base parser,
    // so that branch is elided)
    open fun parseBracket(this_: Expression?): Expression? {
        if (!matchSet(brackets)) return this_

        // sqlglot: MAP_KEYS_ARE_ARBITRARY_EXPRESSIONS peek at _tokens[_index - 2]
        val parseMap = mapKeysAreArbitraryExpressions &&
            (tokens.getOrNull(index - 2)?.text?.uppercase() == "MAP")

        var current = this_
        val bracketKind = prevToken.tokenType

        val expressions = parseCsv {
            parseBracketKeyValue(isMap = bracketKind == TokenType.L_BRACE)
        }

        if (bracketKind == TokenType.L_BRACKET && !match(TokenType.R_BRACKET)) {
            raiseError("Expected ]")
        } else if (bracketKind == TokenType.L_BRACE && !match(TokenType.R_BRACE)) {
            raiseError("Expected }")
        }

        if (bracketKind == TokenType.L_BRACE) {
            current = expression(
                Struct(
                    args(
                        "expressions" to
                            kvToPropEq(expressions.toMutableList(), parseMap = parseMap)
                    )
                )
            )
        } else if (current == null) {
            // sqlglot: build_array_constructor (HAS_DISTINCT_ARRAY_CONSTRUCTORS=false)
            current = expression(Array(args("expressions" to expressions)))
        } else {
            val constructorType = arrayConstructors[current.name.uppercase()]
            if (constructorType != null) {
                return expression(constructorType(args("expressions" to expressions)))
            }
            // sqlglot: apply_index_offset(this, expressions, -self.dialect.INDEX_OFFSET)
            val adjusted = applyIndexOffset(current, expressions, -indexOffset, dialect = dialect)
            current = expression(
                Bracket(args("this" to current, "expressions" to adjusted)),
                comments = current.popComments(),
            )
        }

        addTokenComments(current)
        return parseBracket(current)
    }

    // sqlglot: Parser._parse_comprehension
    fun parseComprehension(this_: Expression?): Expression? {
        val startIndex = index
        val expr = parseColumn()
        // sqlglot: `self._match(...) and self._parse_column()` — False when unmatched
        val position: kotlin.Any? = if (match(TokenType.COMMA)) parseColumn() else false
        if (!match(TokenType.IN)) {
            retreat(startIndex - 1)
            return null
        }
        val iterator = parseColumn()
        val condition = if (matchTextSeq("IF")) parseDisjunction() else null
        return expression(
            Comprehension(
                args(
                    "this" to this_,
                    "expression" to expr,
                    "position" to position,
                    "iterator" to iterator,
                    "condition" to condition,
                )
            )
        )
    }

    // sqlglot: Parser._parse_slice
    protected fun parseSlice(this_: Expression?): Expression? {
        if (!match(TokenType.COLON)) return this_

        val end: Expression?
        if (matchPair(TokenType.DASH, TokenType.COLON, advance = false)) {
            advance()
            end = Neg(args("this" to Literal.number("1")))
        } else {
            end = parseAssignment()
        }
        val step = if (match(TokenType.COLON)) parseUnary() else null
        return expression(Slice(args("this" to this_, "expression" to end, "step" to step)))
    }

    // -----------------------------------------------------------------------
    // Primary layer (sqlglot: _parse_primary / _parse_field / _parse_function)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_paren
    fun parseParen(): Expression? {
        if (!match(TokenType.L_PAREN)) return null

        val comments = prevComments
        val query = parseSelect()

        val expressions: MutableList<Expression> =
            if (query != null) mutableListOf(query) else parseExpressions()

        var this_: Expression? = expressions.firstOrNull()

        if (this_ == null && match(TokenType.R_PAREN, advance = false)) {
            this_ = expression(Tuple())
        } else if (expressions.size > 1 || prevToken.tokenType == TokenType.COMMA) {
            this_ = expression(Tuple(args("expressions" to expressions)))
        } else if (this_ is Select || this_ is SetOperation || this_ is PipeQuery) {
            // sqlglot: exp.UNWRAPPED_QUERIES (brikk: a PipeQuery stands in for the
            // desugared Select sqlglot would have produced here)
            this_ = parseSubquery(this_, parseAlias = false)
        } else if (this_ is Subquery || this_ is Values) {
            // sqlglot: `isinstance(this, (exp.Subquery, exp.Values))`
            this_ = parseSubquery(
                parseQueryModifiers(parseSetOperations(this_)),
                parseAlias = false,
            )
        } else {
            this_ = expression(Paren(args("this" to this_)))
        }

        if (this_ != null) this_.addComments(comments)

        matchRParen(this_)

        if (this_ is Paren && this_.thisArg is AggFunc) {
            return parseWindow(this_)
        }

        return this_
    }

    // sqlglot: Parser._parse_primary
    open fun parsePrimary(): Expression? {
        if (matchSet(primaryParsers.keys)) {
            val tokenType = prevToken.tokenType
            val primary = primaryParsers.getValue(tokenType)(this, prevToken)

            if (tokenType == TokenType.STRING && match(TokenType.STRING, advance = false)) {
                // sqlglot: adjacent string literals -> exp.Concat — not ported.
                return raiseError("Adjacent string literals are not supported yet")
            }

            return primary
        }

        if (matchPair(TokenType.DOT, TokenType.NUMBER)) {
            return Literal.number("0.${prevToken.text}")
        }

        return parseParen()
    }

    // sqlglot: Parser._parse_field
    fun parseField(
        anyToken: kotlin.Boolean = false,
        tokens: Collection<TokenType>? = null,
        anonymousFunc: kotlin.Boolean = false,
    ): Expression? {
        val field = if (anonymousFunc) {
            parseFunction(anonymous = anonymousFunc, anyToken = anyToken) ?: parsePrimary()
        } else {
            parsePrimary() ?: parseFunction(anonymous = anonymousFunc, anyToken = anyToken)
        }
        return field ?: parseIdVar(anyToken = anyToken, tokens = tokens)
    }

    // sqlglot: Parser._parse_function
    fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>? = null,
        anonymous: kotlin.Boolean = false,
        optionalParens: kotlin.Boolean = true,
        anyToken: kotlin.Boolean = false,
    ): Expression? {
        // This allows us to also parse {fn <function>} syntax (Snowflake, MySQL support this)
        // See: https://community.snowflake.com/s/article/SQL-Escape-Sequences
        var fnSyntax = false
        if (match(TokenType.L_BRACE, advance = false) && nextToken.text.uppercase() == "FN") {
            advance(2)
            fnSyntax = true
        }

        val func = parseFunctionCall(
            functions = functions,
            anonymous = anonymous,
            optionalParens = optionalParens,
            anyToken = anyToken,
        )

        if (fnSyntax) match(TokenType.R_BRACE)

        return func
    }

    // sqlglot: Parser._parse_function_args
    protected fun parseFunctionArgs(alias: kotlin.Boolean = false): MutableList<Expression> =
        parseCsv { parseLambda(alias = alias) }

    // sqlglot: Parser._parse_function_call
    protected fun parseFunctionCall(
        functions: Map<String, (List<Expression?>) -> Expression>? = null,
        anonymous: kotlin.Boolean = false,
        optionalParens: kotlin.Boolean = true,
        anyToken: kotlin.Boolean = false,
    ): Expression? {
        if (!currToken.exists) return null

        val comments = currToken.comments
        val prev = prevToken
        val tokenType = currToken.tokenType
        val name: String = currToken.text
        val upper = currToken.text.uppercase()

        val afterDot = prev.tokenType == TokenType.DOT
        // sqlglot: NO_PAREN_FUNCTION_PARSERS["PRIOR"] is installed transiently by
        // _parse_connect_with_prior
        val noParenParser: ((Parser) -> Expression?)? =
            if (connectPriorEnabled && upper == "PRIOR") {
                { p: Parser -> p.expression(Prior(args("this" to p.parseBitwise()))) }
            } else {
                noParenFunctionParsers[upper]
            }
        if (optionalParens &&
            noParenParser != null &&
            tokenType !in invalidFuncNameTokens &&
            !afterDot
        ) {
            advance()
            return parseWindow(noParenParser(this))
        }

        if (nextToken.tokenType != TokenType.L_PAREN) {
            if (optionalParens && tokenType in noParenFunctions && !afterDot) {
                advance()
                return expression(noParenFunctions.getValue(tokenType)())
            }

            return null
        }

        if (anyToken) {
            if (tokenType in reservedTokens) return null
        } else if (tokenType !in funcTokens) {
            return null
        }

        advance(2)

        val parser = if (!anonymous) functionParsers[upper] else null
        var result: Expression?
        if (parser != null) {
            result = parser(this)
        } else {
            val subqueryPredicate = subqueryPredicates[tokenType]

            if (subqueryPredicate != null) {
                var expr: Expression? = null
                if (currToken.tokenType in subqueryTokens) {
                    expr = parseSelect()
                    matchRParen()
                } else if (prev.exists &&
                    (prev.tokenType == TokenType.LIKE || prev.tokenType == TokenType.ILIKE)
                ) {
                    // Backtrack one token since we've consumed the L_PAREN here. Instead, we'd like
                    // to parse "LIKE [ANY | ALL] (...)" as a whole into an exp.Tuple or exp.Paren
                    advance(-1)
                    expr = parseBitwise()
                }

                if (expr != null) {
                    return expression(subqueryPredicate(args("this" to expr)), comments = comments)
                }
            }

            val fns = functions ?: this.functions

            val function = fns[upper]
            var knownFunction = function != null && !anonymous

            val aliasArgs = !knownFunction || upper in functionsWithAliasedArgs
            var fnArgs: MutableList<Expression> = parseFunctionArgs(aliasArgs)

            val postFuncComments = if (currToken.exists) currToken.comments else null
            if (knownFunction && !postFuncComments.isNullOrEmpty()) {
                // If the user-inputted comment "/* sqlglot.anonymous */" is following the function
                // call we'll construct it as exp.Anonymous, even if it's "known"
                if (postFuncComments.any { it.trimStart().startsWith(SQLGLOT_ANONYMOUS) }) {
                    knownFunction = false
                }
            }

            if (aliasArgs && knownFunction) {
                fnArgs = kvToPropEq(fnArgs)
            }

            if (knownFunction) {
                val func = function!!(fnArgs)
                result = validateExpression(func)
                if (preserveOriginalNames) func.meta["name"] = name
            } else {
                val fnName: kotlin.Any = if (tokenType == TokenType.IDENTIFIER) {
                    Identifier(args("this" to name, "quoted" to true))
                } else {
                    name
                }
                result = expression(Anonymous(args("this" to fnName, "expressions" to fnArgs)))
            }
        }

        result?.addComments(comments)

        if (parser != null) {
            match(TokenType.R_PAREN, expression = result)
        } else {
            matchRParen(result)
        }
        return parseWindow(result)
    }

    // sqlglot: Parser._kv_to_prop_eq (KEY_VALUE_DEFINITIONS = Alias/EQ/PropertyEQ/Slice;
    // Parser._to_prop_eq is the identity in the base parser)
    protected fun kvToPropEq(
        expressions: MutableList<Expression>,
        parseMap: kotlin.Boolean = false,
    ): MutableList<Expression> {
        val transformed = mutableListOf<Expression>()

        for (e in expressions) {
            var current = e
            if (current is Alias || current is EQ || current is PropertyEQ || current is Slice) {
                if (current is Alias) {
                    current = expression(
                        PropertyEQ(
                            args(
                                "this" to current.args["alias"],
                                "expression" to current.args["this"],
                            )
                        )
                    )
                }

                if (current !is PropertyEQ) {
                    val thisArg = current.args["this"]
                    current = expression(
                        PropertyEQ(
                            args(
                                "this" to if (parseMap) thisArg else toIdentifier((thisArg as? Expression)?.name ?: ""),
                                "expression" to current.args["expression"],
                            )
                        )
                    )
                }

                val currentThis = current.args["this"]
                if (currentThis is Column) {
                    currentThis.replace(currentThis.args["this"])
                }
            }

            transformed.add(current)
        }

        return transformed
    }

    // sqlglot: expressions.to_identifier
    protected fun toIdentifier(name: String, quoted: kotlin.Boolean? = null): Identifier =
        Identifier(
            args("this" to name, "quoted" to (quoted ?: !SAFE_IDENTIFIER_RE.matches(name)))
        )

    // sqlglot: Parser._parse_string_agg (reached via FUNCTION_PARSERS["STRING_AGG"] in
    // sqlglot's base parser; dispatched from dialect FUNCTION_PARSERS tables here)
    open fun parseStringAgg(): Expression {
        val argsList = mutableListOf<Expression?>()
        if (match(TokenType.DISTINCT)) {
            argsList.add(expression(Distinct(args("expressions" to listOf(parseDisjunction())))))
            if (match(TokenType.COMMA)) {
                argsList.addAll(parseCsv { parseDisjunction() })
            }
        } else {
            argsList.addAll(parseCsv { parseDisjunction() })
        }

        var onOverflow: kotlin.Any? = null
        if (matchTextSeq("ON", "OVERFLOW")) {
            // trino: LISTAGG(expression [, separator] [ON OVERFLOW overflow_behavior])
            onOverflow = if (matchTextSeq("ERROR")) {
                Var(args("this" to "ERROR"))
            } else {
                matchTextSeq("TRUNCATE")
                expression(
                    OverflowTruncateBehavior(
                        args(
                            "this" to parseString(),
                            "with_count" to (
                                matchTextSeq("WITH", "COUNT") ||
                                    !matchTextSeq("WITHOUT", "COUNT")
                                ),
                        )
                    )
                )
            }
        }

        val startIndex = index
        if (!match(TokenType.R_PAREN) && argsList.isNotEmpty()) {
            // postgres: STRING_AGG([DISTINCT] expression, separator [ORDER BY ...])
            argsList[0] = parseLimit(this_ = parseOrder(this_ = argsList[0]))
            return expression(
                GroupConcat(args("this" to argsList[0], "separator" to argsList.getOrNull(1)))
            )
        }

        if (!matchTextSeq("WITHIN", "GROUP")) {
            retreat(startIndex)
            return fromArgList(listOf("this", "separator", "on_overflow"), false) {
                GroupConcat(it)
            }(argsList)
        }

        // The corresponding match_r_paren will be called in parse_function (caller)
        matchLParen()

        return expression(
            GroupConcat(
                args(
                    "this" to parseOrder(this_ = argsList.getOrNull(0)),
                    "separator" to argsList.getOrNull(1),
                    "on_overflow" to onOverflow,
                )
            )
        )
    }

    // sqlglot: Parser._parse_lambda
    open fun parseLambda(alias: kotlin.Boolean = false): Expression? {
        val nextTokenType = nextToken.tokenType

        // Fast path: simple atom (column, literal, null, bool) followed by , or )
        if (nextTokenType in lambdaArgTerminators) {
            val atom = parseAtom()
            if (atom != null) return atom
        }

        val startIndex = index

        if (match(TokenType.L_PAREN)) {
            val expressions = parseCsv { parseLambdaArg() }

            if (!match(TokenType.R_PAREN)) {
                retreat(startIndex)
            } else if (matchSet(lambdas.keys)) {
                return lambdas.getValue(prevToken.tokenType)(this, expressions)
            } else {
                retreat(startIndex)
            }
        } else if (typedLambdaArgs || nextTokenType in lambdas) {
            val expressions = listOf(parseLambdaArg())

            if (matchSet(lambdas.keys)) {
                return lambdas.getValue(prevToken.tokenType)(this, expressions)
            }

            retreat(startIndex)
        }

        val this_: Expression?

        if (match(TokenType.DISTINCT)) {
            this_ = expression(Distinct(args("expressions" to parseCsv { parseDisjunction() })))
        } else {
            match(TokenType.ALL) // ALL is the default/no-op aggregate modifier (SQL-92)
            this_ = parseSelectOrExpression(alias = alias)
        }

        return parseLimit(
            parseRespectOrIgnoreNulls(
                parseOrder(parseHavingMax(parseRespectOrIgnoreNulls(this_)))
            )
        )
    }

    // sqlglot: Parser._replace_lambda
    fun replaceLambda(nodeIn: Expression?, expressions: List<Expression?>): Expression? {
        var node = nodeIn ?: return null

        // name -> declared type (or false for untyped args)
        val lambdaTypes = LinkedHashMap<String, kotlin.Any?>()
        for (e in expressions) {
            if (e != null) lambdaTypes[e.name] = e.args["to"] ?: false
        }

        for (column in node.findAll(Column::class).toList()) {
            column as Column
            val partName = columnParts(column).firstOrNull()?.name ?: continue
            if (!lambdaTypes.containsKey(partName)) continue
            val typ = lambdaTypes[partName]

            var dotOrId: Expression = if (column.table.isNotEmpty()) {
                columnToDot(column)
            } else {
                column.args["this"] as Expression
            }

            if (typ != false && typ is Expression) {
                dotOrId = expression(Cast(args("this" to dotOrId, "to" to typ)))
            }

            var parent = column.parent
            if (parent is Dot) {
                while (parent is Dot) {
                    val grandparent = parent.parent
                    if (grandparent !is Dot) {
                        parent.replace(dotOrId)
                        break
                    }
                    parent = grandparent
                }
            } else {
                if (column === node) {
                    node = dotOrId
                } else {
                    column.replace(dotOrId)
                }
            }
        }
        return node
    }

    // sqlglot: Parser._parse_lambda_arg
    protected fun parseLambdaArg(): Expression? = parseIdVar()

    // sqlglot: Parser._parse_respect_or_ignore_nulls
    protected fun parseRespectOrIgnoreNulls(this_: Expression?): Expression? {
        if (currToken.tokenType == TokenType.VAR) {
            if (matchTextSeq("IGNORE", "NULLS")) {
                return expression(IgnoreNulls(args("this" to this_)))
            }
            if (matchTextSeq("RESPECT", "NULLS")) {
                return expression(RespectNulls(args("this" to this_)))
            }
        }
        return this_
    }

    // sqlglot: Parser._parse_having_max
    protected fun parseHavingMax(this_: Expression?): Expression? {
        if (match(TokenType.HAVING)) {
            matchTexts(setOf("MAX", "MIN"))
            val max = prevToken.text.uppercase() != "MIN"
            return expression(
                HavingMax(args("this" to this_, "expression" to parseColumn(), "max" to max))
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_case
    fun parseCase(): Expression? {
        if (match(TokenType.DOT, advance = false)) {
            // Avoid raising on valid expressions like case.*, supported by, e.g., spark & snowflake
            retreat(index - 1)
            return null
        }

        val ifs = mutableListOf<Expression>()
        var default: Expression? = null

        val comments = prevComments
        val caseExpression = parseDisjunction()

        while (match(TokenType.WHEN)) {
            val this_ = parseDisjunction()
            match(TokenType.THEN)
            val then = parseDisjunction()
            ifs.add(expression(If(args("this" to this_, "true" to then))))
        }

        if (match(TokenType.ELSE)) {
            default = parseDisjunction()
        }

        if (!match(TokenType.END)) {
            // sqlglot: `ELSE interval END` recovery — the INTERVAL parse eagerly consumes
            // the END token as its unit column; rewrite back to a plain column.
            if (default is Interval &&
                (default.args["this"] as? Expression)?.name?.uppercase() == "END"
            ) {
                default = Column(args("this" to Identifier(args("this" to "interval", "quoted" to false))))
            } else {
                raiseError("Expected END after CASE", prevToken)
            }
        }

        return expression(
            Case(args("this" to caseExpression, "ifs" to ifs, "default" to default)),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_if
    fun parseIf(): Expression? {
        val this_: Expression?
        if (match(TokenType.L_PAREN)) {
            val fnArgs = parseCsv { parseAlias(parseAssignment(), explicit = true) }
            // sqlglot: exp.If.from_arg_list(args)
            this_ = validateExpression(
                If(
                    args(
                        "this" to fnArgs.getOrNull(0),
                        "true" to fnArgs.getOrNull(1),
                        "false" to fnArgs.getOrNull(2),
                    )
                )
            )
            matchRParen()
        } else {
            val startIndex = index - 1

            if (noParenIfCommands && startIndex == 0) {
                return parseAsCommand(prevToken)
            }

            val condition = parseDisjunction()

            if (condition == null) {
                retreat(startIndex)
                return null
            }

            match(TokenType.THEN)
            val trueExpr = parseDisjunction()
            val falseExpr = if (match(TokenType.ELSE)) parseDisjunction() else null
            match(TokenType.END)
            this_ = expression(If(args("this" to condition, "true" to trueExpr, "false" to falseExpr)))
        }

        return this_
    }

    // sqlglot: Parser._parse_cast
    fun parseCast(strict: kotlin.Boolean, safe: kotlin.Boolean? = null): Expression? {
        val this_ = parseAssignment()

        if (!match(TokenType.ALIAS)) {
            if (match(TokenType.COMMA)) {
                // sqlglot: exp.CastToStrType — not ported.
                return raiseError("CAST(x, 'type') is not supported yet")
            }

            raiseError("Expected AS after CAST")
        }

        var to = parseTypes(withCollation = true)

        var default: Expression? = null
        if (match(TokenType.DEFAULT)) {
            default = parseBitwise()
            matchTextSeq("ON", "CONVERSION", "ERROR")
        }

        if (matchSet(setOf(TokenType.FORMAT, TokenType.COMMA))) {
            // sqlglot: exp.StrToDate / exp.StrToTime format casts — not ported.
            return raiseError("CAST ... FORMAT is not supported yet")
        } else if (to == null) {
            raiseError("Expected TYPE after CAST")
        } else if (to.thisArg == DType.CHAR && match(TokenType.CHARACTER_SET)) {
            // sqlglot: `to = exp.DType.CHARACTER_SET.into_expr(kind=self._parse_var_or_string())`
            to = DataType(args("this" to DType.CHARACTER_SET, "kind" to parseVarOrString()))
        }

        return buildCast(
            strict = strict,
            this_ = this_,
            to = to,
            format = null,
            safe = safe,
            action = parseVarFromOptions(castActions, raiseUnmatched = false),
            default = default,
        )
    }

    // sqlglot: Parser.build_cast — exp.TryCast not ported (base STRICT_CAST is true, so
    // the non-strict branch never fires).
    fun buildCast(
        strict: kotlin.Boolean,
        this_: Expression?,
        to: Expression?,
        format: Expression? = null,
        safe: kotlin.Boolean? = null,
        action: Expression? = null,
        default: Expression? = null,
    ): Expression? {
        val kwargs = args(
            "this" to this_,
            "to" to to,
            "format" to format,
            "safe" to safe,
            "action" to action,
            "default" to default,
        ).toMutableMap()

        if (!strict) {
            // sqlglot: TryCast(requires_string=dialect.TRY_CAST_REQUIRES_STRING) — base: None
            kwargs["requires_string"] = null
            return expression(TryCast(kwargs))
        }

        return expression(Cast(kwargs))
    }

    // sqlglot: Parser._parse_convert
    fun parseConvert(strict: kotlin.Boolean, safe: kotlin.Boolean? = null): Expression? {
        val this_ = parseBitwise()

        val to: Expression? = if (match(TokenType.USING)) {
            DataType(args("this" to DType.CHARACTER_SET, "kind" to parseCharsetName()))
        } else if (match(TokenType.COMMA)) {
            parseTypes()
        } else {
            null
        }

        return buildCast(strict = strict, this_ = this_, to = to, safe = safe)
    }

    // sqlglot: Parser._parse_extract
    fun parseExtract(): Expression {
        val this_ = parseFunction() ?: parseVarOrString(upper = true)

        if (match(TokenType.FROM)) {
            return expression(Extract(args("this" to this_, "expression" to parseBitwise())))
        }

        if (!match(TokenType.COMMA)) {
            raiseError("Expected FROM or comma after EXTRACT", prevToken)
        }

        return expression(Extract(args("this" to this_, "expression" to parseBitwise())))
    }

    // sqlglot: Parser._parse_char
    fun parseChar(): Expression {
        val expressions = parseCsv { parseAssignment() }
        val charset: kotlin.Any? = if (match(TokenType.USING)) parseCharsetName() else false
        return expression(Chr(args("expressions" to expressions, "charset" to charset)))
    }

    // sqlglot: Parser._parse_charset_name
    protected open fun parseCharsetName(): Expression? =
        parseVar(tokens = setOf(TokenType.BINARY, TokenType.IDENTIFIER))

    // sqlglot: Parser._parse_position
    fun parsePosition(haystackFirst: kotlin.Boolean = false): Expression {
        val posArgs = parseCsv { parseBitwise() }

        if (match(TokenType.IN)) {
            return expression(
                StrPosition(args("this" to parseBitwise(), "substr" to posArgs.getOrNull(0)))
            )
        }

        val haystack: Expression?
        val needle: Expression?
        if (haystackFirst) {
            haystack = posArgs.getOrNull(0)
            needle = posArgs.getOrNull(1)
        } else {
            haystack = posArgs.getOrNull(1)
            needle = posArgs.getOrNull(0)
        }

        return expression(
            StrPosition(
                args("this" to haystack, "substr" to needle, "position" to posArgs.getOrNull(2))
            )
        )
    }

    // sqlglot: Parser._parse_overlay
    fun parseOverlay(): Expression {
        fun parseOverlayArg(text: String): Expression? =
            if (match(TokenType.COMMA) || matchTextSeq(text)) parseBitwise() else null

        return expression(
            Overlay(
                args(
                    "this" to parseBitwise(),
                    "expression" to parseOverlayArg("PLACING"),
                    "from_" to parseOverlayArg("FROM"),
                    "for_" to parseOverlayArg("FOR"),
                )
            )
        )
    }

    // sqlglot: Parser._parse_xml_element
    fun parseXmlElement(): Expression {
        val evalname: kotlin.Boolean?
        val this_: Expression?
        if (matchTextSeq("EVALNAME")) {
            evalname = true
            this_ = parseBitwise()
        } else {
            evalname = null
            matchTextSeq("NAME")
            this_ = parseIdVar()
        }

        // sqlglot: `self._match(COMMA) and self._parse_csv(...)` — False on no comma
        val exprs: kotlin.Any = if (match(TokenType.COMMA)) parseCsv { parseBitwise() } else false
        return expression(
            XMLElement(
                args(
                    "this" to this_,
                    "expressions" to exprs,
                    "evalname" to evalname,
                )
            )
        )
    }

    // sqlglot: Parser._parse_xml_table
    fun parseXmlTable(): Expression {
        var namespaces: List<Expression>? = null
        var passing: List<Expression>? = null
        var columns: List<Expression>? = null

        if (matchTextSeq("XMLNAMESPACES", "(")) {
            namespaces = parseXmlNamespace()
            matchTextSeq(")", ",")
        }

        val this_ = parseString()

        if (matchTextSeq("PASSING")) {
            // The BY VALUE keywords are optional and are provided for semantic clarity
            matchTextSeq("BY", "VALUE")
            passing = parseCsv { parseColumn() }
        }

        val byRef = matchTextSeq("RETURNING", "SEQUENCE", "BY", "REF")

        if (matchTextSeq("COLUMNS")) {
            columns = parseCsv { parseFieldDef() }
        }

        return expression(
            XMLTable(
                args(
                    "this" to this_,
                    "namespaces" to namespaces,
                    "passing" to passing,
                    "columns" to columns,
                    "by_ref" to byRef,
                )
            )
        )
    }

    // sqlglot: Parser._parse_xml_namespace
    protected fun parseXmlNamespace(): List<Expression> {
        val namespaces = mutableListOf<Expression>()

        while (true) {
            val uri = if (match(TokenType.DEFAULT)) {
                parseString()
            } else {
                parseAlias(parseString())
            }
            namespaces.add(expression(XMLNamespace(args("this" to uri))))
            if (!match(TokenType.COMMA)) break
        }

        return namespaces
    }

    // sqlglot: Parser._parse_substring
    fun parseSubstring(): Expression {
        val subArgs: MutableList<Expression?> = parseCsv { parseBitwise() }.toMutableList()

        var start: Expression? = null
        var length: Expression? = null

        while (currToken.exists) {
            if (match(TokenType.FROM)) {
                start = parseBitwise()
            } else if (match(TokenType.FOR)) {
                if (start == null) start = Literal.number("1")
                length = parseBitwise()
            } else {
                break
            }
        }

        if (start != null) subArgs.add(start)
        if (length != null) subArgs.add(length)

        val substring = fromArgList(listOf("this", "start", "length"), false) { Substring(it) }(subArgs)
        return validateExpression(substring)
    }

    // sqlglot: Parser._parse_trim (TRIM_PATTERN_FIRST=false in the base parser)
    fun parseTrim(): Expression {
        var position: String? = null
        var collation: Expression? = null
        var trimExpression: Expression? = null

        if (matchTexts(trimTypes)) {
            position = prevToken.text.uppercase()
        }

        var this_ = parseBitwise()
        if (matchSet(setOf(TokenType.FROM, TokenType.COMMA))) {
            val invertOrder = prevToken.tokenType == TokenType.FROM
            trimExpression = parseBitwise()

            if (invertOrder) {
                val tmp = this_
                this_ = trimExpression
                trimExpression = tmp
            }
        }

        if (match(TokenType.COLLATE)) {
            collation = parseBitwise()
        }

        return expression(
            Trim(
                args(
                    "this" to this_,
                    "position" to position,
                    "expression" to trimExpression,
                    "collation" to collation,
                )
            )
        )
    }

    // sqlglot: Parser._parse_normalize
    fun parseNormalize(): Expression =
        expression(
            Normalize(
                args(
                    "this" to parseBitwise(),
                    "form" to (if (match(TokenType.COMMA)) parseVar() else false),
                )
            )
        )

    // sqlglot: Parser._parse_ceil_floor
    fun parseCeilFloor(factory: NodeFactory): Expression {
        val fnArgs = parseCsv { parseLambda() }
        return expression(
            factory(
                args(
                    "this" to fnArgs.getOrNull(0),
                    "decimals" to fnArgs.getOrNull(1),
                    "to" to if (matchTextSeq("TO")) parseVar() else null,
                )
            )
        )
    }

    // sqlglot: Parser._parse_max_min_by
    fun parseMaxMinBy(factory: NodeFactory): Expression {
        val fnArgs = mutableListOf<Expression>()

        if (match(TokenType.DISTINCT)) {
            fnArgs.add(expression(Distinct(args("expressions" to listOfNotNull(parseLambda())))))
            match(TokenType.COMMA)
        }

        fnArgs.addAll(parseFunctionArgs())

        return expression(
            factory(
                args(
                    "this" to fnArgs.getOrNull(0),
                    "expression" to fnArgs.getOrNull(1),
                    "count" to fnArgs.getOrNull(2),
                )
            )
        )
    }

    // sqlglot: Parser._parse_decode
    fun parseDecode(): Expression {
        val fnArgs = parseCsv { parseDisjunction() }

        if (fnArgs.size < 3) {
            return expression(
                Decode(args("this" to fnArgs.getOrNull(0), "charset" to fnArgs.getOrNull(1)))
            )
        }

        return expression(DecodeCase(args("expressions" to fnArgs)))
    }

    // sqlglot: Parser._parse_json_key_value
    protected fun parseJsonKeyValue(): Expression? {
        matchTextSeq("KEY")
        val key = parseColumn()
        matchSet(jsonKeyValueSeparatorTokens)
        matchTextSeq("VALUE")
        val value = parseBitwise()

        if (key == null && value == null) return null
        return expression(JSONKeyValue(args("this" to key, "expression" to value)))
    }

    // sqlglot: Parser._parse_format_json
    protected fun parseFormatJson(this_: Expression?): Expression? {
        if (this_ == null || !matchTextSeq("FORMAT", "JSON")) return this_
        return expression(FormatJson(args("this" to this_)))
    }

    // sqlglot: Parser._parse_on_handling
    protected fun parseOnHandling(on: String, vararg values: String): kotlin.Any? {
        for (value in values) {
            if (matchTextSeq(value, "ON", on)) return "$value ON $on"
        }

        val startIndex = index
        if (match(TokenType.DEFAULT)) {
            val defaultValue = parseBitwise()
            if (matchTextSeq("ON", on)) return defaultValue
            retreat(startIndex)
        }

        return null
    }

    // sqlglot: Parser._parse_json_object
    fun parseJsonObject(agg: kotlin.Boolean = false): Expression {
        val star = parseStar()
        val expressions: List<Expression?> = if (star != null) {
            listOf(star)
        } else {
            parseCsv { parseFormatJson(parseJsonKeyValue()) }
        }
        val nullHandling = parseOnHandling("NULL", "NULL", "ABSENT")

        var uniqueKeys: kotlin.Boolean? = null
        if (matchTextSeq("WITH", "UNIQUE")) {
            uniqueKeys = true
        } else if (matchTextSeq("WITHOUT", "UNIQUE")) {
            uniqueKeys = false
        }

        matchTextSeq("KEYS")

        val returnType: kotlin.Any? = if (matchTextSeq("RETURNING")) parseFormatJson(parseType()) else false
        val encoding: kotlin.Any? = if (matchTextSeq("ENCODING")) parseVar() else false

        val factory: NodeFactory = if (agg) { a -> JSONObjectAgg(a) } else { a -> JSONObject(a) }
        return expression(
            factory(
                args(
                    "expressions" to expressions,
                    "null_handling" to nullHandling,
                    "unique_keys" to uniqueKeys,
                    "return_type" to returnType,
                    "encoding" to encoding,
                )
            )
        )
    }

    // sqlglot: Parser._parse_json_column_def
    // Note: like Python, this only implements the "JSON_value_column" part.
    protected fun parseJsonColumnDef(): Expression {
        val this_: Expression?
        val ordinality: kotlin.Boolean?
        val kind: Expression?
        val nested: kotlin.Boolean?
        if (!matchTextSeq("NESTED")) {
            this_ = parseIdVar()
            // sqlglot: self._match_pair(...) — False (kept in args) when absent
            ordinality = matchPair(TokenType.FOR, TokenType.ORDINALITY)
            kind = parseTypes(allowIdentifiers = false)
            nested = null
        } else {
            this_ = null
            ordinality = null
            kind = null
            nested = true
        }

        // sqlglot: `self._match_text_seq(...) and ...` — False (kept in args) when absent
        val formatJson: kotlin.Boolean = matchTextSeq("FORMAT", "JSON")
        val path: kotlin.Any? = if (matchTextSeq("PATH")) parseString() else false
        val nestedSchema: Expression? = if (nested == true) parseJsonSchema() else null

        return expression(
            JSONColumnDef(
                args(
                    "this" to this_,
                    "kind" to kind,
                    "path" to path,
                    "nested_schema" to nestedSchema,
                    "ordinality" to ordinality,
                    "format_json" to formatJson,
                )
            )
        )
    }

    // sqlglot: Parser._parse_json_schema
    protected fun parseJsonSchema(): Expression {
        matchTextSeq("COLUMNS")
        return expression(
            JSONSchema(
                args("expressions" to parseWrappedCsv({ parseJsonColumnDef() }, optional = true))
            )
        )
    }

    // sqlglot: Parser._parse_json_table
    fun parseJsonTable(): Expression {
        val this_ = parseFormatJson(parseBitwise())
        // sqlglot: `self._match(TokenType.COMMA) and ...` — False (kept in args) when absent
        val path: kotlin.Any? = if (match(TokenType.COMMA)) parseString() else false
        val errorHandling = parseOnHandling("ERROR", "ERROR", "NULL")
        val emptyHandling = parseOnHandling("EMPTY", "ERROR", "NULL")
        val schema = parseJsonSchema()

        // sqlglot returns the node without self.expression() here
        return JSONTable(
            args(
                "this" to this_,
                "schema" to schema,
                "path" to path,
                "error_handling" to errorHandling,
                "empty_handling" to emptyHandling,
            )
        )
    }

    // sqlglot: Parser._parse_match_against
    fun parseMatchAgainst(): Expression {
        val expressions: List<Expression>
        if (matchTextSeq("TABLE")) {
            // SingleStore MATCH(TABLE ...) syntax
            val table = parseTable()
            expressions = if (table != null) listOf(table) else emptyList()
        } else {
            expressions = parseCsv { parseColumn() }
        }

        matchTextSeq(")", "AGAINST", "(")

        val this_ = parseString()

        val modifier: String? = if (matchTextSeq("IN", "NATURAL", "LANGUAGE", "MODE")) {
            if (matchTextSeq("WITH", "QUERY", "EXPANSION")) {
                "IN NATURAL LANGUAGE MODE WITH QUERY EXPANSION"
            } else {
                "IN NATURAL LANGUAGE MODE"
            }
        } else if (matchTextSeq("IN", "BOOLEAN", "MODE")) {
            "IN BOOLEAN MODE"
        } else if (matchTextSeq("WITH", "QUERY", "EXPANSION")) {
            "WITH QUERY EXPANSION"
        } else {
            null
        }

        return expression(
            MatchAgainst(
                args("this" to this_, "expressions" to expressions, "modifier" to modifier)
            )
        )
    }

    // sqlglot: Parser._parse_var_from_options
    protected fun parseVarFromOptions(
        options: Map<String, List<List<String>>>,
        raiseUnmatched: kotlin.Boolean = true,
    ): Expression? {
        val start = currToken
        if (!start.exists) return null

        var option = start.text.uppercase()
        val continuations =
            if (start.tokenType in textMatchExcludedTokens) null else options[option]

        val startIndex = index
        advance()
        var matched = false
        for (keywords in continuations ?: emptyList()) {
            if (matchTextSeq(*keywords.toTypedArray())) {
                option = "$option ${keywords.joinToString(" ")}"
                matched = true
                break
            }
        }
        if (!matched && (continuations == null || continuations.isNotEmpty())) {
            if (raiseUnmatched) raiseError("Unknown option $option")

            retreat(startIndex)
            return null
        }

        // sqlglot: exp.var(option)
        return Var(args("this" to option))
    }

    // sqlglot: Parser._parse_window
    fun parseWindow(this_: Expression?, alias: kotlin.Boolean = false): Expression? {
        var current = this_
        val func = current
        val comments = func?.comments

        // T-SQL allows the OVER (...) syntax after WITHIN GROUP.
        if (matchTextSeq("WITHIN", "GROUP")) {
            val order = parseWrapped(parseMethod = { parseOrder() })
            current = expression(WithinGroup(args("this" to current, "expression" to order)))
        }

        if (matchPair(TokenType.FILTER, TokenType.L_PAREN)) {
            match(TokenType.WHERE)
            current = expression(
                Filter(args("this" to current, "expression" to parseWhere(skipWhereToken = true)))
            )
            matchRParen()
        }

        // SQL spec defines an optional [ { IGNORE | RESPECT } NULLS ] OVER; relocate an
        // inner IgnoreNulls/RespectNulls onto the AggFunc (Oracle/Snowflake syntax).
        if (current is AggFunc) {
            val ignoreRespect = current.find(IgnoreNulls::class, RespectNulls::class)
            if (ignoreRespect != null && ignoreRespect !== current) {
                ignoreRespect.replace(ignoreRespect.args["this"])
                current = expression(
                    if (ignoreRespect is IgnoreNulls) IgnoreNulls(args("this" to current))
                    else RespectNulls(args("this" to current))
                )
            }
        }

        current = parseRespectOrIgnoreNulls(current)

        // bigquery select from window x AS (partition by ...)
        val over: String?
        if (alias) {
            over = null
            match(TokenType.ALIAS)
        } else if (!matchSet(windowBeforeParenTokens)) {
            return current
        } else {
            over = prevToken.text.uppercase()
        }

        if (!comments.isNullOrEmpty() && func != null) {
            func.popComments()
        }

        if (!match(TokenType.L_PAREN)) {
            return expression(
                Window(args("this" to current, "alias" to parseIdVar(anyToken = false), "over" to over)),
                comments = comments,
            )
        }

        val windowAlias = parseIdVar(anyToken = false, tokens = windowAliasTokens)

        var first: kotlin.Boolean? = if (match(TokenType.FIRST)) true else null
        if (matchTextSeq("LAST")) first = false

        val (partition, order) = parsePartitionAndOrder()
        val kind = if (matchSet(setOf(TokenType.ROWS, TokenType.RANGE)) || matchTextSeq("GROUPS")) {
            prevToken.text
        } else {
            null
        }

        var spec: Expression? = null
        if (kind != null) {
            match(TokenType.BETWEEN)
            val start = parseWindowSpec()

            val end: Map<String, kotlin.Any?> =
                if (match(TokenType.AND)) parseWindowSpec() else emptyMap()
            val exclude =
                if (matchTextSeq("EXCLUDE")) parseVarFromOptions(windowExcludeOptions) else null

            spec = expression(
                WindowSpec(
                    args(
                        "kind" to kind,
                        "start" to start["value"],
                        "start_side" to start["side"],
                        "end" to end["value"],
                        "end_side" to end["side"],
                        "exclude" to exclude,
                    )
                )
            )
        }

        matchRParen()

        val window = expression(
            Window(
                args(
                    "this" to current,
                    "partition_by" to partition,
                    "order" to order,
                    "spec" to spec,
                    "alias" to windowAlias,
                    "over" to over,
                    "first" to first,
                )
            ),
            comments = comments,
        )

        // This covers Oracle's FIRST/LAST syntax: aggregate KEEP (...) OVER (...)
        if (matchSet(windowBeforeParenTokens, advance = false)) {
            return parseWindow(window, alias = alias)
        }

        return window
    }

    // sqlglot: Parser._parse_partition_and_order
    protected fun parsePartitionAndOrder(): Pair<List<Expression>, Expression?> =
        parsePartitionBy() to parseOrder()

    // sqlglot: Parser._parse_window_spec
    protected fun parseWindowSpec(): Map<String, kotlin.Any?> {
        match(TokenType.BETWEEN)

        val value: kotlin.Any? = when {
            matchTextSeq("UNBOUNDED") -> "UNBOUNDED"
            matchTextSeq("CURRENT", "ROW") -> "CURRENT ROW"
            else -> parseBitwise()
        }
        val side = if (matchTexts(windowSides)) prevToken.text else null
        return mapOf("value" to value, "side" to side)
    }

    // sqlglot: Parser._parse_partition_by
    fun parsePartitionBy(): List<Expression> {
        if (match(TokenType.PARTITION_BY)) return parseCsv { parseDisjunction() }
        return emptyList()
    }

    // -----------------------------------------------------------------------
    // Aliases / identifiers / leaf parsers
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_alias
    fun parseAlias(this_: Expression?, explicit: kotlin.Boolean = false): Expression? {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) return this_

        // WINDOW is in ID_VAR_TOKENS, so it can be consumed as an implicit alias. Detect the
        // named-window clause shape (`WINDOW <ident> AS (...)`) and avoid swallowing it.
        if (canParseNamedWindow()) return this_

        val anyToken = match(TokenType.ALIAS)
        val comments = prevComments.toMutableList()

        if (explicit && !anyToken) return this_

        if (match(TokenType.L_PAREN)) {
            val aliases = expression(
                Aliases(
                    args("this" to this_, "expressions" to parseCsv { parseIdVar(anyToken) })
                ),
                comments = comments,
            )
            matchRParen(aliases)
            return aliases
        }

        var result = this_
        val alias = parseIdVar(anyToken, tokens = aliasTokens)
            ?: (if (stringAliases) parseStringAsIdentifier() else null)

        if (alias != null) {
            comments.addAll(alias.popComments())
            result = expression(Alias(args("this" to result, "alias" to alias)), comments = comments)
            val column = result.thisArg as? Expression

            // Moves the comment next to the alias in `expr /* comment */ AS alias`
            if (result.comments.isNullOrEmpty() && column != null && !column.comments.isNullOrEmpty()) {
                result.comments = column.popComments()
            }
        }

        return result
    }

    // sqlglot: Parser._parse_id_var
    fun parseIdVar(
        anyToken: kotlin.Boolean = true,
        tokens: Collection<TokenType>? = null,
    ): Expression? {
        var expression = parseIdentifier()
        if (expression == null &&
            ((anyToken && advanceAny() != null) || matchSet(tokens ?: idVarTokens))
        ) {
            val quoted = prevToken.tokenType == TokenType.STRING
            expression = identifierExpression(quoted = quoted)
        }

        return expression
    }

    // sqlglot: Parser._identifier_expression
    protected fun identifierExpression(token: Token = prevToken, quoted: kotlin.Boolean? = null): Expression =
        expression(Identifier(args("this" to token.text, "quoted" to quoted)), token)

    // sqlglot: Parser._parse_string
    fun parseString(): Expression? {
        if (matchSet(stringParsers.keys)) {
            return stringParsers.getValue(prevToken.tokenType)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_string_as_identifier
    fun parseStringAsIdentifier(): Identifier? {
        if (!match(TokenType.STRING)) return null
        // sqlglot: exp.to_identifier(text, quoted=True)
        return Identifier(args("this" to prevToken.text, "quoted" to true))
    }

    // sqlglot: Parser._parse_number
    fun parseNumber(): Expression? {
        if (matchSet(numericParsers.keys)) {
            return numericParsers.getValue(prevToken.tokenType)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_identifier
    fun parseIdentifier(): Expression? {
        if (match(TokenType.IDENTIFIER)) return identifierExpression(quoted = true)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_var
    fun parseVar(
        anyToken: kotlin.Boolean = false,
        tokens: Collection<TokenType>? = null,
        upper: kotlin.Boolean = false,
    ): Expression? {
        if ((anyToken && advanceAny() != null) ||
            match(TokenType.VAR) ||
            (tokens != null && matchSet(tokens))
        ) {
            return expression(
                Var(args("this" to if (upper) prevToken.text.uppercase() else prevToken.text))
            )
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_var_or_string
    fun parseVarOrString(upper: kotlin.Boolean = false): Expression? =
        parseString() ?: parseVar(anyToken = true, upper = upper)

    // sqlglot: Parser._parse_primary_or_var
    fun parsePrimaryOrVar(): Expression? = parsePrimary() ?: parseVar(anyToken = true)

    // sqlglot: Parser._parse_null
    fun parseNull(): Expression? {
        if (matchSet(setOf(TokenType.NULL, TokenType.UNKNOWN))) {
            return primaryParsers.getValue(TokenType.NULL)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_boolean
    fun parseBoolean(): Expression? {
        if (match(TokenType.TRUE)) return primaryParsers.getValue(TokenType.TRUE)(this, prevToken)
        if (match(TokenType.FALSE)) return primaryParsers.getValue(TokenType.FALSE)(this, prevToken)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_star
    fun parseStar(): Expression? {
        if (match(TokenType.STAR)) return primaryParsers.getValue(TokenType.STAR)(this, prevToken)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_parameter
    fun parseParameter(): Expression {
        val this_ = parseIdentifier() ?: parsePrimaryOrVar()
        return expression(Parameter(args("this" to this_)))
    }

    // sqlglot: Parser._parse_introducer
    fun parseIntroducer(token: Token): Expression {
        val literal = parsePrimary()
        if (literal != null) {
            return expression(
                Introducer(args("this" to token.text, "expression" to literal)),
                token,
            )
        }
        return identifierExpression(token)
    }

    // sqlglot: Parser._parse_locks
    fun parseLocks(): List<Expression> {
        val locks = mutableListOf<Expression>()
        while (true) {
            var update: kotlin.Boolean? = null
            var key: kotlin.Boolean? = null
            if (matchTextSeq("FOR", "UPDATE")) {
                update = true
            } else if (matchTextSeq("FOR", "SHARE") || matchTextSeq("LOCK", "IN", "SHARE", "MODE")) {
                update = false
            } else if (matchTextSeq("FOR", "KEY", "SHARE")) {
                update = false
                key = true
            } else if (matchTextSeq("FOR", "NO", "KEY", "UPDATE")) {
                update = true
                key = true
            } else {
                break
            }

            var lockExpressions: List<Expression?>? = null
            if (matchTextSeq("OF")) {
                lockExpressions = parseCsv { parseTable(schema = true) }
            }

            var wait: kotlin.Any? = null
            if (matchTextSeq("NOWAIT")) {
                wait = true
            } else if (matchTextSeq("WAIT")) {
                wait = parsePrimary()
            } else if (matchTextSeq("SKIP", "LOCKED")) {
                wait = false
            }

            locks.add(
                expression(
                    Lock(
                        args(
                            "update" to update,
                            "expressions" to lockExpressions,
                            "wait" to wait,
                            "key" to key,
                        )
                    )
                )
            )
        }

        return locks
    }

    // sqlglot: Parser._parse_session_parameter
    fun parseSessionParameter(): Expression {
        var kind: String? = null
        var this_ = parseIdVar() ?: parsePrimary()

        if (this_ != null && match(TokenType.DOT)) {
            kind = this_.name
            this_ = parseVar() ?: parsePrimary()
        }

        return expression(SessionParameter(args("this" to this_, "kind" to kind)))
    }

    // sqlglot: Parser._parse_placeholder
    fun parsePlaceholder(): Expression? {
        if (matchSet(placeholderParsers.keys)) {
            val placeholder = placeholderParsers.getValue(prevToken.tokenType)(this)
            if (placeholder != null) return placeholder

            advance(-1)
        }
        return null
    }

    // sqlglot: Parser._parse_star_op
    protected fun parseStarOp(vararg keywords: String): List<Expression>? {
        if (!matchTexts(keywords.toList())) return null
        if (match(TokenType.L_PAREN, advance = false)) {
            return parseWrappedCsv({ parseExpression() })
        }

        val expr = parseAlias(parseDisjunction(), explicit = true)
        return if (expr != null) listOf(expr) else null
    }

    // sqlglot: Parser._parse_star_ops
    fun parseStarOps(): Expression? {
        if (matchTextSeq("COLUMNS", "(", advance = false)) {
            val this_ = parseFunction()
            if (this_ is Columns) this_.set("unpack", true)
            return this_
        }

        val ilike = if (match(TokenType.ILIKE)) parseString() else null

        return expression(
            Star(
                args(
                    "ilike" to ilike,
                    "except_" to parseStarOp("EXCEPT", "EXCLUDE"),
                    "replace" to parseStarOp("REPLACE"),
                    "rename" to parseStarOp("RENAME"),
                )
            )
        )
    }

    // sqlglot: Parser._parse_select_or_expression
    fun parseSelectOrExpression(alias: kotlin.Boolean = false): Expression? =
        parseSetOperations(
            if (alias) parseAlias(parseAssignment(), explicit = true) else parseAssignment()
        ) ?: parseSelect()

    // -----------------------------------------------------------------------
    // Pipe syntax (sqlglot: _parse_pipe_syntax_* — brikk DESIGN DIVERGENCE: instead of
    // desugaring into WITH __tmpN CTE chains at parse time, we build a first-class
    // PipeQuery node with one stage node per pipe operator. Desugaring is an explicit
    // transform: ast/PipeDesugar.kt `desugarPipes`.)
    // -----------------------------------------------------------------------

    // sqlglot: Parser.PIPE_SYNTAX_TRANSFORM_PARSERS (keys)
    protected open val pipeSyntaxTransformOperators: Set<String>
        get() = setOf(
            "AGGREGATE", "AS", "DISTINCT", "EXTEND", "LIMIT", "ORDER BY",
            "PIVOT", "SELECT", "TABLESAMPLE", "UNPIVOT", "WHERE",
        )

    // sqlglot: Parser._parse_pipe_syntax_query (brikk: builds PipeQuery instead of desugaring;
    // head normalization — Subquery/FROM-less -> SELECT * FROM (...) — happens in desugarPipes)
    fun parsePipeSyntaxQuery(query: Expression): Expression? {
        val stages = mutableListOf<Expression>()

        while (match(TokenType.PIPE_GT)) {
            val startIndex = index
            val startText = currToken.text.uppercase()

            if (startText in pipeSyntaxTransformOperators) {
                val stage = parsePipeStage(startText)
                if (stage != null) stages.add(stage)
            } else {
                // The set operators (UNION, etc) and the JOIN operator have a few common
                // starting keywords, making it tricky to disambiguate them without lookahead.
                // The approach here is to try and parse a set operation and if that fails,
                // then try to parse a join operator. If that fails as well, then the operator
                // is not supported. (sqlglot: parser.py _parse_pipe_syntax_query)
                val stage = parsePipeSyntaxSetOperator() ?: parsePipeSyntaxJoin()
                if (stage == null) {
                    retreat(startIndex)
                    raiseError("Unsupported pipe syntax operator: '$startText'.")
                    break
                }
                stages.add(stage)
            }
        }

        return expression(PipeQuery(args("this" to query, "expressions" to stages)))
    }

    // sqlglot: Parser.PIPE_SYNTAX_TRANSFORM_PARSERS (brikk: each handler builds a stage node)
    protected open fun parsePipeStage(startText: String): Expression? = when (startText) {
        "AGGREGATE" -> parsePipeSyntaxAggregate()
        "AS" -> expression(PipeAs(args("alias" to parseTableAlias())))
        "DISTINCT" -> {
            advance()
            expression(PipeDistinct())
        }
        "EXTEND" -> parsePipeSyntaxExtend()
        "LIMIT" -> parsePipeSyntaxLimit()
        "ORDER BY" -> expression(PipeOrderBy(args("this" to parseOrder())))
        "PIVOT", "UNPIVOT" -> parsePipeSyntaxPivot()
        "SELECT" -> parsePipeSyntaxSelect()
        "TABLESAMPLE" -> parsePipeSyntaxTablesample()
        "WHERE" -> expression(PipeWhere(args("this" to parseWhere())))
        else -> null
    }

    // sqlglot: Parser._parse_pipe_syntax_select (brikk: builds PipeSelect)
    protected fun parsePipeSyntaxSelect(): Expression? {
        val select = parseSelect(consumePipe = false) ?: return null
        return expression(
            PipeSelect(args("expressions" to (select.args["expressions"] ?: mutableListOf<Expression>())))
        )
    }

    // sqlglot: Parser._parse_pipe_syntax_extend (brikk: builds PipeExtend)
    protected fun parsePipeSyntaxExtend(): Expression {
        matchTextSeq("EXTEND")
        return expression(PipeExtend(args("expressions" to parseExpressions())))
    }

    // sqlglot: Parser._parse_pipe_syntax_limit (brikk: builds PipeLimit; the keep-min /
    // offset-sum merge semantics live in PipeDesugar)
    protected fun parsePipeSyntaxLimit(): Expression {
        val limit = parseLimit()
        val offset = parseOffset()
        return expression(PipeLimit(args("this" to limit, "offset" to offset)))
    }

    // sqlglot: Parser._parse_pipe_syntax_aggregate_fields
    protected fun parsePipeSyntaxAggregateFields(): Expression? {
        var this_ = parseDisjunction()
        if (matchTextSeq("GROUP", "AND", advance = false)) return this_

        this_ = parseAlias(this_)

        if (matchSet(setOf(TokenType.ASC, TokenType.DESC), advance = false)) {
            val ordered = this_
            return parseOrdered({ ordered })
        }

        return this_
    }

    // sqlglot: Parser._parse_pipe_syntax_aggregate (brikk: builds PipeAggregate carrying the
    // raw parsed elements; the select/group_by/order_by rewrites live in PipeDesugar)
    protected fun parsePipeSyntaxAggregate(): Expression {
        matchTextSeq("AGGREGATE")
        val aggregates = parseCsv { parsePipeSyntaxAggregateFields() }

        var group: MutableList<Expression>? = null
        var groupAndOrder: kotlin.Boolean? = null
        if (match(TokenType.GROUP_BY)) {
            group = parseCsv { parsePipeSyntaxAggregateFields() }
        } else if (matchTextSeq("GROUP", "AND") && match(TokenType.ORDER_BY)) {
            groupAndOrder = true
            group = parseCsv { parsePipeSyntaxAggregateFields() }
        }

        return expression(
            PipeAggregate(
                args(
                    "expressions" to aggregates,
                    "group" to group,
                    "group_and_order" to groupAndOrder,
                )
            )
        )
    }

    // sqlglot: Parser._parse_pipe_syntax_set_operator (brikk: builds PipeSetOperation)
    protected fun parsePipeSyntaxSetOperator(): Expression? {
        // sqlglot passes the current query as `this`; brikk keeps the head out of the stage
        // node, so a placeholder satisfies the SetOperation arg validation.
        val firstSetop = parseSetOperation(this_ = Select()) ?: return null
        if (firstSetop !is SetOperation) return null

        // sqlglot: local _parse_and_unwrap_query
        fun parseAndUnwrapQuery(): Expression? {
            val expr = parseParen() ?: return null
            if (expr !is Subquery) {
                raiseError("Expected Subquery in pipe syntax set operation")
            }
            return unwrapSubqueries(expr)
        }

        val firstRhs = firstSetop.args["expression"] as? Expression
            ?: return null
        if (firstRhs !is Subquery) raiseError("Expected Subquery in pipe syntax set operation")
        firstSetop.set("expression", null)

        val setops = mutableListOf(unwrapSubqueries(firstRhs))
        setops.addAll(parseCsv { parseAndUnwrapQuery() })

        return expression(
            PipeSetOperation(
                args(
                    "this" to firstSetop::class.simpleName!!.uppercase(),
                    "expressions" to setops,
                    "distinct" to firstSetop.args["distinct"],
                    "by_name" to firstSetop.args["by_name"],
                    "side" to firstSetop.args["side"],
                    "kind" to firstSetop.args["kind"],
                    "on" to firstSetop.args["on"],
                )
            )
        )
    }

    /** sqlglot: Subquery.unnest — returns the first non-Subquery. */
    private fun unwrapSubqueries(expression: Expression): Expression {
        var expr = expression
        while (expr is Subquery) expr = expr.thisArg as Expression
        return expr
    }

    // sqlglot: Parser._parse_pipe_syntax_join (brikk: builds PipeJoin)
    protected fun parsePipeSyntaxJoin(): Expression? {
        val join = parseJoin() ?: return null
        return expression(PipeJoin(args("this" to join)))
    }

    // sqlglot: Parser._parse_pipe_syntax_pivot (brikk: builds PipePivot/PipeUnpivot)
    protected fun parsePipeSyntaxPivot(): Expression? {
        val unpivot = currToken.tokenType == TokenType.UNPIVOT
        val pivots = parsePivots() ?: return null
        return expression(
            if (unpivot) {
                PipeUnpivot(args("expressions" to pivots))
            } else {
                PipePivot(args("expressions" to pivots))
            }
        )
    }

    // sqlglot: Parser._parse_pipe_syntax_tablesample (brikk: builds PipeTableSample; the
    // attach-to-last-CTE semantics live in PipeDesugar)
    protected fun parsePipeSyntaxTablesample(): Expression {
        return expression(PipeTableSample(args("this" to parseTableSample())))
    }

    /** Python `isinstance(x, exp.Query)` for the ported subset (Select / set ops / Subquery). */
    protected fun isQuery(expression: Expression): kotlin.Boolean =
        expression is Select || expression is SetOperation || expression is Subquery

    /** sqlglot: Query.subquery(copy=False) with no alias. */
    protected fun subqueryOf(query: Expression?): Subquery =
        Subquery(args("this" to query, "alias" to null))
}

/**
 * sqlglot: sqlglot.parse_one — tokenize + parse and return the single statement.
 * Routes through the [dev.brikk.house.sql.dialects.Dialects] registry; dialects with
 * tokenizer tables but no Dialect port yet fall back to their tokenizer config on the
 * base parser (the pre-registry behavior).
 */
fun parseOne(sql: String, dialect: String = ""): Expression {
    val registered = dev.brikk.house.sql.dialects.Dialects.forNameOrNull(dialect)
    if (registered != null) return registered.parseOne(sql)

    val config = TokenizerConfigs.forName(dialect)
    val tokens = Tokenizer(config).tokenize(sql)
    val result = Parser(tokenizerConfig = config).parse(tokens, sql)
    return result.firstOrNull() ?: throw ParseError("No expression was parsed from '$sql'")
}
