package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypeResolutionHelperTest {

    @Test
    fun `resolveDataTypes returns exact class name when loadable`() {
        val classpathService = mockk<ClasspathService>()
        every { classpathService.loadClass("Foo") } returns Any::class.java

        assertEquals(listOf("Foo"), TypeResolutionHelper.resolveDataTypes("Foo", classpathService))
    }

    @Test
    fun `resolveDataTypes tries default imports for simple name`() {
        val classpathService = mockk<ClasspathService>()
        every { classpathService.loadClass("List") } returns null
        every { classpathService.loadClass("java.util.List") } returns Any::class.java
        every { classpathService.loadClass("java.lang.List") } returns null
        every { classpathService.loadClass("java.io.List") } returns null
        every { classpathService.loadClass("java.net.List") } returns null
        every { classpathService.loadClass("groovy.lang.List") } returns null
        every { classpathService.loadClass("groovy.util.List") } returns null

        assertEquals(listOf("java.util.List"), TypeResolutionHelper.resolveDataTypes("List", classpathService))
    }

    @Test
    fun `resolveDataTypes returns empty for qualified missing class`() {
        val classpathService = mockk<ClasspathService>()
        every { classpathService.loadClass("no.such.Type") } returns null

        assertEquals(emptyList(), TypeResolutionHelper.resolveDataTypes("no.such.Type", classpathService))
    }

    @Test
    fun `refineTypeFromVariableInitializer keeps type when not Object or Class`() {
        val astModel = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()
        val expr = VariableExpression("x")

        assertEquals(
            "java.lang.String",
            TypeResolutionHelper.refineTypeFromVariableInitializer(
                inferredType = "java.lang.String",
                objectExpr = expr,
                ctx = TypeResolutionHelper.VariableInitializerRefinementContext(
                    astModel = astModel,
                    symbolTable = symbolTable,
                    logger = null,
                    inferExpressionType = { "java.util.List" },
                ),
            ),
        )
    }

    @Test
    fun `refineTypeFromVariableInitializer refines Object from initializer when resolvable`() {
        val astModel = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()

        val expr = VariableExpression("x")
        val resolved = mockk<Variable>()

        every { symbolTable.resolveSymbol(expr, astModel) } returns resolved
        every { resolved.hasInitialExpression() } returns true
        every { resolved.initialExpression } returns ConstantExpression(1)

        val refined = TypeResolutionHelper.refineTypeFromVariableInitializer(
            inferredType = "java.lang.Object",
            objectExpr = expr,
            ctx = TypeResolutionHelper.VariableInitializerRefinementContext(
                astModel = astModel,
                symbolTable = symbolTable,
                logger = null,
                inferExpressionType = { "java.lang.Integer" },
            ),
        )

        assertEquals("java.lang.Integer", refined)
    }

    @Test
    fun `refineTypeFromVariableInitializer returns null when inferred is null`() {
        val astModel = mockk<GroovyAstModel>()
        val symbolTable = mockk<SymbolTable>()

        val expr = VariableExpression("x")
        val resolved = mockk<Variable>()

        every { symbolTable.resolveSymbol(expr, astModel) } returns resolved
        every { resolved.hasInitialExpression() } returns true
        every { resolved.initialExpression } returns ConstantExpression(1)

        val refined = TypeResolutionHelper.refineTypeFromVariableInitializer(
            inferredType = null,
            objectExpr = expr,
            ctx = TypeResolutionHelper.VariableInitializerRefinementContext(
                astModel = astModel,
                symbolTable = symbolTable,
                logger = null,
                inferExpressionType = { "java.lang.Integer" },
            ),
        )

        assertNull(refined)
    }
}
