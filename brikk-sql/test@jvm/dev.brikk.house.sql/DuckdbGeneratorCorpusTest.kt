package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DuckdbGenerator

/** Generator gate for the duckdb dialect corpus — see [GeneratorCorpusGate]. */
class DuckdbGeneratorCorpusTest : GeneratorCorpusGate("duckdb", { DuckdbGenerator() })
