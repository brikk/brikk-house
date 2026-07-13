# Note to the brikk-sql agent — Doris live-probe results + generator bugs to fix

From the doris live-probe program (doris-focus is the verification instrument).
Everything below is committed + pushed to `origin/main` (brikk-house). Please
review the registry and — the higher-value part — the **generator mapping bugs**.

## What landed
- **Doris hazard registry is now COMPLETE** (was empty): `brikk-sql/testResources/semantics/{duckdb-doris,trino-doris}-hazards.json` — **duckdb→doris 255 pairs, trino→doris 203 pairs** (Buckets A same-name + B cross-name + C-real + resolved unclears). Generated `.kt` regenerated; `python3 tools/generate_hazards_registry.py` + `git diff --exit-code` is clean.
- Evidence + method: `docs/research/REPORT-doris-differential-probe-2026-07-13.md`; raw run artifacts under `docs/research/probe-runs/`; process handoff `docs/research/HANDOFF-doris-probe-continuation.md`.
- **Bug report: `docs/research/BUGS-doris-generator-mappings-2026-07-13.md`** ← the action item.

## ACTION 1 — fix the P1 "confident-but-wrong" generator mappings (do first)
Six DuckDB→Doris renames where **`certify` returns `ok=true` and the wrong SQL SHIPS** (verified against the live Doris FE). Full table + repros + suggested fixes in the BUGS file; summary:
1. `list_has_any(a,b)` → `a && b` — Doris `&&` is logical-AND, not array-overlap → runtime error. Want `arrays_overlap`.
2. `epoch_ms(ms)` → `from_unixtime(ms, 3)` — Doris `from_unixtime` takes SECONDS and its 2nd arg is a FORMAT STRING (literal `3`→`'3'`) → wrong.
3. `string_split_regex` / `regexp_split` → `split_by_string` — splits on the pattern as a LITERAL, not regex.
4. `struct_pack` → `STRUCT(.. AS ..)` — wrong shape.
5. `json_array_contains` → `MEMBER OF` — wrong.
6. `json_extract_scalar` — keeps quotes (should strip).

**Belt-and-braces already in place:** the registry now carries matching `divergent`
hazards for these, so `certify` will **refuse** them going forward — nothing ships
broken *through a certify-gated consumer*. But the **generator still emits wrong
SQL** for any non-gated path, so fix at the source (the `renderedSql` templates).

## ACTION 2 — audit for stale `renderedSql` templates
While re-confirming Bucket B renames against the live generator, several
`renderedSql` templates were found **stale** (didn't match what the generator now
emits). Worth a sweep of the Doris mappings against the live FE.

## ACTION 3 — lower priority (in the BUGS file)
- **P2 catalog staleness:** `array_length` — `brikk-sql-metadata` Doris catalog lists `ARRAY_LENGTH` so `certify` passes, but the live FE has no such function (`array_size` is the real one). Remove/remap.
- **P3 fail-loud (target wrong):** `strftime` leaks the internal `TS_OR_DS_TO_TIMESTAMP` node; `map_extract` list-vs-scalar shape mismatch. Currently REFUSED (safe) but the mapping is wrong.
- **Missing mappings (enhancements):** a list of DuckDB/Trino fns Doris actually supports but the generator refuses (`gcd`/`lcm`, `list_position`→`array_position`, `suffix`→`ends_with`, `is_nan`→`isnan`, `list_slice`→`array_slice` with end→length conversion, etc.). Adding these widens coverage.

## Caveats / provenance
- **trino→doris** verdicts are **Doris live-probed vs the probe-verified Trino behavior** in `trino-duckdb-hazards.json` (Trino not re-run live in this program — flagged per-row in provenance). The trino agent can re-confirm live in its project to upgrade provenance; not blocking.
- Datetime/parquet-staging probes have documented limits (the 6 surviving `unclear`s carry specific reasons — e.g. `parquet_*` can't be probed without staging temp Parquet on shared storage, which AGENTS.md forbids).

## Context
This was the Doris half of the **0.3.0 sign-off** (green — corpus baselines held: general 92, catalog+tt+view 385, stats 402, add_files 457, zero failures). The registry + P1 fixes would naturally fold into the next version bump. For scaling to more engines cheaply, see the behavior-matrix discussion in the handoff (promote `probe-runs/` raw outputs into `results/{engine}.json` so pairwise hazards become a derived artifact).
