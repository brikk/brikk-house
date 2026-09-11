# TODO - remaining evaluation findings

This active backlog contains only unresolved findings from the 2026-09-02
quality and correctness evaluation. Resolved findings are removed rather than
retained as completed-item noise.

`brikk-sql` is pinned to SQLGlot `v30.17.0-93-gdcc36544a`; generated fixtures
and exact known-failure ledgers under `brikk-sql/brikk-sql/testResources/` are
the behavioral oracle. Intentional divergences in `docs/brikk-extensions.md`
are out of scope.

## Native integration

### EVAL-14 - chDB default tests do not load native code

- [ ] Add a host-platform integration test or check that exercises the packaged
  `libchdb.so` resource path in CI.
- [ ] Fix the stale macOS library comment and use a private per-user extraction
  directory with restrictive permissions.

## Hygiene

### EVAL-15 - small cleanup

- [ ] Alphabetize the `project.yaml` module list.
- [ ] Remove compiler warnings in `brikk-sql/src`.
- [ ] Update the stale version in `tools/publish_maven_local.sh`.
- [ ] Validate release versions as semver and remove indentation-sensitive edits.
- [ ] Decide whether the 340-530 MB `brikk-chdb-native-*` artifacts should ship;
  if they do, attribute the redistributed `libchdb.so` in `ATTRIBUTIONS.md`.
- [ ] Record the Doris parser jar SHA-256 in `vendor/README.md`.
- [ ] Replace exact hazard-registry count assertions with structural checks.
- [ ] Remove or explain the standalone Kotlin pin in `mise.toml`.
