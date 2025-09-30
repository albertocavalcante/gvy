package com.github.albertocavalcante.groovylsp.dsl.hover

import com.github.albertocavalcante.groovylsp.errors.LspResult
import com.github.albertocavalcante.groovylsp.errors.toLspResult
import com.github.albertocavalcante.groovylsp.util.GroovyBuiltinMethods
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.FieldExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.MethodReferenceExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.eclipse.lsp4j.Hover

/**
 * Extension functions for formatting specific AST node types
 */

/**
 * Format a VariableExpression for hover
 */
fun VariableExpression.toHoverContent(): HoverContent {
    // Check if this is actually a built-in method reference (like println without parentheses)
    return if (GroovyBuiltinMethods.isBuiltinMethod(name)) {
        // This is likely a method reference without parentheses (e.g., just "println")
        val category = GroovyBuiltinMethods.getMethodCategory(name)
        val description = GroovyBuiltinMethods.getMethodDescription(name)
        val source = GroovyBuiltinMethods.getMethodSource(name)

        HoverContent.Section(
            title = "Built-in Method Reference",
            content = listOf(
                HoverContent.Code("$name"),
                HoverContent.Text(description),
                HoverContent.KeyValue(
                    listOf(
                        "Type" to category,
                        "Method" to name,
                        "Source" to source,
                        "Usage" to "Can be called with or without parentheses",
                    ),
                ),
                HoverContent.Text("📖 [Groovy Documentation](https://docs.groovy-lang.org/latest/html/groovy-jdk/)"),
            ),
        )
    } else {
        // Regular variable expression
        HoverContent.Code("${type.nameWithoutPackage} $name")
    }
}

/**
 * Format a MethodNode for hover
 */
fun MethodNode.toHoverContent(): HoverContent = if (isConstructor()) {
    // Delegate to constructor formatting for constructor methods
    HoverContent.Section(
        title = "Constructor",
        content = listOf(
            HoverContent.Code("${declaringClass.nameWithoutPackage}(${parametersString()})"),
            HoverContent.KeyValue(
                listOf(
                    "Modifiers" to modifiersString(),
                    "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                ),
            ),
        ),
    )
} else {
    HoverContent.Section(
        title = "Method",
        content = listOf(
            HoverContent.Code(signature()),
            HoverContent.KeyValue(
                listOf(
                    "Return Type" to (returnType?.nameWithoutPackage ?: "def"),
                    "Modifiers" to modifiersString(),
                    "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                ),
            ),
        ),
    )
}

/**
 * Format a ClassNode for hover
 */
fun ClassNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Class",
    content = buildList {
        add(HoverContent.Code(classSignature()))

        if (methods.isNotEmpty()) {
            add(
                HoverContent.Section(
                    "Methods",
                    listOf(HoverContent.List(methods.map { "${it.name}(${it.parametersString()})" })),
                ),
            )
        }

        if (fields.isNotEmpty()) {
            add(
                HoverContent.Section(
                    "Fields",
                    listOf(HoverContent.List(fields.map { "${it.type.nameWithoutPackage} ${it.name}" })),
                ),
            )
        }
    },
)

/**
 * Format a FieldNode for hover
 */
fun FieldNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Field",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage} $name"),
        HoverContent.KeyValue(
            listOf(
                "Type" to type.nameWithoutPackage,
                "Modifiers" to modifiersString(),
                "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                "Initial Value" to (initialValueExpression?.text ?: "none"),
            ),
        ),
    ),
)

/**
 * Format a PropertyNode for hover
 */
fun PropertyNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Property",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage} $name"),
        HoverContent.KeyValue(
            listOf(
                "Type" to type.nameWithoutPackage,
                "Modifiers" to modifiersString(),
                "Owner" to (declaringClass?.nameWithoutPackage ?: "unknown"),
                "Getter" to if (getterBlock != null) "available" else "none",
                "Setter" to if (setterBlock != null) "available" else "none",
            ),
        ),
    ),
)

/**
 * Format a Parameter for hover
 */
fun Parameter.toHoverContent(): HoverContent.Code = HoverContent.Code("${type.nameWithoutPackage} $name")

/**
 * Format a MethodCallExpression for hover
 */
fun MethodCallExpression.toHoverContent(): HoverContent {
    val methodName = method.text
    val isBuiltin = GroovyBuiltinMethods.isBuiltinMethod(methodName)

    return if (isBuiltin) {
        // Built-in Groovy method
        val category = GroovyBuiltinMethods.getMethodCategory(methodName)
        val description = GroovyBuiltinMethods.getMethodDescription(methodName)
        val source = GroovyBuiltinMethods.getMethodSource(methodName)

        HoverContent.Section(
            title = "Built-in Method",
            content = listOf(
                HoverContent.Code("$methodName(${argumentsString()})"),
                HoverContent.Text(description),
                HoverContent.KeyValue(
                    listOf(
                        "Type" to category,
                        "Method" to methodName,
                        "Source" to source,
                        "Receiver" to (objectExpression?.text ?: "this"),
                        "Arguments" to argumentsString(),
                    ),
                ),
                HoverContent.Text("📖 [Groovy Documentation](https://docs.groovy-lang.org/latest/html/groovy-jdk/)"),
            ),
        )
    } else {
        // Regular user-defined method call
        HoverContent.Section(
            title = "Method Call",
            content = listOf(
                HoverContent.Code("$method(${argumentsString()})"),
                HoverContent.KeyValue(
                    listOf(
                        "Method" to method.toString(),
                        "Object" to (objectExpression?.toString() ?: "this"),
                        "Arguments" to argumentsString(),
                    ),
                ),
            ),
        )
    }
}

/**
 * Format a BinaryExpression for hover
 */
fun BinaryExpression.toHoverContent(): HoverContent = when (operation.text) {
    "=" -> HoverContent.Section(
        "Assignment",
        listOf(HoverContent.Code("$leftExpression = $rightExpression")),
    )
    else -> HoverContent.Section(
        "Binary Expression",
        listOf(HoverContent.Code("$leftExpression ${operation.text} $rightExpression")),
    )
}

/**
 * Format a DeclarationExpression for hover
 */
fun DeclarationExpression.toHoverContent(): HoverContent {
    val varExpr = leftExpression as? VariableExpression
    val type = varExpr?.type?.nameWithoutPackage ?: "def"
    val name = varExpr?.name ?: "unknown"

    return HoverContent.Section(
        "Variable Declaration",
        listOf(
            HoverContent.Code("$type $name = $rightExpression"),
            HoverContent.KeyValue(
                listOf(
                    "Type" to type,
                    "Name" to name,
                    "Initial Value" to rightExpression.toString(),
                ),
            ),
        ),
    )
}

/**
 * Format a ClosureExpression for hover
 */
fun ClosureExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Closure",
    content = listOf(
        HoverContent.Code("{ ${parametersString()} -> ... }"),
        HoverContent.KeyValue(
            listOf(
                "Parameters" to parametersString(),
                "Variables in Scope" to variableScope.declaredVariables.keys.joinToString(", "),
            ),
        ),
    ),
)

/**
 * Format a ConstantExpression for hover
 */
fun ConstantExpression.toHoverContent(): HoverContent {
    val typeDescription = when (type.name) {
        "java.lang.String" -> "String literal"
        "java.lang.Integer", "int" -> "Integer literal"
        "java.lang.Double", "double" -> "Double literal"
        "java.lang.Boolean", "boolean" -> "Boolean literal"
        else -> "Constant"
    }

    return HoverContent.Section(
        typeDescription,
        listOf(
            HoverContent.Code(text),
            HoverContent.KeyValue(
                listOf(
                    "Type" to type.nameWithoutPackage,
                    "Value" to text,
                ),
            ),
        ),
    )
}

/**
 * Format a GStringExpression for hover
 */
fun GStringExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "GString",
    content = listOf(
        HoverContent.Code(text),
        HoverContent.KeyValue(
            listOf(
                "Type" to "GString",
                "Template" to text,
                "Values" to values.size.toString(),
            ),
        ),
    ),
)

/**
 * Format an ImportNode for hover
 */
fun ImportNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Import",
    content = listOf(
        HoverContent.Code(formatImport()),
        HoverContent.KeyValue(
            listOf(
                "Class" to className,
                "Alias" to (alias ?: "none"),
                "Package" to (packageName ?: "default"),
                "Star Import" to isStar.toString(),
            ),
        ),
    ),
)

/**
 * Format a PackageNode for hover
 */
fun PackageNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Package",
    content = listOf(
        HoverContent.Code("package $name"),
    ),
)

/**
 * Format an AnnotationNode for hover
 */
fun AnnotationNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Annotation",
    content = listOf(
        HoverContent.Code("@${classNode.nameWithoutPackage}"),
        HoverContent.KeyValue(
            listOf(
                "Type" to classNode.nameWithoutPackage,
                "Members" to (members?.size?.toString() ?: "0"),
            ),
        ),
    ),
)

/**
 * Format a ClassExpression for hover (e.g., String.class)
 */
fun ClassExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Class Reference",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage}.class"),
        HoverContent.KeyValue(
            listOf(
                "Type" to type.nameWithoutPackage,
                "Package" to (type.packageName ?: "default"),
                "Usage" to "Class literal expression",
            ),
        ),
    ),
)

/**
 * Format a PropertyExpression for hover (e.g., object.property)
 */
fun PropertyExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Property Access",
    content = listOf(
        HoverContent.Code("${objectExpression.text}.${property.text}"),
        HoverContent.KeyValue(
            listOf(
                "Property" to property.text,
                "Object" to objectExpression.text,
                "Safe Navigation" to isSafe.toString(),
            ),
        ),
    ),
)

/**
 * Format a ConstructorCallExpression for hover (e.g., new Class())
 */
fun ConstructorCallExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Constructor Call",
    content = listOf(
        HoverContent.Code("new ${type.nameWithoutPackage}(${arguments.text})"),
        HoverContent.KeyValue(
            listOf(
                "Class" to type.nameWithoutPackage,
                "Arguments" to arguments.text,
                "Package" to (type.packageName ?: "default"),
            ),
        ),
    ),
)

/**
 * Format a StaticMethodCallExpression for hover (e.g., Class.staticMethod())
 */
fun StaticMethodCallExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Static Method Call",
    content = listOf(
        HoverContent.Code("${ownerType.nameWithoutPackage}.$method(${arguments.text})"),
        HoverContent.KeyValue(
            listOf(
                "Method" to method,
                "Owner" to ownerType.nameWithoutPackage,
                "Arguments" to arguments.text,
            ),
        ),
    ),
)

/**
 * Format a FieldExpression for hover
 */
fun FieldExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Field Access",
    content = listOf(
        HoverContent.Code("${field.name}"),
        HoverContent.KeyValue(
            listOf(
                "Field" to field.name,
                "Type" to (field.type?.nameWithoutPackage ?: "unknown"),
                "Owner" to (field.owner?.nameWithoutPackage ?: "unknown"),
                "Modifiers" to field.modifiers.toString(),
            ),
        ),
    ),
)

/**
 * Format an AttributeExpression for hover (e.g., @object.attribute)
 */
fun AttributeExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Attribute Access",
    content = listOf(
        HoverContent.Code("@${objectExpression.text}.${property.text}"),
        HoverContent.KeyValue(
            listOf(
                "Attribute" to property.text,
                "Object" to objectExpression.text,
                "Usage" to "Direct field access (bypassing getters/setters)",
            ),
        ),
    ),
)

/**
 * Format an ArgumentListExpression for hover
 */
fun ArgumentListExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Argument List",
    content = listOf(
        HoverContent.Code("(${expressions.joinToString(", ") { it.text }})"),
        HoverContent.KeyValue(
            listOf(
                "Arguments" to expressions.size.toString(),
                "Types" to expressions.mapNotNull { it.type?.nameWithoutPackage }.joinToString(", "),
            ),
        ),
    ),
)

/**
 * Format an ArrayExpression for hover
 */
fun ArrayExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Array Expression",
    content = listOf(
        HoverContent.Code("${type.nameWithoutPackage}[${expressions.size}]"),
        HoverContent.KeyValue(
            listOf(
                "Element Type" to (elementType?.nameWithoutPackage ?: "Object"),
                "Size" to expressions.size.toString(),
                "Elements" to expressions.joinToString(", ") { it.text },
            ),
        ),
    ),
)

/**
 * Format a ListExpression for hover
 */
fun ListExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "List Expression",
    content = listOf(
        HoverContent.Code("[${expressions.joinToString(", ") { it.text }}]"),
        HoverContent.KeyValue(
            listOf(
                "Size" to expressions.size.toString(),
                "Elements" to expressions.joinToString(", ") { it.text },
            ),
        ),
    ),
)

/**
 * Format a MapExpression for hover
 */
fun MapExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Map Expression",
    content = listOf(
        HoverContent.Code(
            "[${mapEntryExpressions.joinToString(", ") {
                "${it.keyExpression.text}: ${it.valueExpression.text}"
            }}]",
        ),
        HoverContent.KeyValue(
            listOf(
                "Entries" to mapEntryExpressions.size.toString(),
            ),
        ),
    ),
)

/**
 * Format a MapEntryExpression for hover
 */
fun MapEntryExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Map Entry",
    content = listOf(
        HoverContent.Code("${keyExpression.text}: ${valueExpression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Key" to keyExpression.text,
                "Value" to valueExpression.text,
                "Key Type" to (keyExpression.type?.nameWithoutPackage ?: "unknown"),
                "Value Type" to (valueExpression.type?.nameWithoutPackage ?: "unknown"),
            ),
        ),
    ),
)

/**
 * Format a RangeExpression for hover
 */
fun RangeExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Range Expression",
    content = listOf(
        HoverContent.Code("${from.text}${if (isInclusive) ".." else "..<"}${to.text}"),
        HoverContent.KeyValue(
            listOf(
                "From" to from.text,
                "To" to to.text,
                "Inclusive" to isInclusive.toString(),
                "Type" to "Range",
            ),
        ),
    ),
)

/**
 * Format a TernaryExpression for hover
 */
fun TernaryExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Ternary Expression",
    content = listOf(
        HoverContent.Code("${booleanExpression.text} ? ${trueExpression.text} : ${falseExpression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Condition" to booleanExpression.text,
                "True Value" to trueExpression.text,
                "False Value" to falseExpression.text,
            ),
        ),
    ),
)

/**
 * Format a CastExpression for hover
 */
fun CastExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Type Cast",
    content = listOf(
        HoverContent.Code("(${type.nameWithoutPackage}) ${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Target Type" to type.nameWithoutPackage,
                "Expression" to expression.text,
                "Coerce" to isCoerce.toString(),
            ),
        ),
    ),
)

/**
 * Format an ElvisOperatorExpression for hover
 */
fun ElvisOperatorExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Elvis Operator",
    content = listOf(
        HoverContent.Code("${trueExpression.text} ?: ${falseExpression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Primary" to trueExpression.text,
                "Default" to falseExpression.text,
                "Usage" to "Returns primary if not null/false, otherwise default",
            ),
        ),
    ),
)

/**
 * Format a MethodPointerExpression for hover
 */
fun MethodPointerExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Method Pointer",
    content = listOf(
        HoverContent.Code("${expression.text}.&${methodName.text}"),
        HoverContent.KeyValue(
            listOf(
                "Object" to expression.text,
                "Method" to methodName.text,
                "Usage" to "Method reference for functional programming",
            ),
        ),
    ),
)

/**
 * Format a ConstructorNode for hover
 */
fun ConstructorNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Constructor",
    content = listOf(
        HoverContent.Code("${declaringClass.nameWithoutPackage}(${parametersString()})"),
        HoverContent.KeyValue(
            listOf(
                "Class" to declaringClass.nameWithoutPackage,
                "Parameters" to parameters.size.toString(),
                "Modifiers" to modifiersString(),
            ),
        ),
    ),
)

/**
 * Format an InnerClassNode for hover
 */
fun InnerClassNode.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Inner Class",
    content = listOf(
        HoverContent.Code("${outerClass?.nameWithoutPackage ?: "unknown"}.$nameWithoutPackage"),
        HoverContent.KeyValue(
            listOf(
                "Name" to nameWithoutPackage,
                "Outer Class" to (outerClass?.nameWithoutPackage ?: "unknown"),
                "Anonymous" to isAnonymous.toString(),
                "Modifiers" to modifiersString(),
            ),
        ),
    ),
)

/**
 * Format a PostfixExpression for hover (e.g., i++)
 */
fun PostfixExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Postfix Expression",
    content = listOf(
        HoverContent.Code("${expression.text}${operation.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Operation" to operation.text,
                "Type" to "Post-increment/decrement",
            ),
        ),
    ),
)

/**
 * Format a PrefixExpression for hover (e.g., ++i)
 */
fun PrefixExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Prefix Expression",
    content = listOf(
        HoverContent.Code("${operation.text}${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Operation" to operation.text,
                "Expression" to expression.text,
                "Type" to "Pre-increment/decrement",
            ),
        ),
    ),
)

/**
 * Format a UnaryMinusExpression for hover (e.g., -value)
 */
fun UnaryMinusExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Unary Minus",
    content = listOf(
        HoverContent.Code("-${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Operation" to "Negation",
            ),
        ),
    ),
)

/**
 * Format a UnaryPlusExpression for hover (e.g., +value)
 */
fun UnaryPlusExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Unary Plus",
    content = listOf(
        HoverContent.Code("+${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Operation" to "Positive",
            ),
        ),
    ),
)

/**
 * Format a NotExpression for hover (e.g., !condition)
 */
fun NotExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Logical NOT",
    content = listOf(
        HoverContent.Code("!${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Operation" to "Logical negation",
            ),
        ),
    ),
)

/**
 * Format a BitwiseNegationExpression for hover (e.g., ~value)
 */
fun BitwiseNegationExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Bitwise NOT",
    content = listOf(
        HoverContent.Code("~${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Operation" to "Bitwise complement",
            ),
        ),
    ),
)

/**
 * Format a BooleanExpression for hover
 */
fun BooleanExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Boolean Expression",
    content = listOf(
        HoverContent.Code(expression.text),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Type" to "Boolean",
            ),
        ),
    ),
)

/**
 * Format a TupleExpression for hover
 */
fun TupleExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Tuple Expression",
    content = listOf(
        HoverContent.Code("(${expressions.joinToString(", ") { it.text }})"),
        HoverContent.KeyValue(
            listOf(
                "Elements" to expressions.size.toString(),
                "Usage" to "Multiple assignment or parameter list",
            ),
        ),
    ),
)

/**
 * Format a SpreadExpression for hover (e.g., *args)
 */
fun SpreadExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Spread Expression",
    content = listOf(
        HoverContent.Code("*${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Usage" to "Spread operator for collections",
            ),
        ),
    ),
)

/**
 * Format a SpreadMapExpression for hover (e.g., *:map)
 */
fun SpreadMapExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Spread Map Expression",
    content = listOf(
        HoverContent.Code("*:${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Usage" to "Spread operator for maps",
            ),
        ),
    ),
)

/**
 * Format a NamedArgumentListExpression for hover
 */
fun NamedArgumentListExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Named Arguments",
    content = listOf(
        HoverContent.Code("named arguments"),
        HoverContent.KeyValue(
            listOf(
                "Type" to "Named parameter list",
                "Usage" to "Method call with named parameters",
            ),
        ),
    ),
)

/**
 * Format a LambdaExpression for hover
 */
fun LambdaExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Lambda Expression",
    content = listOf(
        HoverContent.Code("{ ${parametersString()} -> ... }"),
        HoverContent.KeyValue(
            listOf(
                "Parameters" to parametersString(),
                "Type" to "Lambda/Closure",
            ),
        ),
    ),
)

/**
 * Format a ClosureListExpression for hover
 */
fun ClosureListExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Closure List",
    content = listOf(
        HoverContent.Code("[${expressions.joinToString(", ") { "{ ... }" }}]"),
        HoverContent.KeyValue(
            listOf(
                "Closures" to expressions.size.toString(),
                "Usage" to "List of closure expressions",
            ),
        ),
    ),
)

/**
 * Format an EmptyExpression for hover
 */
fun EmptyExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Empty Expression",
    content = listOf(
        HoverContent.Code("<empty>"),
        HoverContent.KeyValue(
            listOf(
                "Type" to "EmptyExpression",
                "Usage" to "Placeholder or default expression",
            ),
        ),
    ),
)

/**
 * Format a MethodReferenceExpression for hover (e.g., String::valueOf)
 */
fun MethodReferenceExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Method Reference",
    content = listOf(
        HoverContent.Code("${expression.text}::${methodName.text}"),
        HoverContent.KeyValue(
            listOf(
                "Type" to expression.text,
                "Method" to methodName.text,
                "Usage" to "Method reference for functional programming",
            ),
        ),
    ),
)

/**
 * Format an AnnotationConstantExpression for hover
 */
fun AnnotationConstantExpression.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Annotation Constant",
    content = listOf(
        HoverContent.Code(text),
        HoverContent.KeyValue(
            listOf(
                "Value" to text,
                "Type" to "Annotation constant",
            ),
        ),
    ),
)

// ======== PHASE 3: STATEMENT NODES (Reduced Priority) ========

/**
 * Format an ExpressionStatement for hover
 */
fun ExpressionStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Expression Statement",
    content = listOf(
        HoverContent.Code(expression.text),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Type" to "Statement",
            ),
        ),
    ),
)

/**
 * Format a BlockStatement for hover
 */
fun BlockStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Block Statement",
    content = listOf(
        HoverContent.Code("{ ... }"),
        HoverContent.KeyValue(
            listOf(
                "Statements" to statements.size.toString(),
                "Type" to "Code block",
            ),
        ),
    ),
)

/**
 * Format a ReturnStatement for hover
 */
fun ReturnStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Return Statement",
    content = listOf(
        HoverContent.Code("return ${expression?.text ?: ""}"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to (expression?.text ?: "void"),
                "Type" to "Control flow",
            ),
        ),
    ),
)

/**
 * Format an IfStatement for hover
 */
fun IfStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "If Statement",
    content = listOf(
        HoverContent.Code("if (${booleanExpression.text}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Condition" to booleanExpression.text,
                "Has Else" to (elseBlock != null).toString(),
                "Type" to "Conditional",
            ),
        ),
    ),
)

/**
 * Format a ForStatement for hover
 */
fun ForStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "For Loop",
    content = listOf(
        HoverContent.Code("for (${variable.name} in ${collectionExpression.text}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Variable" to variable.name,
                "Collection" to collectionExpression.text,
                "Type" to "Loop",
            ),
        ),
    ),
)

/**
 * Format a WhileStatement for hover
 */
fun WhileStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "While Loop",
    content = listOf(
        HoverContent.Code("while (${booleanExpression.text}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Condition" to booleanExpression.text,
                "Type" to "Loop",
            ),
        ),
    ),
)

/**
 * Format a DoWhileStatement for hover
 */
fun DoWhileStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Do-While Loop",
    content = listOf(
        HoverContent.Code("do { ... } while (${booleanExpression.text})"),
        HoverContent.KeyValue(
            listOf(
                "Condition" to booleanExpression.text,
                "Type" to "Post-test loop",
            ),
        ),
    ),
)

/**
 * Format a SwitchStatement for hover
 */
fun SwitchStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Switch Statement",
    content = listOf(
        HoverContent.Code("switch (${expression.text}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Expression" to expression.text,
                "Cases" to caseStatements.size.toString(),
                "Has Default" to (defaultStatement != null).toString(),
                "Type" to "Control flow",
            ),
        ),
    ),
)

/**
 * Format a CaseStatement for hover
 */
fun CaseStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Case Statement",
    content = listOf(
        HoverContent.Code("case ${expression.text}:"),
        HoverContent.KeyValue(
            listOf(
                "Value" to expression.text,
                "Type" to "Switch case",
            ),
        ),
    ),
)

/**
 * Format a BreakStatement for hover
 */
fun BreakStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Break Statement",
    content = listOf(
        HoverContent.Code("break ${label ?: ""}"),
        HoverContent.KeyValue(
            listOf(
                "Label" to (label ?: "none"),
                "Type" to "Control flow",
            ),
        ),
    ),
)

/**
 * Format a ContinueStatement for hover
 */
fun ContinueStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Continue Statement",
    content = listOf(
        HoverContent.Code("continue ${label ?: ""}"),
        HoverContent.KeyValue(
            listOf(
                "Label" to (label ?: "none"),
                "Type" to "Control flow",
            ),
        ),
    ),
)

/**
 * Format a ThrowStatement for hover
 */
fun ThrowStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Throw Statement",
    content = listOf(
        HoverContent.Code("throw ${expression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Exception" to expression.text,
                "Type" to "Exception handling",
            ),
        ),
    ),
)

/**
 * Format a TryCatchStatement for hover
 */
fun TryCatchStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Try-Catch Statement",
    content = listOf(
        HoverContent.Code("try { ... } catch { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Catch Clauses" to catchStatements.size.toString(),
                "Has Finally" to (finallyStatement != null).toString(),
                "Type" to "Exception handling",
            ),
        ),
    ),
)

/**
 * Format a CatchStatement for hover
 */
fun CatchStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Catch Statement",
    content = listOf(
        HoverContent.Code("catch (${variable.type.nameWithoutPackage} ${variable.name}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Exception Type" to variable.type.nameWithoutPackage,
                "Variable" to variable.name,
                "Type" to "Exception handling",
            ),
        ),
    ),
)

/**
 * Format an AssertStatement for hover
 */
fun AssertStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Assert Statement",
    content = listOf(
        HoverContent.Code("assert ${booleanExpression.text}"),
        HoverContent.KeyValue(
            listOf(
                "Condition" to booleanExpression.text,
                "Message" to (messageExpression?.text ?: "none"),
                "Type" to "Assertion",
            ),
        ),
    ),
)

/**
 * Format a SynchronizedStatement for hover
 */
fun SynchronizedStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Synchronized Statement",
    content = listOf(
        HoverContent.Code("synchronized (${expression.text}) { ... }"),
        HoverContent.KeyValue(
            listOf(
                "Lock Object" to expression.text,
                "Type" to "Concurrency",
            ),
        ),
    ),
)

/**
 * Format an EmptyStatement for hover
 */
fun EmptyStatement.toHoverContent(): HoverContent = HoverContent.Section(
    title = "Empty Statement",
    content = listOf(
        HoverContent.Code(";"),
        HoverContent.KeyValue(
            listOf(
                "Type" to "Empty statement",
                "Usage" to "Placeholder or no-op",
            ),
        ),
    ),
)

/**
 * Generic formatter that dispatches to specific formatters
 */
fun ASTNode.toHoverContent(): HoverContent = when {
    // Declarations and definitions
    isDeclarationNode() -> formatDeclarationNode()
    // Expressions
    isExpressionNode() -> formatExpressionNode()
    // Statements
    isStatementNode() -> formatStatementNode()
    // Annotations and imports
    isMetadataNode() -> formatMetadataNode()
    // Default fallback
    else -> HoverContent.Section(
        "AST Node",
        listOf(
            HoverContent.Text("${this::class.java.simpleName}"),
            HoverContent.Code(toString()),
        ),
    )
}

/**
 * Helper functions for node categorization and formatting
 */
private fun ASTNode.isDeclarationNode(): Boolean = this is MethodNode || this is ClassNode ||
    this is FieldNode || this is PropertyNode || this is Parameter || this is ConstructorNode ||
    this is InnerClassNode

private fun ASTNode.isExpressionNode(): Boolean = isBasicExpressionNode() || isHighPriorityExpressionNode() ||
    isMediumPriorityExpressionNode() || isAdvancedExpressionNode()

private fun ASTNode.isBasicExpressionNode(): Boolean = this is VariableExpression || this is MethodCallExpression ||
    this is BinaryExpression || this is DeclarationExpression || this is ClosureExpression ||
    this is ConstantExpression || this is GStringExpression || this is ClassExpression ||
    this is PropertyExpression || this is ConstructorCallExpression || this is StaticMethodCallExpression ||
    this is FieldExpression || this is AttributeExpression

private fun ASTNode.isHighPriorityExpressionNode(): Boolean =
    this is ArgumentListExpression || this is ArrayExpression || this is ListExpression ||
        this is MapExpression || this is MapEntryExpression || this is RangeExpression ||
        this is TernaryExpression || this is CastExpression || this is ElvisOperatorExpression ||
        this is MethodPointerExpression

private fun ASTNode.isMediumPriorityExpressionNode(): Boolean =
    this is PostfixExpression || this is PrefixExpression || this is UnaryMinusExpression ||
        this is UnaryPlusExpression || this is NotExpression || this is BitwiseNegationExpression ||
        this is BooleanExpression

private fun ASTNode.isAdvancedExpressionNode(): Boolean =
    this is TupleExpression || this is SpreadExpression || this is SpreadMapExpression ||
        this is NamedArgumentListExpression || this is LambdaExpression || this is ClosureListExpression ||
        this is EmptyExpression || this is MethodReferenceExpression || this is AnnotationConstantExpression

private fun ASTNode.isMetadataNode(): Boolean = this is ImportNode || this is PackageNode || this is AnnotationNode

private fun ASTNode.isStatementNode(): Boolean =
    isBasicStatementNode() || isControlFlowStatementNode() || isAdvancedStatementNode()

private fun ASTNode.isBasicStatementNode(): Boolean =
    this is ExpressionStatement || this is BlockStatement || this is ReturnStatement

private fun ASTNode.isControlFlowStatementNode(): Boolean =
    this is IfStatement || this is ForStatement || this is WhileStatement ||
        this is DoWhileStatement || this is SwitchStatement || this is CaseStatement ||
        this is BreakStatement || this is ContinueStatement

private fun ASTNode.isAdvancedStatementNode(): Boolean =
    this is ThrowStatement || this is TryCatchStatement || this is CatchStatement ||
        this is AssertStatement || this is SynchronizedStatement || this is EmptyStatement

private fun ASTNode.formatDeclarationNode(): HoverContent = when (this) {
    is MethodNode -> toHoverContent()
    is ClassNode -> toHoverContent()
    is FieldNode -> toHoverContent()
    is PropertyNode -> toHoverContent()
    is Parameter -> toHoverContent()
    is ConstructorNode -> toHoverContent()
    is InnerClassNode -> toHoverContent()
    else -> HoverContent.Text("Unknown declaration")
}

private fun ASTNode.formatExpressionNode(): HoverContent = when {
    isBasicExpressionNode() -> formatBasicExpression()
    isHighPriorityExpressionNode() -> formatHighPriorityExpression()
    isMediumPriorityExpressionNode() -> formatMediumPriorityExpression()
    isAdvancedExpressionNode() -> formatAdvancedExpression()
    else -> HoverContent.Text("Unknown expression")
}

private fun ASTNode.formatBasicExpression(): HoverContent = when {
    isCallExpression() -> formatCallExpression()
    isAccessExpression() -> formatAccessExpression()
    isValueExpression() -> formatValueExpression()
    isComplexExpression() -> formatComplexExpression()
    else -> HoverContent.Text("Basic expression: ${this::class.simpleName}")
}

/**
 * Checks if the node is a call expression (method, constructor, static method).
 */
private fun ASTNode.isCallExpression(): Boolean = this is MethodCallExpression ||
    this is ConstructorCallExpression ||
    this is StaticMethodCallExpression

/**
 * Formats call expressions (method, constructor, static method calls).
 */
private fun ASTNode.formatCallExpression(): HoverContent = when (this) {
    is MethodCallExpression -> this.toHoverContent()
    is ConstructorCallExpression -> HoverContent.Section("Constructor Call", listOf(HoverContent.Code(this.toString())))
    is StaticMethodCallExpression -> HoverContent.Section(
        "Static Method Call",
        listOf(HoverContent.Code(this.toString())),
    )
    else -> HoverContent.Text("Unknown call expression")
}

/**
 * Checks if the node is an access expression (variable, property, field, attribute).
 */
private fun ASTNode.isAccessExpression(): Boolean = this is VariableExpression ||
    this is PropertyExpression ||
    this is FieldExpression ||
    this is AttributeExpression

/**
 * Formats access expressions (variable, property, field, attribute access).
 */
private fun ASTNode.formatAccessExpression(): HoverContent = when (this) {
    is VariableExpression -> this.toHoverContent()
    is PropertyExpression -> HoverContent.Section("Property Access", listOf(HoverContent.Code(this.toString())))
    is FieldExpression -> HoverContent.Section("Field Access", listOf(HoverContent.Code(this.toString())))
    is AttributeExpression -> HoverContent.Section("Attribute Access", listOf(HoverContent.Code(this.toString())))
    else -> HoverContent.Text("Unknown access expression")
}

/**
 * Checks if the node is a value expression (constant, string, class reference).
 */
private fun ASTNode.isValueExpression(): Boolean = this is ConstantExpression ||
    this is GStringExpression ||
    this is ClassExpression

/**
 * Formats value expressions (constants, strings, class references).
 */
private fun ASTNode.formatValueExpression(): HoverContent = when (this) {
    is ConstantExpression -> this.toHoverContent()
    is GStringExpression -> HoverContent.Section("String Template", listOf(HoverContent.Code(this.toString())))
    is ClassExpression -> HoverContent.Section("Class Reference", listOf(HoverContent.Code(this.toString())))
    else -> HoverContent.Text("Unknown value expression")
}

/**
 * Checks if the node is a complex expression (binary, declaration, closure).
 */
private fun ASTNode.isComplexExpression(): Boolean = this is BinaryExpression ||
    this is DeclarationExpression ||
    this is ClosureExpression

/**
 * Formats complex expressions (binary, declaration, closure expressions).
 */
private fun ASTNode.formatComplexExpression(): HoverContent = when (this) {
    is BinaryExpression -> this.toHoverContent()
    is DeclarationExpression -> this.toHoverContent()
    is ClosureExpression -> this.toHoverContent()
    else -> HoverContent.Text("Unknown complex expression")
}

private fun ASTNode.formatHighPriorityExpression(): HoverContent = when (this) {
    is ArgumentListExpression -> toHoverContent()
    is ArrayExpression -> toHoverContent()
    is ListExpression -> toHoverContent()
    is MapExpression -> toHoverContent()
    is MapEntryExpression -> toHoverContent()
    is RangeExpression -> toHoverContent()
    is TernaryExpression -> toHoverContent()
    is CastExpression -> toHoverContent()
    is ElvisOperatorExpression -> toHoverContent()
    is MethodPointerExpression -> toHoverContent()
    else -> HoverContent.Text("Unknown high priority expression")
}

private fun ASTNode.formatMediumPriorityExpression(): HoverContent = when (this) {
    is PostfixExpression -> toHoverContent()
    is PrefixExpression -> toHoverContent()
    is UnaryMinusExpression -> toHoverContent()
    is UnaryPlusExpression -> toHoverContent()
    is NotExpression -> toHoverContent()
    is BitwiseNegationExpression -> toHoverContent()
    is BooleanExpression -> toHoverContent()
    else -> HoverContent.Text("Unknown medium priority expression")
}

private fun ASTNode.formatAdvancedExpression(): HoverContent = when (this) {
    is TupleExpression -> toHoverContent()
    is SpreadExpression -> toHoverContent()
    is SpreadMapExpression -> toHoverContent()
    is NamedArgumentListExpression -> toHoverContent()
    is LambdaExpression -> toHoverContent()
    is ClosureListExpression -> toHoverContent()
    is EmptyExpression -> toHoverContent()
    is MethodReferenceExpression -> toHoverContent()
    is AnnotationConstantExpression -> toHoverContent()
    else -> HoverContent.Text("Unknown advanced expression")
}

private fun ASTNode.formatMetadataNode(): HoverContent = when (this) {
    is ImportNode -> toHoverContent()
    is PackageNode -> toHoverContent()
    is AnnotationNode -> toHoverContent()
    else -> HoverContent.Text("Unknown metadata")
}

private fun ASTNode.formatStatementNode(): HoverContent = when {
    isBasicStatementNode() -> formatBasicStatement()
    isControlFlowStatementNode() -> formatControlFlowStatement()
    isAdvancedStatementNode() -> formatAdvancedStatement()
    else -> HoverContent.Text("Unknown statement")
}

private fun ASTNode.formatBasicStatement(): HoverContent = when (this) {
    is ExpressionStatement -> toHoverContent()
    is BlockStatement -> toHoverContent()
    is ReturnStatement -> toHoverContent()
    else -> HoverContent.Text("Unknown basic statement")
}

private fun ASTNode.formatControlFlowStatement(): HoverContent = when (this) {
    is IfStatement -> toHoverContent()
    is ForStatement -> toHoverContent()
    is WhileStatement -> toHoverContent()
    is DoWhileStatement -> toHoverContent()
    is SwitchStatement -> toHoverContent()
    is CaseStatement -> toHoverContent()
    is BreakStatement -> toHoverContent()
    is ContinueStatement -> toHoverContent()
    else -> HoverContent.Text("Unknown control flow statement")
}

private fun ASTNode.formatAdvancedStatement(): HoverContent = when (this) {
    is ThrowStatement -> toHoverContent()
    is TryCatchStatement -> toHoverContent()
    is CatchStatement -> toHoverContent()
    is AssertStatement -> toHoverContent()
    is SynchronizedStatement -> toHoverContent()
    is EmptyStatement -> toHoverContent()
    else -> HoverContent.Text("Unknown advanced statement")
}

/**
 * Helper functions for generating strings
 */
private fun MethodNode.signature(): String = buildString {
    if (isStatic) append("static ")
    if (isAbstract) append("abstract ")
    append(modifiersString()).append(" ")
    append(returnType?.nameWithoutPackage ?: "def").append(" ")
    append(name).append("(")
    append(parametersString())
    append(")")
}

private fun ClassNode.classSignature(): String = buildString {
    when {
        isInterface -> append("interface ")
        isEnum -> append("enum ")
        isAbstract -> append("abstract class ")
        else -> append("class ")
    }
    append(nameWithoutPackage)
    superClass?.let { if (it.name != "java.lang.Object") append(" extends ${it.nameWithoutPackage}") }
    if (interfaces.isNotEmpty()) {
        append(" implements ${interfaces.joinToString(", ") { it.nameWithoutPackage }}")
    }
}

private fun ClosureExpression.parametersString(): String =
    parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""

private fun LambdaExpression.parametersString(): String =
    parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""

private fun ConstructorNode.parametersString(): String =
    parameters.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" }

private fun MethodNode.parametersString(): String =
    parameters?.joinToString(", ") { "${it.type.nameWithoutPackage} ${it.name}" } ?: ""

private fun MethodCallExpression.argumentsString(): String =
    if (arguments is org.codehaus.groovy.ast.expr.ArgumentListExpression) {
        val argList = arguments as org.codehaus.groovy.ast.expr.ArgumentListExpression
        argList.expressions.joinToString(", ") { expr ->
            when (expr) {
                is org.codehaus.groovy.ast.expr.ConstantExpression -> {
                    when (expr.type.name) {
                        "java.lang.String" -> "\"${expr.value}\""
                        else -> expr.value.toString()
                    }
                }
                is org.codehaus.groovy.ast.expr.VariableExpression -> expr.name
                else -> expr.text ?: expr.toString()
            }
        }
    } else {
        arguments.toString()
    }

private fun ImportNode.formatImport(): String = buildString {
    append("import ")
    if (isStatic) append("static ")
    append(className)
    if (isStatic && fieldName != null && !isStar) {
        append(".$fieldName")
    }
    if (isStar) append(".*")
    alias?.let { append(" as $it") }
}

private fun ASTNode.modifiersString(): String = buildString {
    val modifiers = when (val node = this@modifiersString) {
        is MethodNode -> node.modifiers
        is FieldNode -> node.modifiers
        is ClassNode -> node.modifiers
        else -> 0
    }

    val parts = mutableListOf<String>()
    if (java.lang.reflect.Modifier.isPublic(modifiers)) parts += "public"
    if (java.lang.reflect.Modifier.isPrivate(modifiers)) parts += "private"
    if (java.lang.reflect.Modifier.isProtected(modifiers)) parts += "protected"
    if (java.lang.reflect.Modifier.isStatic(modifiers)) parts += "static"
    if (java.lang.reflect.Modifier.isFinal(modifiers)) parts += "final"
    if (java.lang.reflect.Modifier.isAbstract(modifiers)) parts += "abstract"

    append(parts.joinToString(" "))
}

/**
 * Main entry point for creating hover from any AST node
 */
fun createHoverFor(node: ASTNode): LspResult<Hover> = hover {
    when (val nodeContent = node.toHoverContent()) {
        is HoverContent.Text -> text(nodeContent.value)
        is HoverContent.Code -> code(nodeContent.language, nodeContent.value)
        is HoverContent.Markdown -> markdown(nodeContent.value)
        is HoverContent.Section -> section(nodeContent.title) {
            nodeContent.content.forEach { item: HoverContent ->
                when (item) {
                    is HoverContent.Text -> text(item.value)
                    is HoverContent.Code -> code(item.language, item.value)
                    is HoverContent.Markdown -> markdown(item.value)
                    is HoverContent.List -> list(item.items)
                    is HoverContent.KeyValue -> keyValue(item.pairs)
                    else -> {}
                }
            }
        }
        is HoverContent.List -> list(nodeContent.items)
        is HoverContent.KeyValue -> keyValue(nodeContent.pairs)
    }
}.toLspResult()
