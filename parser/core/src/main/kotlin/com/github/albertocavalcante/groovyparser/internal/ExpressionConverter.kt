package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.AttributeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BitwiseNegationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClassExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ElvisExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.LambdaExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadMapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.MethodReferenceExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Converts binary, unary, ternary, and other complex expressions.
 *
 * Handles ~420 lines of expression conversion logic.
 */
@Suppress("TooManyFunctions") // Converter pattern requires one function per expression type
internal class ExpressionConverter(private val setRange: (Node, ASTNode) -> Unit) {

    /**
     * Converts a Groovy method call expression.
     */
    fun convertMethodCall(
        expr: MethodCallExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): MethodCallExpr {
        val objectExpr = if (expr.isImplicitThis) {
            null
        } else {
            convertExpr(expr.objectExpression)
        }
        val methodName = expr.methodAsString ?: expr.method?.text ?: "unknown"
        val call = MethodCallExpr(objectExpr, methodName)

        // Convert arguments
        val args = expr.arguments
        if (args is ArgumentListExpression) {
            args.expressions?.forEach { arg ->
                call.addArgument(convertExpr(arg))
            }
        }

        setRange(call, expr)
        return call
    }

    /**
     * Converts a Groovy variable expression.
     */
    fun convertVariable(expr: VariableExpression): VariableExpr {
        val variable = VariableExpr(expr.name)
        setRange(variable, expr)
        return variable
    }

    /**
     * Converts a Groovy binary expression (e.g., a + b, x = y).
     */
    fun convertBinary(
        expr: BinaryExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): BinaryExpr {
        val left = convertExpr(expr.leftExpression)
        val right = convertExpr(expr.rightExpression)
        val operator = expr.operation?.text ?: "?"
        val binary = BinaryExpr(left, operator, right)
        setRange(binary, expr)
        return binary
    }

    /**
     * Converts a Groovy property expression (e.g., obj.property).
     */
    fun convertProperty(
        expr: PropertyExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): PropertyExpr {
        val objectExpr = convertExpr(expr.objectExpression)
        val propertyName = expr.propertyAsString ?: expr.property?.text ?: "unknown"
        val prop = PropertyExpr(objectExpr, propertyName)
        setRange(prop, expr)
        return prop
    }

    /**
     * Converts a Groovy closure expression.
     */
    fun convertClosure(
        expr: ClosureExpression,
        convertStmt: (org.codehaus.groovy.ast.stmt.Statement) -> Statement?,
    ): ClosureExpr {
        val closure = ClosureExpr()

        // Convert parameters
        expr.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            closure.addParameter(parameter)
        }

        // Convert body
        expr.code?.let { code ->
            closure.body = convertStmt(code)
        }

        setRange(closure, expr)
        return closure
    }

    /**
     * Converts a Groovy ternary expression (condition ? trueExpr : falseExpr).
     */
    fun convertTernary(
        expr: TernaryExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): TernaryExpr {
        val condition = convertExpr(expr.booleanExpression.expression)
        val trueExpr = convertExpr(expr.trueExpression)
        val falseExpr = convertExpr(expr.falseExpression)
        val ternary = TernaryExpr(condition, trueExpr, falseExpr)
        setRange(ternary, expr)
        return ternary
    }

    /**
     * Converts a Groovy NOT expression (!expr).
     */
    fun convertNot(
        expr: NotExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): UnaryExpr {
        val inner = convertExpr(expr.expression)
        val unary = UnaryExpr(inner, "!", true)
        setRange(unary, expr)
        return unary
    }

    /**
     * Converts a Groovy unary minus expression (-expr).
     */
    fun convertUnaryMinus(
        expr: UnaryMinusExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): UnaryExpr {
        val inner = convertExpr(expr.expression)
        val unary = UnaryExpr(inner, "-", true)
        setRange(unary, expr)
        return unary
    }

    /**
     * Converts a Groovy unary plus expression (+expr).
     */
    fun convertUnaryPlus(
        expr: UnaryPlusExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): UnaryExpr {
        val inner = convertExpr(expr.expression)
        val unary = UnaryExpr(inner, "+", true)
        setRange(unary, expr)
        return unary
    }

    /**
     * Converts a Groovy prefix expression (++x, --x).
     */
    fun convertPrefix(
        expr: PrefixExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): UnaryExpr {
        val inner = convertExpr(expr.expression)
        val unary = UnaryExpr(inner, expr.operation?.text ?: "++", true)
        setRange(unary, expr)
        return unary
    }

    /**
     * Converts a Groovy postfix expression (x++, x--).
     */
    fun convertPostfix(
        expr: PostfixExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): UnaryExpr {
        val inner = convertExpr(expr.expression)
        val unary = UnaryExpr(inner, expr.operation?.text ?: "++", false)
        setRange(unary, expr)
        return unary
    }

    /**
     * Converts a Groovy cast expression (e.g., (String) value).
     */
    fun convertCast(
        expr: CastExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): CastExpr {
        val inner = convertExpr(expr.expression)
        val targetType = expr.type?.name ?: "Object"
        val cast = CastExpr(inner, targetType, expr.isCoerce)
        setRange(cast, expr)
        return cast
    }

    /**
     * Converts a Groovy constructor call expression (e.g., new ArrayList()).
     */
    fun convertConstructorCall(
        expr: ConstructorCallExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ConstructorCallExpr {
        val typeName = expr.type?.name ?: "Object"
        val constructorCall = ConstructorCallExpr(typeName)

        val args = expr.arguments
        if (args is ArgumentListExpression) {
            args.expressions?.forEach { arg ->
                constructorCall.addArgument(convertExpr(arg))
            }
        }

        setRange(constructorCall, expr)
        return constructorCall
    }

    /**
     * Converts a Groovy Elvis operator expression (value ?: defaultValue).
     */
    fun convertElvis(
        expr: ElvisOperatorExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ElvisExpr {
        val value = convertExpr(expr.trueExpression)
        val defaultValue = convertExpr(expr.falseExpression)
        val elvis = ElvisExpr(value, defaultValue)
        setRange(elvis, expr)
        return elvis
    }

    /**
     * Converts a Groovy bitwise negation expression (~expr).
     */
    fun convertBitwiseNegation(
        expr: BitwiseNegationExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): BitwiseNegationExpr {
        val inner = convertExpr(expr.expression)
        val bitwise = BitwiseNegationExpr(inner)
        setRange(bitwise, expr)
        return bitwise
    }

    /**
     * Converts a Groovy class expression (e.g., String.class).
     */
    fun convertClass(expr: ClassExpression): ClassExpr {
        val classExpr = ClassExpr(expr.type?.name ?: "Object")
        setRange(classExpr, expr)
        return classExpr
    }

    /**
     * Converts a Groovy spread expression (*list).
     */
    fun convertSpread(
        expr: SpreadExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): SpreadExpr {
        val inner = convertExpr(expr.expression)
        val spread = SpreadExpr(inner)
        setRange(spread, expr)
        return spread
    }

    /**
     * Converts a Groovy spread map expression (*:map).
     */
    fun convertSpreadMap(
        expr: SpreadMapExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): SpreadMapExpr {
        val inner = convertExpr(expr.expression)
        val spreadMap = SpreadMapExpr(inner)
        setRange(spreadMap, expr)
        return spreadMap
    }

    /**
     * Converts a Groovy method pointer expression (obj.&method).
     */
    fun convertMethodPointer(
        expr: MethodPointerExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): MethodPointerExpr {
        val obj = convertExpr(expr.expression)
        val method = convertExpr(expr.methodName)
        val methodPointer = MethodPointerExpr(obj, method)
        setRange(methodPointer, expr)
        return methodPointer
    }

    /**
     * Converts a Groovy method reference expression (Class::method).
     */
    fun convertMethodReference(
        expr: MethodReferenceExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): MethodReferenceExpr {
        val obj = convertExpr(expr.expression)
        val method = convertExpr(expr.methodName)
        val methodRef = MethodReferenceExpr(obj, method)
        setRange(methodRef, expr)
        return methodRef
    }

    /**
     * Converts a Groovy lambda expression.
     */
    fun convertLambda(
        expr: LambdaExpression,
        convertStmt: (org.codehaus.groovy.ast.stmt.Statement) -> Statement?,
    ): LambdaExpr {
        val lambda = LambdaExpr()

        expr.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            lambda.addParameter(parameter)
        }

        expr.code?.let { code ->
            lambda.body = convertStmt(code)
        }

        setRange(lambda, expr)
        return lambda
    }

    /**
     * Converts a Groovy declaration expression (def x = 1, String y = "hello").
     */
    fun convertDeclaration(
        expr: DeclarationExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): DeclarationExpr {
        val variable = convertExpr(expr.leftExpression)
        val right = convertExpr(expr.rightExpression)
        val typeName = expr.leftExpression.type?.name ?: "def"
        val declaration = DeclarationExpr(variable, right, typeName)
        setRange(declaration, expr)
        return declaration
    }

    /**
     * Converts a Groovy attribute expression (obj.@field - direct field access).
     */
    fun convertAttribute(
        expr: AttributeExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): AttributeExpr {
        val obj = convertExpr(expr.objectExpression)
        val attrName = expr.propertyAsString ?: expr.property?.text ?: "unknown"
        val attribute = AttributeExpr(obj, attrName)
        setRange(attribute, expr)
        return attribute
    }
}
