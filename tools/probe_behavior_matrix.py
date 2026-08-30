#!/usr/bin/env python3
"""Shared per-engine behavior-matrix probe for StarRocks/Doris (MySQL-family lineage).

Runs a deterministic catalog of semantic TEST VECTORS (SQL expressions grouped by
function + semantic area: NULL algebra, arity/coercion/return type, integer/decimal/
floating rounding, unicode/case/length/indexing, regex, dates/timestamps/time zones,
arrays/maps/JSON, aggregates, boundary inputs) ONCE per engine, records the raw result
(or error) per (engine, vector), then DERIVES pairwise SAME/DIFF verdicts. This avoids
hand-maintained O(n^2) probe duplication: add an engine connection and every pair
involving it is derived from the same vectors.

Engines are live MySQL-protocol endpoints (StarRocks 4.1.4 / Doris 4.1.3 all-in-one
Docker), queried via `docker exec <container> mysql`. Results are stored raw and
reproducibly:
  - vendor/data/behavior-matrix/<engine>-results.tsv   (per-engine raw: vector_id, sql, result)
  - docs/research/probe-runs/<ts>-starrocks-doris.tsv  (pairwise SAME/DIFF evidence)

Never infers semantic identity from names/lineage/docs — only from the live results.

Usage:
  python3 tools/probe_behavior_matrix.py run          # probe all engines, store raw
  python3 tools/probe_behavior_matrix.py verdicts      # derive pairwise + hazards JSON
  python3 tools/probe_behavior_matrix.py all           # run + verdicts
"""

from __future__ import annotations

import datetime
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MATRIX_DIR = ROOT / "vendor" / "data" / "behavior-matrix"
PROBE_RUNS = ROOT / "docs" / "research" / "probe-runs"
SEMANTICS = ROOT / "brikk-sql" / "testResources" / "semantics"
VECTORS_JSON = MATRIX_DIR / "vectors.json"

# Live engine connections (MySQL protocol via docker exec).
ENGINES = {
    "starrocks": {
        "container": "starrocks-4.1.4",
        "version": "4.1.4",
        "port": "9030",
    },
    "doris": {
        "container": "doris-4.1.3",
        "version": "4.1.3",
        "port": "9030",
    },
}


def run_sql(engine: str, sql: str) -> str:
    """Run one SQL statement, return the single scalar result or an <ERR:...> marker.
    Deterministic: -N (no column names), -B (batch/tab), single row/col expected."""
    cfg = ENGINES[engine]
    cmd = [
        "docker", "exec", cfg["container"],
        "mysql", "-uroot", "-h127.0.0.1", "-P" + cfg["port"], "-N", "-B", "-e", sql,
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired:
        return "<ERR:timeout>"
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip().replace("\n", " ")
        return "<ERR:" + err[:200] + ">"
    out = proc.stdout
    # strip a single trailing newline; keep internal representation verbatim
    if out.endswith("\n"):
        out = out[:-1]
    if out == "NULL":
        return "NULL"
    return out.replace("\t", "\\t").replace("\n", "\\n")


def load_vectors() -> list[dict]:
    return json.loads(VECTORS_JSON.read_text())["vectors"]


def cmd_run() -> None:
    vectors = load_vectors()
    MATRIX_DIR.mkdir(parents=True, exist_ok=True)
    for engine in ENGINES:
        lines = ["vector_id\tsql\tresult"]
        ok = 0
        for v in vectors:
            res = run_sql(engine, "SELECT " + v["sql"])
            if not res.startswith("<ERR:"):
                ok += 1
            lines.append(f"{v['id']}\t{v['sql']}\t{res}")
        out = MATRIX_DIR / f"{engine}-results.tsv"
        out.write_text("\n".join(lines) + "\n")
        print(f"{engine} ({ENGINES[engine]['version']}): {ok}/{len(vectors)} vectors ran ok -> {out.relative_to(ROOT)}")


def load_results(engine: str) -> dict[str, str]:
    path = MATRIX_DIR / f"{engine}-results.tsv"
    res = {}
    for line in path.read_text().splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) >= 3:
            res[parts[0]] = parts[2]
    return res


def derive_pairwise(a: str, b: str) -> tuple[list[dict], dict[str, dict]]:
    """Returns (raw evidence rows, per-function verdict aggregation)."""
    vectors = load_vectors()
    ra, rb = load_results(a), load_results(b)
    rows = []
    # function -> {"areas": set, "same": n, "diff": n, "err_both": n, "err_one": n, examples: [...]}
    by_fn: dict[str, dict] = {}
    for v in vectors:
        vid = v["id"]
        fn = v["function"]
        area = v["area"]
        va, vb = ra.get(vid, "<MISSING>"), rb.get(vid, "<MISSING>")
        a_err = va.startswith("<ERR:") or va == "<MISSING>"
        b_err = vb.startswith("<ERR:") or vb == "<MISSING>"
        if a_err and b_err:
            status = "BOTH_ERR"
        elif a_err or b_err:
            status = "ONE_ERR"
        elif va == vb:
            status = "SAME"
        else:
            status = "DIFF"
        rows.append({"id": vid, "function": fn, "area": area, "sql": v["sql"],
                     "status": status, a: va, b: vb})
        agg = by_fn.setdefault(fn, {"areas": set(), "same": 0, "diff": 0,
                                    "both_err": 0, "one_err": 0, "examples": []})
        agg["areas"].add(area)
        if status == "SAME":
            agg["same"] += 1
        elif status == "DIFF":
            agg["diff"] += 1
            if len(agg["examples"]) < 3:
                agg["examples"].append({"sql": v["sql"], a: va, b: vb})
        elif status == "BOTH_ERR":
            agg["both_err"] += 1
        else:
            agg["one_err"] += 1
            if len(agg["examples"]) < 3:
                agg["examples"].append({"sql": v["sql"], a: va, b: vb})
    return rows, by_fn


def verdict_for(agg: dict) -> str:
    """Derive a conservative verdict from the per-function aggregation."""
    same, diff, one_err, both_err = agg["same"], agg["diff"], agg["one_err"], agg["both_err"]
    total = same + diff + one_err + both_err
    if total == 0:
        return "unclear"
    if diff == 0 and one_err == 0 and same > 0:
        return "identical"
    if diff > 0 and same == 0:
        return "divergent"
    if diff > 0 or one_err > 0:
        # some vectors agree, some diverge -> conditionally equivalent (documented corners)
        return "conditionally-equivalent"
    return "unclear"


def cmd_verdicts() -> None:
    ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    PROBE_RUNS.mkdir(parents=True, exist_ok=True)
    vectors = load_vectors()
    # StarRocks<->Doris (both engines are MySQL-family; symmetric probe).
    a, b = "starrocks", "doris"
    rows, by_fn = derive_pairwise(a, b)

    # Raw evidence TSV (reproducible).
    tsv = [f"vector_id\tfunction\tarea\tsql\tstatus\t{a}\t{b}"]
    for r in rows:
        tsv.append(f"{r['id']}\t{r['function']}\t{r['area']}\t{r['sql']}\t{r['status']}\t{r[a]}\t{r[b]}")
    evidence = PROBE_RUNS / f"{ts}-starrocks-doris.tsv"
    evidence.write_text("\n".join(tsv) + "\n")

    # Hazards JSON (source of truth for the generated registry). Both directions share
    # the same function name (MySQL-family), so a/b side-names are identical.
    pairs = []
    for fn in sorted(by_fn):
        agg = by_fn[fn]
        verdict = verdict_for(agg)
        areas = sorted(agg["areas"])
        hazard = None
        if verdict in ("divergent", "conditionally-equivalent"):
            ex = agg["examples"][0] if agg["examples"] else {}
            hazard = (f"{agg['diff']} diverging / {agg['same']} identical vectors; "
                      f"e.g. {ex.get('sql','')} -> {a}={ex.get(a,'?')} {b}={ex.get(b,'?')}")
        pairs.append({
            "starrocks": fn,
            "doris": fn,
            "verdict": verdict,
            "hazard": hazard,
            "areas": areas,
            "provenance": f"probe-runs/{evidence.name}#{fn}",
        })

    hazards = {
        "source_project": "brikk behavior-matrix probe (tools/probe_behavior_matrix.py)",
        "extracted": ts,
        "engines": {a: ENGINES[a]["version"], b: ENGINES[b]["version"]},
        "coverage": {
            "status": "partial",
            "catalogs": {
                "starrocks": "4.1.4 SHOW FULL BUILTIN FUNCTIONS: 820 functions / 6242 overloads",
                "doris": "4.1.3 live all-in-one oracle; repository Doris catalog pin",
            },
            "scope": (
                f"{len(vectors)} behavior vectors yielding {len(pairs)} named concepts; "
                "partial against the 820-function StarRocks catalog; unlisted functions are unprobed"
            ),
        },
        "pairs": pairs,
    }
    # filename must sort a,b alphabetically for the pair-file convention: doris-starrocks
    out = SEMANTICS / "doris-starrocks-hazards.json"
    out.write_text(json.dumps(hazards, indent=1) + "\n")

    counts: dict[str, int] = {}
    for p in pairs:
        counts[p["verdict"]] = counts.get(p["verdict"], 0) + 1
    print(f"wrote {evidence.relative_to(ROOT)} ({len(rows)} vectors)")
    print(f"wrote {out.relative_to(ROOT)} ({len(pairs)} function verdicts): {counts}")


def main() -> None:
    action = sys.argv[1] if len(sys.argv) > 1 else "all"
    if action in ("run", "all"):
        cmd_run()
    if action in ("verdicts", "all"):
        cmd_verdicts()


if __name__ == "__main__":
    main()
