package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import dev.brikk.house.sql.compiler.analysis.PluginGuard
import dev.brikk.house.sql.compiler.analysis.FunctionAnalysis
import dev.brikk.house.sql.compiler.analysis.rethrowIfCancellation
import dev.brikk.house.sql.compiler.analysis.validateColumns
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import dev.brikk.house.sql.generator.UnsupportedError
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol

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
        override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>>
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

        val enclosing = context.containingDeclarations.filterIsInstance<FirNamedFunctionSymbol>()
            .lastOrNull { it.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, context.session) }

        // References in the template are resolved here, so the scope only needs to cover the
        // (already resolved) reference kinds; the name sets are a formality.
        val scope = TemplateScope(emptySet(), emptySet(), emptySet()) { null }
        when (val outcome = SqlTemplateFir.read(sqlArg, scope)) {
            is TemplateOutcome.NotSql -> {
                reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_NOT_CONSTANT, calleeName)
                return
            }
            is TemplateOutcome.Rejected -> {
                reporter.reportOn(outcome.entry.source, BrikkSqlDiagnostics.SQL_BAD_INTERPOLATION, outcome.reason)
                return
            }
            is TemplateOutcome.Ok -> {
                val template = outcome.template
                template.malformedSlot()?.let { slot ->
                    reporter.reportOn(
                        sqlArg.source, BrikkSqlDiagnostics.SQL_BAD_INTERPOLATION,
                        "'${slot.name}' is a Rel parameter and must be used as a table call: write '$${slot.name}()'",
                    )
                    return
                }
                if (template.sql.isBlank()) {
                    reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_EMPTY, calleeName)
                    return
                }
            }
        }
        if (enclosing == null) {
            reporter.reportOn(expression.source, BrikkSqlDiagnostics.SQL_OUTSIDE_BRIKK_FUNCTION, calleeName)
        }
    }
}

/**
 * Surfaces the analysis outcome of a `@BrikkSql` function: SQL parse/resolution errors, and
 * `:name` placeholders that do not match a parameter.
 */
object BrikkSqlFunctionChecker : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, context.session)) {
            try {
                checkEscapingShape(declaration)
            } catch (e: Throwable) {
                PluginGuard.recoverable(e, "escaping shape check(${declaration.name})")
            }
            return
        }
        try {
            checkOrThrow(declaration)
        } catch (e: Throwable) {
            // Boundary: the checker is the one place a failure can still become a diagnostic.
            PluginGuard.recoverable(e, "check(${declaration.name})")
            reporter.reportOn(declaration.source, BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, "internal error: $e")
        }
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkOrThrow(declaration: FirNamedFunction) {
        val brikk = context.session.brikkSql
        brikk.collidingOutputClassId(declaration.symbol)?.let { classId ->
            reporter.reportOn(
                declaration.source,
                BrikkSqlDiagnostics.SQL_OUTPUT_NAME_COLLISION,
                classId.asSingleFqName().asString(),
            )
            return
        }
        val analysis = brikk.analysisOfFunction(declaration.symbol) ?: return
        val call = RawFir.sqlCall(declaration)
        val sqlArg = call?.arguments?.firstOrNull()
        val anchor = sqlArg?.source ?: call?.source ?: declaration.source
        val scope = TemplateScope(emptySet(), emptySet(), emptySet()) { null }
        val template = sqlArg?.let { (SqlTemplateFir.read(it, scope) as? TemplateOutcome.Ok)?.template }
        fun located(start: Int?, end: Int?) = sqlDiagnosticSource(anchor, template, start, end)

        if (brikk.options.debug) {
            reporter.reportOn(
                anchor, BrikkSqlDiagnostics.SQL_DEBUG,
                "fn=${analysis.functionName} inputs=${analysis.inputs.mapValues { it.value.map { c -> c.name } }} " +
                    "output=${analysis.output.map { "${it.name}:${it.type}" }} shape=${analysis.isShape} traits=${analysis.satisfiedTraits.map { it.shortClassName }}",
            )
        }
        if (analysis.error != null) {
            val position = analysis.errorStart?.let { it to analysis.errorEnd }
                ?: errorColumnRange(analysis, analysis.error)
            reporter.reportOn(located(position?.first, position?.second), BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, analysis.error)
            return
        }
        val declared = (analysis.scalarParams + analysis.binds).toSet()
        val used = analysis.fragment.scalarParams.mapNotNull { it.name }
        val dialect = Dialects.forName(analysis.dialect)
        val tokens = dialect.tokenize(analysis.sqlText)
        val parameterOccurrences = try { analysis.fragment.toSourcePreservingExecutable().parameterOccurrences }
            catch (_: UnsupportedError) { emptyList() } // No proved interval: retain the literal anchor.
        // Dot access is not a Kotlin property binding. Never pretend ':object.field' is
        // fully handled by binding only 'object'; require an explicit local/template bind.
        for (param in analysis.fragment.ast.walk().filter { it is Placeholder || it is Parameter }) {
            val parent = param.parent
            if (parent !is Dot || param.argKey != "this") continue
            val range = parameterOccurrences.firstOrNull { it.name == param.name }?.range
            if (range == null) {
                reporter.reportOn(anchor, BrikkSqlDiagnostics.SQL_DOTTED_PARAM, param.name + "." + (parent.expressionArg as? Expression)?.name)
                continue
            }
            val next = tokens.indexOfFirst { it.start >= range.end }
            if (tokens.getOrNull(next)?.tokenType == TokenType.DOT) {
                val last = tokens.getOrNull(next + 1) ?: continue
                reporter.reportOn(located(range.start, last.end + 1), BrikkSqlDiagnostics.SQL_DOTTED_PARAM,
                    analysis.sqlText.substring(range.start, last.end + 1))
            }
        }
        for (p in used) {
            if (p !in declared) {
                val range = parameterOccurrences.firstOrNull { it.name == p }?.range
                reporter.reportOn(located(range?.start, range?.end), BrikkSqlDiagnostics.SQL_UNBOUND_PARAM, p, declared.sorted().joinToString(", "))
            }
        }
        // Probe only the names introduced by interpolation. The SQL parser, not a text scan,
        // distinguishes plain placeholders from comments, strings and casts.
        val params = declaration.valueParameters.associateBy { it.name.asString() }
        var probeName = "__brikk_template_bind"
        while (probeName in params) probeName += "_"
        val references = ArrayList<FirPropertyAccessExpression>()
        if (sqlArg == null) return
        val probe = SqlTemplateFir.read(sqlArg, scope) { entry ->
            references += entry
            probeName
        } as? TemplateOutcome.Ok ?: return // The call checker diagnoses unsupported templates.
        val plainRoots = SqlFragment(probe.template.sql, analysis.dialect).scalarParams
            .mapNotNullTo(HashSet()) { it.name }
        for (entry in references) {
            val name = entry.calleeReference.name.asString()
            val param = params[name] ?: continue
            if (name in analysis.scalarParams && name in plainRoots && entry.calleeReference.toResolvedCallableSymbol() != param.symbol) {
                reporter.reportOn(
                    entry.source, BrikkSqlDiagnostics.SQL_BAD_INTERPOLATION,
                    "plain SQL placeholder '$name' binds the parameter, but '${'$'}$name' refers to a different Kotlin declaration; " +
                        "rename the interpolated local or property",
                )
            }
        }

        // Parameters can be consumed by local computations as well as by SQL. Compare symbols:
        // a same-named local or lambda parameter is not a use of the enclosing parameter.
        val referencedParams = HashSet<FirValueParameterSymbol>()
        declaration.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                element.acceptChildren(this)
            }

            override fun visitResolvedNamedReference(resolvedNamedReference: FirResolvedNamedReference) {
                (resolvedNamedReference.resolvedSymbol as? FirValueParameterSymbol)?.let { referencedParams += it }
            }
        })
        for (param in declaration.valueParameters) {
            val name = param.name.asString()
            if (name in analysis.scalarParams && name !in plainRoots && param.symbol !in referencedParams) {
                reporter.reportOn(param.source, BrikkSqlDiagnostics.SQL_UNUSED_PARAM, name)
            }
        }
        // Unknown columns: qualify strictly against the declared inputs and the catalog.
        val unknown = try {
            validateColumns(analysis, brikk.catalog)
            null
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            e.message ?: e.toString()
        }
        if (unknown != null) {
            val range = errorColumnRange(analysis, unknown)
            reporter.reportOn(located(range?.first, range?.second), BrikkSqlDiagnostics.SQL_ANALYSIS_FAILED, unknown)
        }
    }

    private fun errorColumnRange(analysis: FunctionAnalysis, message: String): Pair<Int, Int>? {
        val name = Regex("(?i)(?:column ['\"]([^'\"]+)['\"]|unknown column: ([^ .]+))")
            .find(message)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return null
        val columns = try { analysis.fragment.ast.findAll(Column::class).filter { it.name.equals(name, true) }.toList() }
            catch (e: Exception) { rethrowIfCancellation(e); return null }
        val column = columns.singleOrNull() ?: return null // repeated names: do not guess the wrong scope
        val leaf = column.thisArg as? Expression ?: return null
        val start = (leaf.meta["start"] as? Number)?.toInt() ?: return null
        val end = (leaf.meta["end"] as? Number)?.toInt() ?: return null
        return start to end + 1
    }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkEscapingShape(declaration: FirNamedFunction) {
        // An explicit return annotation already communicates the escape contract.
        val returnSource = declaration.returnTypeRef.source
        if (returnSource != null && returnSource.kind !is KtFakeSourceElementKind) return
        val body = declaration.body ?: return
        val result = (body.statements.lastOrNull() as? FirReturnExpression)?.result ?: return
        // Refinement wraps its local shape in run { Local; pipe(...) }; its result type
        // still carries the fixed local symbol even if the helper's return was approximated.
        val type = result.resolvedType as? ConeClassLikeType ?: return
        if (type.classId != BrikkSqlNames.REL_CLASS_ID) return
        val shape = type.typeArguments.firstOrNull()?.type as? ConeClassLikeType ?: return
        val symbol = shape.toRegularClassSymbol(context.session) ?: return
        if (symbol.shapeAnchor == null) return
        reporter.reportOn(declaration.source, BrikkSqlDiagnostics.SQL_ESCAPING_LOCAL_SHAPE, declaration.name.asString())
    }

}
