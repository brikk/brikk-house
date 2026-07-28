package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.PrestoGenerator

/** Generator gate for the presto dialect corpus — see [GeneratorCorpusGate]. */
class PrestoGeneratorCorpusTest : GeneratorCorpusGate("presto", { PrestoGenerator() })
