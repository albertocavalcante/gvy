package com.github.albertocavalcante.groovyparser.api.model

/**
 * Information about a symbol (class, method, field, variable).
 */
data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val range: Range,
    val selectionRange: Range = range,
    val containerName: String? = null,
    val detail: String? = null,
)

/**
 * Kind of symbol (maps to LSP SymbolKind).
 */
enum class SymbolKind {
    FILE,
    MODULE,
    NAMESPACE,
    PACKAGE,
    CLASS,
    METHOD,
    PROPERTY,
    FIELD,
    CONSTRUCTOR,
    ENUM,
    INTERFACE,
    FUNCTION,
    VARIABLE,
    CONSTANT,
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
    KEY,
    NULL,
    ENUM_MEMBER,
    STRUCT,
    EVENT,
    OPERATOR,
    TYPE_PARAMETER,
}
