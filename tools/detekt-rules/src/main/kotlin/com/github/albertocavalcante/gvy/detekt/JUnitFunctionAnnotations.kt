// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtUserType

/**
 * The spellings that name a JUnit test or lifecycle annotation in one file, for the rules about the functions JUnit
 * calls (#2507).
 *
 * JUnit calls a function annotated `@Test`, `@ParameterizedTest`, `@RepeatedTest` or `@TestTemplate`, and the
 * lifecycle functions around it (`@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@AfterAll`), and requires each of them to
 * return nothing. `kotlin.test` and JUnit 4 spell the same roles `@Test`, `@BeforeTest`, `@AfterTest`, `@Before`,
 * `@After`, `@BeforeClass` and `@AfterClass`. `@TestFactory` is not one of them: it must return its tests.
 *
 * The lookup is purely syntactic (PSI only) because the pull-request `lint` gate runs detekt without type resolution
 * (#2409). An annotation counts when the file spells its fully qualified name, imports it (under its own name or an
 * alias), or star-imports its package. A plain `@Test` in a file that imports none of these packages is another
 * annotation and does not count, and an explicit import of another annotation takes its name from a star import, as
 * it does for the compiler. Names are read by PSI segment ([qualifiedName]), so backticks, comments and line breaks
 * inside them do not hide an annotation. An annotation that is itself annotated `@Test` (a composed annotation) and a
 * typealias of one cannot be seen. The known limits are in `tools/detekt-rules/README.md`.
 */
internal class JUnitFunctionAnnotations private constructor(private val names: Set<String>) {
    /**
     * The first JUnit test or lifecycle annotation on [function], as the file spells it without its qualifier (`Test`,
     * `BeforeEach`, or an import alias), or null when JUnit does not call the function: it has none of them, or it is
     * a local function.
     */
    fun on(function: KtNamedFunction): String? {
        // JUnit calls members and top-level functions, never a function declared inside another function's body.
        if (function.isLocal) return null
        return function.annotationEntries.firstNotNullOfOrNull { entry ->
            val name = (entry.typeReference?.typeElement as? KtUserType)?.qualifiedName()
            name?.takeIf { it in names }?.substringAfterLast('.')?.let(::spelled)
        }
    }

    /** [name] as source spells it: in backticks when it is not a plain identifier, such as an alias with a space. */
    private fun spelled(name: String): String = if (PLAIN_IDENTIFIER.matches(name)) name else "`$name`"

    companion object {
        /** The annotations JUnit calls a function for, by package. */
        private val ANNOTATIONS_BY_PACKAGE: Map<String, Set<String>> = mapOf(
            "org.junit.jupiter.api" to setOf(
                "Test",
                "RepeatedTest",
                "TestTemplate",
                "BeforeEach",
                "AfterEach",
                "BeforeAll",
                "AfterAll",
            ),
            "org.junit.jupiter.params" to setOf("ParameterizedTest"),
            "kotlin.test" to setOf("Test", "BeforeTest", "AfterTest"),
            "org.junit" to setOf("Test", "Before", "After", "BeforeClass", "AfterClass"),
        )

        private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        private val QUALIFIED_NAMES: Set<String> = ANNOTATIONS_BY_PACKAGE.flatMapTo(mutableSetOf()) { (pkg, names) ->
            names.map { "$pkg.$it" }
        }

        /** The spellings that name a JUnit test or lifecycle annotation in [file]. */
        fun of(file: KtFile): JUnitFunctionAnnotations {
            val imported = mutableSetOf<String>()
            val starImported = mutableSetOf<String>()
            val takenByOtherImports = mutableSetOf<String>()
            for (directive in file.importDirectives) {
                val qualifiedName = directive.importedFqName?.asString() ?: continue
                // An alias keeps its backticks in PSI (`as `T``). qualifiedName() drops them, so drop them here too.
                val importedName = directive.importedName?.asString()?.let(KtPsiUtil::unquoteIdentifier)
                when {
                    directive.isAllUnder -> starImported += ANNOTATIONS_BY_PACKAGE[qualifiedName].orEmpty()
                    importedName != null && qualifiedName in QUALIFIED_NAMES -> imported += importedName
                    importedName != null -> takenByOtherImports += importedName
                }
            }
            // An explicit import wins over a star import, so a name another import took is not JUnit's.
            return JUnitFunctionAnnotations(QUALIFIED_NAMES + imported + (starImported - takenByOtherImports))
        }
    }
}
