package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.ClassNode
import kotlin.reflect.KClass

/**
 * Calculator for ClassNode - resolves type references to their semantic type.
 *
 * This handles cases where the cursor is on a type reference such as:
 * - Return type: `String getName()` - hovering on "String"
 * - Parameter type: `void process(String input)` - hovering on "String"
 * - Field type: `String name` - hovering on "String"
 * - Throws clause: `void foo() throws Exception` - hovering on "Exception"
 * - Extends/implements: `class Foo extends Bar` - hovering on "Bar"
 *
 * The ClassNode itself represents the type, so we convert it directly.
 */
object ClassNodeCalculator : TypeCalculator<ClassNode> {
    override val nodeType: KClass<ClassNode> = ClassNode::class
    override val priority: Int = 20 // High priority for direct node types

    override fun calculate(node: ClassNode, context: TypeContext): SemanticType? = NativeTypeContext.fromClassNode(node)
}
