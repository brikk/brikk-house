#!/usr/bin/env python3
"""Assemble the brikk-sql compiler plugin into one jar for `-Xplugin=` consumption.

Merges (first wins on duplicate entries):
  build/tasks/_brikk-sql-compiler-plugin_jarJvm/brikk-sql-compiler-plugin-jvm.jar
  build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar
  kotlinx-serialization-core-jvm-<ver>.jar   (located in the Kotlin Toolchain's dependency cache)

into build/brikk-sql-compiler-plugin-<kotlin>-<lib>.jar  (default: ...-2.4.0-0.1.0.jar).

The file name deliberately follows KEFS's default detect+version pattern
`<artifact-id>-<kotlin-version>-<lib-version>.jar` so the IDE plugin recognises the
`-Xplugin=` reference with no replacement patterns (docs/vendor/kefs/GUIDE.md §6).

Run after `./kotlin build -m brikk-sql-compiler-plugin`. This is a plain merge, not a
relocating shade: fine for the in-repo smoke module, NOT what a published (KEFS-loadable)
plugin needs — see docs/vendor/kefs/PLUGIN_AUTHORS.md ("dependencies must be relocated").
"""
from __future__ import annotations

import glob
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTIFACT = "brikk-sql-compiler-plugin"
KOTLIN_VERSION = "2.4.0"   # compiler the plugin is compiled against
LIB_VERSION = "0.1.0"
VERSION = f"{KOTLIN_VERSION}-{LIB_VERSION}"
OUT = os.path.join(ROOT, "build", f"{ARTIFACT}-{VERSION}.jar")

LOCAL_JARS = [
    os.path.join(ROOT, "build", "tasks", "_brikk-sql-compiler-plugin_jarJvm", "brikk-sql-compiler-plugin-jvm.jar"),
    os.path.join(ROOT, "build", "tasks", "_brikk-sql_jarJvm", "brikk-sql-jvm.jar"),
]

CACHE_ROOTS = [
    os.environ.get("KOTLIN_TOOLCHAIN_M2_CACHE", ""),
    os.path.expanduser("~/.cache/JetBrains/Kotlin/.m2.cache"),
    os.path.expanduser("~/.m2/repository"),
]

DEP_GLOBS = [
    "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/*/kotlinx-serialization-core-jvm-*[0-9].jar",
]


def find_dep(pattern: str) -> str:
    for root in CACHE_ROOTS:
        if not root:
            continue
        hits = sorted(glob.glob(os.path.join(root, pattern)))
        hits = [h for h in hits if not h.endswith(("-sources.jar", "-javadoc.jar"))]
        if hits:
            return hits[-1]
    sys.exit(f"dependency not found in any cache root: {pattern}")


def main() -> None:
    jars = list(LOCAL_JARS)
    for j in jars:
        if not os.path.exists(j):
            sys.exit(f"missing {j}\nrun: ./kotlin build -m brikk-sql-compiler-plugin")
    jars += [find_dep(p) for p in DEP_GLOBS]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    seen: set[str] = set()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as out:
        for jar in jars:
            with zipfile.ZipFile(jar) as src:
                for info in src.infolist():
                    name = info.filename
                    if name in seen or name.endswith("/"):
                        continue
                    if name.startswith("META-INF/") and (name.endswith((".SF", ".RSA", ".DSA")) or name == "META-INF/MANIFEST.MF"):
                        continue
                    seen.add(name)
                    out.writestr(info, src.read(name))
    print(f"wrote {os.path.relpath(OUT, ROOT)} from {len(jars)} jars ({len(seen)} entries)")


if __name__ == "__main__":
    main()
