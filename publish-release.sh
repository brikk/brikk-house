#!/usr/bin/env bash
#
# Publish a RELEASE of all publishable modules to Maven Central (Central Portal).
#
#   ./publish-release.sh <version>          e.g.  ./publish-release.sh 0.1.0
#
# Required environment variables (Central Portal user token + PGP signing key):
#   KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME
#   KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD
#   KOTLIN_TOOLCHAIN_SIGNING_KEY              full ASCII-armored PGP private key
#   KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE   (or KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE) if encrypted
#
# This temporarily enables the release settings in publish.module-template.yaml
# (version + mavenCentral + signArtifacts) and restores the file on exit, so the committed
# config keeps the keyless SNAPSHOT / GitHub Packages flows working.
#
# Central defaults to "manual" mode: this uploads + validates one deployment bundle per
# module, then stops. Finish the release at:
#   https://central.sonatype.com/publishing/deployments
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

# The toolchain reads KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE (note the KEY_). Accept the
# shorter name too (matches the org secret KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE).
if [ -z "${KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE:-}" ] && [ -n "${KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE:-}" ]; then
  export KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE="$KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE"
fi

missing=""
for v in KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD KOTLIN_TOOLCHAIN_SIGNING_KEY; do
  [ -n "${!v:-}" ] || missing="$missing $v"
done
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
  -e "s|^    #mavenCentral: enabled|    mavenCentral: enabled|" \
  -e "s|^    #signArtifacts: true|    signArtifacts: true|" \
  "$TMPL"

echo ">> Publishing release ${VERSION} to Maven Central (all modules)..."
./kotlin publish mavenCentral

echo ""
echo ">> Bundles uploaded and validated. In 'manual' mode, finish (or drop) each deployment at:"
echo "   https://central.sonatype.com/publishing/deployments"
