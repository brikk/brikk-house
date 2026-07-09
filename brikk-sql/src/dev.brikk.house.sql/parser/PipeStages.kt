package dev.brikk.house.sql.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializable projection of a pipe-stage split, matching brikk's stage-document shape:
 *
 * ```json
 * {
 *   "dialect": "bigquery",
 *   "token_count": 36,
 *   "pipe_operator_count": 3,
 *   "raw_stages": ["FROM Produce", "WHERE item != :varthing", ...]
 * }
 * ```
 */
@Serializable
data class PipeStageDocument(
    val dialect: String,
    @SerialName("token_count") val tokenCount: Int,
    @SerialName("pipe_operator_count") val pipeOperatorCount: Int,
    @SerialName("raw_stages") val rawStages: List<String>,
) {
    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY_JSON else PLAIN_JSON).encodeToString(serializer(), this)
}

private val PLAIN_JSON = Json
private val PRETTY_JSON = Json { prettyPrint = true }

/**
 * One pipe stage: the raw SQL slice between `|>` operators (or before the first one),
 * with its tokens and best-effort leading-operator classification.
 *
 * [start]/[endInclusive] are char offsets into the original SQL covering the first
 * through last token of the stage (surrounding whitespace excluded, internal
 * whitespace preserved).
 */
class PipeStage(
    val rawSql: String,
    val operator: String?,
    val start: Int,
    val endInclusive: Int,
    val tokens: List<Token>,
)

/** Full result of splitting a pipe query into stages. */
class PipeSplitResult(
    val dialect: String,
    val sql: String,
    val tokens: List<Token>,
    val stages: List<PipeStage>,
    val pipeOperatorCount: Int,
) {
    fun toDocument(): PipeStageDocument = PipeStageDocument(
        dialect = dialect,
        tokenCount = tokens.size,
        pipeOperatorCount = pipeOperatorCount,
        rawStages = stages.map { it.rawSql },
    )
}

/**
 * Splits a pipe-syntax SQL query (`FROM t |> WHERE ... |> ...`) into raw stages.
 *
 * Stage boundaries are `|>` (TokenType.PIPE_GT) tokens at bracket depth 0. Because
 * splitting happens on the token stream, `|>` sequences inside string literals,
 * comments, quoted identifiers, or parenthesized subpipelines never split a stage —
 * this is precisely why this is tokenizer-based rather than text-based.
 *
 * This is Phase-0 machinery: raw text slices plus tokens, no AST. Full pipe-operator
 * parsing into first-class stage nodes lands with the parser (Phase 4 of the plan).
 */
object PipeStageSplitter {

    /**
     * [config] defaults to null, in which case it is resolved from [dialect] via
     * [TokenizerConfigs.forName] (unknown dialects fall back to the base config).
     * Pass an explicit [config] to override.
     */
    fun split(
        sql: String,
        dialect: String = "",
        config: TokenizerConfig? = null,
    ): PipeSplitResult {
        val tokens = Tokenizer(config ?: TokenizerConfigs.forName(dialect)).tokenize(sql)

        val stages = mutableListOf<PipeStage>()
        var pipeOperatorCount = 0
        var depth = 0
        var stageStart = 0

        fun closeStage(endExclusive: Int) {
            val stageTokens = tokens.subList(stageStart, endExclusive)
            if (stageTokens.isNotEmpty()) {
                val first = stageTokens.first()
                val last = stageTokens.last()
                stages.add(
                    PipeStage(
                        rawSql = sql.substring(first.start, last.end + 1),
                        operator = detectOperator(stageTokens),
                        start = first.start,
                        endInclusive = last.end,
                        tokens = stageTokens.toList(),
                    )
                )
            }
        }

        tokens.forEachIndexed { i, token ->
            when (token.tokenType) {
                TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE -> depth++
                TokenType.R_PAREN, TokenType.R_BRACKET, TokenType.R_BRACE -> depth--
                TokenType.PIPE_GT -> if (depth == 0) {
                    pipeOperatorCount++
                    closeStage(i)
                    stageStart = i + 1
                }
                else -> {}
            }
        }
        closeStage(tokens.size)

        return PipeSplitResult(
            dialect = dialect,
            sql = sql,
            tokens = tokens,
            stages = stages,
            pipeOperatorCount = pipeOperatorCount,
        )
    }

    /**
     * Best-effort classification of a stage's leading pipe operator, normalized to the
     * GoogleSQL operator names (see reference/googlesql/docs/pipe-syntax.md). JOIN-family
     * and set-operator prefixes are resolved; anything else is the first token's text
     * uppercased (e.g. `AGGREGATE`, which is not a reserved keyword and tokenizes as VAR).
     */
    private fun detectOperator(tokens: List<Token>): String? {
        val first = tokens.firstOrNull() ?: return null

        fun tokenTypeAt(i: Int): TokenType? = tokens.getOrNull(i)?.tokenType

        return when (first.tokenType) {
            TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT -> first.text.uppercase()

            TokenType.RECURSIVE ->
                if (tokenTypeAt(1) == TokenType.UNION) "RECURSIVE UNION" else first.text.uppercase()

            // LEFT/FULL can start either a set operator (LEFT [OUTER] UNION ...) or a join
            TokenType.LEFT, TokenType.FULL -> {
                val next = tokenTypeAt(1)
                val nextNext = tokenTypeAt(2)
                when {
                    next in SET_OPS -> tokens[1].text.uppercase()
                    next == TokenType.OUTER && nextNext in SET_OPS -> tokens[2].text.uppercase()
                    else -> "JOIN"
                }
            }

            TokenType.JOIN, TokenType.CROSS, TokenType.INNER, TokenType.RIGHT,
            TokenType.NATURAL, TokenType.SEMI, TokenType.ANTI, TokenType.ASOF,
            TokenType.STRAIGHT_JOIN,
            -> "JOIN"

            else -> first.text.uppercase()
        }
    }

    private val SET_OPS = setOf(TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT)
}
