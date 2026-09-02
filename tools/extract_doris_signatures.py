#!/usr/bin/env python3
"""Extracts per-function signatures from Apache Doris function class sources — statically.

Companion to tools/generate_doris_functions.py (which extracts names/aliases/kinds from
the Builtin*Functions.java registry). Doris declares each function's overloads as a
mechanical builder chain on a static field:

    public static final List<FunctionSignature> SIGNATURES = ImmutableList.of(
        FunctionSignature.ret(DoubleType.INSTANCE).args(DoubleType.INSTANCE),
        FunctionSignature.ret(StringType.INSTANCE).varArgs(StringType.INSTANCE),
        FunctionSignature.retArgType(0).args(ArrayType.of(AnyDataType.INSTANCE_WITHOUT_INDEX)),
        ...);

This tool parses those chains WITHOUT building fe-core: a tolerant, paren-balancing
line-based extraction — NOT a Java parser. Anything it cannot confidently parse is
recorded verbatim per class under "unparsed" (never guessed, never dropped), and parse
coverage is reported.

Grammar handled (surveyed over the full functions/ tree, pin v0.8.2-31011-gd8fd23f7f38):
  entry    := 'FunctionSignature.ret(' type ')' tail
            | 'FunctionSignature.retArgType(' int ')' tail
  tail     := '.args(' type* ')' | '.varArgs(' type+ ')'
  type     := TypeClass '.' (INSTANCE | SYSTEM_DEFAULT | WILDCARD | MAX | CATALOG_DEFAULT
                              | INSTANCE_WITHOUT_INDEX)
            | 'ArrayType.of(' type ')' | 'MapType.of(' type ',' type ')'
            | ParametricType '.of(' intOrConst+ ')'   (DateTimeV2Type.of(0), .of(RESULT_SCALE))
            | 'DecimalV3Type.createDecimalV3Type(' int ',' int ')'  -> DECIMAL(p,s)
            | 'StructLiteral.constructStructType(ImmutableList.of(' type+ '))' -> STRUCT<...>
            | 'new AnyDataType(' int ')'              -> type variable  ANY_<n>
            | 'new FollowToAnyDataType(' int ')'      -> follows variable ANY_<n>
            | 'new FollowToArgumentType(' int ')'     -> type of argument n: ARG_<n>
            | 'new VariantType(' int ')'              -> VARIANT

intOrConst: an integer literal, or an ALL_CAPS static int constant declared in the same
file (e.g. FromSecond's RESULT_SCALE) — resolved textually.

A class whose file has no SIGNATURES declaration inherits its superclass's (one/two
levels up, e.g. Regexp -> StringRegexPredicate), matching runtime getSignatures()
dispatch.

Not handled (recorded as unparsed): SIGNATURES built in static initializer blocks
(Lag/Lead, Ipv4CIDRToRange's locally-assembled StructType), and classes with no static
SIGNATURES literal at all (dynamic getSignatures()/CustomSignature — all table-valued
functions, rank-like window functions) which simply yield an empty signature list.

Type rendering — Doris Nereids type class -> catalog type string (uppercase SQL-ish,
matching the convention of the DuckDB/Trino catalogs in brikk-sql-metadata):

  BooleanType   BOOLEAN     TinyIntType   TINYINT    SmallIntType  SMALLINT
  IntegerType   INT         BigIntType    BIGINT     LargeIntType  LARGEINT
  FloatType     FLOAT       DoubleType    DOUBLE     DecimalV3Type DECIMAL
  DecimalV2Type DECIMALV2   DateV2Type    DATE       DateType      DATEV1
  DateTimeV2Type DATETIME   DateTimeType  DATETIMEV1 TimeV2Type    TIME
  TimeStampTzType TIMESTAMPTZ CharType    CHAR       VarcharType   VARCHAR
  StringType    STRING      JsonType      JSON       VariantType   VARIANT
  BitmapType    BITMAP      HllType       HLL        QuantileStateType QUANTILE_STATE
  IPv4Type      IPV4        IPv6Type      IPV6       VarBinaryType VARBINARY
  NullType      NULL        StructType    STRUCT     LambdaType    LAMBDA
  AnyDataType   ANY         AggStateType  AGG_STATE

  ArrayType.of(X)  -> ARRAY<X>       MapType.of(K,V) -> MAP<K,V>
  ArrayType.SYSTEM_DEFAULT -> ARRAY  MapType.SYSTEM_DEFAULT -> MAP (untyped containers)
  ParametricType.of(n)     -> BASE(n)  e.g. DateTimeV2Type.of(0) -> DATETIME(0)
  WILDCARD / SYSTEM_DEFAULT / MAX / CATALOG_DEFAULT accessors all render the bare base
  name (precision/scale wildcards are a resolver concern, not a catalog one).
  Unknown type classes -> class name minus a trailing 'Type', uppercased — never dropped.

Nullable mode — besides signatures, each class's ComputeNullable marker interface is
captured (fe's static declaration of result-nullability semantics, see ComputeNullable's
own doc): the class-declaration implements clauses are scanned for marker interfaces
matching (Propagate|Always)*Nullable* (today: PropagateNullable, AlwaysNullable,
AlwaysNotNullable; future variants like PropagateNullableOnDateLikeV2Args flow through
verbatim), walking the extends chain within the functions tree. Resolution mirrors Java:
a concrete `boolean nullable()` override anywhere in the class chain wins over ALL
interface defaults ("class wins" rule) and is recorded as "custom" (Coalesce, Lag,
NullableAggregateFunction subclasses, ...). Multiple distinct markers without an
override are joined with "+" (none exist at the pin). Emitted as "nullable_mode":
<marker|"custom"|null>; null = nothing found. TABLE_VALUED / TABLE_GENERATING classes
always get null: they produce row sets, and TableValuedFunction.nullable() throws —
scalar nullability is not applicable.

Output: vendor/data/doris-signatures.json (the committed reproducibility artifact, same
pattern as vendor/data/trino-functions-481.tsv):

  {"doris_version": "<git describe>",
   "classes": {"Abs": {"kind": "SCALAR", "names": ["ABS"],
                       "signatures": [{"return": "DOUBLE", "args": ["DOUBLE"],
                                       "variadic": false}, ...],
                       "unparsed": [],
                       "nullable_mode": "PropagateNullable"}, ...}}

deterministically ordered (classes sorted, signatures in source order). Only classes
present in the registry (Builtin*Functions.java) are emitted — those are the functions
the catalog exposes.

Usage: python3 tools/extract_doris_signatures.py [<doris-repo-root>]
       Default: reference/doris. After refreshing, re-run
       tools/generate_doris_functions.py to regenerate the Kotlin catalog.
"""

from __future__ import annotations

import glob
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "vendor" / "data" / "doris-signatures.json"

FUNCTIONS_REL = pathlib.Path("fe/fe-core/src/main/java/org/apache/doris/nereids/trees/expressions/functions")
REGISTRY_REL = pathlib.Path("fe/fe-core/src/main/java/org/apache/doris/catalog")

# Registry file -> FunctionKind (mirrors tools/generate_doris_functions.py).
KINDS = {
    "BuiltinScalarFunctions.java": "SCALAR",
    "BuiltinAggregateFunctions.java": "AGGREGATE",
    "BuiltinWindowFunctions.java": "WINDOW",
    "BuiltinTableValuedFunctions.java": "TABLE_VALUED",
    "BuiltinTableGeneratingFunctions.java": "TABLE_GENERATING",
}

# Doris Nereids type class -> catalog type string (see module docstring table).
TYPE_MAP = {
    "BooleanType": "BOOLEAN",
    "TinyIntType": "TINYINT",
    "SmallIntType": "SMALLINT",
    "IntegerType": "INT",
    "BigIntType": "BIGINT",
    "LargeIntType": "LARGEINT",
    "FloatType": "FLOAT",
    "DoubleType": "DOUBLE",
    "DecimalV2Type": "DECIMALV2",
    "DecimalV3Type": "DECIMAL",
    "DateType": "DATEV1",
    "DateV2Type": "DATE",
    "DateTimeType": "DATETIMEV1",
    "DateTimeV2Type": "DATETIME",
    "TimeV2Type": "TIME",
    "TimeStampTzType": "TIMESTAMPTZ",
    "CharType": "CHAR",
    "VarcharType": "VARCHAR",
    "StringType": "STRING",
    "JsonType": "JSON",
    "VariantType": "VARIANT",
    "BitmapType": "BITMAP",
    "HllType": "HLL",
    "QuantileStateType": "QUANTILE_STATE",
    "IPv4Type": "IPV4",
    "IPv6Type": "IPV6",
    "VarBinaryType": "VARBINARY",
    "NullType": "NULL",
    "StructType": "STRUCT",
    "ArrayType": "ARRAY",
    "MapType": "MAP",
    "LambdaType": "LAMBDA",
    "AnyDataType": "ANY",
    "AggStateType": "AGG_STATE",
}

# Static accessors that all render the bare base type name.
BARE_ACCESSORS = {"INSTANCE", "SYSTEM_DEFAULT", "WILDCARD", "MAX", "CATALOG_DEFAULT", "INSTANCE_WITHOUT_INDEX"}

IDENT = re.compile(r"[A-Za-z][A-Za-z0-9_]*$")
QUOTED = re.compile(r'"([^"]+)"')
CLASS_REF = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\.class,")


class Unparseable(Exception):
    pass


def doris_version(doris_root: pathlib.Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(doris_root), "describe", "--tags", "--always"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def parse_registry(registry_dir: pathlib.Path) -> dict[str, dict]:
    """class simple name -> {"kind": first registered kind, "names": [PRIMARY, alias...]}."""
    classes: dict[str, dict] = {}
    files = sorted(glob.glob(str(registry_dir / "Builtin*Functions.java")))
    if not files:
        sys.exit(f"error: no Builtin*Functions.java under {registry_dir}")
    for path in files:
        kind = KINDS.get(os.path.basename(path))
        if kind is None:
            continue
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                m = CLASS_REF.search(line)
                if not m:
                    continue
                cls = m.group(1)
                names = [n.strip().upper() for n in QUOTED.findall(line[m.end():]) if IDENT.match(n.strip())]
                if not names:
                    continue
                entry = classes.setdefault(cls, {"kind": kind, "names": []})
                for n in names:
                    if n not in entry["names"]:
                        entry["names"].append(n)
    return classes


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def find_signatures_block(text: str) -> str | None:
    """Return the argument text inside `SIGNATURES = ImmutableList.of( ... )`, or None."""
    m = re.search(r"\bSIGNATURES\s*=\s*ImmutableList\.(?:of|copyOf)\s*\(", text)
    if m is None:
        return None
    start = m.end()
    depth = 1
    i = start
    while i < len(text) and depth:
        c = text[i]
        if c == '"':  # skip string literal
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    if depth:
        raise Unparseable("unbalanced parens in SIGNATURES block")
    return text[start:i - 1]


def split_top_level(s: str) -> list[str]:
    parts, depth, cur = [], 0, []
    for c in s:
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
            continue
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        cur.append(c)
    parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def render_type(expr: str, consts: dict[str, str]) -> str:
    expr = expr.strip()
    # new AnyDataType(0) / new FollowToAnyDataType(0) -> type variable ANY_0
    m = re.fullmatch(r"new\s+(AnyDataType|FollowToAnyDataType)\s*\(\s*(\d+)\s*\)", expr)
    if m:
        return f"ANY_{m.group(2)}"
    # new FollowToArgumentType(n) -> the type of argument n
    m = re.fullmatch(r"new\s+FollowToArgumentType\s*\(\s*(\d+)\s*\)", expr)
    if m:
        return f"ARG_{m.group(1)}"
    # new VariantType(n) -> VARIANT (n is a variant-subcolumn knob, not a SQL type param)
    m = re.fullmatch(r"new\s+VariantType\s*\(\s*\d+\s*\)", expr)
    if m:
        return "VARIANT"
    # ArrayType.of(T)
    m = re.fullmatch(r"ArrayType\.of\((.*)\)", expr, flags=re.S)
    if m:
        inner = split_top_level(m.group(1))
        # ArrayType.of(T) or ArrayType.of(T, containsNull) — extra boolean args ignored
        if len(inner) >= 1:
            return f"ARRAY<{render_type(inner[0], consts)}>"
        raise Unparseable(expr)
    # MapType.of(K, V)
    m = re.fullmatch(r"MapType\.of\((.*)\)", expr, flags=re.S)
    if m:
        inner = split_top_level(m.group(1))
        if len(inner) == 2:
            return f"MAP<{render_type(inner[0], consts)},{render_type(inner[1], consts)}>"
        raise Unparseable(expr)
    # DecimalV3Type.createDecimalV3Type(p, s) -> DECIMAL(p,s)
    m = re.fullmatch(r"DecimalV3Type\.createDecimalV3Type\(\s*(\d+)\s*,\s*(\d+)\s*\)", expr)
    if m:
        return f"DECIMAL({m.group(1)},{m.group(2)})"
    # StructLiteral.constructStructType(ImmutableList.of(T, ...)) -> STRUCT<T,...>
    m = re.fullmatch(r"StructLiteral\.constructStructType\(\s*ImmutableList\.of\((.*)\)\s*\)", expr, flags=re.S)
    if m:
        fields = [render_type(t, consts) for t in split_top_level(m.group(1))]
        if fields:
            return "STRUCT<" + ",".join(fields) + ">"
        raise Unparseable(expr)
    # Parametric scalar: DateTimeV2Type.of(0), TimeV2Type.of(3), .of(RESULT_SCALE), ...
    m = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\.of\(\s*([A-Z0-9_]+(?:\s*,\s*[A-Z0-9_]+)*)\s*\)", expr)
    if m:
        base = base_type(m.group(1))
        params = []
        for p in re.split(r"\s*,\s*", m.group(2).strip()):
            if not p.isdigit():
                p = consts.get(p)  # same-file static int constant (e.g. RESULT_SCALE)
                if p is None:
                    raise Unparseable(expr)
            params.append(p)
        return f"{base}({','.join(params)})"
    # TypeClass.ACCESSOR
    m = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)\.([A-Z_][A-Z0-9_]*)", expr)
    if m and m.group(2) in BARE_ACCESSORS:
        return base_type(m.group(1))
    raise Unparseable(expr)


def base_type(cls: str) -> str:
    if cls in TYPE_MAP:
        return TYPE_MAP[cls]
    # Unknown type class: strip a trailing 'Type', uppercase — never dropped.
    return (cls[:-4] if cls.endswith("Type") else cls).upper()


def parse_entry(entry: str, consts: dict[str, str]) -> dict:
    """One `FunctionSignature....` chain -> {"return","args","variadic"}."""
    s = re.sub(r"\s+", " ", entry).strip()
    m = re.match(r"FunctionSignature\s*\.\s*(ret|retArgType)\s*\(", s)
    if not m:
        raise Unparseable(entry)
    # find matching close paren of ret(...)
    depth, i = 1, m.end()
    while i < len(s) and depth:
        if s[i] == "(":
            depth += 1
        elif s[i] == ")":
            depth -= 1
        i += 1
    if depth:
        raise Unparseable(entry)
    ret_expr = s[m.end():i - 1].strip()
    if m.group(1) == "retArgType":
        if not re.fullmatch(r"\d+", ret_expr):
            raise Unparseable(entry)
        ret = f"ARG_{ret_expr}"
    else:
        ret = render_type(ret_expr, consts)
    tail = s[i:].strip()
    m = re.fullmatch(r"\.\s*(args|varArgs)\s*\((.*)\)", tail, flags=re.S)
    if not m:
        raise Unparseable(entry)
    variadic = m.group(1) == "varArgs"
    args = [render_type(a, consts) for a in split_top_level(m.group(2))]
    if variadic and not args:
        raise Unparseable(entry)
    return {"return": ret, "args": args, "variadic": variadic}


INT_CONST = re.compile(r"\bstatic\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*(\d+)\s*;")
EXTENDS = re.compile(r"\bclass\s+\w+(?:<[^>]*>)?\s+extends\s+([A-Za-z_][A-Za-z0-9_]*)")

# ComputeNullable marker interfaces (see module docstring, "Nullable mode"). The pattern
# is deliberately loose so future markers (PropagateNullableOnDateLikeV2Args, ...) are
# captured verbatim instead of dropped; non-markers like PropagateNullLiteral (no
# "Nullable") don't match.
NULLABLE_MARKER = re.compile(r"(?:Propagate|Always)\w*Nullable\w*")
NULLABLE_OVERRIDE = re.compile(r"\bboolean\s+nullable\s*\(\s*\)")
# Row-set producers: scalar nullability not applicable (TableValuedFunction.nullable() throws).
NULLABLE_KINDS = {"SCALAR", "AGGREGATE", "WINDOW"}


def extract_nullable_mode(path: pathlib.Path, sources: dict[str, pathlib.Path]) -> str | None:
    """The class's ComputeNullable marker ("PropagateNullable" | "AlwaysNullable" |
    "AlwaysNotNullable" | future variants), "custom" for a concrete nullable() override,
    or None when neither is found up the extends chain."""
    markers: list[str] = []
    cur: pathlib.Path | None = path
    for _ in range(8):  # bounded extends-chain walk (deepest real chain is ~4)
        text = strip_comments(cur.read_text(encoding="utf-8"))
        if NULLABLE_OVERRIDE.search(text):
            # Java's "class wins" rule: a concrete nullable() anywhere in the class chain
            # overrides every marker-interface default, even markers on subclasses.
            return "custom"
        header = re.search(
            r"\bclass\s+" + re.escape(cur.stem) + r"\b(.*?)\{", text, flags=re.S
        )
        if header:
            impl = re.search(r"\bimplements\b(.*)", header.group(1), flags=re.S)
            if impl:
                for ident in re.findall(r"[A-Za-z_][A-Za-z0-9_]*", impl.group(1)):
                    if NULLABLE_MARKER.fullmatch(ident) and ident not in markers:
                        markers.append(ident)
        m = re.search(
            r"\bclass\s+" + re.escape(cur.stem) + r"(?:<[^>]*>)?\s+extends\s+([A-Za-z_][A-Za-z0-9_]*)",
            text,
        )
        cur = sources.get(m.group(1)) if m else None
        if cur is None:
            break
    if not markers:
        return None
    return markers[0] if len(markers) == 1 else "+".join(markers)


def extract_class(
    path: pathlib.Path, sources: dict[str, pathlib.Path], depth: int = 0
) -> tuple[list[dict], list[str]]:
    """-> (signatures, unparsed raw entries) for one function class source file."""
    text = strip_comments(path.read_text(encoding="utf-8"))
    try:
        block = find_signatures_block(text)
    except Unparseable as e:
        return [], [str(e)]
    if block is None:
        # No SIGNATURES declaration: getSignatures() may resolve to the superclass's
        # (e.g. Regexp -> StringRegexPredicate). Follow the extends chain, bounded.
        m = EXTENDS.search(text)
        if m and depth < 3 and m.group(1) in sources:
            return extract_class(sources[m.group(1)], sources, depth + 1)
        return [], []  # dynamic getSignatures() — no static signatures
    consts = {m.group(1): m.group(2) for m in INT_CONST.finditer(text)}
    signatures, unparsed = [], []
    for entry in split_top_level(block):
        try:
            signatures.append(parse_entry(entry, consts))
        except Unparseable:
            unparsed.append(re.sub(r"\s+", " ", entry).strip())
    return signatures, unparsed


def main() -> None:
    doris_root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "reference" / "doris"
    functions_dir = doris_root / FUNCTIONS_REL
    registry_dir = doris_root / REGISTRY_REL
    if not functions_dir.is_dir():
        sys.exit(f"error: {functions_dir} not found — pass a Doris checkout root")

    registry = parse_registry(registry_dir)

    # Index function class sources by simple name (verified unique across subdirs).
    # Also index the parent expressions/ dir non-recursively: a few registered scalar
    # functions live there as predicate expressions (Like, Regexp).
    sources: dict[str, pathlib.Path] = {}
    for path in sorted(functions_dir.rglob("*.java")):
        sources.setdefault(path.stem, path)
    for path in sorted(functions_dir.parent.glob("*.java")):
        sources.setdefault(path.stem, path)

    classes: dict[str, dict] = {}
    n_full = n_partial = n_empty = n_missing = 0
    total_sigs = total_unparsed = 0
    nullable_counts: dict[str, int] = {}
    for cls in sorted(registry):
        entry = registry[cls]
        path = sources.get(cls)
        if path is None:
            print(f"warning: registered class {cls} has no source file — skipped", file=sys.stderr)
            n_missing += 1
            continue
        signatures, unparsed = extract_class(path, sources)
        total_sigs += len(signatures)
        total_unparsed += len(unparsed)
        if signatures and not unparsed:
            n_full += 1
        elif signatures or unparsed:
            n_partial += 1
        else:
            n_empty += 1
        nullable_mode = (
            extract_nullable_mode(path, sources) if entry["kind"] in NULLABLE_KINDS else None
        )
        nullable_counts[nullable_mode or "(none)"] = nullable_counts.get(nullable_mode or "(none)", 0) + 1
        classes[cls] = {
            "kind": entry["kind"],
            "names": entry["names"],
            "signatures": signatures,
            "unparsed": unparsed,
            "nullable_mode": nullable_mode,
        }

    out = {"doris_version": doris_version(doris_root), "classes": classes}
    OUT.write_text(json.dumps(out, indent=1, sort_keys=False) + "\n")

    parsed_pct = 100.0 * total_sigs / (total_sigs + total_unparsed) if total_sigs + total_unparsed else 0.0
    n_classes = len(classes)
    print(f"wrote {OUT} (doris {out['doris_version']})")
    print(f"classes: {n_classes} registered ({n_missing} missing sources)")
    print(f"  fully parsed: {n_full}  partial: {n_partial}  no static SIGNATURES: {n_empty}")
    print(f"signatures: {total_sigs} parsed, {total_unparsed} unparsed "
          f"({parsed_pct:.1f}% signature parse coverage)")
    print("nullable modes: " + ", ".join(f"{k}={v}" for k, v in sorted(nullable_counts.items())))


if __name__ == "__main__":
    main()
