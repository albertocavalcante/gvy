// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import java.net.URI

/**
 * Per-file semantic information extracted from compiled AST.
 * Inspired by Metals/SemanticDB for cross-file symbol resolution.
 *
 * @property uri The file URI this document represents
 * @property symbols All symbols defined in this file (classes, methods, fields, etc.)
 * @property occurrences All symbol usages in this file (references, calls, etc.)
 * @property unresolvedNames The uncertain uses in this file: names it uses that the index did not attribute to a
 *   declaration, and `"*"` when a site's name is not known statically. A rename of `n` may skip this file only
 *   when neither `n` nor `"*"` is here. It holds:
 *   - the name of every call, property read, bare name, attribute read, spread read, method pointer, static call
 *     (static imports and static-context calls, whose members are also named by the import) and annotation member
 *     left unattributed, whatever the reason;
 *   - accessor forms of an unattributed property use: `p.name` records `name` and, as a read, `getName` and
 *     `isName`, as a write `setName` (both for `p.name += 1`), so renaming a getter or setter finds it; the keys
 *     of named arguments (`new P(target: 1)`) record the key and its setter;
 *   - the method an operator dispatches to (`+` is `plus`, `<`/`==` is `compareTo`/`equals`, `in`/`case` is
 *     `isCase`, `for (x in o)` is `iterator`, `++` is `next`, `-a` is `negative`, `as` is `asType`, `o[i]` is
 *     `getAt`/`putAt`), unless both operands are numbers or one is `null`;
 *   - a constant name given to `invokeMethod`/`getProperty`/`setProperty`/`respondsTo`/`hasProperty`;
 *   - `"*"` for a computed member name, a computed name given to those methods, an index on an object that is not
 *     known to be a Map, List or array, and a class whose hierarchy answers calls or reads by itself
 *     (`methodMissing`, `propertyMissing`, `invokeMethod`, `getProperty`, `setProperty`, `GroovyInterceptable`,
 *     `@Delegate`, `@Mixin`), see [GroovyDispatch.dispatchesByItself].
 *
 *   - the property an accessor-shaped unattributed call may stand for, the other direction: `o.getTarget()`
 *     records `getTarget` and `target` (both the JavaBeans spelling, which keeps `URL`, and `uRL`).
 *
 *   Bodies of trait methods and of anonymous classes are collected too (they get no symbols or occurrences);
 *   names the compiler generated (no source position, or a `$` name inside a trait helper) add nothing.
 */
data class SemanticDocument(
    val uri: URI,
    val symbols: List<SymbolInfo>,
    val occurrences: List<SymbolOccurrence>,
    val unresolvedNames: Set<String> = emptySet(),
) {
    /**
     * Find a symbol by its unique ID
     */
    fun findSymbol(symbolId: String): SymbolInfo? = symbols.find { it.symbol == symbolId }

    /**
     * Find all occurrences of a symbol
     */
    fun findOccurrences(symbolId: String): List<SymbolOccurrence> = occurrences.filter { it.symbol == symbolId }

    /**
     * Find symbols by kind
     */
    fun findSymbolsByKind(kind: SymbolKind): List<SymbolInfo> = symbols.filter { it.kind == kind }

    /**
     * Find occurrences by role
     */
    fun findOccurrencesByRole(role: OccurrenceRole): List<SymbolOccurrence> = occurrences.filter { it.role == role }
}

/**
 * Information about a defined symbol (class, method, field, etc.)
 *
 * @property symbol Unique symbol ID following SemanticDB convention (e.g., "com/example/MyClass#myMethod().")
 * @property kind The kind of symbol (CLASS, METHOD, FIELD, etc.)
 * @property range Location where this symbol is defined
 * @property name Simple name of the symbol (e.g., "myMethod")
 * @property owner Owner symbol ID (e.g., for a method, the class ID)
 * @property type The semantic type of the symbol (field type, method return type, variable type), null if not available
 */
data class SymbolInfo(
    val symbol: String,
    val kind: SymbolKind,
    val range: Range,
    val name: String,
    val owner: String?,
    val type: com.github.albertocavalcante.gvy.semantics.SemanticType? = null,
)

/**
 * A usage/reference to a symbol
 *
 * @property symbol Reference to the SymbolInfo.symbol ID
 * @property range Location of this usage
 * @property role How this symbol is being used (DEFINITION, REFERENCE, CALL, etc.)
 */
data class SymbolOccurrence(val symbol: String, val range: Range, val role: OccurrenceRole)

/**
 * An occurrence and its source text captured together under the database's read lock.
 * [sourceText] is null for documents stored through the metadata-only update overload.
 */
data class IndexedOccurrence(val uri: URI, val occurrence: SymbolOccurrence, val sourceText: String?)

/** Immutable semantic metadata and source captured from the same stored compilation. */
data class SemanticDocumentSnapshot(val document: SemanticDocument, val sourceText: String?)

/**
 * Symbol kinds following LSP SymbolKind convention
 */
enum class SymbolKind {
    CLASS,
    INTERFACE,
    METHOD,
    FIELD,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    IMPORT,
    CONSTRUCTOR,
    ENUM,
    ENUM_MEMBER,
}

/**
 * How a symbol is being used at a particular location
 */
enum class OccurrenceRole {
    DEFINITION, // Where the symbol is defined
    REFERENCE, // Reference to the symbol (reading a variable, field access)
    CALL, // Method/constructor call
    TYPE_REF, // Type reference (in type annotations, instanceof, etc.)
    IMPORT, // Import statement
    WRITE, // Writing to a variable/field
}

/**
 * Position range in a file (0-indexed)
 *
 * @property startLine Starting line (0-indexed)
 * @property startColumn Starting column (0-indexed)
 * @property endLine Ending line (0-indexed)
 * @property endColumn Ending column (0-indexed, exclusive)
 */
data class Range(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    init {
        require(startLine >= 0) { "startLine must be >= 0, got $startLine" }
        require(startColumn >= 0) { "startColumn must be >= 0, got $startColumn" }
        require(endLine >= 0) { "endLine must be >= 0, got $endLine" }
        require(endColumn >= 0) { "endColumn must be >= 0, got $endColumn" }
        require(startLine <= endLine) { "startLine must be <= endLine, got $startLine > $endLine" }
        if (startLine == endLine) {
            require(startColumn < endColumn) {
                "When on same line, startColumn must be < endColumn, got $startColumn >= $endColumn"
            }
        }
    }

    /**
     * Check if this range contains a position
     */
    fun contains(line: Int, column: Int): Boolean {
        if (line < startLine || line > endLine) return false
        if (line == startLine && column < startColumn) return false
        if (line == endLine && column >= endColumn) return false
        return true
    }

    /**
     * Check if this range overlaps with another range
     */
    fun overlaps(other: Range): Boolean {
        // No overlap if one range ends before the other starts
        if (endLine < other.startLine) return false
        if (other.endLine < startLine) return false

        // Check for overlap on same line
        if (endLine == other.startLine && endColumn <= other.startColumn) return false
        if (other.endLine == startLine && other.endColumn <= startColumn) return false

        return true
    }
}
