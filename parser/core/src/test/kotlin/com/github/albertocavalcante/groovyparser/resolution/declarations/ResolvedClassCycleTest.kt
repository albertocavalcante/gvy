// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.resolution.declarations

import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

/** Assignability walks the other class's superclasses; a cycle among them must end the walk (#1411). */
class ResolvedClassCycleTest {
    private class FakeClass(override val qualifiedName: String) : ResolvedClassDeclaration {
        var parent: FakeClass? = null
        var implemented: List<FakeClass> = emptyList()

        override val name: String get() = qualifiedName.substringAfterLast('.')
        override val packageName: String get() = qualifiedName.substringBeforeLast('.', "")
        override val superClass: ResolvedReferenceType? get() = parent?.let(::ResolvedReferenceType)
        override val interfaces: List<ResolvedReferenceType> get() = implemented.map(::ResolvedReferenceType)
        override fun isAbstract(): Boolean = false
        override fun isFinal(): Boolean = false
        override fun getAncestors(): List<ResolvedReferenceType> = listOfNotNull(superClass) + interfaces
        override fun getDeclaredFields(): List<ResolvedFieldDeclaration> = emptyList()
        override fun getDeclaredMethods(): List<ResolvedMethodDeclaration> = emptyList()
        override fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration> = emptyList()
    }

    /** `class A extends B {}; class B extends A {}` */
    private val a = FakeClass("p.A")
    private val b = FakeClass("p.B").also { it.parent = a }.also { a.parent = it }

    private fun <T> inTime(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS), block)

    @Test
    fun `a class outside the cycle is not assignable from a class in it`() {
        val target = FakeClass("p.Target")

        assertThat(inTime { target.isAssignableBy(a) }).isFalse()
    }

    @Test
    fun `a class in the cycle is still found as an ancestor`() {
        assertThat(inTime { a.isAssignableBy(b) }).isTrue()
    }

    @Test
    fun `an interface of a class in the cycle is still found`() {
        val marker = FakeClass("p.Marker")
        b.implemented = listOf(marker)

        assertThat(inTime { marker.isAssignableBy(a) }).isTrue()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
