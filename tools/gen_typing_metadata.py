#!/usr/bin/env python3
"""Generates Kotlin typing metadata for brikk-sql from the pinned sqlglot checkout.

Introspects sqlglot's EXPRESSION_METADATA (sqlglot/typing/__init__.py for the base
dialect and sqlglot/typing/<dialect>.py for the ported dialects) and emits:
  - brikk-sql/src/dev.brikk.house.sql/ast/GeneratedTypingMetadata.kt

Each metadata entry maps an expression class to either {"returns": DType} (emitted as
TypingSpec.Returns) or {"annotator": <lambda>}. The annotator lambdas are a small
closed set of helper-call shapes; this script classifies each lambda's SOURCE TEXT
into an AnnotatorRef variant (ast/TypingSpec.kt) and FAILS LOUDLY on any lambda it
cannot classify, so new upstream helpers never silently no-op.

Also emits the COERCES_TO lattice exactly as built by
sqlglot/optimizer/annotate_types.py::_build_coerces_to (introspected from the built
_COERCES_TO map), plus BIGINT_EXTRACT_DATE_PARTS.

Doris inherits MySQL's metadata and Trino inherits Presto's (no typing/<d>.py of
their own), mirroring the Python class hierarchy.

Run from anywhere:  python3 tools/gen_typing_metadata.py
Re-run whenever reference/sqlglot is updated, then review the diff.
"""

from __future__ import annotations

import inspect
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
OUT = ROOT / "brikk-sql" / "src" / "dev.brikk.house.sql" / "ast" / "GeneratedTypingMetadata.kt"

sys.path.insert(0, str(SQLGLOT))

from sqlglot import exp  # noqa: E402
from sqlglot.typing import EXPRESSION_METADATA as BASE_METADATA  # noqa: E402
from sqlglot.typing.mysql import EXPRESSION_METADATA as MYSQL_METADATA  # noqa: E402
from sqlglot.typing.presto import EXPRESSION_METADATA as PRESTO_METADATA  # noqa: E402
from sqlglot.typing.duckdb import EXPRESSION_METADATA as DUCKDB_METADATA  # noqa: E402
from sqlglot.typing.postgres import EXPRESSION_METADATA as POSTGRES_METADATA  # noqa: E402
from sqlglot.optimizer.annotate_types import (  # noqa: E402
    _COERCES_TO,
    BIGINT_EXTRACT_DATE_PARTS,
)


def sqlglot_version() -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(SQLGLOT), "describe", "--tags"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def lambda_source(fn) -> str:
    """Whitespace-normalized source of the statement block containing the lambda."""
    src = inspect.getsource(fn)
    return re.sub(r"\s+", " ", src).strip()


# Ordered (pattern, classify) rules over the normalized lambda source. First match
# wins; more specific shapes must precede the generic _annotate_by_args rule.
def classify(src: str) -> str:
    # presto Rand: conditional by_args/DOUBLE
    if "_annotate_by_args(e, \"this\") if e.this else self._set_type(e, exp.DType.DOUBLE)" in src:
        return "AnnotatorRef.RandThisOrDouble"
    # exp.Case: by_args over the ifs' true branches + default
    if "_annotate_by_args( e, *[if_expr.args[\"true\"] for if_expr in e.args[\"ifs\"]], \"default\" )" in src:
        return "AnnotatorRef.CaseArgs"
    m = re.search(
        r"self\._annotate_by_args\(\s*e,\s*((?:\"[a-z_]+\",?\s*)+)"
        r"((?:promote=True)?,?\s*(?:array=True)?)\s*\)",
        src,
    )
    if m:
        keys = re.findall(r"\"([a-z_]+)\"", m.group(1))
        promote = "promote=True" in m.group(2)
        array = "array=True" in m.group(2)
        opts = ""
        if promote:
            opts += ", promote = true"
        if array:
            opts += ", array = true"
        keys_kt = ", ".join(f'"{k}"' for k in keys)
        return f"AnnotatorRef.ByArgs(listOf({keys_kt}){opts})"
    if "_annotate_binary(e)" in src:
        return "AnnotatorRef.BinaryAnn"
    if "_annotate_unary(e)" in src:
        return "AnnotatorRef.UnaryAnn"
    if "_annotate_by_array_element(e)" in src:
        return "AnnotatorRef.ByArrayElement"
    if "self.schema.get_udf_type(e)" in src:
        return "AnnotatorRef.UdfType"
    if "_annotate_timeunit(e)" in src:
        return "AnnotatorRef.TimeUnitCoercion"
    m = re.search(r"self\._set_type\(e,\s*e\.args\[\"(\w+)\"\]\)", src)
    if m:
        return f'AnnotatorRef.SetTypeFromArg("{m.group(1)}")'
    if "_annotate_map(e)" in src:
        return "AnnotatorRef.MapAnn"
    if "_annotate_bracket(e)" in src:
        return "AnnotatorRef.BracketAnn"
    m = re.search(
        r"self\._set_type\(\s*e,\s*exp\.DType\.(\w+) if e\.args\.get\(\"(\w+)\"\) "
        r"else exp\.DType\.(\w+),?\s*\)",
        src,
    )
    if m:
        return (
            f'AnnotatorRef.FlagType("{m.group(2)}", '
            f"DType.{m.group(1)}, DType.{m.group(3)})"
        )
    if re.search(r"\{\"annotator\": lambda _, e: e\}", src):
        return "AnnotatorRef.Identity"
    m = re.search(r"exp\.DataType\.from_str\(\"ARRAY<(\w+)>\"\)", src)
    if m:
        return f"AnnotatorRef.ArrayOfType(DType.{m.group(1)})"
    for helper in (
        "div", "dot", "explode", "extract", "literal", "struct",
        "to_map", "unnest", "within_group", "subquery",
    ):
        if f"_annotate_{helper}(e)" in src:
            camel = "".join(p.capitalize() for p in helper.split("_"))
            # to_map -> ToMapAnn, within_group -> WithinGroupAnn
            return f"AnnotatorRef.{camel}Ann"
    raise SystemExit(f"gen_typing_metadata.py: UNCLASSIFIED annotator lambda:\n  {src}")


def classify_spec(cls, spec) -> str:
    keys = set(spec)
    if keys == {"returns"}:
        dtype = spec["returns"]
        if not isinstance(dtype, exp.DType):
            raise SystemExit(f"non-DType returns for {cls.__name__}: {dtype!r}")
        return f"TypingSpec.Returns(DType.{dtype.name})"
    if keys == {"annotator"}:
        return f"TypingSpec.Annotate({classify(lambda_source(spec['annotator']))})"
    raise SystemExit(f"unexpected metadata keys for {cls.__name__}: {keys}")


def build_entries(metadata) -> dict[str, str]:
    """expression class name -> Kotlin TypingSpec constructor source."""
    entries: dict[str, str] = {}
    for cls, spec in metadata.items():
        name = cls.__name__
        if name in entries:
            raise SystemExit(f"duplicate class name in metadata: {name}")
        entries[name] = classify_spec(cls, spec)
    return entries


def emit_map(name: str, entries: dict[str, str], base: dict[str, str] | None) -> str:
    lines: list[str] = []
    if base is None:
        lines.append(
            f"    val {name}: kotlin.collections.Map<KClass<out Expression>, TypingSpec> = mapOf("
        )
        items = entries.items()
    else:
        removed = set(base) - set(entries)
        if removed:
            raise SystemExit(f"{name}: dialect metadata removed base entries: {removed}")
        diff = {k: v for k, v in entries.items() if base.get(k) != v}
        lines.append(
            f"    val {name}: kotlin.collections.Map<KClass<out Expression>, TypingSpec> = BASE + mapOf("
        )
        items = diff.items()
    # Sorted for determinism: the Python metadata is built from set comprehensions,
    # whose iteration order varies across interpreter runs.
    for cls_name, spec in sorted(items):
        lines.append(f"        {cls_name}::class to {spec},")
    lines.append("    )")
    return "\n".join(lines)


def main() -> None:
    base = build_entries(BASE_METADATA)
    dialects = {
        "MYSQL": build_entries(MYSQL_METADATA),
        "PRESTO": build_entries(PRESTO_METADATA),
        "DUCKDB": build_entries(DUCKDB_METADATA),
        "POSTGRES": build_entries(POSTGRES_METADATA),
    }

    annotator_variants = {
        v for v in list(base.values()) + [s for d in dialects.values() for s in d.values()]
        if v.startswith("TypingSpec.Annotate")
    }

    parts: list[str] = []
    # NOTE: no `kotlin.String` / `kotlin.collections.Map` / `kotlin.collections.Set`
    # imports — the ast package defines same-named expression classes (String, Map,
    # Set, ...) that the metadata must reference; builtins are fully qualified.
    parts.append(f"""package dev.brikk.house.sql.ast

import kotlin.reflect.KClass

/**
 * sqlglot: EXPRESSION_METADATA (sqlglot/typing/__init__.py + typing/<dialect>.py) and
 * the COERCES_TO lattice built by optimizer/annotate_types.py::_build_coerces_to.
 *
 * Doris shares MYSQL and Trino shares PRESTO (they define no typing tables of their
 * own in Python; the class attribute is inherited).
 *
 * GENERATED by tools/gen_typing_metadata.py from sqlglot {sqlglot_version()} — do not edit by hand.
 */
object GeneratedTypingMetadata {{
""")
    parts.append(emit_map("BASE", base, None))
    parts.append("")
    for name, entries in dialects.items():
        parts.append(emit_map(name, entries, base))
        parts.append("")

    # COERCES_TO lattice (from the already-built _COERCES_TO; insertion order kept)
    parts.append(
        "    // sqlglot: annotate_types._COERCES_TO (built from the Spark ANSI"
        " text/numeric/timelike\n    // precedence lists by _build_coerces_to)"
    )
    parts.append("    val COERCES_TO: kotlin.collections.Map<DType, kotlin.collections.Set<DType>> = mapOf(")
    for dtype, targets in _COERCES_TO.items():
        # sets have no stable order; sort by enum definition order for determinism
        ordered = sorted(targets, key=lambda t: list(exp.DType).index(t))
        targets_kt = ", ".join(f"DType.{t.name}" for t in ordered)
        parts.append(f"        DType.{dtype.name} to setOf({targets_kt}),")
    parts.append("    )")
    parts.append("")

    parts.append(
        "    // sqlglot: annotate_types.BIGINT_EXTRACT_DATE_PARTS (EXTRACT/DATE_PART"
        " specifiers that\n    // return BIGINT instead of INT)"
    )
    parts.append("    val BIGINT_EXTRACT_DATE_PARTS: kotlin.collections.Set<kotlin.String> = setOf(")
    for part in sorted(BIGINT_EXTRACT_DATE_PARTS):
        parts.append(f'        "{part}",')
    parts.append("    )")
    parts.append("}")

    OUT.write_text("\n".join(parts) + "\n")
    total = sum(len(d) for d in dialects.values()) + len(base)
    print(f"Wrote {OUT}")
    print(
        f"base entries: {len(base)}, dialect entries: "
        + ", ".join(f"{n}={len(d)}" for n, d in dialects.items())
    )
    print(f"distinct annotator specs classified: {len(annotator_variants)}")


if __name__ == "__main__":
    main()
