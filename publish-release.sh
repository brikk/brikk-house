#!/usr/bin/env bash
#
# Publish a RELEASE of all publishable modules to Maven Central (Central Portal).
#
#   ./publish-release.sh <version>          e.g.  ./publish-release.sh 0.1.0
#
# Required environment variables (Central Portal user token + PGP signing key). The Central
# credential name differs by toolchain version — set EITHER spelling, this script exports both:
#   KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME   (0.11)  / KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME (newer)
#   KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD   (0.11)  / KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD (newer)
#   KOTLIN_TOOLCHAIN_SIGNING_KEY             full ASCII-armored PGP private key
#   KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE  (or KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE) if encrypted
#
# This temporarily enables the release settings in publish.module-template.yaml
# (version + mavenCentral + signArtifacts) and restores the file on exit, so the committed
# config keeps the keyless SNAPSHOT flow working.
#
# Publishing mode is "auto" (see publish.module-template.yaml): each deployment bundle is
# validated AND published to Maven Central automatically — no manual step in the Portal UI.
# Track deployments at: https://central.sonatype.com/publishing/deployments
#
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: $0 <version>   (e.g. 0.1.0)" >&2
  exit 2
fi
case "$VERSION" in
  *-SNAPSHOT) echo "ERROR: Maven Central rejects SNAPSHOT versions ('$VERSION')." >&2; exit 2 ;;
esac

# Env-var name compatibility. The name the toolchain reads changed between versions:
#   - 0.11 reads KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME/PASSWORD   (NO underscore)
#   - newer reads KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME/PASSWORD (WITH underscore)
# Accept either and export BOTH so it works regardless of toolchain version.
: "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:=${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME:-}}"
: "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:-}}"
: "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:=${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD:-}}"
: "${KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD:=${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:-}}"
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD

# Signing passphrase: toolchain reads *_SIGNING_KEY_PASSPHRASE; accept the shorter
# *_SIGNING_PASSPHRASE too (both directions).
: "${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:=${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:-}}"
: "${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:=${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:-}}"
export KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE

missing=""
[ -n "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME:-}" ] || missing="$missing MAVEN_CENTRAL_USERNAME"
[ -n "${KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD:-}" ] || missing="$missing MAVEN_CENTRAL_PASSWORD"
[ -n "${KOTLIN_TOOLCHAIN_SIGNING_KEY:-}" ]           || missing="$missing SIGNING_KEY"
if [ -n "$missing" ]; then
  echo "ERROR: missing required environment variables:$missing" >&2
  exit 2
fi

TMPL="publish.module-template.yaml"
BACKUP="$(mktemp)"
cp "$TMPL" "$BACKUP"
restore() { cp "$BACKUP" "$TMPL"; rm -f "$BACKUP"; }
trap restore EXIT INT TERM

sed -i \
  -e "s|^    version: .*|    version: ${VERSION}|" \
  -e "s|^    #mavenCentral: { enabled: true, publishingMode: auto }|    mavenCentral: { enabled: true, publishingMode: auto }|" \
  -e "s|^    #signArtifacts: true|    signArtifacts: true|" \
  "$TMPL"

echo ">> Publishing release ${VERSION} to Maven Central (all modules)..."
./kotlin publish mavenCentral

echo ""
echo ">> Bundles uploaded and validated. In 'manual' mode, finish (or drop) each deployment at:"
echo "   https://central.sonatype.com/publishing/deployments"
