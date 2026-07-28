package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.BigqueryGenerator

/** Generator gate for the bigquery dialect corpus — see [GeneratorCorpusGate]. */
class BigqueryGeneratorCorpusTest : GeneratorCorpusGate("bigquery", { BigqueryGenerator() })
