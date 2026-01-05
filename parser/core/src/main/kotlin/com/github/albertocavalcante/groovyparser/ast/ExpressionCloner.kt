package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.expr.ArgumentListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ArrayExpr
import com.github.albertocavalcante.groovyparser.ast.expr.AttributeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BitwiseNegationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BooleanExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClassExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ElvisExpr
import com.github.albertocavalcante.groovyparser.ast.expr.EmptyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.FieldExpr
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.LambdaExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
import com.github.albertocavalcante.groovyparser.ast.expr.NamedArgumentListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.NotExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PostfixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PrefixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadMapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.StaticMethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TupleExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr

@Suppress("TooManyFunctions")
internal object ExpressionCloner {

    @Suppress("CyclomaticComplexMethod")
    fun <T : Expression> clone(node: T): T = when (node) {
        is MethodCallExpr -> cloneMethodCallExpr(node) as T
        is VariableExpr -> cloneVariableExpr(node) as T
        is ConstantExpr -> cloneConstantExpr(node) as T
        is BinaryExpr -> cloneBinaryExpr(node) as T
        is PropertyExpr -> clonePropertyExpr(node) as T
        is ClosureExpr -> cloneClosureExpr(node) as T
        is GStringExpr -> cloneGStringExpr(node) as T
        is ListExpr -> cloneListExpr(node) as T
        is MapExpr -> cloneMapExpr(node) as T
        is MapEntryExpr -> cloneMapEntryExpr(node) as T
        is RangeExpr -> cloneRangeExpr(node) as T
        is TernaryExpr -> cloneTernaryExpr(node) as T
        is UnaryExpr -> cloneUnaryExpr(node) as T
        is CastExpr -> cloneCastExpr(node) as T
        is ConstructorCallExpr -> cloneConstructorCallExpr(node) as T
        is ElvisExpr -> cloneElvisExpr(node) as T
        is SpreadExpr -> cloneSpreadExpr(node) as T
        is SpreadMapExpr -> cloneSpreadMapExpr(node) as T
        is AttributeExpr -> cloneAttributeExpr(node) as T
        is MethodPointerExpr -> cloneMethodPointerExpr(node) as T
        is MethodReferenceExpr -> cloneMethodReferenceExpr(node) as T
        is LambdaExpr -> cloneLambdaExpr(node) as T
        is DeclarationExpr -> cloneDeclarationExpr(node) as T
        is ClassExpr -> cloneClassExpr(node) as T
        is ArrayExpr -> cloneArrayExpr(node) as T
        is PostfixExpr -> clonePostfixExpr(node) as T
        is PrefixExpr -> clonePrefixExpr(node) as T
        is NotExpr -> cloneNotExpr(node) as T
        is BitwiseNegationExpr -> cloneBitwiseNegationExpr(node) as T
        is FieldExpr -> cloneFieldExpr(node) as T
        is StaticMethodCallExpr -> cloneStaticMethodCallExpr(node) as T
        is TupleExpr -> cloneTupleExpr(node) as T
        is BooleanExpr -> cloneBooleanExpr(node) as T
        is ClosureListExpr -> cloneClosureListExpr(node) as T
        is EmptyExpr -> cloneEmptyExpr(node) as T
        is NamedArgumentListExpr -> cloneNamedArgumentListExpr(node) as T
        is ArgumentListExpr -> cloneArgumentListExpr(node) as T
        is AnnotationExpr -> cloneAnnotationExpr(node) as T
        else -> throw UnsupportedOperationException("Cloning not supported for ${node::class.simpleName}")
    }

    private fun <T : Node> clone(node: T): T = NodeCloner.clone(node)

    private fun cloneMethodCallExpr(node: MethodCallExpr): MethodCallExpr {
        val cloned = MethodCallExpr(
            objectExpression = node.objectExpression?.let { clone(it) },
            methodName = node.methodName,
        )
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneVariableExpr(node: VariableExpr): VariableExpr {
        val cloned = VariableExpr(node.name)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneConstantExpr(node: ConstantExpr): ConstantExpr {
        val cloned = ConstantExpr(node.value)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneBinaryExpr(node: BinaryExpr): BinaryExpr {
        val cloned = BinaryExpr(
            left = clone(node.left),
            operator = node.operator,
            right = clone(node.right),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun clonePropertyExpr(node: PropertyExpr): PropertyExpr {
        val cloned = PropertyExpr(
            objectExpression = clone(node.objectExpression),
            propertyName = node.propertyName,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneClosureExpr(node: ClosureExpr): ClosureExpr {
        val cloned = ClosureExpr()
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneGStringExpr(node: GStringExpr): GStringExpr {
        val cloned = GStringExpr()
        node.strings.forEach { cloned.addString(it) }
        node.expressions.forEach { cloned.addExpression(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneListExpr(node: ListExpr): ListExpr {
        val cloned = ListExpr(node.elements.map { clone(it) })
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneMapExpr(node: MapExpr): MapExpr {
        val cloned = MapExpr(node.entries.map { clone(it) })
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneMapEntryExpr(node: MapEntryExpr): MapEntryExpr {
        val cloned = MapEntryExpr(
            key = clone(node.key),
            value = clone(node.value),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneRangeExpr(node: RangeExpr): RangeExpr {
        val cloned = RangeExpr(
            from = clone(node.from),
            to = clone(node.to),
            inclusive = node.inclusive,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneTernaryExpr(node: TernaryExpr): TernaryExpr {
        val cloned = TernaryExpr(
            condition = clone(node.condition),
            trueExpression = clone(node.trueExpression),
            falseExpression = clone(node.falseExpression),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneUnaryExpr(node: UnaryExpr): UnaryExpr {
        val cloned = UnaryExpr(
            operator = node.operator,
            expression = clone(node.expression),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneCastExpr(node: CastExpr): CastExpr {
        val cloned = CastExpr(
            targetType = node.targetType,
            expression = clone(node.expression),
            isCoercion = node.isCoercion,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneConstructorCallExpr(node: ConstructorCallExpr): ConstructorCallExpr {
        val cloned = ConstructorCallExpr(node.typeName)
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneElvisExpr(node: ElvisExpr): ElvisExpr {
        val cloned = ElvisExpr(
            expression = clone(node.expression),
            defaultValue = clone(node.defaultValue),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneSpreadExpr(node: SpreadExpr): SpreadExpr {
        val cloned = SpreadExpr(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneSpreadMapExpr(node: SpreadMapExpr): SpreadMapExpr {
        val cloned = SpreadMapExpr(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneAttributeExpr(node: AttributeExpr): AttributeExpr {
        val cloned = AttributeExpr(
            objectExpression = clone(node.objectExpression),
            attribute = node.attribute,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneMethodPointerExpr(node: MethodPointerExpr): MethodPointerExpr {
        val cloned = MethodPointerExpr(
            objectExpression = clone(node.objectExpression),
            methodName = clone(node.methodName),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneMethodReferenceExpr(node: MethodReferenceExpr): MethodReferenceExpr {
        val cloned = MethodReferenceExpr(
            objectExpression = clone(node.objectExpression),
            methodName = clone(node.methodName),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneLambdaExpr(node: LambdaExpr): LambdaExpr {
        val cloned = LambdaExpr()
        node.parameters.forEach { cloned.parameters.add(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneDeclarationExpr(node: DeclarationExpr): DeclarationExpr {
        val cloned = DeclarationExpr(
            variableExpression = clone(node.variableExpression),
            rightExpression = clone(node.rightExpression),
            type = node.type,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneClassExpr(node: ClassExpr): ClassExpr {
        val cloned = ClassExpr(node.className)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneArrayExpr(node: ArrayExpr): ArrayExpr {
        val cloned = ArrayExpr(
            elementType = node.elementType,
            sizeExpressions = node.sizeExpressions.map { clone(it) },
            initExpressions = node.initExpressions.map { clone(it) },
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun clonePostfixExpr(node: PostfixExpr): PostfixExpr {
        val cloned = PostfixExpr(
            expression = clone(node.expression),
            operator = node.operator,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun clonePrefixExpr(node: PrefixExpr): PrefixExpr {
        val cloned = PrefixExpr(
            expression = clone(node.expression),
            operator = node.operator,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneNotExpr(node: NotExpr): NotExpr {
        val cloned = NotExpr(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneBitwiseNegationExpr(node: BitwiseNegationExpr): BitwiseNegationExpr {
        val cloned = BitwiseNegationExpr(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneAnnotationExpr(node: AnnotationExpr): AnnotationExpr {
        val cloned = AnnotationExpr(node.name)
        node.members.forEach { (key, value) ->
            cloned.members[key] = clone(value)
        }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneFieldExpr(node: FieldExpr): FieldExpr {
        val cloned = FieldExpr(clone(node.scope), node.fieldName)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneStaticMethodCallExpr(node: StaticMethodCallExpr): StaticMethodCallExpr {
        val cloned = StaticMethodCallExpr(node.ownerType, node.methodName)
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneTupleExpr(node: TupleExpr): TupleExpr {
        val cloned = TupleExpr()
        node.elements.forEach { cloned.addElement(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneBooleanExpr(node: BooleanExpr): BooleanExpr {
        val cloned = BooleanExpr(clone(node.expression))
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneClosureListExpr(node: ClosureListExpr): ClosureListExpr {
        val cloned = ClosureListExpr()
        node.expressions.forEach { cloned.addExpression(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneEmptyExpr(node: EmptyExpr): EmptyExpr {
        val cloned = EmptyExpr()
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it)) }
        node.orphanComments.forEach { cloned.addOrphanComment(clone(it)) }
        return cloned
    }

    private fun cloneNamedArgumentListExpr(node: NamedArgumentListExpr): NamedArgumentListExpr {
        val cloned = NamedArgumentListExpr()
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneArgumentListExpr(node: ArgumentListExpr): ArgumentListExpr {
        val cloned = ArgumentListExpr()
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }
}
