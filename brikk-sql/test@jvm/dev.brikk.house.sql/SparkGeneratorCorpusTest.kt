package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.SparkGenerator

/** Generator gate for the spark dialect corpus — see [GeneratorCorpusGate]. */
class SparkGeneratorCorpusTest : GeneratorCorpusGate("spark", { SparkGenerator() })
