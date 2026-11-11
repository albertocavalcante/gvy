package com.github.albertocavalcante.gvy.semantics.native

import groovy.lang.GroovyObject
import groovy.transform.Trait
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.lang.reflect.Executable

/**
 * A reflected method's or constructor's parameter types as source writes them, with [render] formatting
 * each class: the last renders as `Component...` when [isDeclaredVarargs], every other one as is
 * (`int[]` stays an array). The classpath counterpart of [displayType] (#1418).
 */
fun Executable.displayParameterTypes(render: (Class<*>) -> String = Class<*>::getSimpleName): List<String> {
    val types = parameterTypes
    val varargs = isDeclaredVarargs()
    return types.mapIndexed { index, type ->
        if (varargs && type.isArray && index == types.lastIndex) render(type.componentType) + "..." else render(type)
    }
}

/**
 * Whether source declared the last parameter `Type...`. The JVM varargs flag says so for Java, Kotlin
 * and other compilers, but Groovy sets it on every trailing array parameter, so a Groovy-compiled
 * declaring class reads as not varargs: an explicit `String[]` stays an array, and a Groovy
 * `String...` shows as `String[]` too, since its bytecode is the same.
 */
fun Executable.isDeclaredVarargs(): Boolean = isVarArgs && !GROOVY_COMPILED.get(declaringClass)

/**
 * Groovy-compiled classes are GroovyObjects (including `@CompileStatic` ones), traits carry `@Trait`,
 * and plain interfaces carry only their bytecode `SourceFile` attribute, which this reads once per class.
 */
private val GROOVY_COMPILED = object : ClassValue<Boolean>() {
    override fun computeValue(type: Class<*>): Boolean = GroovyObject::class.java.isAssignableFrom(type) ||
        type.isAnnotationPresent(Trait::class.java) ||
        sourceFile(type)?.endsWith(GROOVY_SOURCE) == true
}

private const val GROOVY_SOURCE = ".groovy"

/**
 * The class file's `SourceFile` attribute, or null when the bytes are unavailable (classes defined in
 * memory) or unreadable (a class file newer than ASM supports, or malformed bytes, which ASM reports as
 * assorted runtime exceptions such as `ArrayIndexOutOfBoundsException`): such classes then read as not
 * Groovy, and the answer is cached like any other so a bad class costs one parse, not one per call.
 */
@Suppress("TooGenericExceptionCaught") // ASM's failure modes on user-classpath bytes are unbounded
private fun sourceFile(type: Class<*>): String? = try {
    type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")?.use { input ->
        var source: String? = null
        ClassReader(input).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(file: String?, debug: String?) {
                    source = file
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
        )
        source
    }
} catch (_: Exception) {
    null
}
