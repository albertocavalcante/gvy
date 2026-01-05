package com.github.albertocavalcante.groovyparser.api.model

/**
 * Information about an AST node at a position.
 */
data class NodeInfo(val kind: NodeKind, val name: String?, val range: Range, val text: String? = null)

/**
 * Kind of AST node.
 */
enum class NodeKind {
    // Declarations
    CLASS,
    INTERFACE,
    ENUM,
    TRAIT,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PROPERTY,
    PARAMETER,
    VARIABLE,

    // Expressions
    METHOD_CALL,
    PROPERTY_ACCESS,
    VARIABLE_REFERENCE,
    LITERAL,
    BINARY_EXPRESSION,
    UNARY_EXPRESSION,
    CLOSURE,
    LIST,
    MAP,
    RANGE,

    // Statements
    IF,
    FOR,
    WHILE,
    SWITCH,
    TRY,
    RETURN,
    THROW,
    ASSERT,
    BLOCK,

    // Other
    IMPORT,
    PACKAGE,
    ANNOTATION,
    COMMENT,
    UNKNOWN,
}
