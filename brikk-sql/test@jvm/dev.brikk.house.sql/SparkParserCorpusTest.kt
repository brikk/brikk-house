package dev.brikk.house.sql

/**
 * Parser gate for the spark dialect corpus — see [ParserCorpusGate].
 *
 * NOTE: spark2 has no dialect-corpus JSON in sqlglot's fixtures, so the spark2 dialect is
 * gated only transitively through this spark corpus and the hive corpus (Hive -> Spark2 ->
 * Spark chain); there is no standalone spark2 corpus gate.
 */
class SparkParserCorpusTest : ParserCorpusGate("spark")
