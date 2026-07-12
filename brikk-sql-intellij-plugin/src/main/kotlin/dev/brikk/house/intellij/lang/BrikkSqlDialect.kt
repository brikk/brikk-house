package dev.brikk.house.intellij.lang

import com.intellij.database.Dbms
import com.intellij.lang.Commenter
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlLanguageDialectBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.generic.GenericLexer
import com.intellij.sql.dialects.generic.GenericParser
import com.intellij.sql.dialects.sql92.Sql92Dialect
import com.intellij.sql.dialects.sql92.Sql92ParserDefinition
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import javax.swing.Icon

/**
 * BrikkSQL: the plugin-owned SQL dialect for polyglot files (`.bsql`) — the first-class
 * home of the marker workflow. Blocks declare their dialect with `-- dialect: xyz`,
 * brikk-sql does the real parsing/transpilation, and the host grammar just needs to be
 * lenient with correct `;` statement boundaries.
 *
 * v1 is Generic SQL under our own language id (same recipe: SQL92 tokens helper +
 * Generic lexer/parser — see GenericDialect/GenericParserDefinition), which means no
 * vendor grumbling, our gutter/actions always active ([SqlHosts] treats it lenient),
 * and full control of the id so we can layer per-segment behavior over time (segment
 * re-lexing, dialect-aware completion, pipe syntax support) without churning users.
 */
class BrikkSqlDialect private constructor() : SqlLanguageDialectBase("BrikkSQL") {

    override fun getDbms(): Dbms = Dbms.UNKNOWN

    // Same tokens as Generic SQL: reuse SQL92's helper (GenericDialect does exactly this).
    override fun createTokensHelper(): TokensHelper = Sql92Dialect.INSTANCE.tokensHelper

    // Mirror GenericDialect's implementations (delegate to SQL92).
    override fun isOperatorSupported(token: com.intellij.psi.tree.IElementType): Boolean =
        Sql92Dialect.INSTANCE.isOperatorSupported(token)

    override fun getSystemVariables(): Set<String> = Sql92Dialect.INSTANCE.keywords

    companion object {
        @JvmField
        val INSTANCE = BrikkSqlDialect()
    }
}

/** Generic SQL parsing under the BrikkSQL language (Generic's own recipe, our file type). */
class BrikkSqlParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = Sql92ParserDefinition().elementFactory
    override fun createLexer(project: Project?): Lexer = GenericLexer()
    override fun createParser(project: Project?): PsiParser = GenericParser()
    override fun getFileNodeType(): IFileElementType = FILE

    private companion object {
        private val FILE = SqlFileElementType("BRIKK_SQL_FILE", BrikkSqlDialect.INSTANCE)
    }
}

/** Platform SQL highlighter resolved for the BrikkSQL dialect. */
class BrikkSqlSyntaxHighlighterFactory : SqlSyntaxHighlighterFactory.Base(BrikkSqlDialect.INSTANCE)

class BrikkSqlCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "--"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

object BrikkSqlFileType : LanguageFileType(BrikkSqlDialect.INSTANCE) {
    override fun getName(): String = "Brikk SQL"
    override fun getDescription(): String =
        "Brikk SQL polyglot file: dialect-marker blocks, cross-dialect transpilation, pipe syntax"
    override fun getDefaultExtension(): String = "bsql"
    override fun getIcon(): Icon = com.intellij.icons.AllIcons.Nodes.DataTables
}
