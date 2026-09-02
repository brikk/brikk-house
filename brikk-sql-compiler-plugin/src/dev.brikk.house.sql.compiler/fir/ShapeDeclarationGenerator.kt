package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.analysis.KType
import dev.brikk.house.sql.compiler.analysis.ShapeColumn
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberProperty
import org.jetbrains.kotlin.fir.plugin.createTopLevelClass
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Generates the output shape interface of every `@BrikkSql` function (`fun eventsInRange`
 * -> `interface EventsInRangeOut : Shape, <satisfied traits>`), with one abstract `val` per
 * output column, and supplies the members of call-site local shape classes created by
 * [BrikkSqlCallRefinement].
 *
 * Runs pre-body-resolution: all inputs come from raw FIR + the schema catalog (see
 * docs/RESEARCH-fir-refinement-and-generation.md).
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
class ShapeDeclarationGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    private val brikk get() = session.brikkSql

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(BrikkSqlSession.SQL_PREDICATE, BrikkSqlSession.TRAIT_PREDICATE)
    }

    override fun getTopLevelClassIds(): Set<ClassId> = brikk.functionsByOutClassId.keys

    // Generated classes live in packages that already contain the user's @BrikkSql function,
    // so no new packages are introduced. (Also: this is queried at IMPORTS, before the
    // predicate index exists.)
    override fun hasPackage(packageFqName: FqName): Boolean = false

    override fun generateTopLevelClassLikeDeclaration(classId: ClassId): FirClassLikeSymbol<*>? {
        val analysis = brikk.analysisOf(classId) ?: return null
        val base = if (analysis.isShape) BrikkSqlNames.SHAPE_CLASS_ID else BrikkSqlNames.PARTIAL_CLASS_ID
        return createTopLevelClass(classId, BrikkSqlGeneratedKey, ClassKind.INTERFACE) {
            modality = Modality.ABSTRACT
            superType(base.constructClassLikeType(emptyArray(), isMarkedNullable = false))
            for (trait in analysis.satisfiedTraits) {
                superType(trait.constructClassLikeType(emptyArray(), isMarkedNullable = false))
            }
        }.symbol
    }

    override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> {
        val columns = columnsOf(classSymbol) ?: return emptySet()
        val names = columns.mapTo(LinkedHashSet()) { Name.identifier(it.name) }
        // Call-site local shapes are abstract classes (local interfaces are illegal) and need
        // a constructor for the backend; generated interfaces do not.
        if (classSymbol.classKind == ClassKind.CLASS) names.add(SpecialNames.INIT)
        return names
    }

    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val owner = context.owner
        if (owner.classKind != ClassKind.CLASS || columnsOf(owner) == null) return emptyList()
        return listOf(createConstructor(owner, BrikkSqlGeneratedKey, isPrimary = true).symbol)
    }

    override fun generateProperties(callableId: CallableId, context: MemberGenerationContext?): List<FirPropertySymbol> {
        val owner = context?.owner ?: return emptyList()
        val column = columnsOf(owner)?.firstOrNull { it.name == callableId.callableName.asString() } ?: return emptyList()
        val property = createMemberProperty(
            owner, BrikkSqlGeneratedKey, callableId.callableName, column.type.toCone(), isVal = true, hasBackingField = false,
        ) {
            modality = Modality.ABSTRACT
        }
        return listOf(property.symbol)
    }

    private fun columnsOf(classSymbol: FirClassSymbol<*>): List<ShapeColumn>? {
        val regular = classSymbol as? FirRegularClassSymbol ?: return null
        val origin = regular.origin
        if (origin !is FirDeclarationOrigin.Plugin || origin.key != BrikkSqlGeneratedKey) return null
        return brikk.columnsOf(regular)
    }
}

internal fun KType.toCone(): ConeKotlinType = classId.constructClassLikeType(emptyArray(), isMarkedNullable = nullable)

internal fun ClassId.type(vararg args: ConeKotlinType, nullable: Boolean = false): ConeClassLikeType =
    constructClassLikeType(args as Array<out org.jetbrains.kotlin.fir.types.ConeTypeProjection>, isMarkedNullable = nullable)
