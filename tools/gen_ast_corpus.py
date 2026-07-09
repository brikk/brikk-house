#!/usr/bin/env python3
"""Generates a Python-oracle AST serde corpus for brikk-sql differential testing.

Each case hand-builds an expression tree with DIRECT sqlglot node constructors
(explicit args, no parsing, no fluent builders) so the Kotlin twin in
brikk-sql/test@jvm/dev.brikk.house.sql/AstCorpusDifferentialTest.kt is unambiguous:
the Kotlin test builds the exact same tree by recipe id and asserts that our
Serde.dump output matches `sqlglot.serde.dump` structurally.

Run from anywhere:  python3 tools/gen_ast_corpus.py
Re-run whenever reference/sqlglot is updated or cases are added (keep the Kotlin
twins in AstCorpusDifferentialTest.kt in sync!).
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SQLGLOT = ROOT / "reference" / "sqlglot"
OUT_DIR = ROOT / "brikk-sql" / "testResources" / "ast-corpus"

sys.path.insert(0, str(SQLGLOT))

from sqlglot import expressions as exp  # noqa: E402
from sqlglot.serde import dump  # noqa: E402


def ident(name, quoted=False):
    return exp.Identifier(this=name, quoted=quoted)


def col(name):
    return exp.Column(this=ident(name))


def num(value):
    return exp.Literal(this=str(value), is_string=False)


def lit(value):
    return exp.Literal(this=value, is_string=True)


def case_literal_int():
    return num(5)


def case_literal_string():
    return lit("Hello World")


def case_boolean_eq_is_null():
    return exp.EQ(
        this=exp.Boolean(this=True),
        expression=exp.Is(this=col("a"), expression=exp.Null()),
    )


def case_column_qualified():
    return exp.Column(this=ident("a", quoted=True), table=ident("t"), db=ident("d"))


def case_dot_neg():
    return exp.Neg(
        this=exp.Dot(
            this=exp.Dot(this=ident("d"), expression=ident("t")),
            expression=ident("a"),
        )
    )


def case_alias_add():
    return exp.Alias(
        this=exp.Add(this=col("a"), expression=num(1)),
        alias=ident("x"),
    )


def case_arithmetic():
    return exp.Sub(
        this=exp.Add(
            this=exp.Mul(this=col("a"), expression=num(2)),
            expression=exp.Div(this=col("b"), expression=num(3), typed=False, safe=False),
        ),
        expression=exp.Mod(
            this=exp.Pow(this=col("c"), expression=num(4)),
            expression=num(5),
        ),
    )


def case_logic_not_paren():
    return exp.And(
        this=exp.Or(
            this=exp.EQ(this=col("a"), expression=num(1)),
            expression=exp.GT(this=col("b"), expression=num(2)),
        ),
        expression=exp.Not(
            this=exp.Paren(this=exp.LT(this=col("c"), expression=num(3)))
        ),
    )


def case_comparison_zoo():
    return exp.Xor(
        this=exp.And(
            this=exp.NEQ(this=col("a"), expression=num(1)),
            expression=exp.GTE(this=col("b"), expression=num(2)),
        ),
        expression=exp.Or(
            this=exp.LTE(this=col("c"), expression=num(3)),
            expression=exp.NullSafeEQ(this=col("d"), expression=exp.Null()),
        ),
    )


def case_in_list():
    return exp.In(this=col("a"), expressions=[num(1), num(2), num(3)])


def case_between_like():
    return exp.And(
        this=exp.Between(this=col("a"), low=num(1), high=num(10)),
        expression=exp.ILike(this=col("b"), expression=lit("%x%")),
    )


def case_cast_nested_array():
    return exp.Cast(
        this=col("a"),
        to=exp.DataType(
            this=exp.DataType.Type.ARRAY,
            expressions=[exp.DataType(this=exp.DataType.Type.INT)],
            nested=True,
        ),
    )


def case_cast_decimal():
    return exp.Cast(
        this=num("3.14"),
        to=exp.DataType(this=exp.DataType.Type.DECIMAL),
    )


def case_select_star():
    return exp.Select(
        expressions=[exp.Star()],
        from_=exp.From(this=exp.Table(this=ident("t"))),
    )


def case_select_full():
    return exp.Select(
        expressions=[
            col("a"),
            exp.Alias(this=exp.Sum(this=col("b")), alias=ident("total")),
        ],
        distinct=exp.Distinct(),
        from_=exp.From(this=exp.Table(this=ident("t"), db=ident("d"))),
        where=exp.Where(this=exp.GT(this=col("b"), expression=num(0))),
        group=exp.Group(expressions=[col("a")]),
        having=exp.Having(this=exp.GT(this=exp.Count(this=exp.Star()), expression=num(1))),
        order=exp.Order(
            expressions=[exp.Ordered(this=col("a"), desc=True, nulls_first=False)]
        ),
        limit=exp.Limit(expression=num(10)),
        offset=exp.Offset(expression=num(5)),
    )


def case_join_subquery():
    return exp.Select(
        expressions=[exp.Star()],
        from_=exp.From(
            this=exp.Subquery(
                this=exp.Select(
                    expressions=[col("a")],
                    from_=exp.From(this=exp.Table(this=ident("u"))),
                ),
                alias=exp.TableAlias(this=ident("sq")),
            )
        ),
        joins=[
            exp.Join(
                this=exp.Table(this=ident("v")),
                side="LEFT",
                on=exp.EQ(
                    this=exp.Column(this=ident("a"), table=ident("sq")),
                    expression=exp.Column(this=ident("a"), table=ident("v")),
                ),
            )
        ],
    )


def case_cte_union():
    return exp.Select(
        with_=exp.With(
            expressions=[
                exp.CTE(
                    this=exp.Union(
                        this=exp.Select(expressions=[num(1)]),
                        expression=exp.Select(expressions=[num(2)]),
                        distinct=True,
                    ),
                    alias=exp.TableAlias(this=ident("c")),
                )
            ]
        ),
        expressions=[exp.Star()],
        from_=exp.From(this=exp.Table(this=ident("c"))),
    )


def case_except_intersect():
    return exp.Except(
        this=exp.Select(
            expressions=[col("a")],
            from_=exp.From(this=exp.Table(this=ident("t"))),
        ),
        expression=exp.Intersect(
            this=exp.Select(
                expressions=[col("a")],
                from_=exp.From(this=exp.Table(this=ident("u"))),
            ),
            expression=exp.Select(
                expressions=[col("a")],
                from_=exp.From(this=exp.Table(this=ident("v"))),
            ),
            distinct=True,
        ),
        distinct=False,
    )


def case_functions():
    return exp.Anonymous(
        this="MY_FUNC",
        expressions=[
            col("a"),
            num(1),
            exp.Max(this=col("b")),
            exp.Min(this=col("c")),
            exp.Avg(this=col("d")),
            exp.Count(this=exp.Star(), big_int=True),
        ],
    )


def case_placeholder_parameter():
    return exp.Select(
        expressions=[
            exp.Placeholder(),
            exp.Parameter(this=num(1)),
            exp.Placeholder(this="name", kind=True),
        ]
    )


def case_command():
    return exp.Command(this="SHOW", expression="TABLES LIKE 'x'")


def case_comments_meta():
    column = col("x")
    column.comments = ["leading comment", "trailing comment"]
    column.meta["line"] = 1
    column.meta["col"] = 3
    column.this.comments = ["inner"]
    return column


CASES = [
    ("literal_int", case_literal_int),
    ("literal_string", case_literal_string),
    ("boolean_eq_is_null", case_boolean_eq_is_null),
    ("column_qualified", case_column_qualified),
    ("dot_neg", case_dot_neg),
    ("alias_add", case_alias_add),
    ("arithmetic", case_arithmetic),
    ("logic_not_paren", case_logic_not_paren),
    ("comparison_zoo", case_comparison_zoo),
    ("in_list", case_in_list),
    ("between_like", case_between_like),
    ("cast_nested_array", case_cast_nested_array),
    ("cast_decimal", case_cast_decimal),
    ("select_star", case_select_star),
    ("select_full", case_select_full),
    ("join_subquery", case_join_subquery),
    ("cte_union", case_cte_union),
    ("except_intersect", case_except_intersect),
    ("functions", case_functions),
    ("placeholder_parameter", case_placeholder_parameter),
    ("command", case_command),
    ("comments_meta", case_comments_meta),
]


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


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    corpus = {
        "sqlglot_version": sqlglot_version(),
        "cases": [{"id": case_id, "dump": dump(build())} for case_id, build in CASES],
    }
    out = OUT_DIR / "handbuilt.json"
    out.write_text(json.dumps(corpus, indent=1) + "\n")
    print(f"wrote {out} ({len(CASES)} cases, sqlglot {corpus['sqlglot_version']})")


if __name__ == "__main__":
    main()
