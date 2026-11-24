// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

/**
 * A symbol id in the SemanticDB-style grammar the indexer emits, with the one parser and the one formatter for it.
 *
 * ```
 * class            com/example/Outer$Inner#                  (default package: Foo#)
 * field, property  com/example/MyClass#name.
 * method           com/example/MyClass#name(String,int).     (constructor: name is <init>)
 * parameter        com/example/MyClass#name(String,int).(param)
 * local            com/example/MyClass#name(String,int).local@line:column
 * initializer      com/example/MyClass#{init}.local@line:column
 * import           import#com/example/MyClass                (static: import#com/example/MyClass.member)
 * ```
 *
 * Escaping is gvy's own (a backslash before a reserved character); it is not SemanticDB's backtick quoting. Only
 * member names are escaped. Class and owner names are written as they are and are assumed to hold none of `#`,
 * `(`, `)`, `\` or a `.` in the simple name; Groovy source cannot declare such a class (not verified for
 * bytecode-only classes).
 *
 * A class name is written with `/` for packages and `$` for nesting. The descriptor lists the erased simple
 * parameter type names; generics may appear in ids built elsewhere, so commas inside `<...>` do not separate.
 *
 * Parsing is total: every string is a [Symbol] and no accessor fails. For an id without a backslash an accessor
 * answers what the callers that parsed ids before this type existed got for the same text, including for text no
 * formatter produces; a backslash is now an escape, so ids containing one read differently from those parsers. A
 * malformed id yields a fallback (documented per accessor), never an exception or a null that says "invalid".
 * [owner] splits at the first unescaped `#`; [member] reads after the last unescaped one.
 *
 * Names are escaped: Groovy and Spock allow method names that contain `#`, `.` or `(` (`def "fixes #12"()`). The
 * formatters write a backslash before each of `\`, `#`, `.`, `(`, `)` and the backtick in a member name
 * (`a/B#fixes \#12().`); the accessors skip an escaped character when they look for a separator, and [member]
 * unescapes. A name without those characters is written as it always was. An id written before escaping existed
 * for a name that holds one of them is not read back correctly.
 */
@JvmInline
value class Symbol(val id: String) {

    /**
     * The class part: the text before the first unescaped `#` (`com/example/MyClass`), or the whole id when it
     * has none. For an import id this is `import`.
     */
    val owner: String
        get() = indexOfUnescaped(id, '#').let { if (it < 0) id else id.substring(0, it) }

    /** [owner] as a dotted name (`com.example.MyClass`); the `$` of a nested class is kept. */
    val ownerClassName: String
        get() = owner.replace('/', '.')

    /**
     * The simple name of the member, read after the last unescaped `#`: `` `<init>` `` is `<init>`; otherwise the
     * text before the first unescaped `(`, else before the first unescaped `.`, else the rest. A class id has the
     * empty member; a parameter or a local reads as its method. The name is unescaped. The whole id when there is
     * no `#`.
     */
    val member: String
        get() {
            val hash = lastIndexOfUnescaped(id, '#')
            if (hash < 0) return id
            val part = id.substring(hash + 1)
            if (part.startsWith('`')) {
                val close = part.indexOf('`', 1)
                if (close > 0) return part.substring(1, close)
            }
            val paren = indexOfUnescaped(part, '(')
            val dot = indexOfUnescaped(part, '.')
            val name = when {
                paren >= 0 -> part.substring(0, paren)
                dot >= 0 -> part.substring(0, dot)
                else -> part
            }
            return unescape(name)
        }

    /**
     * The text between the first unescaped `(` and the first unescaped `)`, or null when the id has no such pair
     * in that order. Empty for a method without parameters.
     */
    val descriptor: String?
        get() {
            val open = indexOfUnescaped(id, '(')
            val close = indexOfUnescaped(id, ')')
            return if (open < 0 || close < 0 || open >= close) null else id.substring(open + 1, close)
        }

    /**
     * The number of parameters: 0 without a [descriptor] or for an empty one; otherwise the top-level commas plus
     * one, so empty pieces count (`(a,)` is 2). With unbalanced `<>` it counts the non-blank comma-separated pieces.
     */
    val arity: Int
        get() {
            val params = descriptor.orEmpty()
            return if (params.isEmpty()) 0 else topLevelCommas(params)?.plus(1) ?: nonBlankPieces(params)
        }

    /**
     * The parameter types as written, trimmed, in order. Empty without a [descriptor] or for a blank one. Empty
     * middle pieces stay (`(a,,b)` is `a`, ``, `b`), a trailing one is dropped, unlike [arity]. With unbalanced
     * `<>` the non-blank comma-separated pieces.
     */
    val parameterTypes: List<String>
        get() {
            val params = descriptor.orEmpty().trim()
            if (params.isEmpty()) return emptyList()
            val split = splitTopLevel(params)
            return if (split.balanced) {
                val pieces = if (split.pieces.last().isEmpty()) split.pieces.dropLast(1) else split.pieces
                pieces.map { it.trim() }
            } else {
                params.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

    override fun toString(): String = id

    companion object {
        /** The class id of [className], given with `.` or `/` between packages: `a.b.C` is `a/b/C#`. */
        fun ofClass(className: String): Symbol = Symbol("${className.replace('.', '/')}#")

        /** The class id of [internalName], taken as written: `a/b/C` is `a/b/C#`, with no `.` to `/` conversion. */
        fun ofInternalName(internalName: String): Symbol = Symbol("$internalName#")

        /** The id of the field or property [name] of [owner] (a class id): `a/b/C#name.`. */
        fun ofField(owner: Symbol, name: String): Symbol = Symbol("${owner.id}${escape(name)}.")

        /**
         * The id of a method or constructor. [parameters] is the already-written descriptor (`String,int`); a
         * method without a class ([owner] null) has no class part.
         */
        fun ofMethod(owner: Symbol?, name: String, parameters: String): Symbol =
            Symbol("${owner?.id.orEmpty()}${escape(name)}($parameters).")

        /** The id of the parameter [name] of [method]: `a/b/C#m(int).(name)`. */
        fun ofParameter(method: Symbol, name: String): Symbol = Symbol("${method.id}(${escape(name)})")

        /** The id of the initializer scope of [owner], which locals outside any method hang from: `a/b/C#{init}.`. */
        fun ofInitializer(owner: Symbol): Symbol = Symbol("${owner.id}{init}.")

        /** The id of the local [name] declared at [line]:[column] (0-based) in [scope] (a method or initializer). */
        fun ofLocal(scope: Symbol, name: String, line: Int, column: Int): Symbol =
            Symbol("${scope.id}${escape(name)}@$line:$column")

        /** The id of an import of [className] (`a.b.C`; a star import passes the package). */
        fun ofImport(className: String): Symbol = Symbol("import#${className.replace('.', '/')}")

        /** The id of a static import of [member] of [className]. */
        fun ofStaticImport(className: String, member: String): Symbol =
            Symbol("import#${className.replace('.', '/')}.${escape(member)}")
    }
}

private const val ESCAPE = '\\'

/** The characters a member name must not hold unescaped: the separators of the grammar, the quote, and [ESCAPE]. */
private const val RESERVED = "\\#.()`"

private fun escape(name: String): String {
    if (name.none { it in RESERVED }) return name
    return buildString(name.length + 2) {
        for (char in name) {
            if (char in RESERVED) append(ESCAPE)
            append(char)
        }
    }
}

/** Drops each [ESCAPE] and keeps the character after it; a trailing [ESCAPE] stays. Total: never fails. */
private fun unescape(text: String): String {
    if (ESCAPE !in text) return text
    return buildString(text.length) {
        var index = 0
        while (index < text.length) {
            if (text[index] == ESCAPE && index + 1 < text.length) index++
            append(text[index])
            index++
        }
    }
}

/** The index of the first [target] that is not the character after an [ESCAPE], or -1. */
private fun indexOfUnescaped(text: String, target: Char): Int {
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            ESCAPE -> index++
            target -> return index
        }
        index++
    }
    return -1
}

/** The index of the last [target] that is not the character after an [ESCAPE], or -1. */
private fun lastIndexOfUnescaped(text: String, target: Char): Int {
    var last = -1
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            ESCAPE -> index++
            target -> last = index
        }
        index++
    }
    return last
}

/** The commas outside `<...>`, counted without building pieces; null when the brackets do not pair up. */
private fun topLevelCommas(params: String): Int? {
    var depth = 0
    var commas = 0
    for (char in params) {
        when {
            char == '<' -> depth++
            char == '>' && depth == 0 -> return null
            char == '>' -> depth--
            char == ',' && depth == 0 -> commas++
        }
    }
    return commas.takeIf { depth == 0 }
}

private fun nonBlankPieces(params: String): Int = params.split(',').count { it.isNotBlank() }

/** The pieces of a descriptor at depth zero of `<...>`; [balanced] is false when the brackets do not pair up. */
private class TopLevelSplit(val pieces: List<String>, val balanced: Boolean)

private fun splitTopLevel(params: String): TopLevelSplit {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var balanced = true
    for (char in params) {
        if (char == '>' && depth == 0) {
            balanced = false
            break
        }
        when (char) {
            '<' -> depth++
            '>' -> depth--
        }
        if (char == ',' && depth == 0) {
            pieces.add(current.toString())
            current.clear()
        } else {
            current.append(char)
        }
    }
    pieces.add(current.toString())
    return TopLevelSplit(pieces, balanced && depth == 0)
}
