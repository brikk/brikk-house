#!/usr/bin/env python3
"""Extracts a "first documented in version" per Doris built-in function from a local
apache/doris-website clone -> vendor/data/doris-since-versions.json, joined onto
FunctionDef.sinceVersion by tools/generate_doris_functions.py.

Investigated first (be honest, don't fabricate a source): doris-website's per-function
docs (docs/sql-manual/sql-functions/**/*.md, ~700 files, sampled broadly) carry NO
explicit per-function version field. Frontmatter is only {title, language, description}
(occasionally + sidebar_label); bodies occasionally mention a version in prose for
*removal*/behavior-change notes (e.g. struct-element.md: "removed since version 4.1.3")
but that is deprecation commentary on a handful of functions, not a systematic
"introduced in" fact — there is no `@since`-equivalent anywhere in this repo. The Doris
*engine* repo has no per-function version metadata either (see vendor/README.md's prior
note); doris-website is genuinely the only candidate source, and it doesn't carry the
fact directly.

Method chosen: "first-documented-in" (a defensible heuristic, NOT "introduced in" -- see
the honesty caveats below). doris-website keeps historical per-release doc snapshots
under versioned_docs/version-<X>/sql-manual/sql-functions/ (whatever exists in the clone
is used; at the time this was written: 1.2, 2.0, 2.1, 3.x, 4.x, oldest to newest by doc
file count: 361 -> 412 -> 507 -> 536 -> 666). For each registry function (primary name or
any alias), we take the OLDEST version tier whose docs contain a matching frontmatter
title as its "since_version". The live/unversioned `docs/` tree (the upcoming, unreleased
docs) is used only to distinguish "documented, but not yet in any shipped version" from
"not documented anywhere" -- it does NOT contribute a version number.

Honesty / limitations (also carried into the JSON's "method_notes" and the generator's
KDoc):
  - This is "first DOCUMENTED in", not "first SHIPPED in". A function can ship before its
    doc page is written/ported (docs lag code), or a doc page can be missing from an
    older version tier for reasons unrelated to the function's actual availability there
    (doc reorganizations, translation lag). The heuristic will then over-estimate
    sinceVersion (report a later version than the function actually shipped in).
  - Conversely a function's doc content can be edited in place across versions without
    the function's behavior actually changing across those versions -- title presence
    only tells us "documented", not "semantically identical".
  - No claim is made below the granularity doris-website itself uses: "3.x"/"4.x" are the
    site's own labels (not exact dot releases); we do not attempt to resolve them to a
    specific minor.
  - Titles are matched, not descriptions: a small number of docs use combined titles for
    multi-alias pages ("CURDATE,CURRENT_DATE", "LCASE/LOWER") or an SEO-vs-sidebar split
    ("STRUCT | Struct Functions") -- both are parsed into individual name tokens.
  - i18n/zh-CN and ja-source doc trees are intentionally NOT consulted (docs/ and
    versioned_docs/ are the canonical English source of truth per doris-website's own
    AGENTS.md); a function documented only in a translated tree reads as "unmatched"
    here (e.g. NTH_VALUE at the pinned clone SHA).
  - Registry functions with no matching doc ANYWHERE (including the live docs/ tree) are
    listed verbatim in "unmatched" -- never guessed. At the current pin these are mostly
    internal/legacy/undocumented functions (MySQL interval-literal helpers like
    DAY_MICROSECOND, internal aggregate-rewrite targets like MULTI_DISTINCT_COUNT,
    debug/admin functions like SLEEP/PASSWORD, ...).

Usage: python3 tools/extract_doris_since_versions.py [<doris-website-clone-path>]
       Default: reference/doris-website (a local `git clone --depth 1
       https://github.com/apache/doris-website` -- gitignored, not vendored; see
       vendor/README.md). Re-run after refreshing the clone; re-run
       tools/generate_doris_functions.py afterward to pick up the new JSON.
"""

from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import generate_doris_functions as gdf  # noqa: E402  (reuse the registry name/alias extraction)

DEFAULT_CLONE = ROOT / "reference" / "doris-website"
OUT = ROOT / "vendor" / "data" / "doris-since-versions.json"

TITLE_KV = re.compile(r'"title"\s*:\s*"([^"]*)"')
NAME_TOKEN = re.compile(r"^[A-Z_][A-Z0-9_]*$")


def title_tokens(raw_title: str) -> list[str]:
    """Frontmatter title -> candidate function-name tokens.

    Handles the two combined-title shapes seen in doris-website:
      - "NAME | Category Label"     (SEO title; the bare name precedes the pipe -- verified
                                      against sidebar_label on the pages that carry both)
      - "NAME1,NAME2" / "NAME1/NAME2" (one doc page covering several aliases)
    Plain single-name titles ("ABS") pass through as a 1-element list. Non-identifier
    tokens (multi-word category/"Overview" titles) are dropped -- they cannot collide
    with a registry function name.
    """
    head = raw_title.split("|", 1)[0]
    tokens = []
    for tok in re.split(r"[,/]", head):
        tok = tok.strip().upper()
        if tok and NAME_TOKEN.match(tok):
            tokens.append(tok)
    return tokens


def titles_in(doc_root: pathlib.Path) -> set[str]:
    """All function-name tokens found in frontmatter titles under doc_root/sql-manual/sql-functions/**."""
    titles: set[str] = set()
    for path in doc_root.glob("sql-manual/sql-functions/**/*.md"):
        head = path.read_text(encoding="utf-8", errors="replace")[:400]
        m = TITLE_KV.search(head)
        if m:
            titles.update(title_tokens(m.group(1)))
    return titles


def version_sort_key(label: str) -> tuple[int, int]:
    """"1.2"->(1,2)  "2.0"->(2,0)  "3.x"->(3, 10**6) -- a literal "x" minor sorts newest
    within its major (doris-website uses "x" for "latest minor of this major line")."""
    major_s, _, minor_s = label.partition(".")
    major = int(major_s)
    minor = int(minor_s) if minor_s.isdigit() else 10**6
    return (major, minor)


def discover_version_tiers(clone: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    """Released version tiers, oldest first -- whatever versioned_docs/version-* exists
    in this clone (not hardcoded; report what was actually found)."""
    tiers = []
    for d in sorted((clone / "versioned_docs").glob("version-*")):
        label = d.name[len("version-") :]
        if (d / "sql-manual" / "sql-functions").is_dir():
            tiers.append((label, d))
    tiers.sort(key=lambda t: version_sort_key(t[0]))
    return tiers


def clone_sha(clone: pathlib.Path) -> str:
    return subprocess.run(
        ["git", "-C", str(clone), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()


def main() -> None:
    clone = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CLONE
    if not clone.is_dir():
        sys.exit(
            f"error: {clone} is not a directory -- clone apache/doris-website there first "
            "(git clone --depth 1 https://github.com/apache/doris-website <path>); "
            "see vendor/README.md"
        )

    tiers = discover_version_tiers(clone)
    if not tiers:
        sys.exit(f"error: no versioned_docs/version-*/sql-manual/sql-functions under {clone}")
    print(f"version tiers found (oldest first): {', '.join(label for label, _ in tiers)}", file=sys.stderr)

    tier_titles = [(label, titles_in(d)) for label, d in tiers]
    current_titles = titles_in(clone / "docs")  # unreleased/dev tree: coverage only, no version credit

    by_kind = gdf.collect(gdf.VENDORED_REGISTRY)
    total = sum(len(v) for v in by_kind.values())

    versions: dict[str, str] = {}
    undocumented_in_release: list[str] = []  # documented in the dev tree only, no shipped tier yet
    unmatched: list[str] = []  # no matching doc anywhere (incl. the dev tree)

    for kind in sorted(by_kind):
        for _cls, name, aliases in by_kind[kind]:
            names = [name, *aliases]
            for label, titles in tier_titles:
                if any(n in titles for n in names):
                    versions[name] = label
                    break
            else:
                if any(n in current_titles for n in names):
                    undocumented_in_release.append(name)
                else:
                    unmatched.append(name)

    undocumented_in_release.sort()
    unmatched.sort()

    out = {
        "source": "apache/doris-website",
        "clone_sha": clone_sha(clone),
        "method": "first-documented-in",
        "method_notes": (
            "doris-website carries no explicit per-function since/introduced-in metadata "
            "(checked frontmatter across sql-manual/sql-functions docs and every "
            "versioned_docs tier). Heuristic instead: for each registry function (primary "
            "name or any alias), the OLDEST versioned_docs/version-* tier "
            f"({', '.join(label for label, _ in tiers)}, oldest first at this clone) whose "
            "docs contain a matching frontmatter title. This is 'first documented in', NOT "
            "'introduced in': doc lag/reorg can push the reported version later than the "
            "function actually shipped, never earlier. Combined-alias titles "
            "('CURDATE,CURRENT_DATE', 'LCASE/LOWER') and SEO-vs-sidebar titles "
            "('STRUCT | Struct Functions') are parsed into individual name tokens. Only "
            "docs/ and versioned_docs/ (the canonical English trees) are consulted -- not "
            "i18n/zh-CN or ja-source. Functions documented only in the live/unreleased "
            "docs/ tree (no shipped version tier yet) are listed in "
            "'undocumented_in_release', not given a version. Functions with no matching "
            "doc anywhere are listed verbatim in 'unmatched' -- never guessed."
        ),
        "version_tiers": [label for label, _ in tiers],
        "versions": dict(sorted(versions.items())),
        "undocumented_in_release": undocumented_in_release,
        "unmatched": unmatched,
    }
    OUT.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(
        f"wrote {len(versions)}/{total} defs with sinceVersion, "
        f"{len(undocumented_in_release)} documented-but-unreleased, "
        f"{len(unmatched)} unmatched -> {OUT}"
    )


if __name__ == "__main__":
    main()
