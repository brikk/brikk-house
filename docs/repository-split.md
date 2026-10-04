# SQL and chDB repository split

The split starts from brikk-house commit
`f0b2782` (the 0.16.0 release documentation).

| Repository | Modules | Publication ownership |
| --- | --- | --- |
| `git@github.com:brikk/brikk-sql.git` | SQL core, metadata, verify, oracle | Four existing SQL artifacts. |
| `git@github.com:brikk/brikk-chdb.git` | chDB API, three platform natives, packaging plugin | API and three native artifacts; packaging plugin remains build-only. |
| `git@github.com:brikk/brikk-house.git` | Engine runtime, compiler plugin, tooling, smoke consumer | Local plugin/KEFS development only; no Central library publication. |

The new repositories contain path-filtered histories, including the old flat
module locations and their later grouped locations. Extraction flattens each
group back into root-level module directories. Git commit IDs are necessarily
rewritten in the extracted histories; brikk-house's original history is intact.

SQL owns its generators, committed corpora, vendor data/Doris parser, SQL
research/docs, and backlog. chDB owns its native manifests and binding design.
Compiler integration, KEFS docs, pipeline wiring, schema capture, and the private
ignored `brikk-engine/dogfood/` consumer stay in brikk-house. At the initial split,
existing ignored/untracked build artifacts were not transferred or deleted.

Subsequent ledger cleanup verified that the committed corpora, curated
`*known-failures*.json` files and ledger-enforcement code already live in
`brikk-sql`. The old root-level `brikk-sql/`, `brikk-sql-oracle/` and
`brikk-sql-verify/` directories here contained only 60 ignored generated
`*-ledger-actual.json` reports; those reports and empty directories were removed.
No curated files required another transfer. Current generated diagnostics belong
under the SQL checkout's `build/ledger-actual/`, not the Engine checkout.

All three projects pin Kotlin Toolchain **0.13.0**, using its generated shell and
Windows wrappers. Kotlin/compiler API versions remain **2.4.10**.

## Cross-repository dependencies

The versions below record the extraction baseline. Engine has since upgraded
all five SQL consumers to **0.18.0** from Maven Central; see the
[current Engine README](../README.md#dependencies) and
[current release acceptance](../brikk-engine/samples/duckdb-pipelines/SQL-018-RELEASE-ACCEPTANCE.md).

- Engine runtime, compiler plugin, and tooling consume
  `dev.brikk.house:brikk-sql-jvm:0.16.0` from Maven Central.
- SQL's heavyweight oracle consumes `dev.brikk.house:brikk-chdb:0.16.0`.
- SQL core/metadata do not load native chDB or depend on Engine.
- No source dependency points into a sibling checkout; no Git submodules are used.

Existing Maven coordinates, Java 21 compatibility for core/metadata, native
checksum pins, and optional native integration behavior are preserved. Version
bump/release decisions now belong to each repository independently.

## Publishing

Each extracted repository owns `publish.module-template.yaml`,
`publish-targets.module-template.yaml`, `publish-release.sh`, and its test,
snapshot, and release GitHub Actions workflows. POM URL/SCM fields point to the
new repository. Only that repository's four library tasks enter its release graph.
Public test jobs use placeholder credentials, never signing keys. Build, full
tests, and Maven Local publication gate snapshots and releases.

The new repositories must have access to the existing Central token and signing
secrets. Do not copy private local `.env` files to Git. Moving repository source
does not automatically extend selected-repository organization-secret access.
Validation of local publications does not prove remote authentication, signing,
or Central deployment acceptance. No release version is republished by the split.
