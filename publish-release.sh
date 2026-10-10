#!/usr/bin/env bash
# Explicit, signed Central Portal upload of the IntelliJ helper ONLY.
# Manual Portal approval by default; --auto explicitly enables auto-publication.
# No push to main publishes a release.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || { [ "$#" -eq 2 ] && [ "$2" != "--auto" ]; }; then
  echo "usage: $0 <version> [--auto]" >&2
  exit 2
fi
VERSION="$1"
MODE="manual"
if [ "${2:-}" = "--auto" ]; then MODE="auto"; fi
python3 tools/release_config.py validate "$VERSION"

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

# Run checks before changing the release model or uploading anything.
./kotlin do assemblePluginJar -m brikk-engine-kotlin-compiler-plugin
./kotlin build
./kotlin test
./kotlin check intellijSupport -m brikk-engine-kotlin-intellij-support

TEMPLATE="publish.module-template.yaml"
BACKUP="$(mktemp "$PWD/.publish-base.XXXXXX.module-template.yaml")"
cp "$TEMPLATE" "$BACKUP"
restore() { cp "$BACKUP" "$TEMPLATE"; rm -f "$BACKUP"; }
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 tools/release_config.py template "$VERSION" "./$(basename "$BACKUP")" --publishing-mode "$MODE" > "$TEMPLATE"

./kotlin publish mavenLocal -m brikk-engine-kotlin-intellij-support
./kotlin do verifyLocalIntellijPublication -m brikk-engine-kotlin-intellij-support
./kotlin task :brikk-engine-kotlin-intellij-support:publishToMavenCentral
echo "Support publication mode: $MODE. Deployment status: https://central.sonatype.com/publishing/deployments"
