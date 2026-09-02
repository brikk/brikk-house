# token-corpus

Generated Python-oracle token corpus for the brikk-sql tokenizer differential test.

- Source SQL: `reference/sqlglot/tests/fixtures/identity.sql` from sqlglot's
  MIT-licensed test fixtures (copyright Toby Mao, see `reference/sqlglot/LICENSE`).
- sqlglot version pin: `v30.17.0-93-gdcc36544a`.
- One JSON file per dialect; each case holds the exact token stream produced by
  `Dialect.get_or_raise(<dialect> or None).tokenize(sql)`.

Regenerate with:

```sh
python3 tools/gen_token_corpus.py
```
