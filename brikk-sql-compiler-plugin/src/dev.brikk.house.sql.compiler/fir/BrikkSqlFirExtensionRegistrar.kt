package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import dev.brikk.house.sql.compiler.analysis.rethrowIfCancellation
import dev.brikk.house.sql.compiler.analysis.FunctionAnalysis
import dev.brikk.house.sql.compiler.analysis.toShape
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.shape.ShapeCatalog
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol

@OptIn(FirExtensionApiInternals::class)
class BrikkSqlFirExtensionRegistrar(private val options: BrikkSqlOptions) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> BrikkSqlSession(session, options) }
        +::ShapeDeclarationGenerator
        +::BrikkSqlCallRefinement
        +::BrikkSqlAdditionalCheckers
        registerDiagnosticContainers(BrikkSqlDiagnostics)
    }
}

class BrikkSqlAdditionalCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker>
            get() = setOf(SqlLiteralCallChecker)
    }
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker>
            get() = setOf(BrikkSqlFunctionChecker)
    }
}

/**
 * `Sql.<dialect>(...)` must be the body of a `@BrikkSql` function and take a constant string.
 */
object SqlLiteralCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        if (!callee.hasAnnotation(BrikkSqlNames.BRIKK_SQL_DIALECT_ANNOTATION_CLASS_ID, context.session)) return

        val calleeName = callee.callableId?.asSingleFqName()?.asString() ?: callee.name.asString()
        val sqlArg = expression.arguments.firstOrNull() ?: return

        val sql = sqlArg.constSqlStringOrNull()
        if (sql == null) {
            reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_NOT_CONSTANT, calleeName)
            return
        }
        if (sql.isBlank()) {
            reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_EMPTY, calleeName)
            return
        }
        val enclosing = context.containingDeclarations.filterIsInstance<FirNamedFunctionSymbol>()
            .lastOrNull { it.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, context.session) }
        if (enclosing == null) {
            reporter.reportOn(expression.source, BrikkSqlDiagnostics.SQL_OUTSIDE_BRIKK_FUNCTION, calleeName)
        }
    }
}

/**
 * Surfaces the analysis outcome of a `@BrikkSql` function: SQL parse/resolution errors, and
 * `:name` placeholders that do not match a parameter.
 */
object BrikkSqlFunctionChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, context.session)) return
        try {
            checkOrThrow(declaration)
        } catch (e: Exception) {
            // Boundary: the checker is the one place a failure can still become a diagnostic.
            rethrowIfCancellation(e)
            reporter.reportOn(declaration.source, BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, "internal error: $e")
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkOrThrow(declaration: FirNamedFunction) {
        val brikk = context.session.brikkSql
        val analysis = brikk.analysisOfFunction(declaration.symbol) ?: return
        val call = RawFir.sqlCall(declaration)
        val anchor = call?.arguments?.firstOrNull()?.source ?: call?.source ?: declaration.source

        if (brikk.options.debug) {
            reporter.reportOn(
                anchor, BrikkSqlDiagnostics.SQL_DEBUG,
                "fn=${analysis.functionName} inputs=${analysis.inputs.mapValues { it.value.map { c -> c.name } }} " +
                    "output=${analysis.output.map { "${it.name}:${it.type}" }} shape=${analysis.isShape} traits=${analysis.satisfiedTraits.map { it.shortClassName }}",
            )
        }
        if (analysis.error != null) {
            reporter.reportOn(anchor, BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, analysis.error)
            return
        }
        val declared = analysis.scalarParams.toSet()
        val used = analysis.fragment.scalarParams.mapNotNull { it.name }
        for (p in used) {
            if (p.substringBefore('.') !in declared) {
                reporter.reportOn(anchor, BrikkSqlDiagnostics.SQL_UNBOUND_PARAM, p, declared.sorted().joinToString(", "))
            }
        }
        // Unknown columns: qualify strictly against the declared inputs and the catalog.
        val unknown = try {
            strictQualify(analysis, brikk)
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (unknown != null) reporter.reportOn(anchor, BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, unknown)
    }

    /**
     * Every column reference in the fragment must name a column of a declared input, of a
     * catalog table, or an alias the fragment itself defines. Returns a message or null.
     */
    private fun strictQualify(analysis: FunctionAnalysis, brikk: BrikkSqlSession): String? {
        val fragment = analysis.fragment
        val slots = analysis.inputs.mapValues { (_, cols) -> cols.toShape() }
        val out = fragment.outputShape(ShapeCatalog(brikk.catalog.tables, slots))
        val known = HashSet<String>()
        analysis.inputs.values.flatten().mapTo(known) { it.name.lowercase() }
        brikk.catalog.tables.values.flatMap { it.names() }.mapTo(known) { it.lowercase() }
        out.names().mapTo(known) { it.lowercase() }
        val refs = fragment.ast.findAll(Column::class).map { (it as Column).name }.toSet()
        val bad = refs.filter { it.lowercase() !in known }
        return if (bad.isEmpty()) null else "unknown column(s): ${bad.joinToString(", ")}"
    }
}
