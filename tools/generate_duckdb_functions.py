#!/usr/bin/env python3
"""Generates the DuckDB built-in function catalog for brikk-sql-metadata.

Source of truth: the EMBEDDED DuckDB engine itself, via the python `duckdb` module —
`duckdb_functions()` is the engine's own resolved registry (names, kinds, per-overload
parameter/return types, varargs), so signatures come for free. PIN: the python module
version is embedded in the generated header; regenerate after bumping the module to
track a new engine version.

Modeling decisions (documented in the generated header too):
  - kinds: scalar->SCALAR, aggregate->AGGREGATE, table->TABLE_VALUED,
    macro->SCALAR + nativeKind="macro", table_macro->TABLE_VALUED + nativeKind="table_macro",
    pragma->SKIPPED (PRAGMA statements surfaced as functions, not callable in queries).
  - one FunctionDef per (name, kind): 13 names (range, generate_series, repeat, ...)
    are registered under two kinds and get one def per kind.
  - operator rows (`%`, `||`, `~~`, ...) are skipped: they are grammar-level operators,
    not identifier-callable functions.
  - aliases are NOT folded: duckdb lists every alias (see alias_of) as a full row set of
    its own, so each name gets its own def; FunctionDef.aliases stays empty.
  - NULL types (macro params/returns, table-function returns) are emitted as "ANY".
  - variadic = (varargs IS NOT NULL); the vararg element type itself is not captured by
    the overload model — argTypes list only the fixed parameters.
  - argNames: duckdb_functions().parameters gives per-overload parameter names, captured
    verbatim into FunctionOverload.argNames (names differ across overloads of the same
    function — round(x) vs round(x, precision) — hence the per-overload home; generic
    col0/col1 names and lambda shapes like `lambda(x)` are kept as-is). Rows without
    names yield argNames=null.
  - FunctionDef.profile stays null: duckdb_functions() exposes NO null-propagation
    column (v1.5.4 columns checked: has_side_effects and stability are the only
    behavioral flags, neither describes NULL handling) — honest UNKNOWN.

Usage: python3 tools/generate_duckdb_functions.py
"""

from __future__ import annotations

import pathlib
import re
import sys

import duckdb

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "brikk-sql-metadata" / "src" / "dev.brikk.house.sql.metadata" / "GeneratedDuckdbFunctionCatalog.kt"

IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# duckdb function_type -> (FunctionKind, nativeKind-or-None); pragma is skipped.
KIND_MAP = {
    "scalar": ("SCALAR", None),
    "aggregate": ("AGGREGATE", None),
    "table": ("TABLE_VALUED", None),
    "macro": ("SCALAR", "macro"),
    "table_macro": ("TABLE_VALUED", "table_macro"),
}

KIND_ORDER = ["SCALAR", "AGGREGATE", "WINDOW", "TABLE_VALUED", "TABLE_GENERATING"]


def kstr(s: str) -> str:
    """Kotlin string literal (duckdb type strings can embed quotes, e.g. '"NULL"')."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main() -> None:
    con = duckdb.connect()
    version_row = con.sql("SELECT library_version, source_id FROM pragma_version()").fetchone()
    assert version_row is not None
    (lib_version, source_id) = version_row
    if f"v{duckdb.__version__}" != lib_version:
        sys.exit(f"error: python module {duckdb.__version__} != engine {lib_version}")

    rows = con.sql(
        """
        SELECT function_name, function_type, return_type, parameter_types, varargs, parameters
        FROM duckdb_functions()
        ORDER BY function_name, function_type
        """
    ).fetchall()

    # (name, kind) -> set of (argTypes tuple, returnType, variadic); natives tracks the
    # engine-native function_type strings feeding each group (a few names, e.g.
    # current_database, are registered as BOTH scalar and macro — nativeKind is only set
    # when every engine row in the group is a macro/table_macro). arg_names maps each
    # deduped overload to its parameter names (verified: no two engine rows share an
    # overload key with different names — enforced below so a future engine bump can't
    # silently mis-attach names).
    defs: dict[tuple[str, str], set[tuple[tuple[str, ...], str, bool]]] = {}
    natives: dict[tuple[str, str], set[str | None]] = {}
    arg_names: dict[tuple[str, str, tuple[str, ...], str, bool], tuple[str, ...] | None] = {}
    skipped_pragma = 0
    skipped_ops = 0
    named_overloads = 0
    for name, ftype, ret, param_types, varargs, parameters in rows:
        if ftype == "pragma":
            skipped_pragma += 1
            continue
        if not IDENT.match(name):
            skipped_ops += 1
            continue
        kind, native = KIND_MAP[ftype]
        arg_types = tuple((t or "ANY") for t in (param_types or []))
        overload = (arg_types, ret or "ANY", varargs is not None)
        names = tuple(parameters) if parameters else None
        key = (name, kind, *overload)
        if key in arg_names and arg_names[key] != names:
            sys.exit(f"error: conflicting parameter names for {key}: "
                     f"{arg_names[key]} vs {names} — pick a rule before regenerating")
        arg_names[key] = names
        defs.setdefault((name, kind), set()).add(overload)
        natives.setdefault((name, kind), set()).add(native)

    total_overloads = sum(len(v) for v in defs.values())
    by_kind: dict[str, int] = {k: 0 for k in KIND_ORDER}
    for (_, kind) in defs:
        by_kind[kind] += 1

    lines = [
        "// GENERATED FILE — DO NOT EDIT.",
        f"// Generated by tools/generate_duckdb_functions.py from the embedded DuckDB engine",
        f"// {lib_version} ({source_id}) via the python duckdb module (pinned {duckdb.__version__}):",
        "// duckdb_functions() — the engine's own resolved registry, signatures included.",
        "// DuckDB is MIT licensed. See ATTRIBUTIONS.md and vendor/README.md.",
        "//",
        "// Modeling notes:",
        "//  - kinds normalized to FunctionKind; engine-native 'macro'/'table_macro' preserved",
        "//    in FunctionDef.nativeKind (normalized to SCALAR/TABLE_VALUED).",
        "//  - function_type='pragma' rows are skipped (PRAGMA surface, not query-callable).",
        "//  - operator rows (%, ||, ~~, ...) are skipped (grammar-level, not identifiers).",
        "//    Function-SHAPED grammar-level names (COALESCE, GROUPING, ...) are carried by",
        "//    the handwritten DuckdbGrammarBuiltins.kt and wired in via grammarBuiltins below.",
        "//  - a name registered under two engine kinds (range, generate_series, ...) yields",
        "//    one def per kind.",
        "//  - NULL parameter/return types (macros, table functions) are emitted as \"ANY\".",
        "//  - variadic=true mirrors duckdb's varargs; argTypes list only the fixed parameters.",
        "package dev.brikk.house.sql.metadata",
        "",
        f"/** DuckDB {lib_version} built-in functions: {len(defs)} definitions, {total_overloads} overloads. */",
    ]

    # One block per FunctionDef: (lines, constructor-count). Emitted as chunked private
    # functions — a single top-level initializer would exceed the JVM's 64KB method limit.
    blocks: list[tuple[list[str], int]] = []
    for kind in KIND_ORDER:
        entries = sorted((k, v) for k, v in defs.items() if k[1] == kind)
        if not entries:
            continue
        first_of_kind = True
        for (name, _), overloads in entries:
            block: list[str] = []
            if first_of_kind:
                block.append(f"    // {kind.lower()} ({len(entries)})")
                first_of_kind = False
            group_natives = natives[(name, kind)]
            native = next(iter(group_natives)) if group_natives in ({"macro"}, {"table_macro"}) else None
            block.append(f'    FunctionDef("{name}", FunctionKind.{kind}, overloads = listOf(')
            for arg_types, ret, variadic in sorted(overloads):
                args = ", ".join(kstr(t) for t in arg_types)
                suffix = ", variadic = true" if variadic else ""
                names = arg_names[(name, kind, arg_types, ret, variadic)]
                if names:
                    named_overloads += 1
                    suffix += ", argNames = listOf(" + ", ".join(kstr(n) for n in names) + ")"
                block.append(f'        FunctionOverload(listOf({args}), {kstr(ret)}{suffix}),')
            block.append(f'    ), nativeKind = "{native}"),' if native else "    )),")
            blocks.append((block, 1 + len(overloads)))

    CHUNK_BUDGET = 400  # constructors per chunk function (JVM method-size headroom)
    chunks: list[list[str]] = [[]]
    budget = 0
    for block, cost in blocks:
        if budget + cost > CHUNK_BUDGET and chunks[-1]:
            chunks.append([])
            budget = 0
        chunks[-1].extend(block)
        budget += cost

    lines.append("val DUCKDB_FUNCTION_CATALOG: FunctionCatalog = FunctionCatalog(")
    lines.append("    " + " + ".join(f"chunk{i}()" for i in range(len(chunks))) + ",")
    # Grammar-level function-shaped names (parser special forms absent from
    # duckdb_functions()) live in the handwritten, engine-verified DuckdbGrammarBuiltins.kt.
    lines.append("    grammarBuiltins = DUCKDB_GRAMMAR_BUILTINS,")
    lines.append(")")
    for i, chunk in enumerate(chunks):
        lines.append("")
        lines.append(f"private fun chunk{i}(): List<FunctionDef> = listOf(")
        lines.extend(chunk)
        lines.append(")")
    OUT.write_text("\n".join(lines) + "\n")
    print(
        f"wrote {len(defs)} defs / {total_overloads} overloads from DuckDB {lib_version} -> {OUT}\n"
        f"  per kind: {[f'{k}={v}' for k, v in by_kind.items() if v]}\n"
        f"  argNames on {named_overloads}/{total_overloads} overloads (rest: engine lists no parameters)\n"
        f"  skipped: {skipped_pragma} pragma rows, {skipped_ops} operator rows"
    )


if __name__ == "__main__":
    main()
