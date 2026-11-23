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
 * A class name is written with `/` for packages and `$` for nesting. The descriptor lists the erased simple
 * parameter type names; generics may appear in ids built elsewhere, so commas inside `<...>` do not separate.
 *
 * Parsing is total: every string is a [Symbol] and no accessor fails. An accessor answers what the callers that
 * parsed ids before this type existed got for the same text, including for text no formatter produces, so a
 * malformed id yields a fallback (documented per accessor), never an exception or a null that says "invalid".
 * [owner] splits at the first `#`; [member] reads after the last one.
 *
 * Known limitation (FIXME(#2412)): the formatters do not escape. Groovy and Spock allow method names that contain
 * `#`, `.` or `(` (`def "fixes #12"()`), and the id `a/B#fixes #12().` then has two `#`: [member] reads `12`, not
 * `fixes #12`. Such ids are formatted and parsed as before this type existed; [ofMethod] is not injective for them.
 */
@JvmInline
value class Symbol(val id: String) {

    /**
     * The class part: the text before the first `#` (`com/example/MyClass`), or the whole id when it has none.
     * For an import id this is `import`.
     */
    val owner: String
        get() = id.substringBefore('#')

    /** [owner] as a dotted name (`com.example.MyClass`); the `$` of a nested class is kept. */
    val ownerClassName: String
        get() = owner.replace('/', '.')

    /**
     * The simple name of the member, read after the last `#`: `` `<init>` `` is `<init>`; otherwise the text before
     * the first `(`, else before the first `.`, else the rest. A class id has the empty member; a parameter or a
     * local reads as its method. The whole id when there is no `#`.
     */
    val member: String
        get() {
            val hash = id.lastIndexOf('#')
            if (hash < 0) return id
            val part = id.substring(hash + 1)
            if (part.startsWith('`')) {
                val close = part.indexOf('`', 1)
                if (close > 0) return part.substring(1, close)
            }
            val paren = part.indexOf('(')
            val dot = part.indexOf('.')
            return when {
                paren >= 0 -> part.substring(0, paren)
                dot >= 0 -> part.substring(0, dot)
                else -> part
            }
        }

    /**
     * The text between the first `(` and the first `)`, or null when the id has no such pair in that order.
     * Empty for a method without parameters.
     */
    val descriptor: String?
        get() {
            val open = id.indexOf('(')
            val close = id.indexOf(')')
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
        fun ofField(owner: Symbol, name: String): Symbol = Symbol("${owner.id}$name.")

        /**
         * The id of a method or constructor. [parameters] is the already-written descriptor (`String,int`); a
         * method without a class ([owner] null) has no class part.
         */
        fun ofMethod(owner: Symbol?, name: String, parameters: String): Symbol =
            Symbol("${owner?.id.orEmpty()}$name($parameters).")

        /** The id of the parameter [name] of [method]: `a/b/C#m(int).(name)`. */
        fun ofParameter(method: Symbol, name: String): Symbol = Symbol("${method.id}($name)")

        /** The id of the initializer scope of [owner], which locals outside any method hang from: `a/b/C#{init}.`. */
        fun ofInitializer(owner: Symbol): Symbol = Symbol("${owner.id}{init}.")

        /** The id of the local [name] declared at [line]:[column] (0-based) in [scope] (a method or initializer). */
        fun ofLocal(scope: Symbol, name: String, line: Int, column: Int): Symbol =
            Symbol("${scope.id}$name@$line:$column")

        /** The id of an import of [className] (`a.b.C`; a star import passes the package). */
        fun ofImport(className: String): Symbol = Symbol("import#${className.replace('.', '/')}")

        /** The id of a static import of [member] of [className]. */
        fun ofStaticImport(className: String, member: String): Symbol =
            Symbol("import#${className.replace('.', '/')}.$member")
    }
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
