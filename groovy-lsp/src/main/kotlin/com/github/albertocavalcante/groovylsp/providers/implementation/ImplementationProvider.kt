package com.github.albertocavalcante.groovylsp.providers.implementation

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for finding implementations of interfaces and abstract methods.
 *
 * Supports:
 * - Interface -> Concrete classes implementing it
 * - Interface method -> Concrete method implementations
 * - Abstract method -> Concrete overrides
 */
class ImplementationProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(ImplementationProvider::class.java)

    /**
     * Find all implementations for the symbol at the given position.
     */
    @Suppress("TooGenericExceptionCaught")
    fun provideImplementations(uri: String, position: Position): Flow<Location> = channelFlow {
        logger.debug("Finding implementations for $uri at ${position.line}:${position.character}")

        try {
            val context = createContext(uri, position.toGroovyPosition()) ?: return@channelFlow
            val target = identifyTarget(context) ?: return@channelFlow

            logger.debug("Implementation target: ${target.javaClass.simpleName}")
            findImplementations(target, context.visitor)
        } catch (e: Exception) {
            logger.error("Error finding implementations", e)
        }
    }

    /**
     * Context for implementation search.
     */
    private data class ImplementationContext(
        val documentUri: URI,
        val visitor: GroovyAstModel,
        val symbolTable: SymbolTable,
        val targetNode: ASTNode,
    )

    /**
     * Types of implementation targets.
     */
    private sealed class ImplementationTarget {
        data class InterfaceClass(val classNode: ClassNode) : ImplementationTarget()
        data class AbstractClass(val classNode: ClassNode) : ImplementationTarget()
        data class InterfaceMethod(val methodNode: MethodNode, val ownerInterface: ClassNode) : ImplementationTarget()
        data class AbstractMethod(val methodNode: MethodNode, val ownerClass: ClassNode) : ImplementationTarget()
    }

    /**
     * Create context from URI and position.
     */
    private fun createContext(
        uri: String,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ImplementationContext? {
        val documentUri = URI.create(uri)
        val visitor = compilationService.getAstModel(documentUri) ?: return null
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return null
        val targetNode = visitor.getNodeAt(documentUri, position) ?: return null

        return ImplementationContext(documentUri, visitor, symbolTable, targetNode)
    }

    /**
     * Identify what kind of implementation target we have.
     */
    private fun identifyTarget(context: ImplementationContext): ImplementationTarget? {
        var node = context.targetNode

        // If we got a method call, resolve to its definition first
        if (node is MethodCallExpression) {
            node = node.resolveToDefinition(context.visitor, context.symbolTable, strict = false) ?: return null
        }

        return when (node) {
            is ClassNode -> {
                when {
                    node.isInterface -> ImplementationTarget.InterfaceClass(node)
                    node.isAbstract -> ImplementationTarget.AbstractClass(node)
                    else -> {
                        logger.debug("Node is a concrete class, no implementations to find")
                        null
                    }
                }
            }

            is MethodNode -> {
                val declaringClass = node.declaringClass
                when {
                    declaringClass?.isInterface == true -> {
                        ImplementationTarget.InterfaceMethod(node, declaringClass)
                    }

                    node.isAbstract && declaringClass != null -> {
                        ImplementationTarget.AbstractMethod(node, declaringClass)
                    }

                    else -> {
                        logger.debug("Node is a concrete method, no implementations to find")
                        null
                    }
                }
            }

            else -> {
                logger.debug("Node type ${node.javaClass.simpleName} is not an implementation target")
                null
            }
        }
    }

    /**
     * Find implementations for the target.
     */
    private suspend fun ProducerScope<Location>.findImplementations(
        target: ImplementationTarget,
        originVisitor: GroovyAstModel,
    ) {
        when (target) {
            is ImplementationTarget.InterfaceClass -> findInterfaceImplementations(target.classNode, originVisitor)
            is ImplementationTarget.AbstractClass -> findAbstractClassImplementations(target.classNode, originVisitor)
            is ImplementationTarget.InterfaceMethod -> findMethodImplementations(
                target.methodNode,
                target.ownerInterface,
                originVisitor,
            )

            is ImplementationTarget.AbstractMethod -> findMethodImplementations(
                target.methodNode,
                target.ownerClass,
                originVisitor,
            )
        }
    }

    /**
     * Find all classes extending an abstract class.
     */
    private suspend fun ProducerScope<Location>.findAbstractClassImplementations(
        targetClass: ClassNode,
        originVisitor: GroovyAstModel,
    ) {
        val emittedLocations = mutableSetOf<String>()

        for ((uri, index) in compilationService.getAllSymbolStorages()) {
            val classSymbols = index.findAll<Symbol.Class>(uri)

            for (classSymbol in classSymbols) {
                // Must be a concrete class that extends the target
                if (classSymbol.isInterface || classSymbol.isAbstract) continue

                if (implementsOrExtends(classSymbol, targetClass)) {
                    val fileVisitor = compilationService.getAstModel(uri) ?: originVisitor
                    emitUniqueLocation(classSymbol.node, fileVisitor, emittedLocations)
                }
            }
        }
    }

    /**
     * Find all classes implementing an interface.
     */
    private suspend fun ProducerScope<Location>.findInterfaceImplementations(
        targetInterface: ClassNode,
        originVisitor: GroovyAstModel,
    ) {
        val emittedLocations = mutableSetOf<String>()

        for ((uri, index) in compilationService.getAllSymbolStorages()) {
            val classSymbols = index.findAll<Symbol.Class>(uri)

            for (classSymbol in classSymbols) {
                // Skip interfaces (we want concrete implementations)
                if (classSymbol.isInterface) continue

                // Skip abstract classes for now (could be configurable)
                if (classSymbol.isAbstract) continue

                // Skip the interface itself (or placeholders with same name)
                if (classSymbol.name == targetInterface.name) continue

                // Check if this class implements our target interface
                if (implementsInterface(classSymbol, targetInterface)) {
                    // Get the correct visitor for this file's URI (fixes cross-file discovery)
                    val fileVisitor = compilationService.getAstModel(uri) ?: originVisitor
                    emitUniqueLocation(classSymbol.node, fileVisitor, emittedLocations)
                }
            }
        }
    }

    /**
     * Find all method implementations for an interface/abstract method.
     */
    private suspend fun ProducerScope<Location>.findMethodImplementations(
        targetMethod: MethodNode,
        ownerClass: ClassNode,
        originVisitor: GroovyAstModel,
    ) {
        val emittedLocations = mutableSetOf<String>()

        for ((uri, index) in compilationService.getAllSymbolStorages()) {
            val classSymbols = index.findAll<Symbol.Class>(uri)

            for (classSymbol in classSymbols) {
                // Only consider concrete classes
                if (classSymbol.isInterface || classSymbol.isAbstract) continue

                // Must implement/extend the owner interface/class
                if (!implementsOrExtends(classSymbol, ownerClass)) continue

                // Find matching method in this class
                val matchingMethod = classSymbol.methods.find { method ->
                    methodsMatch(method, targetMethod)
                }

                if (matchingMethod != null) {
                    // Get the correct visitor for this file's URI (fixes cross-file discovery)
                    val fileVisitor = compilationService.getAstModel(uri) ?: originVisitor
                    emitUniqueLocation(matchingMethod, fileVisitor, emittedLocations)
                }
            }
        }
    }

    /**
     * Check if a class implements a specific interface.
     */
    private fun implementsInterface(classSymbol: Symbol.Class, targetInterface: ClassNode): Boolean {
        // The hasInterfaceTransitive function already explores both interfaces and superclasses.
        // We can simplify this by starting the search from the class node itself.
        return hasInterfaceTransitive(classSymbol.node, targetInterface.name, mutableSetOf())
    }

    private fun hasInterfaceTransitive(current: ClassNode, targetName: String, visited: MutableSet<String>): Boolean {
        if (!visited.add(current.name)) return false

        if (current.name == targetName) return true

        // Check implemented interfaces
        for (iface in current.interfaces) {
            if (hasInterfaceTransitive(iface, targetName, visited)) return true
        }

        // Check superclass
        val superClass = current.superClass
        if (superClass != null) {
            if (hasInterfaceTransitive(superClass, targetName, visited)) return true
        }

        return false
    }

    /**
     * Check if a class implements/extends the owner class.
     */
    private fun implementsOrExtends(classSymbol: Symbol.Class, ownerClass: ClassNode): Boolean {
        // Check if implements interface
        if (ownerClass.isInterface) {
            return implementsInterface(classSymbol, ownerClass)
        }

        // Check if extends class using fully qualified name (transitive)
        return extendsClassTransitive(classSymbol.superClass, ownerClass.name, mutableSetOf())
    }

    private fun extendsClassTransitive(current: ClassNode?, targetName: String, visited: MutableSet<String>): Boolean {
        if (current == null) return false
        if (!visited.add(current.name)) return false

        if (current.name == targetName) return true

        return extendsClassTransitive(current.superClass, targetName, visited)
    }

    /**
     * Check if two methods have matching signatures.
     */
    private fun methodsMatch(candidateMethod: MethodNode, targetMethod: MethodNode): Boolean {
        // Name must match
        if (candidateMethod.name != targetMethod.name) return false

        // Parameter count must match
        val candidateParams = candidateMethod.parameters
        val targetParams = targetMethod.parameters
        if (candidateParams.size != targetParams.size) return false

        // Parameter types must match (handle FQN vs simple name)
        for (i in candidateParams.indices) {
            val candidateType = candidateParams[i].type?.name ?: "def"
            val targetType = targetParams[i].type?.name ?: "def"

            if (!typesMatch(candidateType, targetType)) {
                return false
            }
        }

        return true
    }

    /**
     * Check if two type names match, handling FQN vs simple name differences.
     * For example, "String" should match "java.lang.String".
     */
    private fun typesMatch(type1: String, type2: String): Boolean {
        // Fast path: exact match
        if (type1 == type2) return true

        // Handle FQN vs simple name (e.g., String vs java.lang.String)
        val simple1 = type1.substringAfterLast('.')
        val simple2 = type2.substringAfterLast('.')
        return simple1 == simple2 && simple1.isNotEmpty()
    }

    /**
     * Emit a location if not already emitted.
     */
    private suspend fun ProducerScope<Location>.emitUniqueLocation(
        node: ASTNode,
        visitor: GroovyAstModel,
        seen: MutableSet<String>,
    ) {
        val location = node.toLspLocation(visitor) ?: return
        val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
        if (seen.add(key)) {
            send(location)
        }
    }
}
