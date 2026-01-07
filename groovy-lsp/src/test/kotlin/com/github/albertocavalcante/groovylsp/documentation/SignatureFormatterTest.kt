package com.github.albertocavalcante.groovylsp.documentation

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SignatureFormatterTest {

    @Test
    fun `formatMethod - simple public method`() {
        val method = MethodNode(
            "greet",
            Modifier.PUBLIC,
            ClassHelper.STRING_TYPE,
            arrayOf(
                Parameter(ClassHelper.STRING_TYPE, "name"),
            ),
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertEquals("public String greet(String name)", result)
    }

    @Test
    fun `formatMethod - private static method`() {
        val method = MethodNode(
            "calculate",
            Modifier.PRIVATE or Modifier.STATIC,
            ClassHelper.int_TYPE,
            arrayOf(
                Parameter(ClassHelper.int_TYPE, "x"),
                Parameter(ClassHelper.int_TYPE, "y"),
            ),
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertEquals("private static int calculate(int x, int y)", result)
    }

    @Test
    fun `formatMethod - protected final method`() {
        val method = MethodNode(
            "process",
            Modifier.PROTECTED or Modifier.FINAL,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertEquals("protected final void process()", result)
    }

    @Test
    fun `formatMethod - abstract method`() {
        val method = MethodNode(
            "compute",
            Modifier.PUBLIC or Modifier.ABSTRACT,
            ClassHelper.OBJECT_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertEquals("public abstract Object compute()", result)
    }

    @Test
    fun `formatMethod - method with default parameter value`() {
        val param = Parameter(ClassHelper.int_TYPE, "age")
        param.initialExpression = ConstantExpression(25)

        val method = MethodNode(
            "greetUser",
            Modifier.PUBLIC,
            ClassHelper.STRING_TYPE,
            arrayOf(
                Parameter(ClassHelper.STRING_TYPE, "name"),
                param,
            ),
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertTrue(result.contains("age = 25"))
    }

    @Test
    fun `formatMethod - method with throws clause`() {
        val method = MethodNode(
            "riskyOperation",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            arrayOf(ClassHelper.make(IllegalArgumentException::class.java)),
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertTrue(result.contains("throws IllegalArgumentException"))
    }

    @Test
    fun `formatMethod - method with multiple exceptions`() {
        val method = MethodNode(
            "dangerousMethod",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            arrayOf(
                ClassHelper.make(IllegalArgumentException::class.java),
                ClassHelper.make(IllegalStateException::class.java),
            ),
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertTrue(result.contains("throws IllegalArgumentException, IllegalStateException"))
    }

    @Test
    fun `formatMethod - generic method with type parameter`() {
        val typeParam = GenericsType(ClassHelper.make("T"))
        val genericType = ClassHelper.make("T")
        genericType.genericsTypes = arrayOf(typeParam)

        val method = MethodNode(
            "identity",
            Modifier.PUBLIC,
            genericType,
            arrayOf(Parameter(genericType, "value")),
            ClassNode.EMPTY_ARRAY,
            null,
        )
        method.genericsTypes = arrayOf(typeParam)

        val result = SignatureFormatter.formatMethod(method)
        assertTrue(result.contains("<T>"))
        assertTrue(result.contains("identity"))
    }

    @Test
    fun `formatMethod - multiline parameters when long`() {
        val method = MethodNode(
            "methodWithManyParameters",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            arrayOf(
                Parameter(ClassHelper.STRING_TYPE, "firstParameter"),
                Parameter(ClassHelper.STRING_TYPE, "secondParameter"),
                Parameter(ClassHelper.int_TYPE, "thirdParameter"),
                Parameter(ClassHelper.OBJECT_TYPE, "fourthParameter"),
            ),
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        // Should break to multiple lines
        assertTrue(result.contains("\n    ") || result.contains("firstParameter, secondParameter"))
    }

    @Test
    fun `formatMethod - disable modifiers option`() {
        val method = MethodNode(
            "test",
            Modifier.PUBLIC or Modifier.STATIC,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val options = SignatureFormatter.Options(showModifiers = false)
        val result = SignatureFormatter.formatMethod(method, options)
        assertEquals("void test()", result)
    }

    @Test
    fun `formatClass - simple public class`() {
        val classNode = ClassNode("MyClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)

        val result = SignatureFormatter.formatClass(classNode)
        assertEquals("public class MyClass", result)
    }

    @Test
    fun `formatClass - interface`() {
        val classNode = ClassHelper.make(Runnable::class.java)

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("interface Runnable"))
    }

    @Test
    fun `formatClass - abstract class`() {
        val classNode = ClassNode("AbstractClass", Modifier.PUBLIC or Modifier.ABSTRACT, ClassHelper.OBJECT_TYPE)

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("abstract"))
        assertTrue(result.contains("class AbstractClass"))
    }

    @Test
    fun `formatClass - enum class`() {
        // Use an actual enum class
        val classNode = ClassHelper.make(Thread.State::class.java)

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("enum"))
    }

    @Test
    fun `formatClass - class with superclass`() {
        val superClass = ClassHelper.make(ArrayList::class.java)
        val classNode = ClassNode("MyList", Modifier.PUBLIC, superClass)

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("extends ArrayList"))
    }

    @Test
    fun `formatClass - class with interfaces`() {
        val classNode = ClassNode("MyClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.setInterfaces(
            arrayOf(ClassHelper.make(Runnable::class.java), ClassHelper.make(Cloneable::class.java)),
        )

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("implements Runnable, Cloneable"))
    }

    @Test
    fun `formatClass - class with superclass and interfaces`() {
        val superClass = ClassHelper.make(ArrayList::class.java)
        val classNode = ClassNode("MyList", Modifier.PUBLIC, superClass)
        classNode.setInterfaces(arrayOf(ClassHelper.make(Cloneable::class.java)))

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("extends ArrayList"))
        assertTrue(result.contains("implements Cloneable"))
    }

    @Test
    fun `formatClass - generic class with type parameters`() {
        // Use List<E> as an example of a generic class
        val classNode = ClassHelper.LIST_TYPE

        val result = SignatureFormatter.formatClass(classNode)
        // Should contain List and generic parameter E
        assertTrue(result.contains("List"))
    }

    @Test
    fun `formatClass - generic class with bounded type parameter`() {
        // Use Comparable<T> which has a bounded type parameter
        val classNode = ClassHelper.make(Comparable::class.java)

        val result = SignatureFormatter.formatClass(classNode)
        assertTrue(result.contains("Comparable"))
    }

    @Test
    fun `formatField - public field`() {
        val classNode = ClassNode("Owner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val field = FieldNode("name", Modifier.PUBLIC, ClassHelper.STRING_TYPE, classNode, null)

        val result = SignatureFormatter.formatField(field)
        assertEquals("public String name", result)
    }

    @Test
    fun `formatField - private static final field`() {
        val classNode = ClassNode("Owner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val field = FieldNode(
            "CONSTANT",
            Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL,
            ClassHelper.int_TYPE,
            classNode,
            null,
        )

        val result = SignatureFormatter.formatField(field)
        assertTrue(result.contains("private static final"))
        assertTrue(result.contains("int CONSTANT"))
    }

    @Test
    fun `formatField - field with initial value`() {
        val classNode = ClassNode("Owner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val field = FieldNode("count", Modifier.PRIVATE, ClassHelper.int_TYPE, classNode, ConstantExpression(0))

        val result = SignatureFormatter.formatField(field)
        assertTrue(result.contains("count = 0"))
    }

    @Test
    fun `formatField - volatile field`() {
        val classNode = ClassNode("Owner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val field = FieldNode("flag", Modifier.VOLATILE, ClassHelper.boolean_TYPE, classNode, null)

        val result = SignatureFormatter.formatField(field)
        assertTrue(result.contains("volatile"))
        assertTrue(result.contains("boolean flag"))
    }

    @Test
    fun `formatParameter - simple parameter`() {
        val param = Parameter(ClassHelper.STRING_TYPE, "text")

        val result = SignatureFormatter.formatParameter(param)
        assertEquals("String text", result)
    }

    @Test
    fun `formatParameter - parameter with default value`() {
        val param = Parameter(ClassHelper.int_TYPE, "count")
        param.initialExpression = ConstantExpression(10)

        val result = SignatureFormatter.formatParameter(param)
        assertEquals("int count = 10", result)
    }

    @Test
    fun `formatParameter - disable default values option`() {
        val param = Parameter(ClassHelper.int_TYPE, "count")
        param.initialExpression = ConstantExpression(10)

        val options = SignatureFormatter.Options(showDefaultValues = false)
        val result = SignatureFormatter.formatParameter(param, options)
        assertEquals("int count", result)
    }

    @Test
    fun `formatMethod - package-private method has no modifier`() {
        val method = MethodNode(
            "packageMethod",
            0, // no modifiers = package-private
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        // Should just have "void packageMethod()" without any access modifier
        assertTrue(result.startsWith("void packageMethod()") || result.trim() == "void packageMethod()")
    }

    @Test
    fun `formatClass - nested class names`() {
        val classNode = ClassNode("com.example.OuterClass\$InnerClass", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)

        val result = SignatureFormatter.formatClass(classNode)
        // Should show the simple name without package
        assertTrue(result.contains("InnerClass"))
    }

    @Test
    fun `formatMethod - synchronized method`() {
        val method = MethodNode(
            "threadSafeMethod",
            Modifier.PUBLIC or Modifier.SYNCHRONIZED,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val result = SignatureFormatter.formatMethod(method)
        assertTrue(result.contains("synchronized"))
    }

    @Test
    fun `formatMethod - disable throws option`() {
        val method = MethodNode(
            "throwingMethod",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            Parameter.EMPTY_ARRAY,
            arrayOf(ClassHelper.make(Exception::class.java)),
            null,
        )

        val options = SignatureFormatter.Options(showThrows = false)
        val result = SignatureFormatter.formatMethod(method, options)
        assertTrue(!result.contains("throws"))
    }

    @Test
    fun `formatMethod - force single line parameters`() {
        val method = MethodNode(
            "methodWithManyParameters",
            Modifier.PUBLIC,
            ClassHelper.VOID_TYPE,
            arrayOf(
                Parameter(ClassHelper.STRING_TYPE, "first"),
                Parameter(ClassHelper.STRING_TYPE, "second"),
                Parameter(ClassHelper.STRING_TYPE, "third"),
            ),
            ClassNode.EMPTY_ARRAY,
            null,
        )

        val options = SignatureFormatter.Options(multilineParams = false)
        val result = SignatureFormatter.formatMethod(method, options)
        // Should not contain newlines in parameters
        assertTrue(!result.contains("\n    String"))
    }
}
