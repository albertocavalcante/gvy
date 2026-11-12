package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.GdkExtensionMethod
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.services.QualifiedGdkMethod
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import groovy.lang.groovydoc.Groovydoc
import groovy.lang.groovydoc.GroovydocHolder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MethodCallHoverResolutionTest {
    private val classpath = mockk<ClasspathService> {
        every { getMethods(any()) } returns emptyList()
    }
    private val gdk = mockk<GroovyGdkProvider> {
        every { getMethodsForType(any()) } returns emptyList()
        every { getQualifiedMethodsForType(any()) } answers {
            getMethodsForType(firstArg()).map { QualifiedGdkMethod(it, it.parameterTypes) }
        }
    }
    private val semantic = mockk<SemanticTypeResolver> {
        every { resolveType(any(), any()) } returns SemanticType.Known("demo.Repository")
        every { formatSemanticType(any()) } returns "demo.Repository"
    }
    private val resolver = MethodCallMetadataResolver(classpath, gdk, semantic)

    @Test
    fun `declared receiver method takes precedence over a GDK extension`() {
        every { classpath.getMethods("demo.Repository") } returns listOf(reflected("findAll", emptyList()))
        every { gdk.getMethodsForType("demo.Repository") } returns listOf(
            GdkExtensionMethod("findAll", "List", emptyList(), emptyList(), "DefaultGroovyMethods", "extension"),
        )

        val metadata = resolver.resolveMethodCall(call("findAll"), null)

        assertEquals("demo.Repository", metadata?.declaringClass)
    }

    @Test
    fun `JDK targets retain reflected parameter names and documentation before GDK`() {
        val member = reflected("startsWith", listOf("String")).copy(
            declaringClass = "java.lang.String",
            returnType = "boolean",
            parameterNames = listOf("prefix"),
            doc = "Tests whether this string begins with the supplied prefix.",
        )
        every { classpath.getMethods("java.lang.String") } returns listOf(member)
        every { gdk.getMethodsForType("java.lang.String") } returns listOf(
            GdkExtensionMethod("startsWith", "boolean", listOf("String"), listOf("extension"), "GDK", "extension"),
        )

        val metadata = assertNotNull(
            resolver.resolveMethodCall(
                call("startsWith", ConstantExpression("h"), receiver = ConstantExpression("hello")),
                null,
            ),
        )

        assertEquals("boolean startsWith(String prefix)", metadata.signature)
        assertEquals(member.doc, metadata.documentation)
        assertEquals("java.lang.String", metadata.declaringClass)
    }

    @Test
    fun `GDK varargs hover uses display types while matching erased arrays`() {
        val extension = GdkExtensionMethod(
            name = "groupBy",
            returnType = "Map",
            parameterTypes = listOf("Object[]"),
            parameterNames = listOf("closures"),
            originClass = "DefaultGroovyMethods",
            doc = "Groups values by the supplied closures.",
            displayParameterTypes = listOf("Object..."),
        )
        every { gdk.getQualifiedMethodsForType("demo.Repository") } returns listOf(
            QualifiedGdkMethod(extension, listOf("java.lang.Object[]")),
        )

        val metadata = assertNotNull(
            resolver.resolveMethodCall(
                call("groupBy", ConstantExpression(1), ConstantExpression(2)),
                null,
            ),
        )

        assertEquals("Map groupBy(Object... closures)", metadata.signature)
        assertEquals(listOf("Object[]"), extension.parameterTypes)
    }

    @Test
    fun `typed source receiver resolves a default parameter method`() {
        val owner = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val parameter = Parameter(ClassHelper.STRING_TYPE, "currency", ConstantExpression("USD"))
        owner.addMethod(MethodNode("create", Modifier.PUBLIC, ClassHelper.STRING_TYPE, arrayOf(parameter), null, null))
        val expression = call("create", receiver = VariableExpression("repository", owner))

        val metadata = assertNotNull(resolver.resolveMethodCall(expression, null))

        assertContains(metadata.signature, "String create(String currency =")
        assertContains(metadata.signature, "USD")
        assertEquals("demo.Repository", metadata.declaringClass)
    }

    @Test
    fun `ambiguous same arity overloads do not claim a resolved target`() {
        every { classpath.getMethods("demo.Repository") } returns listOf(
            reflected("create", listOf("String")),
            reflected("create", listOf("Integer")),
        )

        val metadata = resolver.resolveMethodCall(call("create", VariableExpression("unknown")), null)

        assertNull(metadata)
    }

    @Test
    fun `incompatible literal argument does not claim a classpath target`() {
        every { classpath.getMethods("demo.Repository") } returns listOf(reflected("create", listOf("String")))

        val metadata = resolver.resolveMethodCall(call("create", ConstantExpression(42)), null)

        assertNull(metadata)
    }

    @Test
    fun `compiler method target renders its signature and documentation`() {
        val owner = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val target = MethodNode("create", Modifier.PUBLIC, ClassHelper.STRING_TYPE, emptyArray(), null, null)
        owner.addMethod(target)
        target.putNodeMetaData(GroovydocHolder.DOC_COMMENT, Groovydoc("Creates a repository entry.", target))
        val expression = call("create").apply { methodTarget = target }
        val generator = HoverContentGenerator(semantic, resolver)

        val content = assertNotNull(generator.generateHover(expression).getOrNull()).contents.right.value

        assertContains(content, "String create()")
        assertContains(content, "Creates a repository entry.")
    }

    @Test
    fun `unrelated transform metadata does not hide a method call hover`() {
        val expression = call("unknown").apply { putNodeMetaData("targetMethod", "unrelated metadata") }

        val content = assertNotNull(HoverContentGenerator(semantic, resolver).generateHover(expression).getOrNull())
            .contents.right.value

        assertContains(content, "unknown()")
    }

    @Test
    fun `source overload selection follows the literal argument type`() {
        val owner = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        owner.addMethod(
            MethodNode(
                "create",
                Modifier.PUBLIC,
                ClassHelper.STRING_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "text")),
                null,
                null,
            ),
        )
        owner.addMethod(
            MethodNode(
                "create",
                Modifier.PUBLIC,
                ClassHelper.Integer_TYPE,
                arrayOf(Parameter(ClassHelper.int_TYPE, "number")),
                null,
                null,
            ),
        )

        val expression = call("create", ConstantExpression(42), receiver = VariableExpression("repository", owner))
        val metadata = assertNotNull(resolver.resolveMethodCall(expression, null))

        assertContains(metadata.signature, "Integer create(int number)")
    }

    @Test
    fun `null arguments do not falsely select the Object overload`() {
        val owner = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        owner.addMethod(
            MethodNode(
                "create",
                Modifier.PUBLIC,
                ClassHelper.STRING_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "text")),
                null,
                null,
            ),
        )
        owner.addMethod(
            MethodNode(
                "create",
                Modifier.PUBLIC,
                ClassHelper.OBJECT_TYPE,
                arrayOf(Parameter(ClassHelper.OBJECT_TYPE, "value")),
                null,
                null,
            ),
        )

        val expression = call("create", ConstantExpression.NULL, receiver = VariableExpression("repository", owner))

        assertNull(resolver.resolveMethodCall(expression, null))
    }

    @Test
    fun `receiver inference cancellation propagates to the caller`() {
        every { semantic.resolveType(any(), any()) } throws CancellationException("hover cancelled")

        assertFailsWith<CancellationException> { resolver.resolveMethodCall(call("findAll"), null) }
    }

    @Test
    fun `same line classes do not borrow an implicit method target from a neighbor`() {
        val (module, expression) = parsedCall(
            "class A { String find() { 'A' } }; class B { void run() { find() } }",
            "B",
            "find",
        )

        assertNull(resolver.resolveMethodCall(expression, module))
    }

    @Test
    fun `delegate first closures do not claim the lexical owner method without compiler evidence`() {
        val (module, expression) = parsedCall(
            """
                class A {
                    String greet() { 'owner' }
                    void run() {
                        def block = { greet() }
                        block.delegate = new B()
                        block.resolveStrategy = Closure.DELEGATE_FIRST
                        block()
                    }
                }
                class B { Integer greet() { 1 } }
            """.trimIndent(),
            "A",
            "greet",
        )

        assertNull(resolver.resolveMethodCall(expression, module))
    }

    private fun parsedCall(
        source: String,
        ownerName: String,
        methodName: String,
    ): Pair<ModuleNode, MethodCallExpression> {
        val unit = SourceUnit.create("Hover.groovy", source)
        unit.parse()
        unit.completePhase()
        unit.nextPhase()
        unit.convert()
        val calls = mutableListOf<MethodCallExpression>()
        unit.ast.classes.single { it.name == ownerName }.methods.single { it.name == "run" }.code.visit(
            object : CodeVisitorSupport() {
                override fun visitMethodCallExpression(call: MethodCallExpression) {
                    if (call.methodAsString == methodName) calls.add(call)
                    super.visitMethodCallExpression(call)
                }
            },
        )
        return unit.ast to calls.single()
    }

    private fun call(
        name: String,
        vararg arguments: Expression,
        receiver: Expression = VariableExpression("repository"),
    ): MethodCallExpression = MethodCallExpression(receiver, name, ArgumentListExpression(arguments.toList())).apply {
        isImplicitThis = false
    }

    private fun reflected(name: String, parameters: List<String>): ReflectedMethod = ReflectedMethod(
        name = name,
        returnType = "String",
        parameters = parameters,
        isStatic = false,
        isPublic = true,
        doc = "Declared member",
        declaringClass = "demo.Repository",
        qualifiedParameters = parameters.map {
            when (it) {
                "String" -> "java.lang.String"
                "Integer" -> "java.lang.Integer"
                else -> it
            }
        },
    )
}
