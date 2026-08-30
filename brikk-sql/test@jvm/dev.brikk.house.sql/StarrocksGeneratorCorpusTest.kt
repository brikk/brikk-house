package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.StarrocksGenerator

/** Generator gate for the starrocks dialect corpus — see [GeneratorCorpusGate]. */
class StarrocksGeneratorCorpusTest : GeneratorCorpusGate("starrocks", { StarrocksGenerator() })
