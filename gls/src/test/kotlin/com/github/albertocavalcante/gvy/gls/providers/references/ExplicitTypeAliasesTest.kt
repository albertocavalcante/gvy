// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ExplicitTypeAliasesTest {
    @Test
    fun `alias proof uses import tokens rather than default alias metadata or comment text`() {
        val cases = mapOf(
            "import p.Foo" to false,
            "import p.Foo /* as Foo */" to false,
            "import p.Foo as Foo" to true,
            "import p.Foo /* 😀 as Nope */ as /* Foo */ Foo" to true,
            "import p.Foo as Other /* as Foo */" to true,
        )
        cases.forEach { (header, expected) ->
            val source = "package p\n$header\nclass Foo {}\nclass Unrelated {}\n"
            val unit = CompilationUnit()
            unit.addSource("AliasCases.groovy", source)
            unit.compile(Phases.SEMANTIC_ANALYSIS)
            val module = unit.ast.modules.single()
            val index = TextIndex(source)

            assertEquals(
                expected,
                module.hasExplicitTypeAlias(
                    module.classes.first {
                        it.name == "p.Foo"
                    },
                    index,
                ),
                header,
            )
            assertFalse(module.hasExplicitTypeAlias(module.classes.first { it.name == "p.Unrelated" }, index), header)
        }
    }
}
