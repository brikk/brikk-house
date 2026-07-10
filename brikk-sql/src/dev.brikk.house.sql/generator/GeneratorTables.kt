package dev.brikk.house.sql.generator

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Any as AnyNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Set as SetNode
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass

/**
 * Dispatch-map registrations and data tables for [Generator].
 *
 * sqlglot builds its dispatch from TRANSFORMS + `<name>_sql` method discovery
 * (generator.py `_build_dispatch`); we register everything explicitly here.
 * One-liner TRANSFORMS lambdas are inlined; multi-line methods live on [Generator].
 */
object GeneratorTables {

    // sqlglot: exp.Properties.Location
    enum class PropLocation {
        POST_CREATE, POST_NAME, POST_SCHEMA, POST_WITH, POST_ALIAS, POST_EXPRESSION,
        POST_INDEX, UNSUPPORTED,
    }

    // sqlglot: exp.Properties.PROPERTY_TO_NAME (inverted NAME_TO_PROPERTY)
    val PROPERTY_TO_NAME: Map<KClass<out Expression>, String> = mapOf(
        AlgorithmProperty::class to "ALGORITHM",
        AutoIncrementProperty::class to "AUTO_INCREMENT",
        CharacterSetProperty::class to "CHARACTER SET",
        ClusteredByProperty::class to "CLUSTERED_BY",
        CollateProperty::class to "COLLATE",
        SchemaCommentProperty::class to "COMMENT",
        CredentialsProperty::class to "CREDENTIALS",
        DefinerProperty::class to "DEFINER",
        DistKeyProperty::class to "DISTKEY",
        DistributedByProperty::class to "DISTRIBUTED_BY",
        DistStyleProperty::class to "DISTSTYLE",
        EngineProperty::class to "ENGINE",
        ExecuteAsProperty::class to "EXECUTE AS",
        FileFormatProperty::class to "FORMAT",
        LanguageProperty::class to "LANGUAGE",
        LocationProperty::class to "LOCATION",
        LockProperty::class to "LOCK",
        PartitionedByProperty::class to "PARTITIONED_BY",
        ReturnsProperty::class to "RETURNS",
        RowFormatProperty::class to "ROW_FORMAT",
        SortKeyProperty::class to "SORTKEY",
        EncodeProperty::class to "ENCODE",
        IncludeProperty::class to "INCLUDE",
    )

    // sqlglot: Generator.PROPERTIES_LOCATION
    val PROPERTIES_LOCATION: Map<KClass<out Expression>, PropLocation> = mapOf(
        AllowedValuesProperty::class to PropLocation.POST_SCHEMA,
        AlgorithmProperty::class to PropLocation.POST_CREATE,
        ApiProperty::class to PropLocation.POST_CREATE,
        ApplicationProperty::class to PropLocation.POST_CREATE,
        AutoIncrementProperty::class to PropLocation.POST_SCHEMA,
        AutoRefreshProperty::class to PropLocation.POST_SCHEMA,
        BackupProperty::class to PropLocation.POST_SCHEMA,
        BlockCompressionProperty::class to PropLocation.POST_NAME,
        CalledOnNullInputProperty::class to PropLocation.POST_SCHEMA,
        CatalogProperty::class to PropLocation.POST_CREATE,
        CharacterSetProperty::class to PropLocation.POST_SCHEMA,
        ChecksumProperty::class to PropLocation.POST_NAME,
        CollateProperty::class to PropLocation.POST_SCHEMA,
        ComputeProperty::class to PropLocation.POST_CREATE,
        CopyGrantsProperty::class to PropLocation.POST_SCHEMA,
        Cluster::class to PropLocation.POST_SCHEMA,
        ClusteredByProperty::class to PropLocation.POST_SCHEMA,
        ClusterProperty::class to PropLocation.POST_SCHEMA,
        DistributedByProperty::class to PropLocation.POST_SCHEMA,
        DuplicateKeyProperty::class to PropLocation.POST_SCHEMA,
        DataBlocksizeProperty::class to PropLocation.POST_NAME,
        DatabaseProperty::class to PropLocation.POST_CREATE,
        DataDeletionProperty::class to PropLocation.POST_SCHEMA,
        DefinerProperty::class to PropLocation.POST_CREATE,
        DictRange::class to PropLocation.POST_SCHEMA,
        DictProperty::class to PropLocation.POST_SCHEMA,
        DynamicProperty::class to PropLocation.POST_CREATE,
        DistKeyProperty::class to PropLocation.POST_SCHEMA,
        DistStyleProperty::class to PropLocation.POST_SCHEMA,
        EmptyProperty::class to PropLocation.POST_SCHEMA,
        EncodeProperty::class to PropLocation.POST_EXPRESSION,
        EngineProperty::class to PropLocation.POST_SCHEMA,
        EnviromentProperty::class to PropLocation.POST_SCHEMA,
        HandlerProperty::class to PropLocation.POST_SCHEMA,
        ParameterStyleProperty::class to PropLocation.POST_SCHEMA,
        ExecuteAsProperty::class to PropLocation.POST_SCHEMA,
        ExternalProperty::class to PropLocation.POST_CREATE,
        FallbackProperty::class to PropLocation.POST_NAME,
        FileFormatProperty::class to PropLocation.POST_WITH,
        FreespaceProperty::class to PropLocation.POST_NAME,
        GlobalProperty::class to PropLocation.POST_CREATE,
        HeapProperty::class to PropLocation.POST_WITH,
        HybridProperty::class to PropLocation.POST_CREATE,
        InheritsProperty::class to PropLocation.POST_SCHEMA,
        IcebergProperty::class to PropLocation.POST_CREATE,
        IncludeProperty::class to PropLocation.POST_SCHEMA,
        InputModelProperty::class to PropLocation.POST_SCHEMA,
        IsolatedLoadingProperty::class to PropLocation.POST_NAME,
        JournalProperty::class to PropLocation.POST_NAME,
        LanguageProperty::class to PropLocation.POST_SCHEMA,
        LikeProperty::class to PropLocation.POST_SCHEMA,
        LocationProperty::class to PropLocation.POST_SCHEMA,
        LockProperty::class to PropLocation.POST_SCHEMA,
        LockingProperty::class to PropLocation.POST_ALIAS,
        LogProperty::class to PropLocation.POST_NAME,
        MaskingProperty::class to PropLocation.POST_CREATE,
        MaterializedProperty::class to PropLocation.POST_CREATE,
        MergeBlockRatioProperty::class to PropLocation.POST_NAME,
        ModuleProperty::class to PropLocation.POST_SCHEMA,
        NetworkProperty::class to PropLocation.POST_CREATE,
        NoPrimaryIndexProperty::class to PropLocation.POST_EXPRESSION,
        OnProperty::class to PropLocation.POST_SCHEMA,
        OnCommitProperty::class to PropLocation.POST_EXPRESSION,
        Order::class to PropLocation.POST_SCHEMA,
        OutputModelProperty::class to PropLocation.POST_SCHEMA,
        PartitionedByProperty::class to PropLocation.POST_WITH,
        PartitionedOfProperty::class to PropLocation.POST_SCHEMA,
        PrimaryKey::class to PropLocation.POST_SCHEMA,
        Property::class to PropLocation.POST_WITH,
        RefreshTriggerProperty::class to PropLocation.POST_SCHEMA,
        RemoteWithConnectionModelProperty::class to PropLocation.POST_SCHEMA,
        ReturnsProperty::class to PropLocation.POST_SCHEMA,
        RollupProperty::class to PropLocation.UNSUPPORTED,
        RowAccessProperty::class to PropLocation.UNSUPPORTED,
        RowFormatProperty::class to PropLocation.POST_SCHEMA,
        RowFormatDelimitedProperty::class to PropLocation.POST_SCHEMA,
        RowFormatSerdeProperty::class to PropLocation.POST_SCHEMA,
        SampleProperty::class to PropLocation.POST_SCHEMA,
        SchemaCommentProperty::class to PropLocation.POST_SCHEMA,
        SecureProperty::class to PropLocation.POST_CREATE,
        SecurityIntegrationProperty::class to PropLocation.POST_CREATE,
        SerdeProperties::class to PropLocation.POST_SCHEMA,
        SetNode::class to PropLocation.POST_SCHEMA,
        SettingsProperty::class to PropLocation.POST_SCHEMA,
        SetProperty::class to PropLocation.POST_CREATE,
        SetConfigProperty::class to PropLocation.POST_SCHEMA,
        SharingProperty::class to PropLocation.POST_EXPRESSION,
        SequenceProperties::class to PropLocation.POST_EXPRESSION,
        TriggerProperties::class to PropLocation.POST_EXPRESSION,
        SortKeyProperty::class to PropLocation.POST_SCHEMA,
        SqlReadWriteProperty::class to PropLocation.POST_SCHEMA,
        SqlSecurityProperty::class to PropLocation.POST_SCHEMA,
        StabilityProperty::class to PropLocation.POST_SCHEMA,
        StorageHandlerProperty::class to PropLocation.POST_SCHEMA,
        StreamingTableProperty::class to PropLocation.POST_CREATE,
        StrictProperty::class to PropLocation.POST_SCHEMA,
        Tags::class to PropLocation.POST_WITH,
        TemporaryProperty::class to PropLocation.POST_CREATE,
        ToTableProperty::class to PropLocation.POST_SCHEMA,
        TransientProperty::class to PropLocation.POST_CREATE,
        TransformModelProperty::class to PropLocation.POST_SCHEMA,
        MergeTreeTTL::class to PropLocation.POST_SCHEMA,
        UnloggedProperty::class to PropLocation.POST_CREATE,
        UsingProperty::class to PropLocation.POST_EXPRESSION,
        UsingTemplateProperty::class to PropLocation.POST_SCHEMA,
        ViewAttributeProperty::class to PropLocation.POST_SCHEMA,
        VirtualProperty::class to PropLocation.POST_CREATE,
        VolatileProperty::class to PropLocation.POST_CREATE,
        WithDataProperty::class to PropLocation.POST_EXPRESSION,
        WithJournalTableProperty::class to PropLocation.POST_NAME,
        WithProcedureOptions::class to PropLocation.POST_SCHEMA,
        WithSchemaBindingProperty::class to PropLocation.POST_SCHEMA,
        WithSystemVersioningProperty::class to PropLocation.POST_SCHEMA,
        ForceProperty::class to PropLocation.POST_CREATE,
    )

    // sqlglot: Generator.TIME_PART_SINGULARS
    val TIME_PART_SINGULARS: Map<String, String> = mapOf(
        "MICROSECONDS" to "MICROSECOND",
        "SECONDS" to "SECOND",
        "MINUTES" to "MINUTE",
        "HOURS" to "HOUR",
        "DAYS" to "DAY",
        "WEEKS" to "WEEK",
        "MONTHS" to "MONTH",
        "QUARTERS" to "QUARTER",
        "YEARS" to "YEAR",
    )

    private val map = LinkedHashMap<KClass<out Expression>, GenMethod>()

    private fun reg(k: KClass<out Expression>, m: GenMethod) {
        map[k] = m
    }

    /**
     * Base dispatch map. Registration groups mirror generator.py's TRANSFORMS dict plus
     * its `<name>_sql` methods (one entry per class, subclass entries listed explicitly
     * because dispatch is exact-class, mirroring Python's `type(expression)` lookup).
     */
    val DISPATCH: Map<KClass<out Expression>, GenMethod> = run {
        // --- leaf / literal ---
        reg(Identifier::class) { e -> identifierSql(e as Identifier) }
        reg(Literal::class) { e -> literalSql(e as Literal) }
        reg(National::class) { e -> nationalSql(e as National) }
        reg(RawString::class) { e -> rawstringSql(e as RawString) }
        reg(Null::class) { e -> nullSql(e as Null) }
        reg(BooleanNode::class) { e -> booleanSql(e as BooleanNode) }
        reg(Star::class) { e -> starSql(e as Star) }
        reg(Var::class) { e -> varSql(e as Var) }
        reg(Parameter::class) { e -> parameterSql(e as Parameter) }
        reg(SessionParameter::class) { e -> sessionparameterSql(e as SessionParameter) }
        reg(Placeholder::class) { e -> placeholderSql(e as Placeholder) }

        // --- columns / tables / aliases ---
        reg(Column::class) { e -> columnSql(e as Column) }
        reg(Pseudocolumn::class) { e -> columnSql(e as Column) } // sqlglot: pseudocolumn_sql
        reg(Dot::class) { e -> dotSql(e as Dot) }
        reg(Alias::class) { e -> aliasSql(e as Alias) }
        reg(Aliases::class) { e -> aliasesSql(e as Aliases) }
        reg(TableAlias::class) { e -> tablealiasSql(e as TableAlias) }
        reg(Table::class) { e -> tableSql(e as Table) }
        reg(TableSample::class) { e -> tablesampleSql(e as TableSample) }
        reg(Pivot::class) { e -> pivotSql(e as Pivot) }
        reg(Version::class) { e -> versionSql(e as Version) }

        // --- query clauses ---
        reg(Select::class) { e -> selectSql(e as Select) }
        reg(Schema::class) { e -> schemaSql(e as Schema) }
        reg(From::class) { e -> fromSql(e as From) }
        reg(Where::class) { e -> whereSql(e as Where) }
        reg(PreWhere::class) { e -> prewhereSql(e as PreWhere) }
        reg(Group::class) { e -> groupSql(e as Group) }
        reg(GroupingSets::class) { e -> groupingsetsSql(e as GroupingSets) }
        reg(Rollup::class) { e -> rollupSql(e as Rollup) }
        reg(Cube::class) { e -> cubeSql(e as Cube) }
        reg(Having::class) { e -> havingSql(e as Having) }
        reg(Qualify::class) { e -> qualifySql(e as Qualify) }
        reg(Connect::class) { e -> connectSql(e as Connect) }
        reg(Prior::class) { e -> priorSql(e as Prior) }
        reg(Order::class) { e -> orderSql(e as Order) }
        reg(Ordered::class) { e -> orderedSql(e as Ordered) }
        reg(WithFill::class) { e -> withfillSql(e as WithFill) }
        reg(Cluster::class) { e -> clusterSql(e as Cluster) }
        reg(Distribute::class) { e -> distributeSql(e as Distribute) }
        reg(Sort::class) { e -> sortSql(e as Sort) }
        reg(Limit::class) { e -> limitSql(e as Limit) }
        reg(LimitOptions::class) { e -> limitoptionsSql(e as LimitOptions) }
        reg(Offset::class) { e -> offsetSql(e as Offset) }
        reg(Fetch::class) { e -> fetchSql(e as Fetch) }
        reg(Join::class) { e -> joinSql(e as Join) }
        reg(Lateral::class) { e -> lateralSql(e as Lateral) }
        reg(Subquery::class) { e -> subquerySql(e as Subquery) }
        reg(With::class) { e -> withSql(e as With) }
        reg(CTE::class) { e -> cteSql(e as CTE) }
        // sqlglot: TRANSFORMS[Union/Except/Intersect] -> set_operations
        reg(Union::class) { e -> setOperations(e as SetOperation) }
        reg(Except::class) { e -> setOperations(e as SetOperation) }
        reg(Intersect::class) { e -> setOperations(e as SetOperation) }
        reg(Values::class) { e -> valuesSql(e as Values) }
        reg(Into::class) { e -> intoSql(e as Into) }
        reg(Tuple::class) { e -> tupleSql(e as Tuple) }
        reg(Hint::class) { e -> hintSql(e as Hint) }
        reg(Distinct::class) { e -> distinctSql(e as Distinct) }
        reg(Lock::class) { e -> lockSql(e as Lock) }
        reg(QueryOption::class) { e -> queryoptionSql(e as QueryOption) }
        reg(Unnest::class) { e -> unnestSql(e as Unnest) }

        // --- window ---
        reg(Window::class) { e -> windowSql(e as Window) }
        reg(WindowSpec::class) { e -> windowspecSql(e as WindowSpec) }
        reg(WithinGroup::class) { e -> withingroupSql(e as WithinGroup) }
        reg(IgnoreNulls::class) { e -> ignorenullsSql(e as IgnoreNulls) }
        reg(RespectNulls::class) { e -> respectnullsSql(e as RespectNulls) }
        reg(Filter::class) { e -> filterSql(e as Filter) }
        reg(HavingMax::class) { e -> havingmaxSql(e as HavingMax) }

        // --- predicates / operators ---
        reg(And::class) { e -> andSql(e as And) }
        reg(Or::class) { e -> orSql(e as Or) }
        reg(Xor::class) { e -> xorSql(e as Xor) }
        reg(Not::class) { e -> notSql(e as Not) }
        reg(Paren::class) { e -> parenSql(e as Paren) }
        reg(Neg::class) { e -> negSql(e as Neg) }
        reg(Add::class) { e -> binary(e as Binary, "+") }
        reg(Sub::class) { e -> binary(e as Binary, "-") }
        reg(Mul::class) { e -> binary(e as Binary, "*") }
        reg(Div::class) { e -> divSql(e as Div) }
        reg(IntDiv::class) { e -> intdivSql(e as IntDiv) }
        reg(Mod::class) { e -> binary(e as Binary, "%") }
        reg(DPipe::class) { e -> dpipeSql(e as DPipe) }
        reg(EQ::class) { e -> binary(e as Binary, "=") }
        reg(NEQ::class) { e -> binary(e as Binary, "<>") }
        reg(GT::class) { e -> binary(e as Binary, ">") }
        reg(GTE::class) { e -> binary(e as Binary, ">=") }
        reg(LT::class) { e -> binary(e as Binary, "<") }
        reg(LTE::class) { e -> binary(e as Binary, "<=") }
        reg(NullSafeEQ::class) { e -> binary(e as Binary, "IS NOT DISTINCT FROM") }
        reg(NullSafeNEQ::class) { e -> binary(e as Binary, "IS DISTINCT FROM") }
        reg(PropertyEQ::class) { e -> binary(e as Binary, ":=") }
        reg(BitwiseAnd::class) { e -> binary(e as Binary, "&") }
        reg(BitwiseOr::class) { e -> binary(e as Binary, "|") }
        reg(BitwiseXor::class) { e -> binary(e as Binary, "^") }
        reg(BitwiseLeftShift::class) { e -> binary(e as Binary, "<<") }
        reg(BitwiseRightShift::class) { e -> binary(e as Binary, ">>") }
        reg(BitwiseNot::class) { e -> "~${sql(e, "this")}" }
        reg(Is::class) { e -> isSql(e as Is) }
        reg(Like::class) { e -> likeSql(e as Like) }
        reg(ILike::class) { e -> ilikeSql(e as ILike) }
        reg(Glob::class) { e -> binary(e as Binary, "GLOB") }
        reg(Match::class) { e -> binary(e as Binary, "MATCH") }
        reg(SimilarTo::class) { e -> binary(e as Binary, "SIMILAR TO") }
        reg(Escape::class) { e -> escapeSql(e as Escape) }
        reg(Overlaps::class) { e -> overlapsSql(e as Overlaps) }
        reg(Distance::class) { e -> binary(e as Binary, "<->") }
        reg(Collate::class) { e -> collateSql(e as Collate) }
        reg(Kwarg::class) { e -> kwargSql(e as Kwarg) }
        reg(Operator::class) { e -> binary(e as Binary, "") } // op produced inside binary()
        reg(Adjacent::class) { e -> binary(e as Binary, "-|-") }
        reg(ArrayContainedBy::class) { e -> binary(e as Binary, "<@") }
        reg(ArrayContainsAll::class) { e -> binary(e as Binary, "@>") }
        reg(ArrayOverlaps::class) { e -> binary(e as Binary, "&&") }
        reg(In::class) { e -> inSql(e as In) }
        reg(Between::class) { e -> betweenSql(e as Between) }
        reg(All::class) { e -> allSql(e as All) }
        reg(AnyNode::class) { e -> anySql(e as AnyNode) }
        reg(Exists::class) { e -> existsSql(e as Exists) }
        reg(Bracket::class) { e -> bracketSql(e as Bracket) }
        reg(Slice::class) { e -> sliceSql(e as Slice) }

        // --- casts / conditionals / misc scalar ---
        reg(Cast::class) { e -> castSql(e as Cast) }
        reg(TryCast::class) { e -> trycastSql(e as TryCast) }
        reg(JSONCast::class) { e -> castSql(e as Cast) }
        reg(Try::class) { e -> trySql(e as Try) }
        reg(Case::class) { e -> caseSql(e as Case) }
        reg(If::class) { e -> ifSql(e as If) }
        reg(Interval::class) { e -> intervalSql(e as Interval) }
        reg(IntervalSpan::class) { e -> "${sql(e, "this")} TO ${sql(e, "expression")}" }
        reg(Anonymous::class) { e -> anonymousSql(e as Anonymous) }
        reg(Lambda::class) { e -> lambdaSql(e as Lambda) }
        reg(AtIndex::class) { e -> atindexSql(e as AtIndex) }
        reg(AtTimeZone::class) { e -> attimezoneSql(e as AtTimeZone) }
        reg(FromTimeZone::class) { e -> fromtimezoneSql(e as FromTimeZone) }
        reg(NextValueFor::class) { e -> nextvalueforSql(e as NextValueFor) }
        reg(Comprehension::class) { e -> comprehensionSql(e as Comprehension) }
        reg(ColumnPrefix::class) { e -> columnprefixSql(e as ColumnPrefix) }
        reg(Opclass::class) { e -> opclassSql(e as Opclass) }
        reg(ConnectByRoot::class) { e -> "CONNECT_BY_ROOT ${sql(e, "this")}" }
        reg(CurrentCatalog::class) { _ -> "CURRENT_CATALOG" }
        reg(SessionUser::class) { _ -> "SESSION_USER" }
        reg(FormatJson::class) { e -> formatjsonSql(e as FormatJson) }
        reg(JoinHint::class) { e -> joinhintSql(e as JoinHint) }

        // --- functions with special rendering ---
        reg(Extract::class) { e -> extractSql(e as Extract) }
        reg(Trim::class) { e -> trimSql(e as Trim) }
        reg(Concat::class) { e -> concatSql(e as Concat) }
        reg(ConcatWs::class) { e -> concatwsSql(e as ConcatWs) }
        reg(MatchAgainst::class) { e -> matchagainstSql(e as MatchAgainst) }
        reg(CurrentDate::class) { e -> currentdateSql(e as CurrentDate) }
        reg(Log::class) { e -> logSql(e as Log) }
        reg(ToChar::class) { e -> tocharSql(e as ToChar) }
        reg(AnyValue::class) { e -> anyvalueSql(e as AnyValue) }
        reg(ArrayAgg::class) { e -> arrayaggSql(e as ArrayAgg) }
        reg(Median::class) { e -> medianSql(e as Median) }
        reg(Pad::class) { e -> padSql(e as Pad) }
        reg(Chr::class) { e -> chrSql(e as Chr) }
        reg(Rand::class) { e -> randSql(e as Rand) }
        reg(ParseJSON::class) { e -> parsejsonSql(e as ParseJSON) }
        reg(Struct::class) { e -> structSql(e as Struct) }
        reg(Ceil::class) { e -> ceilFloor(e) } // sqlglot: TRANSFORMS[Ceil]
        reg(Floor::class) { e -> ceilFloor(e) } // sqlglot: TRANSFORMS[Floor]
        reg(VarMap::class) { e -> func("MAP", e.args["keys"], e.args["values"]) }

        // --- JSON ---
        reg(JSONKeyValue::class) { e -> jsonkeyvalueSql(e as JSONKeyValue) }
        reg(JSONPath::class) { e -> jsonpathSql(e as JSONPath) }
        reg(JSONObject::class) { e -> jsonobjectSql(e) } // sqlglot: TRANSFORMS[JSONObject]
        reg(JSONObjectAgg::class) { e -> jsonobjectSql(e) }
        // sqlglot: jsonpath.JSON_PATH_PART_TRANSFORMS
        reg(JSONPathFilter::class) { e -> "?${e.thisArg}" }
        reg(JSONPathKey::class) { e -> jsonpathkeySql(e as JSONPathKey) }
        reg(JSONPathRecursive::class) { e -> "..${e.thisArg ?: ""}" }
        reg(JSONPathRoot::class) { _ -> "$" }
        reg(JSONPathScript::class) { e -> "(${e.thisArg}" }
        reg(JSONPathSelector::class) { e -> "[${jsonPathPart(e.thisArg)}]" }
        reg(JSONPathSlice::class) { e ->
            listOf(e.args["start"], e.args["end"], e.args["step"])
                .filter { it != null }
                .joinToString(":") { if (it == false) "" else jsonPathPart(it) }
        }
        reg(JSONPathSubscript::class) { e -> jsonpathsubscriptSql(e as JSONPathSubscript) }
        reg(JSONPathUnion::class) { e ->
            "[${e.expressionsArg.joinToString(",") { jsonPathPart(it) }}]"
        }
        reg(JSONPathWildcard::class) { _ -> "*" }

        // --- statements / DDL ---
        reg(Uncache::class) { e -> uncacheSql(e as Uncache) }
        reg(Cache::class) { e -> cacheSql(e as Cache) }
        reg(CharacterSet::class) { e -> charactersetSql(e as CharacterSet) }
        reg(Create::class) { e -> createSql(e as Create) }
        reg(Clone::class) { e -> cloneSql(e as Clone) }
        reg(Describe::class) { e -> describeSql(e as Describe) }
        reg(Heredoc::class) { e -> heredocSql(e as Heredoc) }
        reg(Directory::class) { e -> directorySql(e as Directory) }
        reg(Delete::class) { e -> deleteSql(e as Delete) }
        reg(Drop::class) { e -> dropSql(e as Drop) }
        reg(Insert::class) { e -> insertSql(e as Insert) }
        reg(OnConflict::class) { e -> onconflictSql(e as OnConflict) }
        reg(Returning::class) { e -> returningSql(e as Returning) }
        reg(Update::class) { e -> updateSql(e as Update) }
        reg(Kill::class) { e -> killSql(e as Kill) }
        reg(Pragma::class) { e -> pragmaSql(e as Pragma) }
        reg(SetNode::class) { e -> setSql(e as SetNode) }
        reg(SetItem::class) { e -> setitemSql(e as SetItem) }
        reg(Use::class) { e -> useSql(e as Use) }
        reg(Command::class) { e -> commandSql(e as Command) }
        reg(Comment::class) { e -> commentSql(e as Comment) }
        reg(Transaction::class) { e -> transactionSql(e as Transaction) }
        reg(Commit::class) { e -> commitSql(e as Commit) }
        reg(Rollback::class) { e -> rollbackSql(e as Rollback) }
        reg(LoadData::class) { e -> loaddataSql(e as LoadData) }
        reg(Return::class) { e -> returnSql(e as Return) }
        reg(Partition::class) { e -> partitionSql(e as Partition) }
        reg(PartitionRange::class) { e -> partitionrangeSql(e as PartitionRange) }
        reg(Grant::class) { e -> grantSql(e as Grant) }
        reg(Revoke::class) { e -> revokeSql(e as Revoke) }
        reg(GrantPrivilege::class) { e -> grantprivilegeSql(e as GrantPrivilege) }
        reg(GrantPrincipal::class) { e -> grantprincipalSql(e as GrantPrincipal) }
        reg(Analyze::class) { e -> analyzeSql(e as Analyze) }
        reg(AnalyzeSample::class) { e -> analyzesampleSql(e as AnalyzeSample) }
        reg(AnalyzeStatistics::class) { e -> analyzestatisticsSql(e as AnalyzeStatistics) }
        reg(AnalyzeHistogram::class) { e -> analyzehistogramSql(e as AnalyzeHistogram) }
        reg(AnalyzeDelete::class) { e -> analyzedeleteSql(e as AnalyzeDelete) }
        reg(AnalyzeListChainedRows::class) { e -> analyzelistchainedrowsSql(e as AnalyzeListChainedRows) }
        reg(AnalyzeValidate::class) { e -> analyzevalidateSql(e as AnalyzeValidate) }
        reg(AnalyzeColumns::class) { e -> sql(e, "this") } // sqlglot: TRANSFORMS[AnalyzeColumns]
        reg(AnalyzeWith::class) { e -> expressions(e, prefix = "WITH ", sep = " ") }

        // --- ALTER family ---
        reg(Alter::class) { e -> alterSql(e as Alter) }
        reg(AlterColumn::class) { e -> altercolumnSql(e as AlterColumn) }
        reg(AlterRename::class) { e -> alterrenameSql(e as AlterRename) }
        reg(RenameColumn::class) { e -> renamecolumnSql(e as RenameColumn) }
        reg(AlterSet::class) { e -> altersetSql(e as AlterSet) }
        reg(DropPartition::class) { e -> droppartitionSql(e as DropPartition) }
        reg(DropPrimaryKey::class) { e -> dropprimarykeySql(e as DropPrimaryKey) }
        reg(AddConstraint::class) { e -> addconstraintSql(e as AddConstraint) }
        reg(AddPartition::class) { e -> addpartitionSql(e as AddPartition) }

        // --- schema objects ---
        reg(ColumnDef::class) { e -> columndefSql(e as ColumnDef) }
        reg(ColumnPosition::class) { e -> columnpositionSql(e as ColumnPosition) }
        reg(ColumnConstraint::class) { e -> columnconstraintSql(e as ColumnConstraint) }
        reg(Constraint::class) { e -> constraintSql(e as Constraint) }
        reg(Check::class) { e -> checkSql(e as Check) }
        reg(ForeignKey::class) { e -> foreignkeySql(e as ForeignKey) }
        reg(PrimaryKey::class) { e -> primarykeySql(e as PrimaryKey) }
        reg(Reference::class) { e -> referenceSql(e as Reference) }
        reg(Index::class) { e -> indexSql(e as Index) }
        reg(IndexParameters::class) { e -> indexparametersSql(e as IndexParameters) }
        reg(DataType::class) { e -> datatypeSql(e as DataType) }
        reg(DataTypeParam::class) { e -> datatypeparamSql(e as DataTypeParam) }
        reg(UserDefinedFunction::class) { e -> userdefinedfunctionSql(e as UserDefinedFunction) }

        // --- column constraints (sqlglot: TRANSFORMS one-liners + methods) ---
        reg(AutoIncrementColumnConstraint::class) { e ->
            autoincrementcolumnconstraintSql(e as AutoIncrementColumnConstraint)
        }
        reg(CaseSpecificColumnConstraint::class) { e ->
            "${if (e.args["not_"] == true) "NOT " else ""}CASESPECIFIC"
        }
        reg(CharacterSetColumnConstraint::class) { e -> "CHARACTER SET ${sql(e, "this")}" }
        reg(CollateColumnConstraint::class) { e -> "COLLATE ${sql(e, "this")}" }
        reg(CommentColumnConstraint::class) { e -> "COMMENT ${sql(e, "this")}" }
        reg(CompressColumnConstraint::class) { e ->
            compresscolumnconstraintSql(e as CompressColumnConstraint)
        }
        reg(ComputedColumnConstraint::class) { e ->
            computedcolumnconstraintSql(e as ComputedColumnConstraint)
        }
        reg(DateFormatColumnConstraint::class) { e -> "FORMAT ${sql(e, "this")}" }
        reg(DefaultColumnConstraint::class) { e -> "DEFAULT ${sql(e, "this")}" }
        reg(EncodeColumnConstraint::class) { e -> "ENCODE ${sql(e, "this")}" }
        reg(EphemeralColumnConstraint::class) { e ->
            "EPHEMERAL${if (e.args["this"] != null) " ${sql(e, "this")}" else ""}"
        }
        reg(ExcludeColumnConstraint::class) { e -> "EXCLUDE ${sql(e, "this").trimStart()}" }
        reg(GeneratedAsIdentityColumnConstraint::class) { e ->
            generatedasidentitycolumnconstraintSql(e as GeneratedAsIdentityColumnConstraint)
        }
        reg(GeneratedAsRowColumnConstraint::class) { e ->
            "GENERATED ALWAYS AS ROW ${if (e.args["start"] == true) "START" else "END"}" +
                if (e.args["hidden"] == true) " HIDDEN" else ""
        }
        reg(InlineLengthColumnConstraint::class) { e -> "INLINE LENGTH ${sql(e, "this")}" }
        reg(InvisibleColumnConstraint::class) { _ -> "INVISIBLE" }
        reg(NotForReplicationColumnConstraint::class) { _ -> "NOT FOR REPLICATION" }
        reg(NotNullColumnConstraint::class) { e ->
            notnullcolumnconstraintSql(e as NotNullColumnConstraint)
        }
        reg(OnUpdateColumnConstraint::class) { e -> "ON UPDATE ${sql(e, "this")}" }
        reg(PathColumnConstraint::class) { e -> "PATH ${sql(e, "this")}" }
        reg(PrimaryKeyColumnConstraint::class) { e ->
            primarykeycolumnconstraintSql(e as PrimaryKeyColumnConstraint)
        }
        reg(TitleColumnConstraint::class) { e -> "TITLE ${sql(e, "this")}" }
        reg(UniqueColumnConstraint::class) { e ->
            uniquecolumnconstraintSql(e as UniqueColumnConstraint)
        }
        reg(UppercaseColumnConstraint::class) { _ -> "UPPERCASE" }
        reg(ZeroFillColumnConstraint::class) { _ -> "ZEROFILL" }
        reg(CheckColumnConstraint::class) { e ->
            // sqlglot: checkcolumnconstraint_sql
            val enforced = if (e.args["enforced"] == true) " ENFORCED" else ""
            "CHECK (${sql(e, "this")})$enforced"
        }

        // --- properties (sqlglot: TRANSFORMS one-liners + methods) ---
        reg(Properties::class) { e -> propertiesSql(e as Properties) }
        reg(AllowedValuesProperty::class) { e -> "ALLOWED_VALUES ${expressions(e, flat = true)}" }
        reg(AutoRefreshProperty::class) { e -> "AUTO REFRESH ${sql(e, "this")}" }
        reg(BackupProperty::class) { e -> "BACKUP ${sql(e, "this")}" }
        reg(CalledOnNullInputProperty::class) { _ -> "CALLED ON NULL INPUT" }
        reg(CharacterSetProperty::class) { e ->
            "${if (e.args["default"] == true) "DEFAULT " else ""}CHARACTER SET=${sql(e, "this")}"
        }
        reg(ClusterProperty::class) { e -> clusterpropertySql(e as ClusterProperty) }
        reg(ClusteredByProperty::class) { e -> clusteredbypropertySql(e as ClusteredByProperty) }
        reg(CopyGrantsProperty::class) { _ -> "COPY GRANTS" }
        reg(DictProperty::class) { e -> dictpropertySql(e as DictProperty) }
        reg(DictRange::class) { e -> dictrangeSql(e as DictRange) }
        reg(DictSubProperty::class) { e -> dictsubpropertySql(e as DictSubProperty) }
        reg(ApiProperty::class) { _ -> "API" }
        reg(ApplicationProperty::class) { _ -> "APPLICATION" }
        reg(CatalogProperty::class) { _ -> "CATALOG" }
        reg(ComputeProperty::class) { _ -> "COMPUTE" }
        reg(DatabaseProperty::class) { _ -> "DATABASE" }
        reg(DynamicProperty::class) { _ -> "DYNAMIC" }
        reg(EmptyProperty::class) { _ -> "EMPTY" }
        reg(ExecuteAsProperty::class) { e -> nakedProperty(e as Property) }
        reg(ExternalProperty::class) { _ -> "EXTERNAL" }
        reg(FallbackProperty::class) { e ->
            "${if (e.args["no"] == true) "NO " else ""}FALLBACK" +
                if (e.args["protection"] == true) " PROTECTION" else ""
        }
        reg(ForceProperty::class) { _ -> "FORCE" }
        reg(GlobalProperty::class) { _ -> "GLOBAL" }
        reg(HeapProperty::class) { _ -> "HEAP" }
        reg(HybridProperty::class) { _ -> "HYBRID" }
        reg(IcebergProperty::class) { _ -> "ICEBERG" }
        reg(InheritsProperty::class) { e -> "INHERITS (${expressions(e, flat = true)})" }
        reg(InputModelProperty::class) { e -> "INPUT${sql(e, "this")}" }
        reg(LanguageProperty::class) { e -> nakedProperty(e as Property) }
        reg(LikeProperty::class) { e -> likepropertySql(e as LikeProperty) }
        reg(LocationProperty::class) { e -> nakedProperty(e as Property) }
        reg(LockingProperty::class) { e -> lockingpropertySql(e as LockingProperty) }
        reg(LogProperty::class) { e -> "${if (e.args["no"] == true) "NO " else ""}LOG" }
        reg(MaskingProperty::class) { _ -> "MASKING" }
        reg(MaterializedProperty::class) { _ -> "MATERIALIZED" }
        reg(NetworkProperty::class) { _ -> "NETWORK" }
        reg(NoPrimaryIndexProperty::class) { _ -> "NO PRIMARY INDEX" }
        reg(OnProperty::class) { e -> "ON ${sql(e, "this")}" }
        reg(OnCommitProperty::class) { e ->
            "ON COMMIT ${if (e.args["delete"] == true) "DELETE" else "PRESERVE"} ROWS"
        }
        reg(OutputModelProperty::class) { e -> "OUTPUT${sql(e, "this")}" }
        reg(ReturnsProperty::class) { e ->
            // sqlglot: TRANSFORMS[ReturnsProperty]
            if (e.args["null"] == true) "RETURNS NULL ON NULL INPUT" else nakedProperty(e as Property)
        }
        reg(RollupProperty::class) { e -> rolluppropertySql(e as RollupProperty) }
        reg(RowAccessProperty::class) { _ -> "ROW ACCESS" }
        reg(RowFormatDelimitedProperty::class) { e ->
            rowformatdelimitedpropertySql(e as RowFormatDelimitedProperty)
        }
        reg(SampleProperty::class) { e -> "SAMPLE BY ${sql(e, "this")}" }
        reg(SecureProperty::class) { _ -> "SECURE" }
        reg(SecurityIntegrationProperty::class) { _ -> "SECURITY" }
        reg(SetConfigProperty::class) { e -> sql(e, "this") }
        reg(SetProperty::class) { e -> "${if (e.args["multi"] == true) "MULTI" else ""}SET" }
        reg(SettingsProperty::class) { e -> "SETTINGS${seg("")}${expressions(e)}" }
        reg(SharingProperty::class) { e -> "SHARING=${sql(e, "this")}" }
        reg(SqlReadWriteProperty::class) { e -> e.name }
        reg(SqlSecurityProperty::class) { e -> "SQL SECURITY ${sql(e, "this")}" }
        reg(StabilityProperty::class) { e -> e.name }
        reg(StreamingTableProperty::class) { _ -> "STREAMING" }
        reg(StrictProperty::class) { _ -> "STRICT" }
        reg(Tags::class) { e -> "TAG (${expressions(e, flat = true)})" }
        reg(TemporaryProperty::class) { _ -> "TEMPORARY" }
        reg(ToTableProperty::class) { e -> "TO ${sql(e.thisArg)}" }
        reg(TransientProperty::class) { _ -> "TRANSIENT" }
        reg(UnloggedProperty::class) { _ -> "UNLOGGED" }
        reg(UppercaseColumnConstraint::class) { _ -> "UPPERCASE" }
        reg(ViewAttributeProperty::class) { e -> "WITH ${sql(e, "this")}" }
        reg(VirtualProperty::class) { _ -> "VIRTUAL" }
        reg(VolatileProperty::class) { _ -> "VOLATILE" }
        reg(WithDataProperty::class) { e -> withdatapropertySql(e as WithDataProperty) }
        reg(WithJournalTableProperty::class) { e -> "WITH JOURNAL TABLE=${sql(e, "this")}" }
        reg(WithSchemaBindingProperty::class) { e -> "WITH SCHEMA ${sql(e, "this")}" }
        reg(OnCluster::class) { e -> onclusterSql(e as OnCluster) }

        map.toMap()
    }
}
