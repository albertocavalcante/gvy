package com.github.albertocavalcante.gvy.semantics.delegation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for DelegationResolver - resolving implicit receivers in closures.
 * Based on IntelliJ's delegatesTo package pattern.
 *
 * TODO(#638): These tests require real AST context from parsed Groovy code.
 * They are disabled until the integration with the parser is complete.
 */
class DelegationResolverTest {

    @Test
    fun `DelegationStrategy has correct values`() {
        assertEquals(0, DelegationStrategy.OWNER_FIRST.value)
        assertEquals(1, DelegationStrategy.DELEGATE_FIRST.value)
        assertEquals(2, DelegationStrategy.OWNER_ONLY.value)
        assertEquals(3, DelegationStrategy.DELEGATE_ONLY.value)
        assertEquals(4, DelegationStrategy.TO_SELF.value)
    }

    @Test
    fun `DelegationStrategy fromValue returns correct strategy`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(0))
        assertEquals(DelegationStrategy.DELEGATE_FIRST, DelegationStrategy.fromValue(1))
        assertEquals(DelegationStrategy.OWNER_ONLY, DelegationStrategy.fromValue(2))
        assertEquals(DelegationStrategy.DELEGATE_ONLY, DelegationStrategy.fromValue(3))
        assertEquals(DelegationStrategy.TO_SELF, DelegationStrategy.fromValue(4))
    }

    @Test
    fun `DelegationStrategy fromValue returns default for unknown value`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(99))
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.fromValue(-1))
    }

    @Test
    fun `DelegationStrategy DEFAULT is OWNER_FIRST`() {
        assertEquals(DelegationStrategy.OWNER_FIRST, DelegationStrategy.DEFAULT)
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves this in closure - closure's own methods`() {
        // Given: { method() } where method is defined on the closure itself
        // When: resolving the method call without explicit receiver
        // Then: should find method on 'this' (the closure)
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves owner when this fails`() {
        // Given: class Foo { def bar() { { method() } } }
        // When: method() is not on closure, check owner (Foo)
        // Then: should resolve to Foo.method()
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves delegate when set`() {
        // Given: closure.delegate = builder; closure.call()
        // When: resolving name() inside closure
        // Then: should check builder.name()
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects DelegatesTo annotation`() {
        // Given: void run(@DelegatesTo(Builder) Closure c)
        // When: c is called with { name = 'foo' }
        // Then: name should resolve to Builder.name
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects OWNER_FIRST strategy`() {
        // Given: closure with delegate set, strategy = OWNER_FIRST
        // When: both owner and delegate have method 'foo'
        // Then: should resolve to owner.foo
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `respects DELEGATE_FIRST strategy`() {
        // Given: closure with delegate set, strategy = DELEGATE_FIRST
        // Note: DELEGATE_FIRST is NOT the default. OWNER_FIRST is the default per Groovy spec:
        // https://docs.groovy-lang.org/latest/html/documentation/core-closures.html#_delegation_strategy
        // When: both owner and delegate have method 'foo'
        // Then: should resolve to delegate.foo
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves Gradle DSL dependencies block`() {
        // Given: dependencies { implementation 'foo' }
        // When: resolving 'implementation' method
        // Then: should resolve to DependencyHandler.implementation
    }

    @Test
    @Disabled("Requires real AST context - see TODO #638")
    fun `resolves Jenkins pipeline sh step`() {
        // Given: pipeline { stage('Build') { sh 'make' } }
        // When: resolving 'sh' method
        // Then: should resolve to pipeline step
    }
}
