#!/usr/bin/env bash
# Run with bash; no upstream build, publication, or writes to the client checkout.
set -euo pipefail
umask 077
unset CDPATH
export LC_ALL=C

usage() {
    printf '%s\n' \
        'Usage: bash tools/verify_doris_consumer.sh --client CHECKOUT \' \
        '  --core-jar FILE --metadata-jar FILE [--upstream ROOT] [--scratch-dir PARENT]' \
        'JAVA_HOME must select JDK 21. PARENT must exist; a fresh child is retained there.' \
        'Only the two Doris PIPE suites run, with test.sqlTranspiler=absent.'
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

upstream=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
client= core_jar= metadata_jar= scratch_parent=${TMPDIR:-/tmp}
while (($#)); do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --client|--core-jar|--metadata-jar|--upstream|--scratch-dir)
            (($# >= 2)) && [[ -n "$2" ]] || die "Missing value for $1"
            case "$1" in
                --client) client=$2 ;;
                --core-jar) core_jar=$2 ;;
                --metadata-jar) metadata_jar=$2 ;;
                --upstream) upstream=$2 ;;
                --scratch-dir) scratch_parent=$2 ;;
            esac
            shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
[[ -n "$client" && -n "$core_jar" && -n "$metadata_jar" ]] || { usage >&2; exit 1; }
[[ -d "$client" && -d "$upstream" && -d "$scratch_parent" ]] || die 'Checkout, upstream, and scratch parent must exist'
client=$(cd -- "$client" && pwd -P)
upstream=$(cd -- "$upstream" && pwd -P)
scratch_parent=$(cd -- "$scratch_parent" && pwd -P)
case "$scratch_parent/" in
    "$client/"*) die 'Scratch must be outside the client checkout' ;;
    "$upstream/build/"*) ;;
    "$upstream/"*) die 'Upstream scratch must be under its ignored build directory' ;;
esac
[[ -f "$upstream/tools/doris-consumer.init.gradle" ]] || die 'Missing upstream verification init script'
[[ -f "$core_jar" && -f "$metadata_jar" ]] || die 'Both explicit candidate JARs must exist'
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && -x "$JAVA_HOME/bin/javac" ]] || die 'Set JAVA_HOME to JDK 21'
JAVA_HOME=$(cd -- "$JAVA_HOME" && pwd -P)
# These paths become quoted JVM options. Spaces work, option delimiters do not.
case "$scratch_parent$JAVA_HOME" in
    *\"*|*\\*|*$'\n'*|*$'\r'*) die 'Scratch/JDK paths cannot contain quotes, backslashes, or newlines' ;;
esac
java_version=$(env -i "$JAVA_HOME/bin/java" -version 2>&1)
case "$java_version" in
    *'version "21.'*|*'version "21"'*) ;;
    *) die 'JAVA_HOME must select JDK 21, not an IDE-bundled newer runtime' ;;
esac
if command -v sha256sum >/dev/null 2>&1; then
    hash_command=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    hash_command=(shasum -a 256)
else
    die 'Install sha256sum or shasum'
fi
hash_file() {
    local output
    output=$("${hash_command[@]}" < "$1")
    printf '%s' "${output%% *}"
}
client_git() {
    GIT_OPTIONAL_LOCKS=0 git -C "$client" -c core.fsmonitor=false "$@"
}
[[ $(client_git rev-parse --show-toplevel) == "$client" ]] || die '--client must be the Git checkout root'

scratch=$(mktemp -d "$scratch_parent/doris-consumer.XXXXXXXX")
printf 'Verification scratch: %s\n' "$scratch"
trap 'rc=$?; printf "Exit: %s; retained scratch: %s\n" "$rc" "$scratch"; exit "$rc"' EXIT
snapshot=$scratch/client
mkdir -p "$snapshot" "$scratch/jars" "$scratch/home" "$scratch/tmp" \
    "$scratch/gradle-home" "$scratch/xdg-cache" "$scratch/xdg-config" "$scratch/xdg-data"
client_git rev-parse HEAD > "$scratch/client-sha.txt"
client_git status --porcelain=v1 -z --no-renames --untracked-files=all > "$scratch/client-status.z"
while IFS= read -r -d '' entry; do
    # Status and shell-escaped filenames only, never diffs or file contents.
    printf '%s %q\n' "${entry:0:2}" "${entry:3}"
done < "$scratch/client-status.z" > "$scratch/client-status.txt"
: > "$scratch/inputs.sha256"
: > "$scratch/omitted-inputs.txt"

# Filesystem traversal includes dirty, untracked, and ignored build inputs, but
# never follows links or copies a checkout wholesale. Hidden entries stay out.
shopt -s nullglob nocasematch
copy_input() {
    local source=$1 relative=${1#"$client/"} name=${1##*/} child line
    case "$name" in
        .*|build|out|target|dist|cache|caches|*-cache|*-caches|node_modules|__pycache__|venv|env|verification-*) return ;;
        *secret*|*credential*|*password*|*access-token*|*auth-token*|*.env|*.env.*|*.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|local.properties)
            printf '%q\n' "$relative" >> "$scratch/omitted-inputs.txt"; return ;;
    esac
    [[ ! -L "$source" ]] || die "Symlink in allowlisted inputs: $relative"
    if [[ -d "$source" ]]; then
        for child in "$source"/*; do copy_input "$child"; done
        return
    fi
    [[ -f "$source" ]] || die "Non-regular build input: $relative"
    case "$relative" in
        gradlew|gradlew.bat|gradle/wrapper/gradle-wrapper.jar|vendor/lib/*.jar) ;;
        *.kt|*.kts|*.java|*.groovy|*.gradle|*.toml|*.xml|*.sql|*.tree|*.html|*.template|*.svg|*.png|*.txt|*.md|*.sh|*.bat|*.g4|LICENSE*|NOTICE*) ;;
        src/*.properties|vendor/*.properties|*.json|*.yaml|*.yml|*.csv) ;;
        gradle.properties)
            # Local Gradle properties often contain credentials or external paths.
            # Accept only the client's current non-secret build settings.
            while IFS= read -r line || [[ -n "$line" ]]; do
                case "$line" in ''|\#*) continue ;; esac
                if [[ "$line" =~ ^(org\.gradle\.configuration-cache|org\.gradle\.caching|kotlin\.stdlib\.default\.dependency)=(true|false)$ ]] ||
                   [[ "$line" =~ ^(org\.gradle\.jvmargs|kotlin\.daemon\.jvmargs)=-Xmx[0-9]+[mMgG](\ -Dfile.encoding=UTF-8)?$ ]]; then
                    continue
                fi
                die 'Unapproved gradle.properties entry; review the allowlist, do not copy local credentials'
            done < "$source" ;;
        gradle/wrapper/gradle-wrapper.properties)
            while IFS= read -r line || [[ -n "$line" ]]; do
                case "$line" in
                    ''|\#*|distributionBase=GRADLE_USER_HOME|zipStoreBase=GRADLE_USER_HOME|distributionPath=wrapper/dists|zipStorePath=wrapper/dists|validateDistributionUrl=true) ;;
                    *)
                        [[ "$line" =~ ^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-[0-9.]+-bin\.zip$ ]] ||
                        [[ "$line" =~ ^distributionSha256Sum=[0-9a-fA-F]{64}$ ]] ||
                        [[ "$line" =~ ^networkTimeout=[0-9]+$ ]] || die 'Unapproved Gradle wrapper property'
                        ;;
                esac
            done < "$source" ;;
        *) printf '%q\n' "$relative" >> "$scratch/omitted-inputs.txt"; return ;;
    esac
    mkdir -p "$snapshot/$(dirname -- "$relative")"
    cp -p "$source" "$snapshot/$relative"
    printf '%s  %q\n' "$(hash_file "$snapshot/$relative")" "$relative" >> "$scratch/inputs.sha256"
}
for name in src vendor gradle scripts build.gradle build.gradle.kts settings.gradle settings.gradle.kts \
    gradle.properties gradlew gradlew.bat LICENSE NOTICE THIRD_PARTY_NOTICES.md; do
    if [[ -e "$client/$name" || -L "$client/$name" ]]; then copy_input "$client/$name"; fi
done
shopt -u nocasematch
for name in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties \
    build.gradle.kts settings.gradle.kts \
    src/test/kotlin/dev/sort/doris/pipes/DorisPipesUpgradeTest.kt \
    src/test/kotlin/dev/sort/doris/pipes/DorisPipesActionTest.kt; do
    [[ -f "$snapshot/$name" ]] || die "Missing required snapshot input: $name"
done

# Copy, then pin the bytes. Later upstream rebuilds cannot change this run.
cp "$core_jar" "$scratch/jars/brikk-sql-jvm.jar"
cp "$metadata_jar" "$scratch/jars/brikk-sql-metadata-jvm.jar"
cp "$upstream/tools/doris-consumer.init.gradle" "$scratch/doris-consumer.init.gradle"
core_hash=$(hash_file "$scratch/jars/brikk-sql-jvm.jar")
metadata_hash=$(hash_file "$scratch/jars/brikk-sql-metadata-jvm.jar")
[[ "$core_hash" != "$metadata_hash" ]] || die 'Core and metadata must be distinct JARs'
printf '%s  jars/brikk-sql-jvm.jar\n%s  jars/brikk-sql-metadata-jvm.jar\n' \
    "$core_hash" "$metadata_hash" > "$scratch/jars.sha256"
printf '%s  doris-consumer.init.gradle\n%s  verify_doris_consumer.sh\n' \
    "$(hash_file "$scratch/doris-consumer.init.gradle")" "$(hash_file "${BASH_SOURCE[0]}")" > "$scratch/tooling.sha256"
printf 'Client SHA: %s\nInput manifest SHA-256: %s\nCore SHA-256: %s\nMetadata SHA-256: %s\n' \
    "$(< "$scratch/client-sha.txt")" "$(hash_file "$scratch/inputs.sha256")" "$core_hash" "$metadata_hash"

# Do not inherit passwords, ORG_GRADLE_PROJECT_*, JVM agents/options, or user
# Gradle init scripts. The caller may use mise to select JAVA_HOME beforehand.
(
    cd -- "$snapshot"
    env -i HOME="$scratch/home" JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        LANG=C.UTF-8 TMPDIR="$scratch/tmp" GRADLE_USER_HOME="$scratch/gradle-home" \
        XDG_CACHE_HOME="$scratch/xdg-cache" XDG_CONFIG_HOME="$scratch/xdg-config" XDG_DATA_HOME="$scratch/xdg-data" \
        JAVA_OPTS="-Duser.home=\"$scratch/home\" -Djava.io.tmpdir=\"$scratch/tmp\"" \
        /bin/sh ./gradlew --no-daemon --no-configuration-cache --no-build-cache --console=plain --max-workers=2 \
        --gradle-user-home "$scratch/gradle-home" --project-cache-dir "$scratch/project-cache" \
        --init-script "$scratch/doris-consumer.init.gradle" \
        "-Dorg.gradle.java.home=$JAVA_HOME" \
        "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -Duser.home=\"$scratch/home\" -Djava.io.tmpdir=\"$scratch/tmp\"" \
        -Porg.gradle.java.installations.auto-detect=false -Porg.gradle.java.installations.auto-download=false \
        "-Porg.gradle.java.installations.paths=$JAVA_HOME" \
        -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false \
        -Porg.jetbrains.intellij.platform.selfUpdateCheck=false \
        "-Porg.jetbrains.intellij.platform.intellijPlatformCache=$scratch/intellij-cache" \
        "-Pconsumer.scratch=$scratch" "-Pconsumer.coreSha256=$core_hash" "-Pconsumer.metadataSha256=$metadata_hash" \
        -Ptest.sqlTranspiler=absent :test \
        --tests dev.sort.doris.pipes.DorisPipesUpgradeTest \
        --tests dev.sort.doris.pipes.DorisPipesActionTest
) > "$scratch/gradle.log" 2>&1 || die "Consumer verification failed; inspect $scratch/gradle.log and reports/"
[[ -f "$scratch/PASS" ]] || die 'Gradle returned success without verified suite reports'
printf 'PASS: both suites and required cases passed without skips. Reports: %s/reports\n' "$scratch"
