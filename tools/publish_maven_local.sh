#!/usr/bin/env bash
# Publishes the JVM artifacts of brikk-sql modules to Maven Local (~/.m2/repository).
#
# Why not `./kotlin publish`: Amper 0.11's publishing is preview and KMP-library
# publications are not yet consumable by other projects (JVM-only works). Until that
# lands, this script installs the JVM jars with hand-written POMs. Re-run after any
# change you want consumers to pick up:
#
#   ./kotlin build && tools/publish_maven_local.sh
#
# Coordinates (version bump: edit VERSION):
#   dev.brikk.house:brikk-sql:0.1.0-SNAPSHOT           (engine: parse/transpile/AST/shape)
#   dev.brikk.house:brikk-sql-metadata:0.1.0-SNAPSHOT  (featherweight function catalogs)
#   dev.brikk.house:brikk-sql-verify:0.1.0-SNAPSHOT    (native-grammar verifiers; JDK 25)
set -euo pipefail
cd "$(dirname "$0")/.."

GROUP="dev.brikk.house"
VERSION="${VERSION:-0.1.0-SNAPSHOT}"
KOTLIN_STDLIB="2.3.21"
KX_SERIALIZATION_JSON="1.10.0"

pom() { # pom <artifact> <deps-xml>
  cat > "/tmp/brikk-pom-$1.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP}</groupId>
  <artifactId>$1</artifactId>
  <version>${VERSION}</version>
  <packaging>jar</packaging>
  <licenses><license><name>Apache-2.0</name></license></licenses>
  <dependencies>$2</dependencies>
</project>
EOF
}

dep() { echo "<dependency><groupId>$1</groupId><artifactId>$2</artifactId><version>$3</version></dependency>"; }

install() { # install <artifact> <jar-path>
  mvn -q install:install-file -Dfile="$2" -DpomFile="/tmp/brikk-pom-$1.xml"
  echo "installed ${GROUP}:$1:${VERSION}"
}

KOTLIN_DEPS="$(dep org.jetbrains.kotlin kotlin-stdlib ${KOTLIN_STDLIB})$(dep org.jetbrains.kotlinx kotlinx-serialization-json ${KX_SERIALIZATION_JSON})"

pom brikk-sql-metadata "${KOTLIN_DEPS}"
install brikk-sql-metadata "build/tasks/_brikk-sql-metadata_jarJvm/brikk-sql-metadata-jvm.jar"

pom brikk-sql "$(dep ${GROUP} brikk-sql-metadata ${VERSION})${KOTLIN_DEPS}"
install brikk-sql "build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar"

# brikk-sql-verify: requires a JDK 25 RUNTIME (trino-parser 481 = class-file 69).
# DorisVerifier needs the vendored FE jar: pass -Dbrikk.doris.parser.jar=<path> or put
# it on the classpath (see vendor/README.md).
pom brikk-sql-verify "${KOTLIN_DEPS}$(dep io.trino trino-parser 481)$(dep org.duckdb duckdb_jdbc 1.5.4.0)$(dep org.antlr antlr4-runtime 4.13.1)"
install brikk-sql-verify "build/tasks/_brikk-sql-verify_jarJvm/brikk-sql-verify-jvm.jar"
