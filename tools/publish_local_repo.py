#!/usr/bin/env python3
"""Publish the merged compiler-plugin jar into a local Maven-layout repository for KEFS.

    ./kotlin build -m brikk-sql-compiler-plugin
    python3 tools/assemble_plugin_jar.py
    python3 tools/publish_local_repo.py --ide-kotlin-version 2.4.0-ij253-45 [--lib-version 0.1.0]

writes

    build/repo/dev/brikk/house/brikk-sql-compiler-plugin/<ide>-<lib>/brikk-sql-compiler-plugin-<ide>-<lib>.jar
    build/repo/dev/brikk/house/brikk-sql-compiler-plugin/<ide>-<lib>/brikk-sql-compiler-plugin-<ide>-<lib>.pom
    build/repo/dev/brikk/house/brikk-sql-compiler-plugin/maven-metadata.xml

The version follows the KEFS scheme `<kotlin-version>-<lib-version>` (docs/vendor/kefs/
PLUGIN_AUTHORS.md). Pass the value shown by the IDE action "KEFS: Copy Kotlin IDE Version";
default is the compiler version we build against (2.4.0). The jar itself is always the one
compiled against 2.4.0 — naming it with the IDE's version is the cheap compatibility
experiment; KEFS's exception analyzer reports if the IDE compiler rejects it.

KEFS setup (Tools > Kotlin External FIR Support > Artifacts):
  - Maven Repositories: add Local (File path) -> <project>/build/repo
  - Kotlin Compiler Plugins: add bundle
        name:        brikk-sql
        coordinates: dev.brikk.house:brikk-sql-compiler-plugin
        version matching: Latest
        repositories: the local one
        replacement patterns: leave ALL THREE at their defaults
            version:   <kotlin-version>-<lib-version>
            detect:    <artifact-id>
            search:    <artifact-id>
        (the project's -Xplugin jar is named brikk-sql-compiler-plugin-2.4.0-0.1.0.jar, i.e.
         exactly <artifact-id>-<kotlin-version>-<lib-version>.jar, so defaults match)
  - Save; commit the generated .idea/kotlin-plugins.xml.
Re-run this script after every plugin change; KEFS file-watches the repo and reloads.
"""
from __future__ import annotations

import argparse
import os
import shutil
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUP = "dev.brikk.house"
ARTIFACT = "brikk-sql-compiler-plugin"
BUILT_VERSION = "2.4.0-0.1.0"  # what assemble_plugin_jar.py produces
JAR = os.path.join(ROOT, "build", f"{ARTIFACT}-{BUILT_VERSION}.jar")

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <packaging>jar</packaging>
</project>
"""

METADATA = """<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <versioning>
    <latest>{latest}</latest>
    <release>{latest}</release>
    <versions>
{versions}
    </versions>
    <lastUpdated>{stamp}</lastUpdated>
  </versioning>
</metadata>
"""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ide-kotlin-version", default="2.4.0", help="Kotlin compiler version of the IDE (KEFS: Copy Kotlin IDE Version)")
    ap.add_argument("--lib-version", default="0.1.0")
    ap.add_argument("--repo", default=os.path.join(ROOT, "build", "repo"))
    args = ap.parse_args()

    if not os.path.exists(JAR):
        raise SystemExit(f"missing {JAR}\nrun: ./kotlin build -m brikk-sql-compiler-plugin && python3 tools/assemble_plugin_jar.py")

    version = f"{args.ide_kotlin_version}-{args.lib_version}"
    art_dir = os.path.join(args.repo, *GROUP.split("."), ARTIFACT)
    ver_dir = os.path.join(art_dir, version)
    os.makedirs(ver_dir, exist_ok=True)

    base = f"{ARTIFACT}-{version}"
    shutil.copyfile(JAR, os.path.join(ver_dir, base + ".jar"))
    with open(os.path.join(ver_dir, base + ".pom"), "w") as f:
        f.write(POM.format(group=GROUP, artifact=ARTIFACT, version=version))

    versions = sorted(d for d in os.listdir(art_dir) if os.path.isdir(os.path.join(art_dir, d)))
    with open(os.path.join(art_dir, "maven-metadata.xml"), "w") as f:
        f.write(METADATA.format(
            group=GROUP, artifact=ARTIFACT, latest=version,
            versions="\n".join(f"      <version>{v}</version>" for v in versions),
            stamp=time.strftime("%Y%m%d%H%M%S"),
        ))
    print(f"published {GROUP}:{ARTIFACT}:{version} -> {os.path.relpath(ver_dir, ROOT)}")


if __name__ == "__main__":
    main()
