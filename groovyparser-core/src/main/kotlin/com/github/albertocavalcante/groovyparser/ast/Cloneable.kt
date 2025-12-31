package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.body.TypeDeclaration
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
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
import com.github.albertocavalcante.groovyparser.ast.stmt.ContinueStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * Provides deep cloning capability for AST nodes.
 *
 * Similar to JavaParser's CloneVisitor.
 */
object NodeCloner {

    /**
     * Deep clones a node and all its children.
     * The cloned node will have no parent set.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Node> clone(node: T): T = when (node) {
        is CompilationUnit -> cloneCompilationUnit(node) as T
        is ClassDeclaration -> cloneClassDeclaration(node) as T
        is MethodDeclaration -> cloneMethodDeclaration(node) as T
        is FieldDeclaration -> cloneFieldDeclaration(node) as T
        is ConstructorDeclaration -> cloneConstructorDeclaration(node) as T
        is Parameter -> cloneParameter(node) as T
        is BlockStatement -> cloneBlockStatement(node) as T
        is ExpressionStatement -> cloneExpressionStatement(node) as T
        is IfStatement -> cloneIfStatement(node) as T
        is ForStatement -> cloneForStatement(node) as T
        is WhileStatement -> cloneWhileStatement(node) as T
        is ReturnStatement -> cloneReturnStatement(node) as T
        is TryCatchStatement -> cloneTryCatchStatement(node) as T
        is CatchClause -> cloneCatchClause(node) as T
        is SwitchStatement -> cloneSwitchStatement(node) as T
        is CaseStatement -> cloneCaseStatement(node) as T
        is ThrowStatement -> cloneThrowStatement(node) as T
        is AssertStatement -> cloneAssertStatement(node) as T
        is BreakStatement -> cloneBreakStatement(node) as T
        is ContinueStatement -> cloneContinueStatement(node) as T
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
        is ImportDeclaration -> cloneImportDeclaration(node) as T
        is PackageDeclaration -> clonePackageDeclaration(node) as T
        is LineComment -> cloneLineComment(node) as T
        is BlockComment -> cloneBlockComment(node) as T
        is JavadocComment -> cloneJavadocComment(node) as T
        else -> throw UnsupportedOperationException("Cloning not supported for ${node::class.simpleName}")
    }

    private fun cloneRange(range: Range?): Range? = range?.let { Range(it.begin, it.end) }

    private fun cloneCompilationUnit(node: CompilationUnit): CompilationUnit {
        val cloned = CompilationUnit()
        node.packageDeclaration.ifPresent { cloned.setPackageDeclaration(clone(it)) }
        node.imports.forEach { cloned.addImport(clone(it)) }
        node.types.forEach { cloned.addType(clone(it) as TypeDeclaration) }
        cloned.range = cloneRange(node.range)
        node.comment?.let { cloned.setComment(clone(it) as Comment) }
        node.orphanComments.forEach { cloned.addOrphanComment(clone(it) as Comment) }
        return cloned
    }

    private fun cloneClassDeclaration(node: ClassDeclaration): ClassDeclaration {
        val cloned = ClassDeclaration(
            name = node.name,
            isInterface = node.isInterface,
            isEnum = node.isEnum,
            isScript = node.isScript,
        )
        cloned.superClass = node.superClass
        node.implementedTypes.forEach { cloned.implementedTypes.add(it) }
        node.fields.forEach { cloned.addField(clone(it)) }
        node.methods.forEach { cloned.addMethod(clone(it)) }
        node.constructors.forEach { cloned.addConstructor(clone(it)) }
        cloned.range = cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it) as Comment) }
        return cloned
    }

    private fun cloneMethodDeclaration(node: MethodDeclaration): MethodDeclaration {
        val cloned = MethodDeclaration(name = node.name, returnType = node.returnType)
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.isStatic = node.isStatic
        cloned.isAbstract = node.isAbstract
        cloned.isFinal = node.isFinal
        cloned.range = cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it) as Comment) }
        return cloned
    }

    private fun cloneFieldDeclaration(node: FieldDeclaration): FieldDeclaration {
        val cloned = FieldDeclaration(name = node.name, type = node.type)
        cloned.isStatic = node.isStatic
        cloned.isFinal = node.isFinal
        cloned.hasInitializer = node.hasInitializer
        cloned.range = cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it) as Comment) }
        return cloned
    }

    private fun cloneConstructorDeclaration(node: ConstructorDeclaration): ConstructorDeclaration {
        val cloned = ConstructorDeclaration(name = node.name)
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        cloned.range = cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it) as Comment) }
        return cloned
    }

    private fun cloneParameter(node: Parameter): Parameter {
        val cloned = Parameter(name = node.name, type = node.type)
        cloned.range = cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        return cloned
    }

    // Statement clones
    private fun cloneBlockStatement(node: BlockStatement): BlockStatement {
        val cloned = BlockStatement()
        node.statements.forEach { cloned.addStatement(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneExpressionStatement(node: ExpressionStatement): ExpressionStatement {
        val cloned = ExpressionStatement(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneIfStatement(node: IfStatement): IfStatement {
        val cloned = IfStatement(
            condition = clone(node.condition),
            thenStatement = clone(node.thenStatement),
            elseStatement = node.elseStatement?.let { clone(it) },
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneForStatement(node: ForStatement): ForStatement {
        val cloned = ForStatement(
            variableName = node.variableName,
            collectionExpression = clone(node.collectionExpression),
            body = clone(node.body),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneWhileStatement(node: WhileStatement): WhileStatement {
        val cloned = WhileStatement(
            condition = clone(node.condition),
            body = clone(node.body),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneReturnStatement(node: ReturnStatement): ReturnStatement {
        val cloned = ReturnStatement(node.expression?.let { clone(it) })
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneTryCatchStatement(node: TryCatchStatement): TryCatchStatement {
        val cloned = TryCatchStatement(clone(node.tryBlock))
        node.catchClauses.forEach { cloned.catchClauses.add(cloneCatchClause(it)) }
        node.finallyBlock?.let { cloned.finallyBlock = clone(it) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneCatchClause(node: CatchClause): CatchClause {
        val cloned = CatchClause(
            parameter = clone(node.parameter),
            body = clone(node.body),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneSwitchStatement(node: SwitchStatement): SwitchStatement {
        val cloned = SwitchStatement(clone(node.expression))
        node.cases.forEach { cloned.cases.add(cloneCaseStatement(it)) }
        node.defaultCase?.let { cloned.defaultCase = clone(it) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneCaseStatement(node: CaseStatement): CaseStatement {
        val cloned = CaseStatement(
            expression = clone(node.expression),
            body = clone(node.body),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneThrowStatement(node: ThrowStatement): ThrowStatement {
        val cloned = ThrowStatement(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneAssertStatement(node: AssertStatement): AssertStatement {
        val cloned = AssertStatement(
            condition = clone(node.condition),
            message = node.message?.let { clone(it) },
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneBreakStatement(node: BreakStatement): BreakStatement {
        val cloned = BreakStatement(node.label)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneContinueStatement(node: ContinueStatement): ContinueStatement {
        val cloned = ContinueStatement(node.label)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    // Expression clones
    private fun cloneMethodCallExpr(node: MethodCallExpr): MethodCallExpr {
        val cloned = MethodCallExpr(
            objectExpression = node.objectExpression?.let { clone(it) },
            methodName = node.methodName,
        )
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneVariableExpr(node: VariableExpr): VariableExpr {
        val cloned = VariableExpr(node.name)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneConstantExpr(node: ConstantExpr): ConstantExpr {
        val cloned = ConstantExpr(node.value)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneBinaryExpr(node: BinaryExpr): BinaryExpr {
        val cloned = BinaryExpr(
            left = clone(node.left),
            operator = node.operator,
            right = clone(node.right),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun clonePropertyExpr(node: PropertyExpr): PropertyExpr {
        val cloned = PropertyExpr(
            objectExpression = clone(node.objectExpression),
            propertyName = node.propertyName,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneClosureExpr(node: ClosureExpr): ClosureExpr {
        val cloned = ClosureExpr()
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneGStringExpr(node: GStringExpr): GStringExpr {
        val cloned = GStringExpr()
        node.strings.forEach { cloned.addString(it) }
        node.expressions.forEach { cloned.addExpression(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneListExpr(node: ListExpr): ListExpr {
        val cloned = ListExpr(node.elements.map { clone(it) })
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneMapExpr(node: MapExpr): MapExpr {
        val cloned = MapExpr(node.entries.map { clone(it) as MapEntryExpr })
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneMapEntryExpr(node: MapEntryExpr): MapEntryExpr {
        val cloned = MapEntryExpr(
            key = clone(node.key),
            value = clone(node.value),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneRangeExpr(node: RangeExpr): RangeExpr {
        val cloned = RangeExpr(
            from = clone(node.from),
            to = clone(node.to),
            inclusive = node.inclusive,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneTernaryExpr(node: TernaryExpr): TernaryExpr {
        val cloned = TernaryExpr(
            condition = clone(node.condition),
            trueExpression = clone(node.trueExpression),
            falseExpression = clone(node.falseExpression),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneUnaryExpr(node: UnaryExpr): UnaryExpr {
        val cloned = UnaryExpr(
            operator = node.operator,
            expression = clone(node.expression),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneCastExpr(node: CastExpr): CastExpr {
        val cloned = CastExpr(
            targetType = node.targetType,
            expression = clone(node.expression),
            isCoercion = node.isCoercion,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneConstructorCallExpr(node: ConstructorCallExpr): ConstructorCallExpr {
        val cloned = ConstructorCallExpr(node.typeName)
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneElvisExpr(node: ElvisExpr): ElvisExpr {
        val cloned = ElvisExpr(
            expression = clone(node.expression),
            defaultValue = clone(node.defaultValue),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneSpreadExpr(node: SpreadExpr): SpreadExpr {
        val cloned = SpreadExpr(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneSpreadMapExpr(node: SpreadMapExpr): SpreadMapExpr {
        val cloned = SpreadMapExpr(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneAttributeExpr(node: AttributeExpr): AttributeExpr {
        val cloned = AttributeExpr(
            objectExpression = clone(node.objectExpression),
            attribute = node.attribute,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneMethodPointerExpr(node: MethodPointerExpr): MethodPointerExpr {
        val cloned = MethodPointerExpr(
            objectExpression = clone(node.objectExpression),
            methodName = clone(node.methodName),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneMethodReferenceExpr(node: MethodReferenceExpr): MethodReferenceExpr {
        val cloned = MethodReferenceExpr(
            objectExpression = clone(node.objectExpression),
            methodName = clone(node.methodName),
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneLambdaExpr(node: LambdaExpr): LambdaExpr {
        val cloned = LambdaExpr()
        node.parameters.forEach { cloned.parameters.add(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneDeclarationExpr(node: DeclarationExpr): DeclarationExpr {
        val cloned = DeclarationExpr(
            variableExpression = clone(node.variableExpression),
            rightExpression = clone(node.rightExpression),
            type = node.type,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneClassExpr(node: ClassExpr): ClassExpr {
        val cloned = ClassExpr(node.className)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneArrayExpr(node: ArrayExpr): ArrayExpr {
        val cloned = ArrayExpr(
            elementType = node.elementType,
            sizeExpressions = node.sizeExpressions.map { clone(it) },
            initExpressions = node.initExpressions.map { clone(it) },
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun clonePostfixExpr(node: PostfixExpr): PostfixExpr {
        val cloned = PostfixExpr(
            expression = clone(node.expression),
            operator = node.operator,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun clonePrefixExpr(node: PrefixExpr): PrefixExpr {
        val cloned = PrefixExpr(
            expression = clone(node.expression),
            operator = node.operator,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneNotExpr(node: NotExpr): NotExpr {
        val cloned = NotExpr(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneBitwiseNegationExpr(node: BitwiseNegationExpr): BitwiseNegationExpr {
        val cloned = BitwiseNegationExpr(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    // Other nodes
    private fun cloneAnnotationExpr(node: AnnotationExpr): AnnotationExpr {
        val cloned = AnnotationExpr(node.name)
        node.members.forEach { (key, value) ->
            cloned.members[key] = clone(value)
        }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneImportDeclaration(node: ImportDeclaration): ImportDeclaration {
        val cloned = ImportDeclaration(
            name = node.name,
            isStatic = node.isStatic,
            isStarImport = node.isStarImport,
        )
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun clonePackageDeclaration(node: PackageDeclaration): PackageDeclaration {
        val cloned = PackageDeclaration(node.name)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    // Comments
    private fun cloneLineComment(node: LineComment): LineComment = LineComment(node.content, cloneRange(node.range))

    private fun cloneBlockComment(node: BlockComment): BlockComment = BlockComment(node.content, cloneRange(node.range))

    private fun cloneJavadocComment(node: JavadocComment): JavadocComment =
        JavadocComment(node.content, cloneRange(node.range))

    // MoreExpressions
    private fun cloneFieldExpr(node: FieldExpr): FieldExpr {
        val cloned = FieldExpr(clone(node.scope), node.fieldName)
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneStaticMethodCallExpr(node: StaticMethodCallExpr): StaticMethodCallExpr {
        val cloned = StaticMethodCallExpr(node.ownerType, node.methodName)
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneTupleExpr(node: TupleExpr): TupleExpr {
        val cloned = TupleExpr()
        node.elements.forEach { cloned.addElement(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneBooleanExpr(node: BooleanExpr): BooleanExpr {
        val cloned = BooleanExpr(clone(node.expression))
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneClosureListExpr(node: ClosureListExpr): ClosureListExpr {
        val cloned = ClosureListExpr()
        node.expressions.forEach { cloned.addExpression(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    @Suppress("UnusedParameter")
    private fun cloneEmptyExpr(node: EmptyExpr): EmptyExpr = EmptyExpr

    private fun cloneNamedArgumentListExpr(node: NamedArgumentListExpr): NamedArgumentListExpr {
        val cloned = NamedArgumentListExpr()
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }

    private fun cloneArgumentListExpr(node: ArgumentListExpr): ArgumentListExpr {
        val cloned = ArgumentListExpr()
        node.arguments.forEach { cloned.addArgument(clone(it)) }
        cloned.range = cloneRange(node.range)
        return cloned
    }
}

/**
 * Extension function for easy cloning.
 */
fun <T : Node> T.clone(): T = NodeCloner.clone(this)
