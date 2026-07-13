#!/usr/bin/env python3
"""Generates the shipped semantic-hazard registry data for brikk-sql-metadata from the
live-probe-verified hazards JSON.

Source of truth: brikk-sql/testResources/semantics/trino-duckdb-hazards.json — 241
(trino, duckdb) pair verdicts extracted from the trino-ducklake research reports (each
entry's `provenance` points at the report section that pinned it). The JSON stays where
it is (tests consume it directly); this tool derives the shipped Kotlin data from it.
Rerunning the tool on an unchanged JSON is byte-deterministic — CI can enforce sync with
`python3 tools/generate_hazards_registry.py && git diff --exit-code`.

Keying (mirrored in HazardRegistry's KDoc — keep both in sync):
  - every entry is keyed BOTH ways: by its Trino-side name for the trino->duckdb map and
    by its DuckDB-side name for the duckdb->trino map (the name a fragment parsed under
    that source dialect would carry);
  - keys per side-name: the raw string uppercased (constructs like "CAST (primitive)"
    stay retrievable verbatim but never match parsed function names — intentional),
    plus each bare-identifier alternative from `a / b / c` lists (a trailing `()` is
    stripped: `today()` -> TODAY);
  - collisions (same key claimed by several entries, e.g. Trino `concat` has both a
    divergent argument-coercion entry and an identical ||-mapping entry) resolve to the
    WORST verdict: DIVERGENT > UNCLEAR > CONDITIONALLY_EQUIVALENT > NO_EQUIVALENT >
    IDENTICAL; ties keep the first entry in JSON order. A hazard registry must be
    conservative.

Usage: python3 tools/generate_hazards_registry.py [<json-path>]
       (default: brikk-sql/testResources/semantics/trino-duckdb-hazards.json)
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "brikk-sql" / "testResources" / "semantics" / "trino-duckdb-hazards.json"
OUT = ROOT / "brikk-sql-metadata" / "src" / "dev.brikk.house.sql.metadata" / "GeneratedTrinoDuckdbHazards.kt"

VERDICTS = {
    "identical": "IDENTICAL",
    "divergent": "DIVERGENT",
    "conditionally-equivalent": "CONDITIONALLY_EQUIVALENT",
    "no-equivalent": "NO_EQUIVALENT",
    "unclear": "UNCLEAR",
}

# Worst-first preference for key collisions (see module docstring).
SEVERITY_RANK = {
    "DIVERGENT": 0,
    "UNCLEAR": 1,
    "CONDITIONALLY_EQUIVALENT": 2,
    "NO_EQUIVALENT": 3,
    "IDENTICAL": 4,
}

IDENT = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")

CHUNK = 40  # entries per emitted function (keeps methods well under JVM bytecode limits)


def kstr(s: str) -> str:
    out = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    out = out.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return '"' + out + '"'


def keys_for(side_name: str) -> list[str]:
    """All lookup keys one side-name contributes (raw + bare-identifier alternatives)."""
    keys = {side_name.strip().upper()}
    for piece in side_name.split("/"):
        piece = piece.strip()
        if piece.endswith("()"):
            piece = piece[:-2]
        if IDENT.match(piece):
            keys.add(piece.upper())
    return sorted(keys)


def build_key_map(pairs: list[dict], side: str) -> dict[str, int]:
    """key -> entry index, collisions resolved worst-verdict-first, then JSON order."""
    chosen: dict[str, int] = {}
    for i, pair in enumerate(pairs):
        rank = SEVERITY_RANK[VERDICTS[pair["verdict"]]]
        for key in keys_for(pair[side]):
            prev = chosen.get(key)
            if prev is None or rank < SEVERITY_RANK[VERDICTS[pairs[prev]["verdict"]]]:
                chosen[key] = i
    return chosen


def emit_entry(pair: dict) -> list[str]:
    verdict = VERDICTS[pair["verdict"]]
    lines = [f"    FunctionHazard(HazardVerdict.{verdict},"]
    hazard = pair.get("hazard")
    if hazard:
        lines.append(f"        hazard = {kstr(hazard)},")
    areas = pair.get("areas") or []
    if areas:
        lines.append("        areas = listOf(" + ", ".join(kstr(a) for a in areas) + "),")
    lines.append(f"        provenance = {kstr(pair['provenance'])}),")
    return lines


def emit_key_map(name: str, doc: str, key_map: dict[str, int]) -> list[str]:
    lines = [
        f"/** {doc} */",
        f"internal val {name}: Map<String, FunctionHazard> = buildMap {{",
    ]
    for key in sorted(key_map):
        lines.append(f"    put({kstr(key)}, TRINO_DUCKDB_HAZARD_ENTRIES[{key_map[key]}])")
    lines.append("}")
    return lines


def main() -> None:
    src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_JSON
    data = json.loads(src.read_text())
    pairs = data["pairs"]
    for pair in pairs:
        if pair["verdict"] not in VERDICTS:
            sys.exit(f"error: unknown verdict {pair['verdict']!r} (update VERDICTS?)")

    trino_keys = build_key_map(pairs, "trino")
    duckdb_keys = build_key_map(pairs, "duckdb")

    lines = [
        "// GENERATED FILE — DO NOT EDIT.",
        "// Generated by tools/generate_hazards_registry.py from",
        f"// brikk-sql/testResources/semantics/{src.name} ({data['source_project']},",
        f"// extracted {data['extracted']}): live-probe-verified trino<->duckdb semantics",
        "// verdicts. Regenerate after editing the JSON; the run is byte-deterministic.",
        "//",
        "// Keying and collision policy: see the tool's docstring / HazardRegistry KDoc",
        "// (raw side-name + bare-identifier alternatives; collisions keep the WORST",
        "// verdict, ties keep JSON order).",
        "package dev.brikk.house.sql.metadata",
        "",
        f"/** The {len(pairs)} probe-verified (trino, duckdb) pair verdicts, in JSON order. */",
        "internal val TRINO_DUCKDB_HAZARD_ENTRIES: List<FunctionHazard> = " +
        " +\n    ".join(f"hazardsChunk{c}()" for c in range((len(pairs) + CHUNK - 1) // CHUNK)),
        "",
    ]

    for c in range(0, len(pairs), CHUNK):
        chunk = pairs[c:c + CHUNK]
        lines.append(f"private fun hazardsChunk{c // CHUNK}(): List<FunctionHazard> = listOf(")
        for i, pair in enumerate(chunk):
            lines.append(f"    // [{c + i}] trino: {pair['trino']!r} | duckdb: {pair['duckdb']!r}")
            lines.extend(emit_entry(pair))
        lines.append(")")
        lines.append("")

    lines.extend(emit_key_map(
        "TRINO_TO_DUCKDB_HAZARDS",
        f"trino->duckdb lookup: {len(trino_keys)} keys (Trino-side names) over "
        f"{len(pairs)} entries.",
        trino_keys,
    ))
    lines.append("")
    lines.extend(emit_key_map(
        "DUCKDB_TO_TRINO_HAZARDS",
        f"duckdb->trino lookup: {len(duckdb_keys)} keys (DuckDB-side names) over "
        f"{len(pairs)} entries.",
        duckdb_keys,
    ))

    OUT.write_text("\n".join(lines) + "\n")
    counts: dict[str, int] = {}
    for pair in pairs:
        counts[pair["verdict"]] = counts.get(pair["verdict"], 0) + 1
    print(f"wrote {OUT.relative_to(ROOT)}: {len(pairs)} entries, "
          f"{len(trino_keys)} trino->duckdb keys, {len(duckdb_keys)} duckdb->trino keys, "
          f"verdicts {counts}")


if __name__ == "__main__":
    main()
