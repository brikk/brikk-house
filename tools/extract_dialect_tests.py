#!/usr/bin/env python3
"""Extract sqlglot inline dialect test assertions into machine-readable JSON.

Parses reference/sqlglot/tests/dialects/test_*.py with the `ast` module (never
executes test code) and extracts every statically-resolvable
`validate_identity` / `validate_all` assertion, grouped by dialect, into
brikk-sql/testResources/dialect-corpus/<dialect>.json.

Output schema per dialect file:
    {
      "sqlglot_version": "<git describe of reference/sqlglot>",
      "dialect": "snowflake",            # "" for the base/generic dialect
      "identity": [
        {"sql": "...", "expected": null | "...", "pretty": false,
         # optional flags, present only when true:
         "identify": true, "check_command_warning": true}
      ],
      "transpile": [
        {"sql": "...", "read": {dialect: sql, ...}, "write": {dialect: sql, ...},
         # a write value may be {"error": "UnsupportedError"} meaning the
         # generation must raise with unsupported_level=RAISE
         # optional flags, present only when true:
         "pretty": true, "identify": true}
      ],
      "stats": {"identity_count": N, "transpile_count": N, "skipped_dynamic": N}
    }

Semantics (mirrors tests/dialects/test_dialect.py Validator):
  identity:  parse_one(sql, read=dialect).sql(dialect=dialect, pretty=pretty,
             identify=identify) == (expected or sql)
  transpile: for each read entry:  parse_one(read_sql, read_dialect)
                 .sql(dialect, unsupported_level=IGNORE, ...) == sql
             for each write entry: parse_one(sql, dialect)
                 .sql(write_dialect, unsupported_level=IGNORE, ...) == write_sql
             (or raises when write value is {"error": ...})

Regenerate with:
    python3 tools/extract_dialect_tests.py

Sanity check against live Python sqlglot (uses PYTHONPATH-free direct import
of reference/sqlglot) runs by default; disable with --no-verify.
"""

from __future__ import annotations

import argparse
import ast
import json
import random
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent.parent
SQLGLOT_DIR = ROOT / "reference" / "sqlglot"
DIALECTS_DIR = SQLGLOT_DIR / "tests" / "dialects"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "dialect-corpus"

METHODS = ("validate_identity", "validate_all")


class Dynamic(Exception):
    """Raised when an argument cannot be resolved to a static literal."""


# ---------------------------------------------------------------------------
# Static literal folding
# ---------------------------------------------------------------------------


def fold_string(node: ast.AST) -> str:
    """Fold an AST node into a string constant, or raise Dynamic.

    Handles: plain constants, implicit adjacent-string concatenation (already
    merged by the parser into a single Constant), f-strings without any
    interpolation, and trivial `"a" + "b"` BinOp chains.
    """
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        parts = []
        for value in node.values:
            if isinstance(value, ast.Constant) and isinstance(value.value, str):
                parts.append(value.value)
            else:
                raise Dynamic(f"f-string interpolation at line {node.lineno}")
        return "".join(parts)
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        return fold_string(node.left) + fold_string(node.right)
    raise Dynamic(f"{type(node).__name__} at line {getattr(node, 'lineno', '?')}")


def fold_bool(node: ast.AST) -> bool:
    if isinstance(node, ast.Constant) and isinstance(node.value, bool):
        return node.value
    raise Dynamic(f"non-constant bool at line {getattr(node, 'lineno', '?')}")


def fold_dialect_value(node: ast.AST) -> Any:
    """Fold a read/write dict value: SQL string or an exception class name."""
    if isinstance(node, ast.Name):
        # e.g. "presto": UnsupportedError
        return {"error": node.id}
    if isinstance(node, ast.Attribute):
        # e.g. errors.UnsupportedError (defensive; not currently used)
        return {"error": node.attr}
    return fold_string(node)


def fold_rw_dict(node: ast.AST) -> Dict[str, Any]:
    if isinstance(node, ast.Constant) and node.value is None:
        return {}
    if not isinstance(node, ast.Dict):
        raise Dynamic(f"non-literal dict at line {getattr(node, 'lineno', '?')}")
    result: Dict[str, Any] = {}
    for key, value in zip(node.keys, node.values):
        if key is None:  # **spread
            raise Dynamic(f"dict spread at line {node.lineno}")
        key_str = fold_string(key)
        result[key_str] = fold_dialect_value(value)
    return result


# ---------------------------------------------------------------------------
# Call extraction
# ---------------------------------------------------------------------------


def extract_identity(call: ast.Call) -> Dict[str, Any]:
    if not call.args:
        raise Dynamic(f"no positional args at line {call.lineno}")
    sql = fold_string(call.args[0])

    expected: Optional[str] = None
    pretty = False
    identify = False
    check_command_warning = False

    if len(call.args) >= 2:
        expected = fold_string(call.args[1])
    if len(call.args) >= 3:
        pretty = fold_bool(call.args[2])
    if len(call.args) >= 4:
        raise Dynamic(f"unexpected extra positional args at line {call.lineno}")

    for kw in call.keywords:
        if kw.arg == "write_sql":
            expected = fold_string(kw.value)
        elif kw.arg == "pretty":
            pretty = fold_bool(kw.value)
        elif kw.arg == "identify":
            identify = fold_bool(kw.value)
        elif kw.arg == "check_command_warning":
            check_command_warning = fold_bool(kw.value)
        else:
            raise Dynamic(f"unknown kwarg {kw.arg!r} at line {call.lineno}")

    entry: Dict[str, Any] = {"sql": sql, "expected": expected, "pretty": pretty}
    if identify:
        entry["identify"] = True
    if check_command_warning:
        entry["check_command_warning"] = True
    return entry


def extract_all(call: ast.Call) -> Optional[Dict[str, Any]]:
    """Returns None for assertion-free calls (no read and no write)."""
    if not call.args:
        raise Dynamic(f"no positional args at line {call.lineno}")
    sql = fold_string(call.args[0])

    read: Dict[str, Any] = {}
    write: Dict[str, Any] = {}
    pretty = False
    identify = False

    if len(call.args) >= 2:
        read = fold_rw_dict(call.args[1])
    if len(call.args) >= 3:
        write = fold_rw_dict(call.args[2])
    if len(call.args) >= 4:
        raise Dynamic(f"unexpected extra positional args at line {call.lineno}")

    for kw in call.keywords:
        if kw.arg == "read":
            read = fold_rw_dict(kw.value)
        elif kw.arg == "write":
            write = fold_rw_dict(kw.value)
        elif kw.arg == "pretty":
            pretty = fold_bool(kw.value)
        elif kw.arg == "identify":
            identify = fold_bool(kw.value)
        else:
            raise Dynamic(f"unknown kwarg {kw.arg!r} at line {call.lineno}")

    if not read and not write:
        return None  # asserts nothing about generation

    entry: Dict[str, Any] = {"sql": sql, "read": read, "write": write}
    if pretty:
        entry["pretty"] = True
    if identify:
        entry["identify"] = True
    return entry


# ---------------------------------------------------------------------------
# Per-file extraction
# ---------------------------------------------------------------------------


def class_dialect(cls: ast.ClassDef) -> str:
    """Read the class-level `dialect = "..."` attribute. None/absent -> ""."""
    for item in cls.body:
        if isinstance(item, ast.Assign):
            for target in item.targets:
                if isinstance(target, ast.Name) and target.id == "dialect":
                    value = item.value
                    if isinstance(value, ast.Constant) and isinstance(value.value, str):
                        return value.value
    return ""


def extract_file(path: Path) -> Tuple[Dict[str, Dict[str, list]], Counter, int]:
    """Extract one test file.

    Returns (per-dialect {"identity": [...], "transpile": [...]},
             skipped-dynamic counter keyed by file name, empty validate_all count).
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    per_dialect: Dict[str, Dict[str, list]] = defaultdict(
        lambda: {"identity": [], "transpile": []}
    )
    skipped: Counter = Counter()
    empty_all = 0

    for cls in tree.body:
        if not isinstance(cls, ast.ClassDef):
            continue
        dialect = class_dialect(cls)
        bucket = per_dialect[dialect]
        for node in ast.walk(cls):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            if not (isinstance(func, ast.Attribute) and func.attr in METHODS):
                continue
            try:
                if func.attr == "validate_identity":
                    bucket["identity"].append(extract_identity(node))
                else:
                    entry = extract_all(node)
                    if entry is None:
                        empty_all += 1
                    else:
                        bucket["transpile"].append(entry)
            except Dynamic as exc:
                skipped[f"{path.name}: {exc}"] += 1

    return per_dialect, skipped, empty_all


# ---------------------------------------------------------------------------
# Verification against live sqlglot
# ---------------------------------------------------------------------------


def verify_cases(
    corpora: Dict[str, Dict[str, Any]], sample_size: int, seed: int
) -> bool:
    sys.path.insert(0, str(SQLGLOT_DIR))
    import logging

    logging.disable(logging.CRITICAL)  # silence check_command_warning cases
    import sqlglot
    from sqlglot import ErrorLevel, parse_one
    from sqlglot.errors import SqlglotError

    rng = random.Random(seed)

    identity_pool = [
        (dialect, case)
        for dialect, corpus in corpora.items()
        for case in corpus["identity"]
    ]
    transpile_pool = [
        (dialect, case)
        for dialect, corpus in corpora.items()
        for case in corpus["transpile"]
    ]

    print(f"\nSanity check (seed={seed}) against live sqlglot "
          f"{getattr(sqlglot, '__version__', 'source checkout')}:")
    ok = True

    for dialect, case in rng.sample(identity_pool, sample_size):
        read = dialect or None
        expected = case["expected"] or case["sql"]
        label = f"  [identity/{dialect or 'base'}] {case['sql'][:70]!r}"
        try:
            actual = parse_one(case["sql"], read=read).sql(
                dialect=read,
                pretty=case.get("pretty", False),
                identify=case.get("identify", False),
            )
            if actual == expected:
                print(f"{label} ... PASS")
            else:
                ok = False
                print(f"{label} ... FAIL\n    expected: {expected!r}\n    actual:   {actual!r}")
        except SqlglotError as exc:
            ok = False
            print(f"{label} ... ERROR {exc}")

    for dialect, case in rng.sample(transpile_pool, sample_size):
        read_dialect = dialect or None
        label = f"  [transpile/{dialect or 'base'}] {case['sql'][:70]!r}"
        failures = []
        try:
            for rd, rsql in case["read"].items():
                actual = parse_one(rsql, rd).sql(
                    read_dialect,
                    unsupported_level=ErrorLevel.IGNORE,
                    pretty=case.get("pretty", False),
                    identify=case.get("identify", False),
                )
                if actual != case["sql"]:
                    failures.append(f"read[{rd}]: expected {case['sql']!r}, got {actual!r}")
            expression = parse_one(case["sql"], read=read_dialect)
            for wd, wsql in case["write"].items():
                if isinstance(wsql, dict):  # {"error": "UnsupportedError"}
                    try:
                        expression.sql(wd, unsupported_level=ErrorLevel.RAISE)
                        failures.append(f"write[{wd}]: expected {wsql['error']}, none raised")
                    except SqlglotError:
                        pass
                else:
                    actual = expression.sql(
                        wd,
                        unsupported_level=ErrorLevel.IGNORE,
                        pretty=case.get("pretty", False),
                        identify=case.get("identify", False),
                    )
                    if actual != wsql:
                        failures.append(f"write[{wd}]: expected {wsql!r}, got {actual!r}")
        except SqlglotError as exc:
            failures.append(f"exception: {exc}")
        if failures:
            ok = False
            print(f"{label} ... FAIL")
            for f in failures:
                print(f"    {f}")
        else:
            print(f"{label} ... PASS")

    return ok


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def sqlglot_version() -> str:
    try:
        return subprocess.run(
            ["git", "describe", "--tags"],
            cwd=SQLGLOT_DIR,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def grep_call_sites() -> int:
    """Denominator for coverage: textual validate_* call sites (grep-style)."""
    total = 0
    for path in sorted(DIALECTS_DIR.glob("test_*.py")):
        text = path.read_text(encoding="utf-8")
        total += len(re.findall(r"validate_identity\(", text))
        total += len(re.findall(r"validate_all\(", text))
    # subtract the two method *definitions* in test_dialect.py
    return total - 2


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract sqlglot dialect test assertions into JSON."
    )
    parser.add_argument("--no-verify", action="store_true",
                        help="skip the live-sqlglot sanity check")
    parser.add_argument("--seed", type=int, default=None,
                        help="RNG seed for sanity-check sampling (default: random)")
    args = parser.parse_args()

    version = sqlglot_version()
    print(f"sqlglot version: {version}")

    # dialect -> {"identity": [...], "transpile": [...]}
    corpora: Dict[str, Dict[str, Any]] = defaultdict(
        lambda: {"identity": [], "transpile": []}
    )
    skipped_by_file: Counter = Counter()  # file -> count
    skipped_reasons: Counter = Counter()  # "file: reason" -> count
    skipped_by_dialect: Counter = Counter()
    empty_all_total = 0

    for path in sorted(DIALECTS_DIR.glob("test_*.py")):
        per_dialect, skipped, empty_all = extract_file(path)
        empty_all_total += empty_all
        for reason, n in skipped.items():
            skipped_reasons[reason] += n
            skipped_by_file[path.name] += n
        for dialect, bucket in per_dialect.items():
            corpora[dialect]["identity"].extend(bucket["identity"])
            corpora[dialect]["transpile"].extend(bucket["transpile"])
        # attribute file-level skips to the file's primary dialect
        primary = max(
            per_dialect, key=lambda d: len(per_dialect[d]["identity"]) + len(per_dialect[d]["transpile"]),
            default="",
        )
        skipped_by_dialect[primary] += sum(skipped.values())

    # drop dialects with no assertions at all (e.g. helper-only classes)
    corpora = {
        d: c for d, c in corpora.items() if c["identity"] or c["transpile"]
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for stale in OUT_DIR.glob("*.json"):
        stale.unlink()

    rows = []
    total_identity = total_transpile = 0
    for dialect in sorted(corpora):
        corpus = corpora[dialect]
        name = dialect or "base"
        doc = {
            "sqlglot_version": version,
            "dialect": dialect,
            "identity": corpus["identity"],
            "transpile": corpus["transpile"],
            "stats": {
                "identity_count": len(corpus["identity"]),
                "transpile_count": len(corpus["transpile"]),
                "skipped_dynamic": skipped_by_dialect.get(dialect, 0),
            },
        }
        out = OUT_DIR / f"{name}.json"
        out.write_text(
            json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        rows.append((name, doc["stats"]))
        total_identity += doc["stats"]["identity_count"]
        total_transpile += doc["stats"]["transpile_count"]

    total_skipped = sum(skipped_by_file.values())
    denominator = grep_call_sites()
    extracted = total_identity + total_transpile
    coverage = 100.0 * extracted / denominator if denominator else 0.0

    # ------------------------------------------------------------------ report
    print(f"\n{'dialect':<14} {'identity':>9} {'transpile':>10} {'skipped':>8}")
    print("-" * 45)
    for name, stats in sorted(
        rows, key=lambda r: -(r[1]["identity_count"] + r[1]["transpile_count"])
    ):
        print(f"{name:<14} {stats['identity_count']:>9} "
              f"{stats['transpile_count']:>10} {stats['skipped_dynamic']:>8}")
    print("-" * 45)
    print(f"{'TOTAL':<14} {total_identity:>9} {total_transpile:>10} {total_skipped:>8}")
    print(f"\nassertion-free validate_all calls (no read/write): {empty_all_total}")
    print(f"call sites (grep denominator): {denominator}")
    print(f"extracted: {extracted}  coverage: {coverage:.1f}%")

    print("\nTop files by skipped_dynamic:")
    for fname, n in skipped_by_file.most_common(10):
        print(f"  {fname}: {n}")

    # ------------------------------------------------------------------ README
    readme_rows = "\n".join(
        f"| {name} | {stats['identity_count']} | {stats['transpile_count']} | "
        f"{stats['skipped_dynamic']} |"
        for name, stats in sorted(rows)
    )
    (OUT_DIR / "README.md").write_text(
        f"""# sqlglot dialect test corpus

Machine-extracted from the inline dialect test assertions of
[sqlglot](https://github.com/tobymao/sqlglot) (`tests/dialects/test_*.py`),
pinned at **{version}** (see `reference/sqlglot`).

sqlglot is Copyright (c) 2025 Toby Mao and released under the MIT License.
This corpus is a mechanical transformation of its test suite and carries the
same license and attribution.

## Regeneration

```
python3 tools/extract_dialect_tests.py
```

## Semantics

- `identity`: parse `sql` under `dialect`, regenerate under `dialect`
  (`pretty`/`identify` as flagged); result must equal `expected` if non-null,
  else `sql`. Entries flagged `check_command_warning` parse into a bare
  `Command` node with a warning in sqlglot — the round-trip still holds.
- `transpile`: for each `read` entry, parse under that dialect and generate
  under `dialect` (unsupported errors ignored); result must equal `sql`.
  For each `write` entry, parse `sql` under `dialect` and generate under the
  entry's dialect; result must equal the entry value — unless the value is
  `{{"error": "UnsupportedError"}}`, in which case generation with
  `unsupported_level=RAISE` must raise.
- Dialect `""` (file `base.json`) is sqlglot's generic dialect, including the
  pipe-syntax gate tests from `test_pipe_syntax.py`.

## Stats

| dialect | identity | transpile | skipped_dynamic |
|---|---|---|---|
{readme_rows}
| **TOTAL** | **{total_identity}** | **{total_transpile}** | **{total_skipped}** |

Coverage: {extracted} of {denominator} textual `validate_*` call sites
({coverage:.1f}%). Skipped calls use runtime-computed arguments (loops,
variables, f-string interpolation) and cannot be extracted statically.
""",
        encoding="utf-8",
    )
    print(f"\nWrote {len(rows)} dialect files + README.md to {OUT_DIR}")

    if not args.no_verify:
        seed = args.seed if args.seed is not None else random.randrange(1 << 30)
        if not verify_cases(corpora, sample_size=5, seed=seed):
            print("\nSANITY CHECK FAILED", file=sys.stderr)
            return 1
        print("Sanity check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
