package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisGenerator

/** Generator gate for the doris dialect corpus — see [GeneratorCorpusGate]. */
class DorisGeneratorCorpusTest : GeneratorCorpusGate("doris", { DorisGenerator() })
