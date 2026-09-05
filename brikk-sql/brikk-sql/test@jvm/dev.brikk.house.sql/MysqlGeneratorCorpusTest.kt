package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.MysqlGenerator

/** Generator gate for the mysql dialect corpus — see [GeneratorCorpusGate]. */
class MysqlGeneratorCorpusTest : GeneratorCorpusGate("mysql", { MysqlGenerator() })
