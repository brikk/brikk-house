package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.Args
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.args

// Hand-ported custom function builders layered on top of GENERATED_FUNCTION_BY_NAME,
// mirroring the non-from_arg_list entries of reference/sqlglot parser.py
// Parser.FUNCTIONS. Dialect-dependent flags use base-dialect defaults; dialects
// can override the whole `functions` provider on Parser.

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: parser.build_var_map
internal fun buildVarMap(argsList: List<Expression?>): Expression {
    val first = argsList.getOrNull(0)
    if (argsList.size == 1 && first is dev.brikk.house.sql.ast.Star) {
        return dev.brikk.house.sql.ast.StarMap(args("this" to first))
    }
    val keys = mutableListOf<Expression?>()
    val values = mutableListOf<Expression?>()
    for (i in argsList.indices step 2) {
        keys.add(argsList[i])
        values.add(argsList.getOrNull(i + 1))
    }
    return dev.brikk.house.sql.ast.VarMap(
        args(
            "keys" to dev.brikk.house.sql.ast.Array(args("expressions" to keys)),
            "values" to dev.brikk.house.sql.ast.Array(args("expressions" to values)),
        )
    )
}

// sqlglot: parser.build_like
internal fun buildLike(argsList: List<Expression?>): Expression {
    val like = dev.brikk.house.sql.ast.Like(
        args("this" to seqGet(argsList, 1), "expression" to seqGet(argsList, 0))
    )
    return if (argsList.size > 2) {
        dev.brikk.house.sql.ast.Escape(args("this" to like, "expression" to seqGet(argsList, 2)))
    } else like
}

// sqlglot: parser.build_logarithm (base: LOG_BASE_FIRST=true, LOG_DEFAULTS_TO_LN=false)
internal fun buildLogarithm(argsList: List<Expression?>): Expression {
    val this_ = seqGet(argsList, 0)
    val expression = seqGet(argsList, 1)
    if (expression != null) {
        return dev.brikk.house.sql.ast.Log(args("this" to this_, "expression" to expression))
    }
    return dev.brikk.house.sql.ast.Log(args("this" to this_))
}

// sqlglot: parser.build_hex (base: HEX_LOWERCASE=false)
internal fun buildHex(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.Hex(args("this" to seqGet(argsList, 0)))

// sqlglot: parser.build_lower — LOWER(HEX(..)) -> LowerHex
internal fun buildLower(argsList: List<Expression?>): Expression {
    val arg = seqGet(argsList, 0)
    return if (arg is dev.brikk.house.sql.ast.Hex) {
        dev.brikk.house.sql.ast.LowerHex(args("this" to arg.args["this"]))
    } else {
        dev.brikk.house.sql.ast.Lower(args("this" to arg))
    }
}

// sqlglot: parser.build_upper — UPPER(HEX(..)) -> Hex
internal fun buildUpper(argsList: List<Expression?>): Expression {
    val arg = seqGet(argsList, 0)
    return if (arg is dev.brikk.house.sql.ast.Hex) {
        dev.brikk.house.sql.ast.Hex(args("this" to arg.args["this"]))
    } else {
        dev.brikk.house.sql.ast.Upper(args("this" to arg))
    }
}

// sqlglot: parser.build_mod — wraps binary operands in Paren
internal fun buildMod(argsList: List<Expression?>): Expression {
    var this_ = seqGet(argsList, 0)
    var expression = seqGet(argsList, 1)
    if (this_ is dev.brikk.house.sql.ast.Binary) this_ = dev.brikk.house.sql.ast.Paren(args("this" to this_))
    if (expression is dev.brikk.house.sql.ast.Binary) {
        expression = dev.brikk.house.sql.ast.Paren(args("this" to expression))
    }
    return dev.brikk.house.sql.ast.Mod(args("this" to this_, "expression" to expression))
}

// sqlglot: parser.build_pad
internal fun buildPad(argsList: List<Expression?>, isLeft: kotlin.Boolean = true): Expression =
    dev.brikk.house.sql.ast.Pad(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "fill_pattern" to seqGet(argsList, 2),
            "is_left" to isLeft,
        )
    )

// sqlglot: parser.build_trim
internal fun buildTrim(argsList: List<Expression?>, isLeft: kotlin.Boolean = true): Expression =
    dev.brikk.house.sql.ast.Trim(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "position" to if (isLeft) "LEADING" else "TRAILING",
        )
    )

// sqlglot: parser.build_coalesce
internal fun buildCoalesce(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.Coalesce(
        args("this" to seqGet(argsList, 0), "expressions" to argsList.drop(1))
    )

// sqlglot: parser.build_locate_strposition
internal fun buildLocateStrposition(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.StrPosition(
        args(
            "this" to seqGet(argsList, 1),
            "substr" to seqGet(argsList, 0),
            "position" to seqGet(argsList, 2),
        )
    )

// sqlglot: parser.build_convert_timezone (no default source tz in base)
internal fun buildConvertTimezone(argsList: List<Expression?>): Expression {
    if (argsList.size == 2) {
        return dev.brikk.house.sql.ast.ConvertTimezone(
            args("target_tz" to seqGet(argsList, 0), "timestamp" to seqGet(argsList, 1))
        )
    }
    return fromArgList(listOf("source_tz", "target_tz", "timestamp", "options"), false) {
        dev.brikk.house.sql.ast.ConvertTimezone(it)
    }(argsList)
}

// sqlglot: parser.build_array_append / prepend / concat / remove
// (base: ARRAY_FUNCS_PROPAGATES_NULLS=false — null_propagation arg omitted when false)
internal fun buildArrayAppend(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.ArrayAppend(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "null_propagation" to false,
        )
    )

internal fun buildArrayPrepend(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.ArrayPrepend(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "null_propagation" to false,
        )
    )

internal fun buildArrayConcat(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.ArrayConcat(
        args(
            "this" to seqGet(argsList, 0),
            "expressions" to argsList.drop(1),
            "null_propagation" to false,
        )
    )

internal fun buildArrayRemove(argsList: List<Expression?>): Expression =
    dev.brikk.house.sql.ast.ArrayRemove(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "null_propagation" to false,
        )
    )

// sqlglot: Parser.FUNCTIONS custom lambda entries (base-dialect flag values baked in)
internal fun customFunctionBuilders(): kotlin.collections.Map<kotlin.String, (List<Expression?>) -> Expression> {
    val m = LinkedHashMap<kotlin.String, (List<Expression?>) -> Expression>()
    for (name in listOf("COALESCE", "IFNULL", "NVL")) m[name] = ::buildCoalesce
    m["ARRAY"] = { fnArgs -> dev.brikk.house.sql.ast.Array(args("expressions" to fnArgs)) }
    // base: ARRAY_AGG_INCLUDES_NULLS=true -> nulls_excluded = None (omitted)
    m["ARRAYAGG"] = { fnArgs -> dev.brikk.house.sql.ast.ArrayAgg(args("this" to seqGet(fnArgs, 0))) }
    m["ARRAY_AGG"] = m.getValue("ARRAYAGG")
    m["ARRAY_APPEND"] = ::buildArrayAppend
    m["ARRAY_CAT"] = ::buildArrayConcat
    m["ARRAY_CONCAT"] = ::buildArrayConcat
    m["ARRAY_INTERSECT"] = { fnArgs ->
        dev.brikk.house.sql.ast.ArrayIntersect(args("expressions" to fnArgs))
    }
    m["ARRAY_INTERSECTION"] = m.getValue("ARRAY_INTERSECT")
    m["ARRAY_PREPEND"] = ::buildArrayPrepend
    m["ARRAY_REMOVE"] = ::buildArrayRemove
    m["COUNT"] = { fnArgs ->
        dev.brikk.house.sql.ast.Count(
            args(
                "this" to seqGet(fnArgs, 0),
                "expressions" to fnArgs.drop(1),
                "big_int" to true,
            )
        )
    }
    // base: STRICT_STRING_CONCAT=false -> safe=true; CONCAT[_WS]_COALESCE=false
    m["CONCAT"] = { fnArgs ->
        dev.brikk.house.sql.ast.Concat(
            args("expressions" to fnArgs, "safe" to true, "coalesce" to false)
        )
    }
    m["CONCAT_WS"] = { fnArgs ->
        dev.brikk.house.sql.ast.ConcatWs(
            args("expressions" to fnArgs, "safe" to true, "coalesce" to false)
        )
    }
    m["CONVERT_TIMEZONE"] = ::buildConvertTimezone
    m["DATE_TO_DATE_STR"] = { fnArgs ->
        dev.brikk.house.sql.ast.Cast(
            args(
                "this" to seqGet(fnArgs, 0),
                "to" to dev.brikk.house.sql.ast.DataType(args("this" to dev.brikk.house.sql.ast.DType.TEXT)),
            )
        )
    }
    m["GENERATE_DATE_ARRAY"] = { fnArgs ->
        dev.brikk.house.sql.ast.GenerateDateArray(
            args(
                "start" to seqGet(fnArgs, 0),
                "end" to seqGet(fnArgs, 1),
                "step" to (seqGet(fnArgs, 2) ?: dev.brikk.house.sql.ast.Interval(
                    args(
                        "this" to Literal.string("1"),
                        "unit" to dev.brikk.house.sql.ast.Var(args("this" to "DAY")),
                    )
                )),
            )
        )
    }
    // base: UUID_IS_STRING_TYPE=false -> is_string = None (omitted)
    m["GENERATE_UUID"] = { _ -> dev.brikk.house.sql.ast.Uuid() }
    m["GLOB"] = { fnArgs ->
        dev.brikk.house.sql.ast.Glob(
            args("this" to seqGet(fnArgs, 1), "expression" to seqGet(fnArgs, 0))
        )
    }
    // base: LEAST_GREATEST_IGNORES_NULLS=true
    m["GREATEST"] = { fnArgs ->
        dev.brikk.house.sql.ast.Greatest(
            args("this" to seqGet(fnArgs, 0), "expressions" to fnArgs.drop(1), "ignore_nulls" to true)
        )
    }
    m["LEAST"] = { fnArgs ->
        dev.brikk.house.sql.ast.Least(
            args("this" to seqGet(fnArgs, 0), "expressions" to fnArgs.drop(1), "ignore_nulls" to true)
        )
    }
    m["HEX"] = ::buildHex
    m["LIKE"] = ::buildLike
    m["LOG"] = ::buildLogarithm
    m["LOG2"] = { fnArgs ->
        dev.brikk.house.sql.ast.Log(
            args("this" to Literal.number("2"), "expression" to seqGet(fnArgs, 0))
        )
    }
    m["LOG10"] = { fnArgs ->
        dev.brikk.house.sql.ast.Log(
            args("this" to Literal.number("10"), "expression" to seqGet(fnArgs, 0))
        )
    }
    m["LOWER"] = ::buildLower
    m["LPAD"] = { fnArgs -> buildPad(fnArgs) }
    m["LEFTPAD"] = { fnArgs -> buildPad(fnArgs) }
    m["LTRIM"] = { fnArgs -> buildTrim(fnArgs) }
    m["MOD"] = ::buildMod
    m["RIGHTPAD"] = { fnArgs -> buildPad(fnArgs, isLeft = false) }
    m["RPAD"] = { fnArgs -> buildPad(fnArgs, isLeft = false) }
    m["RTRIM"] = { fnArgs -> buildTrim(fnArgs, isLeft = false) }
    m["SCOPE_RESOLUTION"] = { fnArgs ->
        if (fnArgs.size != 2) {
            dev.brikk.house.sql.ast.ScopeResolution(args("expression" to seqGet(fnArgs, 0)))
        } else {
            dev.brikk.house.sql.ast.ScopeResolution(
                args("this" to seqGet(fnArgs, 0), "expression" to seqGet(fnArgs, 1))
            )
        }
    }
    m["STRPOS"] = fromArgList(
        listOf("this", "substr", "position", "occurrence"), false
    ) { dev.brikk.house.sql.ast.StrPosition(it) }
    m["CHARINDEX"] = ::buildLocateStrposition
    m["INSTR"] = m.getValue("STRPOS")
    m["LOCATE"] = ::buildLocateStrposition
    m["TIME_TO_TIME_STR"] = { fnArgs ->
        dev.brikk.house.sql.ast.Cast(
            args(
                "this" to seqGet(fnArgs, 0),
                "to" to dev.brikk.house.sql.ast.DataType(args("this" to dev.brikk.house.sql.ast.DType.TEXT)),
            )
        )
    }
    m["TO_HEX"] = ::buildHex
    m["TS_OR_DS_TO_DATE_STR"] = { fnArgs ->
        dev.brikk.house.sql.ast.Substring(
            args(
                "this" to dev.brikk.house.sql.ast.Cast(
                    args(
                        "this" to seqGet(fnArgs, 0),
                        "to" to dev.brikk.house.sql.ast.DataType(
                            args("this" to dev.brikk.house.sql.ast.DType.TEXT)
                        ),
                    )
                ),
                "start" to Literal.number("1"),
                "length" to Literal.number("10"),
            )
        )
    }
    m["UNNEST"] = { fnArgs ->
        dev.brikk.house.sql.ast.Unnest(
            args("expressions" to listOfNotNull(seqGet(fnArgs, 0)))
        )
    }
    m["UPPER"] = ::buildUpper
    m["UUID"] = { _ -> dev.brikk.house.sql.ast.Uuid() }
    m["UUID_STRING"] = { fnArgs ->
        dev.brikk.house.sql.ast.Uuid(
            args("this" to seqGet(fnArgs, 0), "name" to seqGet(fnArgs, 1))
        )
    }
    m["VAR_MAP"] = ::buildVarMap
    return m
}
