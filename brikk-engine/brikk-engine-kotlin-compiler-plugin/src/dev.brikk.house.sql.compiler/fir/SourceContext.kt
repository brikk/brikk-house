package dev.brikk.house.sql.compiler.fir

import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

internal data class SourceImport(val fqName: FqName, val alias: String?, val isAllUnder: Boolean)

/** The IDE can expose declaration PSI before the FIR provider knows its containing file. */
internal fun sourceImports(file: FirFile?, source: KtSourceElement?): List<SourceImport> =
    if (file != null) {
        file.imports.mapNotNull { imp ->
            imp.importedFqName?.let { SourceImport(it, imp.aliasName?.asString(), imp.isAllUnder) }
        }
    } else {
        sourceKtFile(source)?.importDirectives.orEmpty().mapNotNull { imp ->
            imp.importedFqName?.let { SourceImport(it, imp.aliasName, imp.isAllUnder) }
        }
    }

internal fun sourceFilePath(file: FirFile?, source: KtSourceElement?): String? =
    file?.sourceFile?.path ?: sourceKtFile(source)?.virtualFile?.path

private fun sourceKtFile(source: KtSourceElement?): KtFile? =
    (source as? KtPsiSourceElement)?.psi?.containingFile as? KtFile
