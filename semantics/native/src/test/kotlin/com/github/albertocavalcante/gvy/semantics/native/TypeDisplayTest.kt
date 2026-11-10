package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals

/** Types read as source writes them; classes that source never names keep their `$` binary form (#1407). */
class TypeDisplayTest {
    private val outer = ClassNode("a.Outer", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)

    @Test
    fun `plain, array and named nested types keep the source form`() {
        assertEquals("Outer", outer.displayName())
        assertEquals("int[][]", ClassHelper.int_TYPE.makeArray().makeArray().displayName())
        val inner = InnerClassNode(outer, "a.Outer\$Inner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        assertEquals("Outer.Inner", inner.displayName())
        assertEquals("Outer.Inner[]", inner.makeArray().displayName())
    }

    @Test
    fun `an anonymous class keeps its binary name, nested under a named outer`() {
        val anonymous = InnerClassNode(outer, "a.Outer\$1", 0, ClassHelper.OBJECT_TYPE).apply { isAnonymous = true }
        val inner = InnerClassNode(outer, "a.Outer\$Inner", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val nested = InnerClassNode(inner, "a.Outer\$Inner\$2", 0, ClassHelper.OBJECT_TYPE).apply { isAnonymous = true }

        assertEquals("Outer\$1", anonymous.displayName())
        assertEquals("Outer\$1[]", anonymous.makeArray().displayName())
        assertEquals("Outer.Inner\$2", nested.displayName())
    }

    @Test
    fun `a synthetic generated class keeps its binary name`() {
        val script = ClassNode("Script", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val closure =
            InnerClassNode(script, "Script\$_run_closure1", Modifier.PUBLIC or SYNTHETIC, ClassHelper.OBJECT_TYPE)

        assertEquals("Script\$_run_closure1", closure.displayName())
        val flagged = InnerClassNode(script, "Script\$_gen3", Modifier.PUBLIC, OBJECT).apply { isSynthetic = true }
        assertEquals("Script\$_gen3", flagged.displayName())
    }

    @Test
    fun `a closure class keeps its binary name without the synthetic flag`() {
        val script = ClassNode("Script", Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        val closure = InnerClassNode(script, "Script\$_run_closure2", Modifier.PUBLIC, ClassHelper.CLOSURE_TYPE)

        assertEquals("Script\$_run_closure2", closure.displayName())
    }

    @Test
    fun `nodes without structure fall back to the name rule`() {
        // Decompiled or reflected nodes carry only the binary name.
        assertEquals("Outer\$1", ClassNode("a.Outer\$1", 0, ClassHelper.OBJECT_TYPE).displayName())
        assertEquals("Script\$_run_closure1", ClassNode("Script\$_run_closure1", 0, OBJECT).displayName())
        assertEquals("Outer.Inner\$2", ClassNode("a.Outer\$Inner\$2", 0, ClassHelper.OBJECT_TYPE).displayName())
        assertEquals("Outer.Inner", ClassNode("a.Outer\$Inner", 0, ClassHelper.OBJECT_TYPE).displayName())
    }

    @Test
    fun `a user-declared nested class named with an underscore keeps the source form`() {
        val source = "package a\nclass Outer {\n  static class _Helper {}\n" +
            "  Runnable r = new Runnable() { void run() {} }\n}\n"
        val request = ParseRequest(URI.create("file:///Outer.groovy"), source)
        val module = checkNotNull(GroovyParserFacade().parse(request).ast)

        val names = module.classes.associate { it.name to it.displayName() }

        assertEquals("Outer._Helper", names["a.Outer\$_Helper"], "found $names")
        assertEquals("Outer\$1", names["a.Outer\$1"], "found $names")
    }

    private companion object {
        const val SYNTHETIC = 0x1000
        val OBJECT: ClassNode = ClassHelper.OBJECT_TYPE
    }
}
