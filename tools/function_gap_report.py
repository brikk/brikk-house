#!/usr/bin/env python3
"""Function gap report: cross-engine function transpilation analysis.

For each ordered pair among the engines of record {duckdb, trino, doris}
(the dialects for which we have extracted function catalogs), classify every
function in the SOURCE catalog into one of four buckets:

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
  D  no target equivalent   - none of the above. These are what
                              SqlFragment.unmappableFunctions() flags at
                              transpile time.

Raw data sources (preferred over the generated Kotlin catalogs):
  - vendor/data/doris-signatures.json
  - vendor/data/trino-functions-481.tsv
  - python module `duckdb` -> duckdb_functions()

sqlglot knowledge comes from reference/sqlglot (read-only oracle).

Outputs (regenerated on every run, deterministic):
  - brikk-sql/testResources/semantics/gap-report.json
  - docs/research/function-gap-report.md

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

ENGINES = ["duckdb", "trino", "doris"]
# Dialects consulted for c1 evidence (sqlglot knowledge outside the source
# dialect). "" is the base/default dialect.
HELPER_DIALECTS = ["", "trino", "presto", "duckdb", "postgres", "mysql", "clickhouse", "doris"]

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
    path = ROOT / "vendor" / "data" / "trino-functions-481.tsv"
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
            tree = sqlglot.parse_one(synth_sql(name, n), read=dialect or None)
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
        out = node.sql(dialect=target, unsupported_level=ErrorLevel.RAISE)
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

def analyze_pair(src, tgt, catalogs, lookups, transforms, c3_index):
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
        if sorted(entry.aliases) != [name]:
            rec["aliases"] = sorted(entry.aliases)

        target_primary = tgt_lookup.get(name)
        if target_primary is not None:
            rec["bucket"] = "A"
            rec["targetName"] = target_primary
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
                rec["specialVia"] = "unsupported"
                rec["renderCheck"] = "error"
                rec["renderError"] = r["error"]
                results.append(rec)
                continue
            head = r.get("head")
            in_transforms = cls in tgt_transforms
            if head and head != name:
                rec["bucket"] = "B"
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
                rec["specialVia"] = "expression-render"
                rec["renderedSql"] = r["sql"]
                rec["renderCheck"] = "ok"
                results.append(rec)
                continue
            if in_transforms:
                rec["bucket"] = "B"
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
            rec["candidates"] = candidates
        else:
            rec["bucket"] = "D"
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


def write_json(pairs_data, path):
    out = OrderedDict()
    out["_generated_by"] = "tools/function_gap_report.py"
    out["_warning"] = (
        "Bucket A (same-name) is CANDIDATE-OK only: identical names do not imply "
        "identical semantics. Nothing in this report is verified-correct."
    )
    out["pairs"] = OrderedDict()
    for (src, tgt), entries in pairs_data.items():
        counts, sub = bucket_counts(entries)
        out["pairs"]["{}->{}".format(src, tgt)] = OrderedDict(
            [("counts", counts), ("subCounts", sub), ("entries", entries)]
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(out, indent=1) + "\n")


def write_markdown(pairs_data, catalogs, path):
    lines = []
    w = lines.append
    w("# Function gap report: duckdb / trino / doris")
    w("")
    w("Generated by `tools/function_gap_report.py` (re-runnable; regenerates this file")
    w("and `brikk-sql/testResources/semantics/gap-report.json`).")
    w("")
    w("## Method")
    w("")
    w("For each ordered pair among the engines of record (duckdb, trino, doris),")
    w("every primary function name in the source catalog is classified:")
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
    w("- **D - no target equivalent**: none of the above. These are exactly the")
    w("  functions `SqlFragment.unmappableFunctions(target)` flags at transpile time.")
    w("")
    w("## Caveats (read before acting on this report)")
    w("")
    w("- **Same name does not mean same semantics.** Bucket A is candidate-OK, not")
    w("  verified-OK. Null handling, argument order, collation, overflow and type")
    w("  coercion routinely differ between engines even for identically named")
    w("  functions. Verification requires semantic testing, which this report does")
    w("  not do.")
    w("- Catalogs exist only for doris, trino and duckdb. mysql / postgres /")
    w("  clickhouse catalog extraction is future work, so pairs involving those")
    w("  dialects are not analyzed here even though brikk supports them.")
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
    for e in ENGINES:
        w("| {} | {} |".format(e, len(catalogs[e])))
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
    path.write_text("\n".join(lines) + "\n")


# --------------------------------------------------------------------------

def main():
    start = time.time()
    catalogs = {"doris": load_doris(), "trino": load_trino(), "duckdb": load_duckdb()}
    lookups = {e: build_lookup(catalogs[e]) for e in ENGINES}
    c3_index = {e: build_c3_index(catalogs[e]) for e in ENGINES}
    transforms = {e: dict(Dialect.get_or_raise(e).generator_class.TRANSFORMS) for e in ENGINES}

    pairs_data = OrderedDict()
    for src in ENGINES:
        for tgt in ENGINES:
            if src == tgt:
                continue
            pairs_data[(src, tgt)] = analyze_pair(src, tgt, catalogs, lookups, transforms, c3_index)

    write_json(pairs_data, ROOT / "brikk-sql" / "testResources" / "semantics" / "gap-report.json")
    runtime = time.time() - start
    write_markdown(pairs_data, catalogs, ROOT / "docs" / "research" / "function-gap-report.md")

    for (src, tgt), entries in pairs_data.items():
        counts, sub = bucket_counts(entries)
        print(
            "{:>6} -> {:<6} total={:<4} A={:<4} B={:<4} (err={:<3}) C={:<4} (c1/c2={:<3} c3={:<3}) D={}".format(
                src, tgt, sum(counts.values()), counts["A"], counts["B"], sub["B_render_error"],
                counts["C"], sub["C_with_c1_or_c2"], sub["C_c3_hint_only"], counts["D"],
            )
        )
    print("runtime: {:.1f}s".format(runtime))


if __name__ == "__main__":
    main()
