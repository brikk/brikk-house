package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.ClickhouseGenerator

/** Generator gate for the clickhouse dialect corpus — see [GeneratorCorpusGate]. */
class ClickhouseGeneratorCorpusTest : GeneratorCorpusGate("clickhouse", { ClickhouseGenerator() })
