package com.github.albertocavalcante.groovylsp.providers.symbols

import com.github.albertocavalcante.groovylsp.ast.SymbolExtractor
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides document symbols (outline view) for Groovy files.
 * Extracts classes, methods, fields, and properties from the AST to create
 * a hierarchical symbol structure for editor outline views.
 */
@Suppress("TooGenericExceptionCaught") // Symbol provider handles all AST extraction errors
class DocumentSymbolProvider(private val compilationService: GroovyCompilationService) {

    private val logger = LoggerFactory.getLogger(DocumentSymbolProvider::class.java)

    companion object {
        // Approximate line length for import statements
        private const val IMPORT_LINE_LENGTH = 80
    }

    /**
     * Provides document symbols for the given URI.
     * Returns a hierarchical structure where classes contain their methods and fields.
     *
     * @param uri The document URI
     * @return List of document symbols, or empty list if no symbols found
     */
    fun provideDocumentSymbols(uri: String): List<Either<SymbolInformation, DocumentSymbol>> {
        logger.debug("Providing document symbols for $uri")

        try {
            val documentUri = URI.create(uri)
            val ast = compilationService.getAst(documentUri) ?: run {
                logger.debug("No AST available for $uri")
                return emptyList()
            }

            val classSymbols = SymbolExtractor.extractClassSymbols(ast)
            val importSymbols = SymbolExtractor.extractImportSymbols(ast)

            val documentSymbols = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
            documentSymbols.addAll(createImportDocumentSymbols(importSymbols))
            documentSymbols.addAll(createClassDocumentSymbols(classSymbols))

            logger.debug("Found ${documentSymbols.size} document symbols for $uri")
            return documentSymbols
        } catch (e: Exception) {
            logger.error("Error providing document symbols for $uri", e)
            return emptyList()
        }
    }

    private fun createImportDocumentSymbols(
        importSymbols: List<com.github.albertocavalcante.groovylsp.ast.ImportSymbol>,
    ): List<Either<SymbolInformation, DocumentSymbol>> = importSymbols.map { importSymbol ->
        val documentSymbol = DocumentSymbol().apply {
            name = importSymbol.className ?: importSymbol.packageName
            kind = SymbolKind.Namespace
            range = createRange(importSymbol.line, 0, importSymbol.line, IMPORT_LINE_LENGTH)
            selectionRange = createRange(importSymbol.line, 0, importSymbol.line, IMPORT_LINE_LENGTH)
            detail = if (importSymbol.isStatic) "static import" else "import"
        }
        Either.forRight<SymbolInformation, DocumentSymbol>(documentSymbol)
    }

    private fun createClassDocumentSymbols(
        classSymbols: List<com.github.albertocavalcante.groovylsp.ast.ClassSymbol>,
    ): List<Either<SymbolInformation, DocumentSymbol>> = classSymbols.map { classSymbol ->
        Either.forRight<SymbolInformation, DocumentSymbol>(createClassDocumentSymbol(classSymbol))
    }

    /**
     * Creates a DocumentSymbol for a class, including all its methods and fields.
     */
    private fun createClassDocumentSymbol(
        classSymbol: com.github.albertocavalcante.groovylsp.ast.ClassSymbol,
    ): DocumentSymbol {
        val classNode = classSymbol.astNode as org.codehaus.groovy.ast.ClassNode
        val documentSymbol = createBaseClassDocumentSymbol(classSymbol, classNode)

        addMethodsToClassSymbol(documentSymbol, classNode)
        addFieldsToClassSymbol(documentSymbol, classNode)

        return documentSymbol
    }

    /**
     * Creates the base DocumentSymbol structure for a class.
     */
    private fun createBaseClassDocumentSymbol(
        classSymbol: com.github.albertocavalcante.groovylsp.ast.ClassSymbol,
        classNode: org.codehaus.groovy.ast.ClassNode,
    ): DocumentSymbol = DocumentSymbol().apply {
        name = classSymbol.name
        kind = when {
            classNode.isInterface -> SymbolKind.Interface
            classNode.isEnum -> SymbolKind.Enum
            classNode.isAnnotationDefinition -> SymbolKind.Namespace // Closest to annotation
            else -> SymbolKind.Class
        }

        // Class range from start line to last line
        range = createRange(
            startLine = classSymbol.line,
            startChar = classSymbol.column,
            endLine = classNode.lastLineNumber - 1, // Convert to 0-based
            endChar = 0,
        )

        // Selection range is just the class name
        selectionRange = createRange(
            startLine = classSymbol.line,
            startChar = classSymbol.column,
            endLine = classSymbol.line,
            endChar = classSymbol.column + classSymbol.name.length,
        )

        detail = buildString {
            if (classNode.isInterface) {
                append("interface ")
            } else if (classNode.isEnum) {
                append("enum ")
            } else if (classNode.isAnnotationDefinition) {
                append("@interface ")
            } else {
                append("class ")
            }
            append(classSymbol.name)

            if (classSymbol.packageName != null) {
                append(" (${classSymbol.packageName})")
            }
        }

        children = mutableListOf<DocumentSymbol>()
    }

    /**
     * Adds method symbols to a class DocumentSymbol.
     */
    private fun addMethodsToClassSymbol(documentSymbol: DocumentSymbol, classNode: org.codehaus.groovy.ast.ClassNode) {
        val methodSymbols = SymbolExtractor.extractMethodSymbols(classNode)
        methodSymbols.forEach { methodSymbol ->
            val methodDocumentSymbol = DocumentSymbol().apply {
                name = methodSymbol.name
                kind = if (methodSymbol.name == "<init>") SymbolKind.Constructor else SymbolKind.Method

                range = createRange(
                    startLine = methodSymbol.line,
                    startChar = methodSymbol.column,
                    endLine = methodSymbol.line,
                    endChar = methodSymbol.column + methodSymbol.name.length + 20, // Approximate method length
                )

                selectionRange = createRange(
                    startLine = methodSymbol.line,
                    startChar = methodSymbol.column,
                    endLine = methodSymbol.line,
                    endChar = methodSymbol.column + methodSymbol.name.length,
                )

                detail = buildMethodSignature(methodSymbol)
            }
            documentSymbol.children.add(methodDocumentSymbol)
        }
    }

    /**
     * Adds field symbols to a class DocumentSymbol.
     */
    private fun addFieldsToClassSymbol(documentSymbol: DocumentSymbol, classNode: org.codehaus.groovy.ast.ClassNode) {
        val fieldSymbols = SymbolExtractor.extractFieldSymbols(classNode)
        fieldSymbols.forEach { fieldSymbol ->
            val fieldDocumentSymbol = DocumentSymbol().apply {
                name = fieldSymbol.name
                kind = SymbolKind.Field

                range = createRange(
                    startLine = fieldSymbol.line,
                    startChar = fieldSymbol.column,
                    endLine = fieldSymbol.line,
                    endChar = fieldSymbol.column + fieldSymbol.name.length + 20, // Approximate field length
                )

                selectionRange = createRange(
                    startLine = fieldSymbol.line,
                    startChar = fieldSymbol.column,
                    endLine = fieldSymbol.line,
                    endChar = fieldSymbol.column + fieldSymbol.name.length,
                )

                detail = buildFieldSignature(fieldSymbol)
            }
            documentSymbol.children.add(fieldDocumentSymbol)
        }
    }

    /**
     * Builds a human-readable method signature.
     */
    private fun buildMethodSignature(methodSymbol: com.github.albertocavalcante.groovylsp.ast.MethodSymbol): String =
        buildString {
            append(methodSymbol.returnType)
            append(" ")
            append(methodSymbol.name)
            append("(")
            append(methodSymbol.parameters.joinToString(", ") { "${it.type} ${it.name}" })
            append(")")
        }

    /**
     * Builds a human-readable field signature.
     */
    private fun buildFieldSignature(fieldSymbol: com.github.albertocavalcante.groovylsp.ast.FieldSymbol): String =
        buildString {
            if (fieldSymbol.isStatic) append("static ")
            if (fieldSymbol.isFinal) append("final ")

            when {
                fieldSymbol.isPrivate -> append("private ")
                fieldSymbol.isProtected -> append("protected ")
                fieldSymbol.isPublic -> append("public ")
            }

            append(fieldSymbol.type)
            append(" ")
            append(fieldSymbol.name)
        }

    /**
     * Creates a Range from line/character coordinates.
     */
    private fun createRange(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range = Range(
        Position(startLine, startChar),
        Position(endLine, endChar),
    )
}
