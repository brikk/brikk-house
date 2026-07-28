package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.TrinoGenerator

/** Generator gate for the trino dialect corpus — see [GeneratorCorpusGate]. */
class TrinoGeneratorCorpusTest : GeneratorCorpusGate("trino", { TrinoGenerator() })
