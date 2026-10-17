#!/usr/bin/env python3
"""Compose a native Toolchain release override for the IntelliJ support artifact."""

import argparse
import json
import re

NUMBER = r"(?:0|[1-9][0-9]*)"
PRERELEASE_ID = rf"(?:{NUMBER}|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER = re.compile(
    rf"{NUMBER}\.{NUMBER}\.{NUMBER}"
    rf"(?:-(?P<prerelease>{PRERELEASE_ID}(?:\.{PRERELEASE_ID})*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
)


def validate_version(version):
    match = SEMVER.fullmatch(version)
    if not match or "SNAPSHOT" in (match.group("prerelease") or "").upper().split("."):
        raise ValueError(f"Release version must be non-SNAPSHOT SemVer: {version!r}")


def release_template(version, base_template, publishing_mode="manual"):
    validate_version(version)
    if publishing_mode not in {"manual", "auto"}:
        raise ValueError("Publishing mode must be manual or auto")
    return (
        "# Temporary release settings. Restored by publish-release.sh.\n"
        f"apply:\n  - {json.dumps(base_template)}\n"
        "settings:\n  publishing:\n"
        f"    version: {json.dumps(version)}\n"
        f"    mavenCentral: {{ enabled: true, publishingMode: {publishing_mode} }}\n"
        "    signArtifacts: true\n"
    )


def local_candidate_template(version, base_template):
    """An unsigned local release candidate: the release version, resolving released
    dependencies (such as a local brikk-sql core candidate) from Maven Local. Never Central."""
    validate_version(version)
    return (
        "# Temporary local release-candidate settings. Restored by publish-release.sh.\n"
        f"apply:\n  - {json.dumps(base_template)}\n"
        "repositories:\n  - url: mavenLocal\n    resolve: true\n    publish: true\n"
        "settings:\n  publishing:\n"
        f"    version: {json.dumps(version)}\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("version")
    template = commands.add_parser("template")
    template.add_argument("version")
    template.add_argument("base_template")
    template.add_argument("--publishing-mode", choices=["manual", "auto"], default="manual")
    local = commands.add_parser("local-template")
    local.add_argument("version")
    local.add_argument("base_template")
    args = parser.parse_args()
    try:
        validate_version(args.version)
        if args.command == "template":
            print(release_template(args.version, args.base_template, args.publishing_mode), end="")
        elif args.command == "local-template":
            print(local_candidate_template(args.version, args.base_template), end="")
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
