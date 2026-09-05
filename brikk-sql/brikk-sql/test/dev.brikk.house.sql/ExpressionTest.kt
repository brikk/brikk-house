package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Neg
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Sum
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.args
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExpressionTest {

    private fun ident(name: String, quoted: Boolean = false): Identifier =
        Identifier(args("this" to name, "quoted" to quoted))

    private fun col(name: String): Column = Column(args("this" to ident(name)))

    private fun num(value: Int): Literal =
        Literal(args("this" to value.toString(), "is_string" to false))

    private fun selectFromT(vararg expressions: Expression): Select = Select(
        args(
            "expressions" to expressions.toList(),
            "from_" to From(args("this" to Table(args("this" to ident("t"))))),
        )
    )

    // --- parent / argKey / index wiring -----------------------------------

    @Test
    fun constructorWiresParents() {
        val a = col("a")
        val b = num(1)
        val eq = EQ(args("this" to a, "expression" to b))

        assertSame(eq, a.parent)
        assertEquals("this", a.argKey)
        assertNull(a.index)
        assertSame(eq, b.parent)
        assertEquals("expression", b.argKey)
        assertSame(a, (a.thisArg as Identifier).parent)
    }

    @Test
    fun setWiresParentAndRemovesOnNull() {
        val select = Select()
        val where = Where(args("this" to EQ(args("this" to col("a"), "expression" to num(1)))))
        select.set("where", where)

        assertSame(select, where.parent)
        assertEquals("where", where.argKey)
        assertNull(where.index)
        assertSame(where, select.args["where"])

        // set(key, null) pops the arg entirely (sqlglot: Expression.set)
        select.set("where", null)
        assertFalse("where" in select.args)
    }

    @Test
    fun appendBuildsListsAndIndexes() {
        val select = Select()
        val a = col("a")
        val b = col("b")
        select.append("expressions", a)
        select.append("expressions", b)

        assertEquals(listOf<Any?>(a, b), select.args["expressions"])
        assertSame(select, a.parent)
        assertEquals("expressions", a.argKey)
        assertEquals(0, a.index)
        assertEquals(1, b.index)
    }

    @Test
    fun setWithIndexOverwritesInsertsAndRemoves() {
        val select = selectFromT(col("a"), col("b"), col("c"))
        val x = col("x")

        // overwrite
        select.set("expressions", x, index = 1)
        assertEquals(listOf("a", "x", "c"), select.expressionsArg.map { (it as Column).name })
        assertEquals(1, x.index)

        // insert without overwrite
        val y = col("y")
        select.set("expressions", y, index = 1, overwrite = false)
        assertEquals(listOf("a", "y", "x", "c"), select.expressionsArg.map { (it as Column).name })

        // remove by setting null; following indices are decremented
        select.set("expressions", null, index = 0)
        assertEquals(listOf("y", "x", "c"), select.expressionsArg.map { (it as Column).name })
        assertEquals(listOf(0, 1, 2), select.expressionsArg.map { (it as Column).index })
    }

    // --- copy ---------------------------------------------------------------

    @Test
    fun copyIsDeepAndDetached() {
        val select = selectFromT(col("a"))
        val where = Where(args("this" to EQ(args("this" to col("b"), "expression" to num(1)))))
        select.set("where", where)
        select.comments = mutableListOf("hello")
        select.meta["line"] = 1

        val copy = select.copy() as Select
        assertEquals(select, copy)
        assertNull(copy.parent)
        assertEquals(mutableListOf("hello"), copy.comments)
        assertEquals(1, copy.meta["line"])

        // mutating the copy must not affect the original
        (copy.find<Column>() ?: error("no column")).set("this", ident("z"))
        copy.comments!!.add("more")
        assertNotEquals(select, copy)
        assertEquals(mutableListOf("hello"), select.comments)
        assertEquals("a", select.find<Column>()!!.name)
    }

    // --- traversal ----------------------------------------------------------

    @Test
    fun dfsAndBfsOrders() {
        val tree = EQ(
            args(
                "this" to Add(args("this" to col("a"), "expression" to col("b"))),
                "expression" to num(1),
            )
        )

        val dfs = tree.dfs().map { it.key }.toList()
        assertEquals(
            listOf("eq", "add", "column", "identifier", "column", "identifier", "literal"),
            dfs,
        )

        val bfs = tree.bfs().map { it.key }.toList()
        assertEquals(
            listOf("eq", "add", "literal", "column", "column", "identifier", "identifier"),
            bfs,
        )
    }

    @Test
    fun walkPrune() {
        val tree = selectFromT(col("a"))
        val pruned = tree.walk(bfs = false, prune = { it is Column }).toList()
        assertTrue(pruned.any { it is Column })
        assertFalse(pruned.any { it is Identifier && it.name == "a" })
    }

    @Test
    fun findAndFindAll() {
        val select = selectFromT(col("a"), Alias(args("this" to Sum(args("this" to col("b"))), "alias" to ident("s"))))

        assertEquals(listOf("a", "b"), select.findAll<Column>().map { it.name }.toList())
        assertEquals("a", select.find<Column>()!!.name)
        assertNull(select.find<Neg>())

        val sum = select.find<Sum>()!!
        assertSame(select, sum.findAncestor<Select>())
        assertTrue(sum.findAncestor<Alias>() is Alias)
        assertNull(sum.findAncestor<Where>())
    }

    @Test
    fun rootDepthUnnest() {
        val inner = col("a")
        val tree = Not(args("this" to Paren(args("this" to Paren(args("this" to inner))))))

        assertSame(tree, inner.root())
        assertEquals(0, tree.depth)
        assertEquals(3, inner.depth)

        val paren = tree.thisArg as Paren
        assertSame(inner, paren.unnest())
        assertSame(tree, tree.unnest())
    }

    // --- transform / replace / pop -------------------------------------------

    @Test
    fun transformReplacesNodes() {
        val select = selectFromT(col("a"), col("b"))

        val transformed = select.transform { node ->
            if (node is Column && node.name == "a") col("renamed") else node
        }

        assertEquals(listOf("renamed", "b"), (transformed as Select).findAll<Column>().map { it.name }.toList())
        // copy=true (default) leaves the original untouched
        assertEquals(listOf("a", "b"), select.findAll<Column>().map { it.name }.toList())

        // in-place transform mutates
        select.transform(copy = false) { node ->
            if (node is Column && node.name == "b") col("c") else node
        }
        assertEquals(listOf("a", "c"), select.findAll<Column>().map { it.name }.toList())
    }

    @Test
    fun transformRemovesListNodes() {
        val select = selectFromT(col("a"), col("b"))
        val transformed = select.transform { node ->
            if (node is Column && node.name == "a") null else node
        } as Select
        assertEquals(listOf("b"), transformed.findAll<Column>().map { it.name }.toList())
    }

    @Test
    fun replaceSwapsNode() {
        val select = selectFromT(col("a"))
        val target = select.find<Column>()!!
        val replacement = col("z")
        val result = target.replace(replacement)

        assertSame(replacement, result)
        assertEquals(listOf("z"), select.findAll<Column>().map { it.name }.toList())
        assertNull(target.parent)
        assertNull(target.argKey)
        assertNull(target.index)
        assertSame(select, replacement.parent)
        assertEquals(0, replacement.index)
    }

    @Test
    fun popRemovesFromParent() {
        val select = selectFromT(col("a"), col("b"))
        val popped = select.find<Column>()!!.pop()

        assertEquals("a", popped.name)
        assertNull(popped.parent)
        assertEquals(listOf("b"), select.findAll<Column>().map { it.name }.toList())
        assertEquals(0, (select.expressionsArg[0] as Column).index)
    }

    // --- equality / hashCode ---------------------------------------------------

    @Test
    fun equalTreesAreEqualRegardlessOfParent() {
        val standalone = col("a")
        val select = selectFromT(col("a"))
        val nested = select.find<Column>()!!

        assertEquals(standalone, nested) // parent is ignored
        assertEquals(standalone.hashCode(), nested.hashCode())
        assertNotEquals<Expression>(col("a"), col("b"))
        assertNotEquals(selectFromT(col("a")), selectFromT(col("a"), col("b")))
        assertEquals(selectFromT(col("a")), selectFromT(col("a")))
    }

    @Test
    fun equalityIgnoresCommentsAndMeta() {
        val plain = col("a")
        val annotated = col("a")
        annotated.comments = mutableListOf("hi")
        annotated.meta["line"] = 12

        assertEquals(plain, annotated)
        assertEquals(plain.hashCode(), annotated.hashCode())
    }

    @Test
    fun equalityNormalizesLikePythonHash() {
        // null/false args are equivalent to absent args on regular nodes
        assertEquals(
            Identifier(args("this" to "a", "quoted" to false)),
            Identifier(args("this" to "a")),
        )
        // strings compare case-insensitively on regular nodes...
        assertEquals(
            Table(args("this" to ident("t"), "db" to "DB")),
            Table(args("this" to ident("t"), "db" to "db")),
        )
        // ...but case-sensitively on raw-args nodes (Literal, Identifier)
        assertNotEquals(
            Literal(args("this" to "A", "is_string" to true)),
            Literal(args("this" to "a", "is_string" to true)),
        )
        assertNotEquals(ident("A"), ident("a"))
        // literal string vs number
        assertNotEquals(
            Literal(args("this" to "5", "is_string" to true)),
            Literal(args("this" to "5", "is_string" to false)),
        )
        // empty list args are equivalent to absent args
        assertEquals(Select(args("expressions" to emptyList<Any?>())), Select())
        // different classes never compare equal
        assertNotEquals<Expression>(Star(), Null())
    }

    // --- serde (common smoke; the oracle differential runs on JVM) -----------

    @Test
    fun serdeRoundTrip() {
        val select = selectFromT(col("a"), Alias(args("this" to Sum(args("this" to col("b"))), "alias" to ident("s"))))
        select.comments = mutableListOf("c1")
        select.meta["line"] = 7

        val dumped = Serde.dump(select)
        val loaded = Serde.loadExpression(dumped)

        assertEquals(select, loaded)
        assertEquals(dumped, Serde.dump(loaded))
        assertEquals(mutableListOf("c1"), loaded.comments)
        assertEquals(7L, loaded.meta["line"]) // integral scalars load as Long
    }
}
