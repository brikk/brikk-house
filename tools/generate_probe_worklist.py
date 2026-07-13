#!/usr/bin/env python3
"""Generate the Doris semantic-probe worklist from gap-report bucket-A entries.

Reads:
  - brikk-sql/testResources/semantics/gap-report.json
  - brikk-sql/testResources/semantics/trino-duckdb-hazards.json (prior evidence)

Writes:
  - docs/research/doris-probe-worklist.md

For each of duckdb→doris and trino→doris, every bucket-A (same-name-in-both-catalogs)
entry is listed. When the same function name already has a trino↔duckdb hazard verdict,
that prior evidence is annotated (highest-suspicion DIVERGENT first). Suggested probe
areas come from the prior hazard's areas, else "baseline".

Rerunning on unchanged inputs is byte-deterministic.
Usage: python3 tools/generate_probe_worklist.py
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEMANTICS = ROOT / "brikk-sql" / "testResources" / "semantics"
GAP_REPORT = SEMANTICS / "gap-report.json"
TRINO_DUCKDB_HAZARDS = SEMANTICS / "trino-duckdb-hazards.json"
OUT = ROOT / "docs" / "research" / "doris-probe-worklist.md"

# Pairs the partner team will probe (source → doris).
TARGET_PAIRS = (
    ("duckdb", "doris"),
    ("trino", "doris"),
)

VERDICTS = {
    "identical": "IDENTICAL",
    "divergent": "DIVERGENT",
    "conditionally-equivalent": "CONDITIONALLY_EQUIVALENT",
    "no-equivalent": "NO_EQUIVALENT",
    "unclear": "UNCLEAR",
}

# Sort: DIVERGENT first (highest suspicion), then other priors by severity, then none.
SEVERITY_RANK = {
    "DIVERGENT": 0,
    "UNCLEAR": 1,
    "CONDITIONALLY_EQUIVALENT": 2,
    "NO_EQUIVALENT": 3,
    "IDENTICAL": 4,
}

IDENT = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")


def keys_for(side_name: str) -> list[str]:
    """Same keying as tools/generate_hazards_registry.py."""
    keys = {side_name.strip().upper()}
    for piece in side_name.split("/"):
        piece = piece.strip()
        if piece.endswith("()"):
            piece = piece[:-2]
        if IDENT.match(piece):
            keys.add(piece.upper())
    return sorted(keys)


def build_side_index(pairs: list[dict], side: str) -> dict[str, dict]:
    """Uppercased lookup key → worst-verdict hazard pair (JSON order on ties)."""
    chosen: dict[str, dict] = {}
    chosen_rank: dict[str, int] = {}
    for pair in pairs:
        rank = SEVERITY_RANK[VERDICTS[pair["verdict"]]]
        for key in keys_for(pair[side]):
            prev = chosen_rank.get(key)
            if prev is None or rank < prev:
                chosen[key] = pair
                chosen_rank[key] = rank
    return chosen


def md_cell(s: str) -> str:
    """Escape pipe/newlines for a single markdown table cell."""
    return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ").strip()


def arity_note(entry: dict) -> str:
    arity = entry.get("arity") or "arity-unknown"
    extras: list[str] = []
    if entry.get("sqlglotTranslatesTo"):
        extras.append(f"sqlglot→{entry['sqlglotTranslatesTo']}")
    if extras:
        return f"{arity}; " + ", ".join(extras)
    return arity


def prior_cell(prior: dict | None) -> str:
    if prior is None:
        return "—"
    verdict = VERDICTS[prior["verdict"]]
    hazard = prior.get("hazard")
    if hazard:
        # Keep the table readable; full text is in the hazards JSON.
        text = hazard if len(hazard) <= 160 else hazard[:157] + "..."
        return f"**{verdict}** — {md_cell(text)}"
    return f"**{verdict}**"


def areas_cell(prior: dict | None) -> str:
    if prior is None:
        return "baseline"
    areas = prior.get("areas") or []
    if not areas:
        return "baseline"
    return ", ".join(areas)


def sort_key(row: tuple) -> tuple:
    """(name, kind, arity, prior_or_None, ...) — DIVERGENT first, then other prior, then none."""
    name, _kind, _arity, prior = row[0], row[1], row[2], row[3]
    if prior is None:
        return (2, 99, name.lower())
    verdict = VERDICTS[prior["verdict"]]
    if verdict == "DIVERGENT":
        return (0, 0, name.lower())
    return (1, SEVERITY_RANK[verdict], name.lower())


def collect_bucket_a(gap: dict, source: str, target: str) -> list[dict]:
    key = f"{source}->{target}"
    pair = gap["pairs"].get(key)
    if pair is None:
        sys.exit(f"error: gap-report.json missing pair {key!r}")
    return [e for e in pair["entries"] if e.get("bucket") == "A"]


def emit_table(rows: list[tuple]) -> list[str]:
    lines = [
        "| function | kinds | arity note | prior trino↔duckdb verdict + hazard | suggested probe areas |",
        "| --- | --- | --- | --- | --- |",
    ]
    for name, kind, arity, prior in sorted(rows, key=sort_key):
        lines.append(
            f"| `{md_cell(name)}` | {md_cell(kind)} | {md_cell(arity)} | "
            f"{prior_cell(prior)} | {md_cell(areas_cell(prior))} |"
        )
    return lines


def main() -> None:
    gap = json.loads(GAP_REPORT.read_text())
    hazards = json.loads(TRINO_DUCKDB_HAZARDS.read_text())
    haz_pairs = hazards["pairs"]
    by_trino = build_side_index(haz_pairs, "trino")
    by_duckdb = build_side_index(haz_pairs, "duckdb")
    side_index = {"trino": by_trino, "duckdb": by_duckdb}

    stats: dict[str, dict[str, int]] = {}
    sections: list[str] = []

    for source, target in TARGET_PAIRS:
        entries = collect_bucket_a(gap, source, target)
        index = side_index[source]
        rows: list[tuple] = []
        prior_n = 0
        divergent_n = 0
        for e in entries:
            name = e["name"]
            prior = index.get(name.strip().upper())
            if prior is not None:
                prior_n += 1
                if VERDICTS[prior["verdict"]] == "DIVERGENT":
                    divergent_n += 1
            rows.append(
                (
                    name,
                    e.get("kind") or "?",
                    arity_note(e),
                    prior,
                )
            )
        pair_key = f"{source}->{target}"
        stats[pair_key] = {
            "bucket_a": len(entries),
            "prior": prior_n,
            "divergent": divergent_n,
        }
        sections.append(f"## {source} → {target}")
        sections.append("")
        sections.append(
            f"Bucket A same-name candidates: **{len(entries)}**. "
            f"With prior trino↔duckdb evidence: **{prior_n}** "
            f"(of which **{divergent_n}** prior-DIVERGENT)."
        )
        sections.append("")
        sections.extend(emit_table(rows))
        sections.append("")

    header = [
        "# Doris semantic probe worklist",
        "",
        "Hand-off list for live differential probes of **duckdb→doris** and **trino→doris**",
        "same-name (bucket A) functions. Bucket A means the name exists in both catalogs —",
        "that is exactly where silent semantic divergence hides; identical names do **not**",
        "imply identical semantics (`gap-report.json` warning).",
        "",
        "## Methodology reference",
        "",
        "Follow the ducklake probe methodology documented in",
        "[function-semantics-trino-duckdb.md — verification methodology]",
        "(function-semantics-trino-duckdb.md#their-verification-methodology-what-verified-means):",
        "",
        "1. **Live differential testing** against both engines (not doc-reading).",
        "2. **Probe classes** that emit result tables for the same inputs on source and Doris;",
        "   paste findings into a short REPORT, then delete the throwaway probe.",
        "3. **Divergence-pressure corpus** — NULL algebra, Unicode edge cases (ZWJ emoji,",
        "   Turkish İ, German ß), timezone/session-zone traps, leap days, negative epochs,",
        "   type-coercion corners.",
        "4. **Discipline:** when in doubt, mark `unclear` / do not claim equivalence.",
        "",
        "Prior trino↔duckdb verdicts (from `trino-duckdb-hazards.json`) are **hints only** —",
        "re-check every annotated function against Doris; a prior IDENTICAL does not mean",
        "Doris matches either engine.",
        "",
        "## Expected deliverable format",
        "",
        "Fill `brikk-sql/testResources/semantics/{duckdb,trino}-doris-hazards.json` `pairs`",
        "arrays with one object per probed function (same schema as `trino-duckdb-hazards.json`):",
        "",
        "```json",
        "{",
        '  "duckdb": "<source-side name>",   // or "trino" for the trino-doris file',
        '  "doris": "<doris-side name>",',
        '  "verdict": "identical" | "divergent" | "conditionally-equivalent" | "no-equivalent" | "unclear",',
        '  "hazard": "<human-readable finding or null>",',
        '  "areas": ["null", "unicode", "..."],',
        '  "provenance": "<REPORT-file.md#anchor or probe-id>"',
        "}",
        "```",
        "",
        "Then regenerate the shipped registry:",
        "",
        "```bash",
        "python3 tools/generate_hazards_registry.py",
        "```",
        "",
        "Sort order below: **prior-DIVERGENT first** (highest suspicion), then other prior",
        "evidence (by severity), then functions with no trino↔duckdb prior (alphabetical).",
        "",
        f"_Generated by `tools/generate_probe_worklist.py` from `gap-report.json` + "
        f"`trino-duckdb-hazards.json`. Re-run is byte-deterministic._",
        "",
    ]

    body = "\n".join(header + sections)
    if not body.endswith("\n"):
        body += "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(body)

    print(f"wrote {OUT.relative_to(ROOT)}")
    for pair_key, s in stats.items():
        print(
            f"  {pair_key}: bucket-A={s['bucket_a']}, "
            f"prior-evidence={s['prior']}, prior-DIVERGENT={s['divergent']}"
        )


if __name__ == "__main__":
    main()
