package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.HiveGenerator

/** Generator gate for the hive dialect corpus — see [GeneratorCorpusGate]. */
class HiveGeneratorCorpusTest : GeneratorCorpusGate("hive", { HiveGenerator() })
