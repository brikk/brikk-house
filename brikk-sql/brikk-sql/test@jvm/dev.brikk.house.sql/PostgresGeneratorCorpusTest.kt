package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.PostgresGenerator

/** Generator gate for the postgres dialect corpus — see [GeneratorCorpusGate]. */
class PostgresGeneratorCorpusTest : GeneratorCorpusGate("postgres", { PostgresGenerator() })
