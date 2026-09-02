#!/usr/bin/env python3
"""Function relationship report: cross-engine/codegen transpilation analysis.

For every StarRocks direction against every registered brikk dialect, classify the
source function universe into relationship buckets. Engine catalogs are preferred;
when no extracted engine catalog exists, the pinned SQLGlot parser function registry
is used as explicitly weaker code-generation evidence. The existing catalog-backed
engine pairs are retained too.

  A  same-name passthrough  - name exists in the target catalog (aliases count).
                              Candidate-OK ONLY: same name does NOT mean same
                              semantics. Sub-annotated with arity overlap.
  B  translated             - sqlglot parses the source name into a typed node
                              under the source dialect AND the target generator
                              renders that node specially (rename / TRANSFORMS
                              entry / unsupported error). A deliberate
                              translation exists. Render-checked.
  C  rename candidates      - not A or B, but the target plausibly has an
                              equivalent:
                                c1: sqlglot alias/class knowledge (typed node
                                    reachable via another dialect or via the
                                    node class's alternate sql_names)
                                c2: catalog alias-set intersection
                                c3: low-confidence lexical hint (normalized
                                    name equality) - HINTS, not candidates.
  D  no equivalent evidence - none of the above in the available target evidence.
                               For an engine catalog this is a capability gap; for a
                               SQLGlot-codegen target it means codegen found no relation,
                               not proof the inaccessible engine lacks one.

Raw data sources (preferred over the generated Kotlin catalogs):
  - vendor/data/doris-signatures.json
  - vendor/data/trino-functions-483.tsv
  - vendor/data/starrocks-builtin-functions-4.1.4.tsv
  - vendor/data/clickhouse-functions-26.5.1.1.tsv
  - python module `duckdb` -> duckdb_functions()

sqlglot knowledge comes from reference/sqlglot (read-only oracle).

Outputs (regenerated on every run, deterministic):
  - brikk-sql/testResources/semantics/gap-report.json         (small manifest/index)
  - brikk-sql/testResources/semantics/function-gaps/<src>__<tgt>.json  (per-pair detail)
  - docs/research/function-gap-report.md

The monolithic detailed report was split: `gap-report.json` is now a deterministic
manifest holding global metadata, warnings, evidence/catalog provenance, the
registered/unavailable dialect classification, and a `pairs` map from each ordered pair
to its counts/subCounts and its detail file. The full per-function entry lists live in
one file per ordered pair under `function-gaps/`. Stale pair files no longer referenced
by the manifest are removed on every run.

Usage: python3 tools/function_gap_report.py
"""

from __future__ import annotations

import json
import re
import sys
import time
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "reference" / "sqlglot"))

import sqlglot  # noqa: E402
from sqlglot import exp  # noqa: E402
from sqlglot.dialects.dialect import Dialect  # noqa: E402
from sqlglot.errors import ErrorLevel  # noqa: E402

# Engines with an extracted function catalog (raw-source or live dump). Every ordered
# pair among these is analyzed in BOTH directions.
CATALOG_ENGINES = ["duckdb", "trino", "doris", "starrocks", "clickhouse"]

# ALL brikk-registered real-engine dialects (see Dialects.forNameOrNull). Dialects here
# but NOT in ENGINES have no extracted catalog yet: the report records them under
# `unavailable_engines` with the reason, so downstream certification conservatively
# refuses the pair instead of silently treating it as covered. `base`/sqlglot and
# datafusion are modelled separately (translation dialect / brikk-native).
SQLGLOT_DIALECTS = [
    "sqlglot", "mysql", "doris", "starrocks", "presto", "trino", "duckdb",
    "postgres", "clickhouse", "hive", "spark2", "spark", "bigquery",
]
ALL_REGISTERED_DIALECTS = SQLGLOT_DIALECTS + ["datafusion"]

# Dialects consulted for c1 evidence (sqlglot knowledge outside the source
# dialect). "" is the base/default dialect.
HELPER_DIALECTS = ["", "trino", "presto", "duckdb", "postgres", "mysql", "clickhouse", "doris", "starrocks"]

IDENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
HEAD_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(")
# Prefixes stripped (from either side) for the c3 lexical hint.
C3_PREFIXES = ("array_", "list_", "str_", "string_", "regexp_", "regex_", "map_")
# Grammar-level constructs: renders headed by these are legitimate syntax forms,
# not catalog functions, so they are exempt from the ghost-name flag.
SYNTAX_HEADS = {"cast", "try_cast", "extract", "if", "case", "row", "interval", "values", "trim"}

MAX_SYNTH_ARGS = 6
ARG_TRY_FALLBACK = [1, 2, 0, 3]


# --------------------------------------------------------------------------
# Catalog loading
# --------------------------------------------------------------------------

class CatalogEntry:
    __slots__ = ("name", "kind", "aliases", "fixed_arities", "variadic_mins", "arity_known")

    def __init__(self, name, kind):
        self.name = name  # primary name, lowercase
        self.kind = kind
        self.aliases = set()  # lowercase, includes primary
        self.fixed_arities = set()
        self.variadic_mins = set()
        self.arity_known = True


def load_doris():
    data = json.loads((ROOT / "vendor" / "data" / "doris-signatures.json").read_text())
    entries = {}
    for cls_name in sorted(data["classes"]):
        info = data["classes"][cls_name]
        names = [n.lower() for n in info["names"] if IDENT_RE.match(n)]
        if not names:
            continue
        primary = names[0]
        e = entries.get(primary)
        if e is None:
            e = CatalogEntry(primary, info["kind"].lower())
            entries[primary] = e
        e.aliases.update(names)
        for sig in info.get("signatures", []):
            n = len(sig.get("args", []))
            if sig.get("variadic"):
                e.variadic_mins.add(n)
            else:
                e.fixed_arities.add(n)
        if not info.get("signatures") and info.get("unparsed"):
            e.arity_known = False
    return entries


def load_trino():
    entries = {}
    path = ROOT / "vendor" / "data" / "trino-functions-483.tsv"
    for line in path.read_text().splitlines():
        cols = line.split("\t")
        if len(cols) < 4:
            continue
        name, _ret, args, kind = cols[0], cols[1], cols[2], cols[3]
        name = name.lower()
        if not IDENT_RE.match(name):
            continue
        e = entries.get(name)
        if e is None:
            e = CatalogEntry(name, kind.lower())
            e.aliases.add(name)
            entries[name] = e
        # paren-aware top-level comma split for arg count
        depth = 0
        count = 0
        stripped = args.strip()
        if stripped:
            count = 1
            for ch in stripped:
                if ch in "(<[":
                    depth += 1
                elif ch in ")>]":
                    depth -= 1
                elif ch == "," and depth == 0:
                    count += 1
        e.fixed_arities.add(count)
        # NOTE: the TSV carries no variadic marker; variadic functions
        # (concat, greatest, ...) appear with a single flattened overload.
    return entries


def load_duckdb():
    import duckdb

    rows = duckdb.sql(
        """
        select function_name, alias_of, function_type, parameter_types, varargs
        from duckdb_functions()
        where function_type <> 'pragma'
        order by function_name
        """
    ).fetchall()
    entries = {}
    alias_pairs = []  # (alias, primary)
    for name, alias_of, ftype, ptypes, varargs in rows:
        name = name.lower()
        if not IDENT_RE.match(name):
            continue
        if alias_of:
            alias_pairs.append((name, alias_of.lower()))
            continue
        e = entries.get(name)
        if e is None:
            e = CatalogEntry(name, ftype)
            e.aliases.add(name)
            entries[name] = e
        n = len(ptypes or [])
        if varargs:
            e.variadic_mins.add(n)
        else:
            e.fixed_arities.add(n)
    for alias, primary in alias_pairs:
        if primary in entries:
            entries[primary].aliases.add(alias)
        elif alias not in entries:
            # alias of a non-identifier/operator primary: keep as its own entry
            e = CatalogEntry(alias, "scalar")
            e.aliases.add(alias)
            e.arity_known = False
            entries[alias] = e
    return entries


def load_starrocks():
    """StarRocks 4.1.4 catalog from the live `SHOW FULL BUILTIN FUNCTIONS` dump
    (vendor/data/starrocks-builtin-functions-4.1.4.tsv). Signature column is
    `name(TYPE, TYPE, ...)`; a trailing `...` marks a variadic tail."""
    path = ROOT / "vendor" / "data" / "starrocks-builtin-functions-4.1.4.tsv"
    entries = {}
    lines = path.read_text().splitlines()
    for line in lines[1:]:  # skip header
        cols = line.split("\t")
        if len(cols) < 3:
            continue
        sig, _ret, ftype = cols[0], cols[1], cols[2]
        m = HEAD_RE.match(sig + "(") if "(" not in sig else re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\((.*)\)$", sig)
        if not m:
            continue
        name = m.group(1).lower()
        if not IDENT_RE.match(name):
            continue
        arg_str = (m.group(2) if m.lastindex and m.lastindex >= 2 else "").strip()
        args = [a.strip() for a in arg_str.split(",")] if arg_str else []
        variadic = bool(args) and args[-1] == "..."
        concrete = args[:-1] if variadic else args
        kind = {"scalar": "scalar", "aggregate": "aggregate", "table": "table"}.get(
            ftype.lower(), ftype.lower()
        )
        e = entries.get(name)
        if e is None:
            e = CatalogEntry(name, kind)
            e.aliases.add(name)
            entries[name] = e
        if variadic:
            e.variadic_mins.add(len(concrete))
        else:
            e.fixed_arities.add(len(concrete))
    return entries


def load_clickhouse():
    """ClickHouse 26.5.1.1 names/kinds/aliases from system.functions.

    The dump has no signatures, so arity remains explicitly unknown. alias_to is engine
    evidence and is folded only when it names a distinct function case-insensitively.
    """
    path = ROOT / "vendor" / "data" / "clickhouse-functions-26.5.1.1.tsv"
    rows = [line.split("\t") for line in path.read_text().splitlines()[1:]]
    entries = {}
    aliases = []
    for cols in rows:
        if len(cols) < 4:
            continue
        name, is_aggregate, _case_insensitive, alias_to = cols[:4]
        low = name.lower()
        if not IDENT_RE.match(low):
            continue
        target = alias_to.lower() if alias_to else ""
        if target and target != low:
            aliases.append((low, target))
            continue
        e = entries.setdefault(low, CatalogEntry(low, "aggregate" if is_aggregate == "1" else "scalar"))
        e.aliases.add(low)
        e.arity_known = False

    for alias, target in aliases:
        e = entries.get(target)
        if e is None:
            # Preserve the engine row even when system.functions did not emit a separate
            # primary row for alias_to; the relationship remains engine-evidenced.
            e = CatalogEntry(target, "scalar")
            e.aliases.add(target)
            e.arity_known = False
            entries[target] = e
        e.aliases.add(alias)
    return entries


def sqlglot_name(dialect):
    return None if dialect == "sqlglot" else dialect


def load_sqlglot_codegen(dialect):
    """Pinned SQLGlot parser function registry as a codegen-only function universe.

    This is relationship evidence, NOT an engine catalog and NOT semantic evidence.
    Arity/kind are deliberately unknown.
    """
    parser = Dialect.get_or_raise(sqlglot_name(dialect)).parser_class
    entries = {}
    for raw in sorted(parser.FUNCTIONS):
        name = raw.lower()
        if not IDENT_RE.match(name):
            continue
        e = CatalogEntry(name, "codegen")
        e.aliases.add(name)
        e.arity_known = False
        entries[name] = e
    return entries


def build_lookup(entries):
    """lowercase name (incl. aliases) -> primary name."""
    lookup = {}
    for primary in sorted(entries):
        for a in sorted(entries[primary].aliases):
            lookup.setdefault(a, primary)
    return lookup


# --------------------------------------------------------------------------
# sqlglot helpers
# --------------------------------------------------------------------------

_parse_cache = {}


def try_arg_counts(entry):
    counts = sorted(c for c in entry.fixed_arities if c <= MAX_SYNTH_ARGS)
    counts += sorted(c for c in entry.variadic_mins if c <= MAX_SYNTH_ARGS and c not in counts)
    for c in ARG_TRY_FALLBACK:
        if c not in counts:
            counts.append(c)
    return counts


def synth_sql(name, n):
    return "SELECT {}({})".format(name, ", ".join("a{}".format(i + 1) for i in range(n)))


def parse_typed(name, dialect, counts):
    """Parse a synthetic call; return (select_expr, func_node) if it yields a
    typed (non-Anonymous) node, else None."""
    key = (dialect, name, tuple(counts))
    if key in _parse_cache:
        return _parse_cache[key]
    result = None
    for n in counts:
        try:
            tree = sqlglot.parse_one(synth_sql(name, n), read=sqlglot_name(dialect))
        except Exception:
            continue
        try:
            node = tree.selects[0]
        except Exception:
            continue
        if isinstance(node, exp.Alias):
            node = node.this
        if isinstance(node, exp.Anonymous) or isinstance(node, exp.Column):
            continue
        if isinstance(node, exp.Func) or isinstance(node, exp.Expression):
            result = (tree, node)
            break
    _parse_cache[key] = result
    return result


def render(tree, node, target):
    """Render the parsed func node under target. Returns dict with
    status ok/error, sql, head (leading function name) if any."""
    try:
        out = node.sql(dialect=sqlglot_name(target), unsupported_level=ErrorLevel.RAISE)
    except Exception as ex:
        return {"status": "error", "error": "{}: {}".format(type(ex).__name__, str(ex).strip())}
    m = HEAD_RE.match(out)
    return {"status": "ok", "sql": out, "head": m.group(1).lower() if m else None}


def c3_norms(name):
    """Normalized forms for the lexical hint."""
    base = name.lower().replace("_", "")
    forms = {base}
    low = name.lower()
    for p in C3_PREFIXES:
        if low.startswith(p) and len(low) > len(p):
            forms.add(low[len(p):].replace("_", ""))
    return forms


# --------------------------------------------------------------------------
# Pair analysis
# --------------------------------------------------------------------------

def analyze_pair(src, tgt, catalogs, lookups, transforms, c3_index, evidence):
    src_entries = catalogs[src]
    tgt_entries = catalogs[tgt]
    tgt_lookup = lookups[tgt]
    tgt_transforms = transforms[tgt]

    results = []
    for name in sorted(src_entries):
        entry = src_entries[name]
        counts = try_arg_counts(entry)
        parsed = parse_typed(name, src, counts)
        rec = OrderedDict()
        rec["name"] = name
        rec["kind"] = entry.kind
        rec["sourceEvidence"] = evidence[src]
        if sorted(entry.aliases) != [name]:
            rec["aliases"] = sorted(entry.aliases)

        target_primary = tgt_lookup.get(name)
        if target_primary is not None:
            rec["bucket"] = "A"
            rec["classification"] = "same-name"
            rec["targetName"] = target_primary
            rec["targetEvidence"] = evidence[tgt]
            rec["arity"] = arity_check(entry, tgt_entries[target_primary])
            if parsed is not None:
                rec["nodeClass"] = type(parsed[1]).__name__
                r = render(*parsed, target=tgt)
                if r["status"] == "ok" and r.get("head") and r["head"] != name:
                    # same-name exists in target, but sqlglot deliberately
                    # translates to something else -> semantics likely differ!
                    rec["sqlglotTranslatesTo"] = r["head"]
                    rec["renderedSql"] = r["sql"]
            results.append(rec)
            continue

        if parsed is not None:
            tree, node = parsed
            cls = type(node)
            rec["nodeClass"] = cls.__name__
            r = render(tree, node, tgt)
            if r["status"] == "error":
                rec["bucket"] = "B"
                rec["classification"] = "typed-rewrite"
                rec["specialVia"] = "unsupported"
                rec["renderCheck"] = "error"
                rec["renderError"] = r["error"]
                results.append(rec)
                continue
            head = r.get("head")
            in_transforms = cls in tgt_transforms
            if head and head != name:
                rec["bucket"] = "B"
                rec["classification"] = "typed-rewrite"
                rec["specialVia"] = "transforms" if in_transforms else "renamed"
                rec["targetRendering"] = head
                rec["renderedSql"] = r["sql"]
                rec["renderCheck"] = "ok"
                if head not in tgt_lookup and head not in SYNTAX_HEADS:
                    rec["renderedNameNotInTargetCatalog"] = True
                results.append(rec)
                continue
            if head is None:
                # rendered to non-function syntax (operator, keyword form...)
                rec["bucket"] = "B"
                rec["classification"] = "typed-rewrite"
                rec["specialVia"] = "expression-render"
                rec["renderedSql"] = r["sql"]
                rec["renderCheck"] = "ok"
                results.append(rec)
                continue
            if in_transforms:
                rec["bucket"] = "B"
                rec["classification"] = "typed-rewrite"
                rec["specialVia"] = "transforms"
                rec["targetRendering"] = head
                rec["renderedSql"] = r["sql"]
                rec["renderCheck"] = "ok"
                if head not in SYNTAX_HEADS:
                    rec["renderedNameNotInTargetCatalog"] = True
                results.append(rec)
                continue
            # falls through to bucket C with typed-class knowledge

        candidates = collect_c_candidates(name, entry, parsed, src, tgt, tgt_lookup, c3_index[tgt])
        if candidates:
            rec["bucket"] = "C"
            rec["classification"] = "rename-candidate"
            rec["candidates"] = candidates
        else:
            rec["bucket"] = "D"
            rec["classification"] = "no-equivalent-evidence"
        results.append(rec)
    return results


def arity_check(src_entry, tgt_entry):
    if not src_entry.arity_known or not tgt_entry.arity_known:
        return "arity-unknown"
    if not (src_entry.fixed_arities or src_entry.variadic_mins):
        return "arity-unknown"
    if not (tgt_entry.fixed_arities or tgt_entry.variadic_mins):
        return "arity-unknown"

    def target_accepts(n):
        if n in tgt_entry.fixed_arities:
            return True
        return any(n >= m for m in tgt_entry.variadic_mins)

    for n in src_entry.fixed_arities:
        if target_accepts(n):
            return "arity-compatible"
    for m in src_entry.variadic_mins:
        if tgt_entry.variadic_mins:
            return "arity-compatible"
        if any(n >= m for n in tgt_entry.fixed_arities):
            return "arity-compatible"
    return "arity-suspect"


def collect_c_candidates(name, entry, parsed, src, tgt, tgt_lookup, tgt_c3):
    candidates = []
    seen = set()

    def add(evidence, target_name, detail):
        key = (evidence, target_name)
        if key in seen:
            return
        seen.add(key)
        c = OrderedDict()
        c["evidence"] = evidence
        c["target"] = target_name
        c.update(detail)
        candidates.append(c)

    # c1a: source parses typed but default render keeps the (missing) name;
    # the node class's alternate sql_names may exist in the target.
    if parsed is not None:
        cls = type(parsed[1])
        sql_names = []
        try:
            sql_names = [n.lower() for n in cls.sql_names()]
        except Exception:
            pass
        for alt in sorted(sql_names):
            if alt != name and alt in tgt_lookup:
                add("c1", tgt_lookup[alt], {"via": "node-class sql_names", "nodeClass": cls.__name__})

    # c1b: some other dialect's parser knows this name as a typed node whose
    # target rendering lands on a target-catalog name.
    if parsed is None:
        counts = try_arg_counts(entry)
        for helper in HELPER_DIALECTS:
            if helper == src:
                continue
            p = parse_typed(name, helper, counts)
            if p is None:
                continue
            r = render(*p, target=tgt)
            if r["status"] == "ok" and r.get("head") and r["head"] != name and r["head"] in tgt_lookup:
                add(
                    "c1",
                    tgt_lookup[r["head"]],
                    {
                        "via": "parsed under dialect '{}'".format(helper or "base"),
                        "nodeClass": type(p[1]).__name__,
                        "renderedSql": r["sql"],
                    },
                )
                break

    # c2: another alias of the same source function exists in the target.
    for alias in sorted(entry.aliases):
        if alias != name and alias in tgt_lookup:
            add("c2", tgt_lookup[alias], {"via": "source alias set"})

    # c3: low-confidence lexical hint (normalized-name equality).
    for form in sorted(c3_norms(name)):
        for tgt_name in tgt_c3.get(form, ()):  # already sorted
            if tgt_name != name:
                add("c3", tgt_name, {"via": "lexical normalization (HINT ONLY)"})

    return candidates


def build_c3_index(entries):
    index = {}
    for primary in sorted(entries):
        for form in c3_norms(primary):
            index.setdefault(form, []).append(primary)
    for form in index:
        index[form] = sorted(set(index[form]))
    return index


# --------------------------------------------------------------------------
# Report generation
# --------------------------------------------------------------------------

def bucket_counts(entries):
    counts = {"A": 0, "B": 0, "C": 0, "D": 0}
    sub = {
        "A_arity_compatible": 0,
        "A_arity_suspect": 0,
        "A_arity_unknown": 0,
        "A_sqlglot_translates_differently": 0,
        "B_render_ok": 0,
        "B_render_error": 0,
        "C_with_c1_or_c2": 0,
        "C_c3_hint_only": 0,
    }
    for e in entries:
        counts[e["bucket"]] += 1
        if e["bucket"] == "A":
            sub["A_" + e["arity"].replace("-", "_")] += 1
            if "sqlglotTranslatesTo" in e:
                sub["A_sqlglot_translates_differently"] += 1
        elif e["bucket"] == "B":
            sub["B_render_ok" if e["renderCheck"] == "ok" else "B_render_error"] += 1
        elif e["bucket"] == "C":
            evidences = {c["evidence"] for c in e["candidates"]}
            if evidences & {"c1", "c2"}:
                sub["C_with_c1_or_c2"] += 1
            else:
                sub["C_c3_hint_only"] += 1
    return counts, sub


WARNING_TEXT = (
    "Bucket A (same-name) is CANDIDATE-OK only: identical names do not imply "
    "identical semantics. Nothing in this report is verified-correct."
)

# Directory (relative to the semantics dir) holding one detail file per ordered pair.
PAIR_DIR_NAME = "function-gaps"


def pair_key(src, tgt):
    return "{}->{}".format(src, tgt)


def pair_file_name(src, tgt):
    """Stable, filesystem-safe detail filename for an ordered pair.

    `src` and `tgt` are dialect identifiers (already `[A-Za-z0-9_]+`), so a `__`
    separator is unambiguous and needs no escaping.
    """
    return "{}__{}.json".format(src, tgt)


def write_split_report(pairs_data, pair_metadata, evidence, manifest_path):
    """Write the small manifest and the per-pair detail files.

    The manifest keeps global metadata, warnings, evidence/catalog provenance, the
    registered/unavailable dialect classification, and maps every ordered pair to its
    counts, subCounts, entry count and detail file. Each detail file preserves the full
    pair record (metadata + counts + subCounts + every entry) with no information loss.
    Stale detail files not referenced by the freshly written manifest are removed.
    """
    semantics_dir = manifest_path.parent
    pair_dir = semantics_dir / PAIR_DIR_NAME
    pair_dir.mkdir(parents=True, exist_ok=True)

    manifest = OrderedDict()
    manifest["_generated_by"] = "tools/function_gap_report.py"
    manifest["_warning"] = WARNING_TEXT
    manifest["_layout"] = (
        "Split report: this manifest indexes per-pair detail files under "
        "'{}/'. Each pair's full entry list lives in its own file (see pairs[*].file). "
        "Regenerate with: python3 tools/function_gap_report.py".format(PAIR_DIR_NAME)
    )
    manifest["dialectEvidence"] = OrderedDict(
        (d, evidence[d]) for d in ALL_REGISTERED_DIALECTS
    )
    # Registered vs unavailable engine classification, mirroring the analysis universe.
    manifest["registeredDialects"] = list(ALL_REGISTERED_DIALECTS)
    manifest["catalogEngines"] = list(CATALOG_ENGINES)
    manifest["unavailableDialects"] = [
        d for d in ALL_REGISTERED_DIALECTS if d not in SQLGLOT_DIALECTS
    ]
    manifest["pairDirectory"] = PAIR_DIR_NAME
    manifest["pairs"] = OrderedDict()

    expected_files = set()
    for (src, tgt), entries in pairs_data.items():
        counts, sub = bucket_counts(entries)
        # Full detail record for the per-pair file.
        detail = OrderedDict(pair_metadata[(src, tgt)])
        detail["counts"] = counts
        detail["subCounts"] = sub
        detail["entries"] = entries

        fname = pair_file_name(src, tgt)
        expected_files.add(fname)
        (pair_dir / fname).write_text(json.dumps(detail, indent=1) + "\n")

        # Compact manifest entry: metadata + counts + pointer to the detail file.
        idx = OrderedDict(pair_metadata[(src, tgt)])
        idx["counts"] = counts
        idx["subCounts"] = sub
        idx["entryCount"] = len(entries)
        idx["file"] = "{}/{}".format(PAIR_DIR_NAME, fname)
        manifest["pairs"][pair_key(src, tgt)] = idx

    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=1) + "\n")

    # Safely remove stale generated pair files no longer referenced by the manifest.
    removed = []
    if pair_dir.is_dir():
        for existing in sorted(pair_dir.glob("*.json")):
            if existing.name not in expected_files:
                existing.unlink()
                removed.append(existing.name)
    return removed


def write_markdown(pairs_data, pair_metadata, catalogs, evidence, path):
    lines = []
    w = lines.append
    w("# Function relationship report: StarRocks and registered dialects")
    w("")
    w("Generated by `tools/function_gap_report.py` (re-runnable; regenerates this file,")
    w("the manifest `brikk-sql/testResources/semantics/gap-report.json`, and the per-pair")
    w("detail files under `brikk-sql/testResources/semantics/function-gaps/`).")
    w("")
    w("## Report layout")
    w("")
    w("The machine-readable report is split to keep reviews and merges manageable:")
    w("")
    w("- `brikk-sql/testResources/semantics/gap-report.json` - small deterministic")
    w("  **manifest**: global metadata, the `_warning`, `dialectEvidence` provenance, the")
    w("  registered/unavailable dialect classification, and a `pairs` map from each ordered")
    w("  pair to its `counts`, `subCounts`, `entryCount` and detail `file`.")
    w("- `brikk-sql/testResources/semantics/function-gaps/<src>__<tgt>.json` - one file per")
    w("  ordered pair holding that pair's full record (metadata + counts + every entry).")
    w("")
    w("Refresh everything with a single deterministic run:")
    w("")
    w("```bash")
    w("python3 tools/function_gap_report.py")
    w("```")
    w("")
    w("## Method")
    w("")
    w("Every StarRocks direction against every registered dialect is present. Engine")
    w("catalogs are preferred; SQLGlot-backed dialects without one use their pinned")
    w("parser/codegen function registry as explicitly weaker relationship evidence.")
    w("")
    w("- **A - same-name passthrough**: the name (or an alias) exists in the target")
    w("  catalog. Annotated with signature arity overlap (`arity-compatible` /")
    w("  `arity-suspect` / `arity-unknown`).")
    w("- **B - translated**: sqlglot parses the name into a typed node under the")
    w("  source dialect and the target generator renders that node specially")
    w("  (rename, TRANSFORMS entry, non-function expression form, or an explicit")
    w("  unsupported error). Each entry was render-checked by parsing a synthetic")
    w("  call (arg counts taken from catalog signatures) and generating under the")
    w("  target dialect.")
    w("- **C - rename candidates**: not A/B, but a plausible target equivalent was")
    w("  found. Evidence levels: `c1` = sqlglot alias/class knowledge (the node")
    w("  class's alternate sql_names, or a typed parse under another dialect whose")
    w("  target rendering lands on a target-catalog name); `c2` = catalog alias-set")
    w("  intersection (another alias of the same source function exists in the")
    w("  target); `c3` = lexical normalization only - **hints, not candidates**.")
    w("- **D - no equivalent evidence**: none of the above in the available target")
    w("  evidence. Engine-catalog targets make this a capability gap; codegen-only")
    w("  targets remain semantically unavailable and certification refuses them.")
    w("")
    w("## Caveats (read before acting on this report)")
    w("")
    w("- **Same name does not mean same semantics.** Bucket A is candidate-OK, not")
    w("  verified-OK. Null handling, argument order, collation, overflow and type")
    w("  coercion routinely differ between engines even for identically named")
    w("  functions. Verification requires semantic testing, which this report does")
    w("  not do.")
    w("- `sqlglot-codegen` proves parser/canonical-node/generator relationships only; it")
    w("  is not proof the real engine registers the name or shares its semantics.")
    w("- DataFusion is brikk-native and has no SQLGlot oracle in this tool. Its two")
    w("  StarRocks records are intentional `unavailable` records, never omitted.")
    w("- Trino's TSV carries no variadic marker (e.g. CONCAT appears as a single")
    w("  flattened overload), so trino-side arity data understates accepted arity;")
    w("  some `arity-suspect` annotations with trino as target are false alarms.")
    w("- Synthetic-call parsing cannot exercise special-syntax functions (EXTRACT,")
    w("  TRIM ... FROM, etc.) or functions requiring literal arguments; such parse")
    w("  failures fall back to catalog-only evidence. Render checks use simple")
    w("  column arguments, so renderings that depend on literal argument values may")
    w("  differ in real queries.")
    w("- Bucket B render-check errors are explicit sqlglot UnsupportedError paths;")
    w("  entries marked `renderedNameNotInTargetCatalog` render to a function name")
    w("  the target catalog does not list - both are live translation issues worth")
    w("  follow-up.")
    w("- c3 lexical hints strip underscores and common prefixes (array_, list_,")
    w("  str_, ...). They are cheap string matches with no semantic backing.")
    w("")
    w("## Catalog sizes (primary names analyzed)")
    w("")
    w("| engine | functions |")
    w("|---|---|")
    for e in ALL_REGISTERED_DIALECTS:
        w("| {} | {} |".format(e, len(catalogs.get(e, {}))))
    w("")
    w("## Bucket counts per pair")
    w("")
    w("| pair | total | A | A-arity-suspect | B | B-render-err | C (c1/c2) | C (c3 only) | D |")
    w("|---|---|---|---|---|---|---|---|---|")
    for (src, tgt), entries in pairs_data.items():
        counts, sub = bucket_counts(entries)
        w(
            "| {}->{} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
                src,
                tgt,
                sum(counts.values()),
                counts["A"],
                sub["A_arity_suspect"],
                counts["B"],
                sub["B_render_error"],
                sub["C_with_c1_or_c2"],
                sub["C_c3_hint_only"],
                counts["D"],
            )
        )
    w("")

    # Bucket C full list
    w("## Bucket C - rename candidates (full list)")
    w("")
    w("`c1`/`c2` are actionable candidates; `c3` entries are lexical **hints only**.")
    for (src, tgt), entries in pairs_data.items():
        strong = [e for e in entries if e["bucket"] == "C" and any(c["evidence"] in ("c1", "c2") for c in e["candidates"])]
        hints = [e for e in entries if e["bucket"] == "C" and not any(c["evidence"] in ("c1", "c2") for c in e["candidates"])]
        if not strong and not hints:
            continue
        w("")
        w("### {} -> {}".format(src, tgt))
        if strong:
            w("")
            w("Candidates (c1/c2):")
            w("")
            w("| source | kind | candidate target | evidence | via |")
            w("|---|---|---|---|---|")
            for e in strong:
                for c in e["candidates"]:
                    if c["evidence"] in ("c1", "c2"):
                        w(
                            "| {} | {} | {} | {} | {} |".format(
                                e["name"], e["kind"], c["target"], c["evidence"], c.get("via", "")
                            )
                        )
        if hints:
            w("")
            w("Lexical hints (c3 - low confidence, verify manually):")
            w("")
            w("| source | kind | hint target |")
            w("|---|---|---|")
            for e in hints:
                targets = sorted({c["target"] for c in e["candidates"]})
                w("| {} | {} | {} |".format(e["name"], e["kind"], ", ".join(targets)))
    w("")

    # Bucket A arity-suspect
    w("## Bucket A - arity-suspect (same name, no overlapping arity)")
    w("")
    w("Same-name functions whose source overload arg-counts never fit any target")
    w("overload. Trino-target rows may be false alarms (no variadic data in TSV).")
    for (src, tgt), entries in pairs_data.items():
        suspects = [e for e in entries if e["bucket"] == "A" and e["arity"] == "arity-suspect"]
        if not suspects:
            continue
        w("")
        w("### {} -> {}".format(src, tgt))
        w("")
        w("| function | kind |")
        w("|---|---|")
        for e in suspects:
            w("| {} | {} |".format(e["name"], e["kind"]))
    w("")

    # Bucket A but sqlglot translates differently
    any_div = any(
        "sqlglotTranslatesTo" in e for entries in pairs_data.values() for e in entries if e["bucket"] == "A"
    )
    if any_div:
        w("## Bucket A - same-name exists but sqlglot translates differently")
        w("")
        w("The target catalog has the same name, yet sqlglot deliberately renders a")
        w("different form - a strong signal that the same-named functions are NOT")
        w("semantically equivalent. Treat these as high-priority semantics reviews.")
        for (src, tgt), entries in pairs_data.items():
            div = [e for e in entries if e["bucket"] == "A" and "sqlglotTranslatesTo" in e]
            if not div:
                continue
            w("")
            w("### {} -> {}".format(src, tgt))
            w("")
            w("| source | sqlglot renders | rendered sql |")
            w("|---|---|---|")
            for e in div:
                w("| {} | {} | `{}` |".format(e["name"], e["sqlglotTranslatesTo"], e["renderedSql"]))
        w("")

    # Bucket B render failures
    w("## Bucket B - render-check failures (live translation bugs / known-unsupported)")
    w("")
    for (src, tgt), entries in pairs_data.items():
        fails = [e for e in entries if e["bucket"] == "B" and e["renderCheck"] == "error"]
        ghost = [e for e in entries if e["bucket"] == "B" and e.get("renderedNameNotInTargetCatalog")]
        if not fails and not ghost:
            continue
        w("### {} -> {}".format(src, tgt))
        w("")
        if fails:
            w("Render errors (UnsupportedError etc.):")
            w("")
            w("| function | node class | error |")
            w("|---|---|---|")
            for e in fails:
                w("| {} | {} | {} |".format(e["name"], e["nodeClass"], e["renderError"].replace("|", "\\|")))
            w("")
        if ghost:
            w("Renders to a name absent from the target catalog (suspect translations):")
            w("")
            w("| function | node class | rendered |")
            w("|---|---|---|")
            for e in ghost:
                w("| {} | {} | `{}` |".format(e["name"], e["nodeClass"], e.get("renderedSql", "")))
            w("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines).rstrip() + "\n")


# --------------------------------------------------------------------------

def main():
    start = time.time()
    catalogs = {
        "doris": load_doris(),
        "trino": load_trino(),
        "duckdb": load_duckdb(),
        "starrocks": load_starrocks(),
        "clickhouse": load_clickhouse(),
    }
    evidence = {
        "duckdb": "engine-catalog: duckdb_functions() 1.5.5",
        "trino": "engine-catalog: vendor/data/trino-functions-483.tsv",
        "doris": "engine-catalog: vendor/data/doris-signatures.json",
        "starrocks": "engine-catalog: SHOW FULL BUILTIN FUNCTIONS 4.1.4",
        "clickhouse": "engine-catalog: system.functions 26.5.1.1",
        "sqlglot": "translation-only: pinned SQLGlot parser/codegen registry",
        "datafusion": "unavailable: brikk-native; no SQLGlot oracle or extracted catalog",
    }
    for dialect in SQLGLOT_DIALECTS:
        if dialect not in catalogs:
            catalogs[dialect] = load_sqlglot_codegen(dialect)
            evidence[dialect] = "sqlglot-codegen: pinned parser/canonical-node/generator evidence only"

    lookups = {e: build_lookup(catalogs[e]) for e in SQLGLOT_DIALECTS}
    c3_index = {e: build_c3_index(catalogs[e]) for e in SQLGLOT_DIALECTS}
    transforms = {
        e: dict(Dialect.get_or_raise(sqlglot_name(e)).generator_class.TRANSFORMS)
        for e in SQLGLOT_DIALECTS
    }

    pairs_data = OrderedDict()
    pair_metadata = {}
    # Preserve all ordered engine-catalog comparisons.
    for src in CATALOG_ENGINES:
        for tgt in CATALOG_ENGINES:
            if src == tgt:
                continue
            pairs_data[(src, tgt)] = analyze_pair(
                src, tgt, catalogs, lookups, transforms, c3_index, evidence
            )
            pair_metadata[(src, tgt)] = {
                "analysisStatus": "classified",
                "sourceEvidence": evidence[src],
                "targetEvidence": evidence[tgt],
            }

    # Required closed list: StarRocks against every registered SQLGlot-backed dialect,
    # in both directions. Existing catalog pairs above are not duplicated.
    for other in SQLGLOT_DIALECTS:
        if other == "starrocks":
            continue
        for src, tgt in (("starrocks", other), (other, "starrocks")):
            if (src, tgt) in pairs_data:
                continue
            pairs_data[(src, tgt)] = analyze_pair(
                src, tgt, catalogs, lookups, transforms, c3_index, evidence
            )
            pair_metadata[(src, tgt)] = {
                "analysisStatus": "classified",
                "sourceEvidence": evidence[src],
                "targetEvidence": evidence[tgt],
                "semanticEvidence": "unavailable unless a live hazard scope exists; certification refuses uncovered concepts",
            }

    # DataFusion has neither an extracted catalog nor a SQLGlot dialect. Emit intentional
    # records in both directions rather than silently omitting it.
    for src, tgt in (("starrocks", "datafusion"), ("datafusion", "starrocks")):
        pairs_data[(src, tgt)] = []
        pair_metadata[(src, tgt)] = {
            "analysisStatus": "unavailable",
            "sourceEvidence": evidence[src],
            "targetEvidence": evidence[tgt],
            "reason": "DataFusion is brikk-native and has no SQLGlot oracle or extracted engine catalog",
        }

    removed = write_split_report(
        pairs_data, pair_metadata, evidence,
        ROOT / "brikk-sql" / "testResources" / "semantics" / "gap-report.json",
    )
    runtime = time.time() - start
    write_markdown(
        pairs_data, pair_metadata, catalogs, evidence,
        ROOT / "docs" / "research" / "function-gap-report.md",
    )

    for (src, tgt), entries in pairs_data.items():
        counts, sub = bucket_counts(entries)
        print(
            "{:>6} -> {:<6} total={:<4} A={:<4} B={:<4} (err={:<3}) C={:<4} (c1/c2={:<3} c3={:<3}) D={}".format(
                src, tgt, sum(counts.values()), counts["A"], counts["B"], sub["B_render_error"],
                counts["C"], sub["C_with_c1_or_c2"], sub["C_c3_hint_only"], counts["D"],
            )
        )
    print(
        "wrote manifest gap-report.json + {} pair files under {}/".format(
            len(pairs_data), PAIR_DIR_NAME
        )
    )
    if removed:
        print("removed {} stale pair file(s): {}".format(len(removed), ", ".join(removed)))
    print("runtime: {:.1f}s".format(runtime))


if __name__ == "__main__":
    main()
