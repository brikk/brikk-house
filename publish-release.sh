#!/usr/bin/env bash
# Release of the IntelliJ helper ONLY. Runtime, compiler artifacts and build plugins never publish.
#   <version>           signed Central Portal upload, manual Portal approval
#   <version> --auto    signed Central Portal upload, automatic publication after validation
#   <version> --local   unsigned local candidate in Maven Local; resolves released
#                       dependencies (brikk-sql core) from Maven Local too; no credentials
# The checks are scoped to the helper: its build, release configuration and publication
# verification (POM/core-only/no-snapshot, notices, independent consumer). No push to main
# publishes a release.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "$2" != "--auto" ] && [ "$2" != "--local" ]; }; then
  echo "usage: $0 <version> [--auto|--local]" >&2
  exit 2
fi
VERSION="$1"
MODE="manual"
if [ "${2:-}" = "--auto" ]; then MODE="auto"; fi
if [ "${2:-}" = "--local" ]; then MODE="local"; fi
python3 tools/release_config.py validate "$VERSION"
python3 -m unittest discover -s tools -p 'test_*.py'

if [ "$MODE" != "local" ]; then
  : "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:-}}"
  : "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:-}}"
  : "${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:=${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:-}}"
  export KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE
  for key in KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD KOTLIN_TOOLCHAIN_SIGNING_KEY; do
    if [ -z "${!key:-}" ]; then
      echo "ERROR: missing $key" >&2
      exit 2
    fi
  done
fi

# Temporary native publication-model override, always restored.
TEMPLATE="publish.module-template.yaml"
BACKUP="$(mktemp "$PWD/.publish-base.XXXXXX.module-template.yaml")"
cp "$TEMPLATE" "$BACKUP"
restore() { cp "$BACKUP" "$TEMPLATE"; rm -f "$BACKUP"; }
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [ "$MODE" = "local" ]; then
  python3 tools/release_config.py local-template "$VERSION" "./$(basename "$BACKUP")" > "$TEMPLATE"
else
  python3 tools/release_config.py template "$VERSION" "./$(basename "$BACKUP")" --publishing-mode "$MODE" > "$TEMPLATE"
fi

./kotlin build -m brikk-engine-kotlin-intellij-support
./kotlin publish mavenLocal -m brikk-engine-kotlin-intellij-support
./kotlin do verifyLocalIntellijPublication -m brikk-engine-kotlin-intellij-support
if [ "$MODE" = "local" ]; then
  echo "Unsigned local candidate dev.brikk.house:brikk-engine-kotlin-intellij-support:$VERSION is in Maven Local; nothing was uploaded."
  exit 0
fi
./kotlin task :brikk-engine-kotlin-intellij-support:publishToMavenCentral
echo "Support publication mode: $MODE. Deployment status: https://central.sonatype.com/publishing/deployments"
