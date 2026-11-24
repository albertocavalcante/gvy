// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.GroovyCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.JenkinsCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.MemberAccessCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.SpockCompletionStrategy
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Pins which completion strategies run per mode, in which order, and that the stateless ones are shared. */
class CompletionStrategyWiringTest {
    private val service = GroovyCompilationService()

    private fun orderFor(mode: GroovyMode): List<KClass<*>> =
        CompletionProvider.buildStrategies(mode, service, null).map { it::class }

    @Test
    fun `groovy mode runs member access, spock then groovy`() {
        assertEquals(
            listOf(
                MemberAccessCompletionStrategy::class,
                SpockCompletionStrategy::class,
                GroovyCompletionStrategy::class,
            ),
            orderFor(GroovyMode.GROOVY),
        )
    }

    @Test
    fun `jenkins mode adds the jenkins strategy after member access`() {
        assertEquals(
            listOf(
                MemberAccessCompletionStrategy::class,
                JenkinsCompletionStrategy::class,
                SpockCompletionStrategy::class,
                GroovyCompletionStrategy::class,
            ),
            orderFor(GroovyMode.JENKINS),
        )
    }

    @Test
    fun `auto mode runs the same strategies as jenkins mode`() {
        assertEquals(orderFor(GroovyMode.JENKINS), orderFor(GroovyMode.AUTO))
    }

    @Test
    fun `stateless strategies are shared across requests and modes`() {
        GroovyMode.entries.forEach { mode ->
            val first = CompletionProvider.buildStrategies(mode, service, null)
            val second = CompletionProvider.buildStrategies(mode, service, null)
            // Index 0 is member access, which holds the request's compilation service.
            first.zip(second).drop(1).forEach { (a, b) ->
                assertSame(a, b, "${a::class.simpleName} was rebuilt between $mode requests")
            }
        }
        val groovy = CompletionProvider.buildStrategies(GroovyMode.GROOVY, service, null)
        val jenkins = CompletionProvider.buildStrategies(GroovyMode.JENKINS, service, null)
        assertSame(groovy.first { it is SpockCompletionStrategy }, jenkins.first { it is SpockCompletionStrategy })
    }
}
