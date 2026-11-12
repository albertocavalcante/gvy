// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.stc.ExtensionMethodNode
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MethodCallHoverResolutionTest {
    private val classpath = mockk<ClasspathService> {
        every { getMethods(any()) } returns emptyList()
        every { loadClass(any()) } returns null
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

    interface CustomMarker

    class CustomArgument : CustomMarker

    interface BinaryContract {
        fun accept(value: String): String
    }

    abstract class BinaryReceiver : BinaryContract {
        fun accept(value: Any): String = value.toString()
    }

    @Test
    fun `reflected interface overload uses actual classpath type metadata`() {
        val marker = reflected("accept", listOf("CustomMarker")).copy(
            qualifiedParameters = listOf(CustomMarker::class.java.name),
        )
        val generic = reflected("accept", listOf("Object")).copy(qualifiedParameters = listOf("java.lang.Object"))
        every { classpath.getMethods("demo.Repository") } returns listOf(generic, marker)
        every { classpath.loadClass(CustomMarker::class.java.name) } returns CustomMarker::class.java
        val argument = VariableExpression("argument", ClassHelper.make(CustomArgument::class.java))

        val metadata = assertNotNull(resolver.resolveMethodCall(call("accept", argument), null))

        assertContains(metadata.signature, "CustomMarker")
    }

    @Test
    fun `unavailable reflected parameter identity declines the whole overload set`() {
        val marker = reflected("accept", listOf("CustomMarker")).copy(
            qualifiedParameters = listOf(CustomMarker::class.java.name),
        )
        val generic = reflected("accept", listOf("Object")).copy(qualifiedParameters = listOf("java.lang.Object"))
        every { classpath.getMethods("demo.Repository") } returns listOf(generic, marker)
        val argument = VariableExpression("argument", ClassHelper.make(CustomArgument::class.java))

        assertNull(resolver.resolveMethodCall(call("accept", argument), null))
    }

    @Test
    fun `unavailable GDK parameter identity declines the whole extension overload set`() {
        val generic = GdkExtensionMethod("accept", "String", listOf("Object"), listOf("value"), "GDK", "generic")
        val marker = generic.copy(parameterTypes = listOf("CustomMarker"))
        every { gdk.getQualifiedMethodsForType("demo.Repository") } returns listOf(
            QualifiedGdkMethod(generic, listOf("java.lang.Object")),
            QualifiedGdkMethod(marker, listOf(CustomMarker::class.java.name)),
        )
        val argument = VariableExpression("argument", ClassHelper.make(CustomArgument::class.java))

        assertNull(resolver.resolveMethodCall(call("accept", argument), null))
    }

    @Test
    fun `inferred binary receiver and inherited interface owner retain their actual hierarchy`() {
        val receiverName = BinaryReceiver::class.java.name
        val generic = reflected("accept", listOf("Object")).copy(
            declaringClass = receiverName,
            qualifiedParameters = listOf("java.lang.Object"),
        )
        val specific = reflected("accept", listOf("String")).copy(declaringClass = BinaryContract::class.java.name)
        every { semantic.resolveType(any(), any()) } returns SemanticType.Known(receiverName)
        every { classpath.getMethods(receiverName) } returns listOf(generic, specific)
        every { classpath.loadClass(receiverName) } returns BinaryReceiver::class.java
        every { classpath.loadClass(BinaryContract::class.java.name) } returns BinaryContract::class.java

        val metadata = assertNotNull(resolver.resolveMethodCall(call("accept", ConstantExpression("text")), null))

        assertEquals(BinaryContract::class.java.name, metadata.declaringClass)
        assertContains(metadata.signature, "String accept(String")
    }

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
    fun `source receiver shadows a stale binary method with the same class name`() {
        val source = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        every { classpath.getMethods("demo.Repository") } returns listOf(reflected("removed", emptyList()))
        val expression = call("removed", receiver = VariableExpression("repository", source))

        assertNull(resolver.resolveMethodCall(expression, null))
    }

    @Test
    fun `private specific source overload does not misreport the public Object overload`() {
        val expression = sourceAccessCall(Modifier.PRIVATE)

        assertNull(expression.methodTarget)
        assertNull(resolver.resolveMethodCall(expression, null))
    }

    @Test
    fun `protected specific source overload does not misreport the public Object overload`() {
        val expression = sourceAccessCall(Modifier.PROTECTED)

        assertNull(expression.methodTarget)
        assertNull(resolver.resolveMethodCall(expression, null))
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
    fun `attached instance extension target uses its declaration without static or receiver parameters`() {
        val target = extensionTarget()
        val parameters = target.parameters
        val parameterStaticContexts = parameters.map { it.isInStaticContext }
        val owner = target.declaringClass
        val declaration = target.extensionMethodNode
        val declarationStaticContexts = declaration.parameters.map { it.isInStaticContext }
        declaration.putNodeMetaData(
            GroovydocHolder.DOC_COMMENT,
            Groovydoc("Decorates text using the supplied suffix.", declaration),
        )
        val expression = call("decorate", ConstantExpression("!"), receiver = ConstantExpression("hello")).apply {
            methodTarget = target
        }

        val metadata = assertNotNull(resolver.resolveMethodCall(expression, null))
        val content = assertNotNull(HoverContentGenerator(semantic, resolver).generateHover(expression).getOrNull())
            .contents.right.value

        assertEquals("demo.HoverExtensions", metadata.declaringClass)
        assertContains(metadata.signature, "String decorate(String suffix)")
        assertFalse(metadata.signature.contains("static"), metadata.signature)
        assertFalse(metadata.signature.contains("self"), metadata.signature)
        assertContains(assertNotNull(metadata.documentation), "Decorates text using the supplied suffix.")
        assertContains(content, "demo.HoverExtensions")
        assertContains(content, "Decorates text using the supplied suffix.")
        assertSame(target, expression.methodTarget)
        assertSame(parameters, target.parameters)
        assertEquals(parameterStaticContexts, target.parameters.map { it.isInStaticContext })
        assertSame(owner, target.declaringClass)
        assertTrue(target.isStatic)
        assertEquals(2, declaration.parameters.size)
        assertEquals("self", declaration.parameters.first().name)
        assertEquals(declarationStaticContexts, declaration.parameters.map { it.isInStaticContext })
        assertTrue(declaration.isStatic)
    }

    @Test
    fun `attached static extension retains its static call style and underlying declaration owner`() {
        val target = extensionTarget(
            "static String decorate(Class<String> self, String suffix) { suffix }",
            staticExtension = true,
        )
        val expression = call(
            "decorate",
            ConstantExpression("!"),
            receiver = ClassExpression(ClassHelper.STRING_TYPE),
        ).apply {
            methodTarget = target
        }

        val metadata = assertNotNull(resolver.resolveMethodCall(expression, null))

        assertEquals("demo.HoverExtensions", metadata.declaringClass)
        assertContains(metadata.signature, "static")
        assertContains(metadata.signature, "String decorate(String suffix)")
        assertFalse(metadata.signature.contains("self"), metadata.signature)
        assertTrue(target.isStaticExtension)
        assertTrue(target.isStatic)
    }

    @Test
    fun `attached extension preserves generic source varargs annotations and throws without mutating its AST`() {
        val target = extensionTarget(
            "@Deprecated static <T> T decorate(String self, @Deprecated T... values) throws IOException { null }",
        )
        val parameters = target.parameters
        val generics = target.genericsTypes
        val annotations = target.annotations.toList()
        val exceptions = target.exceptions

        val metadata = target.toHoverMetadata()

        assertContains(metadata.signature, "<T> T decorate(@Deprecated T... values)")
        assertContains(metadata.signature, "@Deprecated")
        assertContains(metadata.signature, "throws IOException")
        assertFalse(metadata.signature.contains("static"), metadata.signature)
        assertSame(parameters, target.parameters)
        assertSame(generics, target.genericsTypes)
        assertEquals(annotations, target.annotations)
        assertSame(exceptions, target.exceptions)
        assertTrue(target.isStatic)
    }

    @Test
    fun `attached extension keeps a fixed array parameter distinct from source varargs`() {
        val target = extensionTarget("static String decorate(String self, String[] values) { self }")

        val metadata = target.toHoverMetadata()

        assertContains(metadata.signature, "String decorate(String[] values)")
        assertFalse(metadata.signature.contains("..."), metadata.signature)
        assertEquals("demo.HoverExtensions", metadata.declaringClass)
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

    private fun extensionTarget(
        declaration: String = "static String decorate(String self, String suffix) { self + suffix }",
        staticExtension: Boolean = false,
    ): ExtensionMethodNode {
        val unit = SourceUnit.create("HoverExtensions.groovy", "package demo; class HoverExtensions { $declaration }")
        unit.parse()
        unit.completePhase()
        unit.nextPhase()
        unit.convert()
        val method = unit.ast.classes.single().methods.single { it.name == "decorate" }
        return ExtensionMethodNode(
            method,
            method.name,
            method.modifiers,
            method.returnType,
            method.parameters.drop(1).toTypedArray(),
            method.exceptions,
            method.code,
            staticExtension,
        ).apply {
            declaringClass = method.parameters.first().type
            genericsTypes = method.genericsTypes
            addAnnotations(method.annotations)
        }
    }

    private fun call(
        name: String,
        vararg arguments: Expression,
        receiver: Expression = VariableExpression("repository"),
    ): MethodCallExpression = MethodCallExpression(receiver, name, ArgumentListExpression(arguments.toList())).apply {
        isImplicitThis = false
    }

    private fun sourceAccessCall(visibility: Int): MethodCallExpression {
        val owner = ClassNode("demo.Repository", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        owner.addMethod(
            MethodNode(
                "pick",
                visibility,
                ClassHelper.STRING_TYPE,
                arrayOf(Parameter(ClassHelper.STRING_TYPE, "text")),
                null,
                null,
            ),
        )
        owner.addMethod(
            MethodNode(
                "pick",
                Modifier.PUBLIC,
                ClassHelper.OBJECT_TYPE,
                arrayOf(Parameter(ClassHelper.OBJECT_TYPE, "value")),
                null,
                null,
            ),
        )
        val expression = call("pick", ConstantExpression("text"), receiver = VariableExpression("other", owner))
        owner.addMethod(
            MethodNode(
                "run",
                Modifier.PUBLIC,
                ClassHelper.OBJECT_TYPE,
                arrayOf(Parameter(owner, "other")),
                null,
                ExpressionStatement(expression),
            ),
        )
        val extension = GdkExtensionMethod("pick", "Object", listOf("Object"), listOf("value"), "GDK", "fallback")
        every { gdk.getQualifiedMethodsForType(owner.name) } returns listOf(
            QualifiedGdkMethod(extension, listOf("java.lang.Object")),
        )
        return expression
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
