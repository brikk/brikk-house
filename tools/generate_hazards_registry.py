#!/usr/bin/env python3
"""Generates the shipped semantic-hazard registry data for brikk-sql-metadata from the
live-probe-verified hazards JSON files.

Source of truth: brikk-sql/testResources/semantics/*-hazards.json — one file per dialect
pair, named <a>-<b>-hazards.json (e.g. trino-duckdb-hazards.json with 241 probe-verified
(trino, duckdb) pair verdicts). The JSON stays where it is (tests consume it directly);
this tool derives one Generated{A}{B}Hazards.kt per file and expects HazardRegistry to
wire every directional map through lookup(source, target, name). Rerunning the tool on
unchanged JSON is byte-deterministic — CI can enforce sync with
`python3 tools/generate_hazards_registry.py && git diff --exit-code`.

Keying (mirrored in HazardRegistry's KDoc — keep both in sync):
  - every entry is keyed BOTH ways: by its source-side name for the a->b map and
    by its target-side name for the b->a map (the name a fragment parsed under
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

Empty pair files (pairs: []) are tolerated: the generator emits emptyList() entries and
empty directional maps so HazardRegistry can wire the pair before probes land.

Usage: python3 tools/generate_hazards_registry.py [<json-path> ...]
       (default: every brikk-sql/testResources/semantics/*-hazards.json, sorted)
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEMANTICS_DIR = ROOT / "brikk-sql" / "testResources" / "semantics"
OUT_DIR = ROOT / "brikk-sql-metadata" / "src" / "dev.brikk.house.sql.metadata"
HAZARDS_GLOB = "*-hazards.json"

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


def emit_entry(pair: dict, source_field: str, target_field: str) -> list[str]:
    """One FunctionHazard, with sourceName/targetName oriented to the map's direction
    (source_field/target_field are the JSON dialect keys for this direction)."""
    verdict = VERDICTS[pair["verdict"]]
    lines = [f"    FunctionHazard(HazardVerdict.{verdict},"]
    hazard = pair.get("hazard")
    if hazard:
        lines.append(f"        hazard = {kstr(hazard)},")
    areas = pair.get("areas") or []
    if areas:
        lines.append("        areas = listOf(" + ", ".join(kstr(a) for a in areas) + "),")
    lines.append(f"        provenance = {kstr(pair['provenance'])},")
    lines.append(f"        sourceName = {kstr(pair[source_field])},")
    lines.append(f"        targetName = {kstr(pair[target_field])}),")
    return lines


def emit_entries_block(
    entries_name: str, chunk_prefix: str, pairs: list[dict],
    a: str, b: str, source_field: str, target_field: str,
) -> list[str]:
    """An oriented entries list (`entries_name`) + its chunk functions. sourceName/
    targetName follow source_field->target_field so each lookup direction is correct."""
    lines: list[str] = []
    if not pairs:
        lines.append(
            f"/** The 0 probe-verified ({source_field}->{target_field}) verdicts. */"
        )
        lines.append(f"internal val {entries_name}: List<FunctionHazard> = emptyList()")
        return lines
    n_chunks = (len(pairs) + CHUNK - 1) // CHUNK
    lines.append(
        f"/** The {len(pairs)} probe-verified ({source_field}->{target_field}) "
        f"verdicts, in JSON order. */"
    )
    lines.append(
        f"internal val {entries_name}: List<FunctionHazard> = "
        + " +\n    ".join(f"{chunk_prefix}Chunk{c}()" for c in range(n_chunks))
    )
    lines.append("")
    for c in range(0, len(pairs), CHUNK):
        chunk = pairs[c : c + CHUNK]
        lines.append(f"private fun {chunk_prefix}Chunk{c // CHUNK}(): List<FunctionHazard> = listOf(")
        for i, pair in enumerate(chunk):
            lines.append(f"    // [{c + i}] {a}: {pair[a]!r} | {b}: {pair[b]!r}")
            lines.extend(emit_entry(pair, source_field, target_field))
        lines.append(")")
        lines.append("")
    return lines


def emit_key_map(
    name: str, doc: str, key_map: dict[str, int], entries_name: str
) -> list[str]:
    lines = [
        f"/** {doc} */",
        f"internal val {name}: Map<String, FunctionHazard> = buildMap {{",
    ]
    for key in sorted(key_map):
        lines.append(f"    put({kstr(key)}, {entries_name}[{key_map[key]}])")
    lines.append("}")
    return lines


def parse_pair_filename(path: pathlib.Path) -> tuple[str, str]:
    """trino-duckdb-hazards.json -> ('trino', 'duckdb')."""
    name = path.name
    if not name.endswith("-hazards.json"):
        sys.exit(f"error: expected *-hazards.json, got {name!r}")
    stem = name[: -len("-hazards.json")]
    parts = stem.split("-")
    if len(parts) != 2 or not all(parts):
        sys.exit(
            f"error: hazards filename must be <a>-<b>-hazards.json, got {name!r}"
        )
    return parts[0], parts[1]


# File/type PascalCase (GeneratedTrinoDuckdbHazards) vs human display (DuckDB-side names).
PASCAL_DIALECT = {
    "trino": "Trino",
    "duckdb": "Duckdb",
    "doris": "Doris",
    "clickhouse": "Clickhouse",
}
DISPLAY_DIALECT = {
    "trino": "Trino",
    "duckdb": "DuckDB",
    "doris": "Doris",
    "clickhouse": "ClickHouse",
}


def pascal_dialect(d: str) -> str:
    """trino -> Trino, duckdb -> Duckdb (matches existing GeneratedTrinoDuckdbHazards)."""
    return PASCAL_DIALECT.get(d, d[:1].upper() + d[1:] if d else d)


def display_dialect(d: str) -> str:
    """Human label in KDoc: duckdb -> DuckDB (byte-stable with the original generator)."""
    return DISPLAY_DIALECT.get(d, pascal_dialect(d))


def generate_one(src: pathlib.Path) -> None:
    a, b = parse_pair_filename(src)
    data = json.loads(src.read_text())
    pairs = data.get("pairs") or []
    if not isinstance(pairs, list):
        sys.exit(f"error: {src.name}: 'pairs' must be a list")
    for pair in pairs:
        if pair["verdict"] not in VERDICTS:
            sys.exit(f"error: unknown verdict {pair['verdict']!r} (update VERDICTS?)")
        if a not in pair or b not in pair:
            sys.exit(
                f"error: {src.name}: each pair needs {a!r} and {b!r} side-name fields"
            )

    a_keys = build_key_map(pairs, a) if pairs else {}
    b_keys = build_key_map(pairs, b) if pairs else {}

    a_up, b_up = a.upper(), b.upper()
    a_pas, b_pas = pascal_dialect(a), pascal_dialect(b)
    a_disp, b_disp = display_dialect(a), display_dialect(b)
    # Two direction-oriented entry lists: sourceName/targetName follow the lookup
    # direction, so a b->a hit reports the b-side name as sourceName (and vice versa).
    ab_entries_name = f"{a_up}_TO_{b_up}_HAZARD_ENTRIES"
    ba_entries_name = f"{b_up}_TO_{a_up}_HAZARD_ENTRIES"
    a_to_b_name = f"{a_up}_TO_{b_up}_HAZARDS"
    b_to_a_name = f"{b_up}_TO_{a_up}_HAZARDS"
    out = OUT_DIR / f"Generated{a_pas}{b_pas}Hazards.kt"

    extracted = data.get("extracted")
    extracted_s = "null" if extracted is None else str(extracted)
    source_project = data.get("source_project", "")

    lines = [
        "// GENERATED FILE — DO NOT EDIT.",
        "// Generated by tools/generate_hazards_registry.py from",
        f"// brikk-sql/testResources/semantics/{src.name} ({source_project},",
        f"// extracted {extracted_s}): live-probe-verified {a}<->{b} semantics",
        "// verdicts. Regenerate after editing the JSON; the run is byte-deterministic.",
        "//",
        "// Keying and collision policy: see the tool's docstring / HazardRegistry KDoc",
        "// (raw side-name + bare-identifier alternatives; collisions keep the WORST",
        "// verdict, ties keep JSON order).",
        "package dev.brikk.house.sql.metadata",
        "",
    ]

    lines.extend(
        emit_entries_block(ab_entries_name, f"{a}{b_pas}", pairs, a, b, a, b)
    )
    lines.append("")
    lines.extend(
        emit_entries_block(ba_entries_name, f"{b}{a_pas}", pairs, a, b, b, a)
    )
    lines.append("")

    lines.extend(
        emit_key_map(
            a_to_b_name,
            f"{a}->{b} lookup: {len(a_keys)} keys ({a_disp}-side names) over "
            f"{len(pairs)} entries.",
            a_keys,
            ab_entries_name,
        )
    )
    lines.append("")
    lines.extend(
        emit_key_map(
            b_to_a_name,
            f"{b}->{a} lookup: {len(b_keys)} keys ({b_disp}-side names) over "
            f"{len(pairs)} entries.",
            b_keys,
            ba_entries_name,
        )
    )

    out.write_text("\n".join(lines) + "\n")
    counts: dict[str, int] = {}
    for pair in pairs:
        counts[pair["verdict"]] = counts.get(pair["verdict"], 0) + 1
    print(
        f"wrote {out.relative_to(ROOT)}: {len(pairs)} entries, "
        f"{len(a_keys)} {a}->{b} keys, {len(b_keys)} {b}->{a} keys, "
        f"verdicts {counts}"
    )


def discover_sources() -> list[pathlib.Path]:
    return sorted(SEMANTICS_DIR.glob(HAZARDS_GLOB))


def main() -> None:
    if len(sys.argv) > 1:
        sources = [pathlib.Path(p) for p in sys.argv[1:]]
    else:
        sources = discover_sources()
        if not sources:
            sys.exit(f"error: no {HAZARDS_GLOB} under {SEMANTICS_DIR.relative_to(ROOT)}")
    for src in sources:
        if not src.is_file():
            sys.exit(f"error: not a file: {src}")
        generate_one(src)


if __name__ == "__main__":
    main()
