package com.github.albertocavalcante.gvy.gls.types

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.semantics.SemanticType
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Part of #1525: a method call on a class declared in another workspace file is typed from the workspace index.
 */
class CrossFileMethodReturnTypeTest {

    private lateinit var compilationService: GroovyCompilationService

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
    }

    @AfterEach
    fun tearDown() {
        compilationService.clearCaches()
    }

    @Test
    fun `method call on class from another file has the declared return type`(): Unit = runBlocking {
        val moneyUri = URI.create("file:///demo/money/Money.groovy")
        val orderUri = URI.create("file:///demo/order/Order.groovy")
        compilationService.compile(
            moneyUri,
            """
            package demo.money
            class Money {
                BigDecimal amount
                String currency() { 'BRL' }
                Money plus(Money other) { this }
            }
            """.trimIndent(),
        )
        compilationService.compile(
            orderUri,
            """
            package demo.order
            import demo.money.Money
            class Order {
                void run(Money money) {
                    def c = money.currency()
                    def p = money.plus(money)
                }
            }
            """.trimIndent(),
        )

        val resolver = SemanticTypeResolver(
            ReflectionTypeSolver(),
            compilationService.getWorkspaceSymbolIndex(),
        )
        val module = checkNotNull(compilationService.getAst(orderUri) as? ModuleNode)
        val calls = collectCalls(module)

        assertThat(resolver.resolveType(calls.getValue("currency"), module))
            .isEqualTo(SemanticType.Known("java.lang.String"))
        assertThat(resolver.resolveType(calls.getValue("plus"), module))
            .isEqualTo(SemanticType.Known("demo.money.Money"))
    }

    private fun collectCalls(module: ModuleNode): Map<String, MethodCallExpression> {
        val found = mutableMapOf<String, MethodCallExpression>()
        val visitor = object : ClassCodeVisitorSupport() {
            override fun getSourceUnit(): SourceUnit? = null
            override fun visitMethodCallExpression(call: MethodCallExpression) {
                found[call.methodAsString.orEmpty()] = call
                super.visitMethodCallExpression(call)
            }
        }
        module.classes.forEach { visitor.visitClass(it) }
        return found
    }
}
