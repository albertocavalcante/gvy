// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

/**
 * What an import statement binds, independent of where it sits in the file.
 *
 * Names are normalized so that the same import read from the text and from the compiler AST compares equal:
 * `$` nested-class separators become `.`, and an alias equal to the simple name is no alias.
 */
internal sealed interface ImportTarget {
    /** `import a.B`, `import a.B as C` */
    @ConsistentCopyVisibility
    data class Type private constructor(val qualifiedName: String, val alias: String?) : ImportTarget {
        /** The name the code uses to refer to the type. */
        val bindingName: String get() = alias ?: qualifiedName.substringAfterLast('.')

        companion object {
            fun of(qualifiedName: String, alias: String?): Type {
                val name = normalizeName(qualifiedName)
                return Type(name, alias?.takeUnless { it == name.substringAfterLast('.') })
            }
        }
    }

    /** `import a.*`: never proven unused, so never removed. */
    data class Star(val packageName: String) : ImportTarget

    /** `import static a.B.member`, `import static a.B.member as m` */
    @ConsistentCopyVisibility
    data class StaticMember private constructor(val className: String, val member: String, val alias: String?) :
        ImportTarget {
        /** The name the code uses to refer to the member. */
        val bindingName: String get() = alias ?: member

        companion object {
            fun of(className: String, member: String, alias: String?): StaticMember =
                StaticMember(normalizeName(className), member, alias?.takeUnless { it == member })
        }
    }

    /** `import static a.B.*`: never proven unused, so never removed. */
    data class StaticStar(val className: String) : ImportTarget
}

/** An import the compiler-backed detector proved unused, located by the 0-based line its statement starts on. */
internal data class UnusedImport(val target: ImportTarget, val line: Int)

private fun normalizeName(name: String): String = name.replace('$', '.')
