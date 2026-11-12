// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/** Converting classes whose superclasses form a cycle finishes, and none of them reads as a script (#1411). */
class DeclarationConverterCycleTest {
    @Test
    fun `a superclass cycle converts without hanging`() {
        val result = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            GroovyParser().parse("class A extends B {}\nclass B extends A {}\n")
        }

        val classes = result.result.orElseThrow().types.filterIsInstance<ClassDeclaration>()
        assertThat(classes.map { it.name }).containsExactlyInAnyOrder("A", "B")
        assertThat(classes).noneMatch { it.isScript }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
