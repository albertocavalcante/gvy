package com.github.albertocavalcante.groovyjenkins.extraction

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import org.slf4j.LoggerFactory

/**
 * Represents a parameter extracted from bytecode.
 *
 * @property name Parameter name (may be synthetic like "arg0" if not compiled with -parameters)
 * @property type Fully qualified type name
 * @property isRequired True if from @DataBoundConstructor, false if from @DataBoundSetter
 */
data class ExtractedParam(val name: String, val type: String, val isRequired: Boolean = true)

/**
 * Represents a Step class found during bytecode scanning.
 *
 * @property className Fully qualified class name of the Step
 * @property simpleName Simple class name
 * @property functionName The step function name (from getFunctionName or @Symbol)
 * @property takesBlock Whether the step takes an implicit block argument
 * @property constructorParams Parameters from @DataBoundConstructor
 * @property setterParams Parameters from @DataBoundSetter methods
 */
data class ScannedStep(
    val className: String,
    val simpleName: String,
    val functionName: String?,
    val takesBlock: Boolean = false,
    val constructorParams: List<ExtractedParam> = emptyList(),
    val setterParams: List<ExtractedParam> = emptyList(),
    val pluginId: String? = null,
)

/**
 * Scans JAR files or classpath to extract Jenkins Step metadata using ClassGraph.
 *
 * This scanner reads bytecode directly without loading classes, avoiding ClassLoader issues
 * that would occur if we tried to load Jenkins plugin classes without the full Jenkins runtime.
 *
 * The scanning is deterministic - same input always produces same output.
 */
class BytecodeScanner {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val STEP_CLASS = "org.jenkinsci.plugins.workflow.steps.Step"
        private const val STEP_DESCRIPTOR_CLASS = "org.jenkinsci.plugins.workflow.steps.StepDescriptor"
        private const val DATA_BOUND_CONSTRUCTOR = "org.kohsuke.stapler.DataBoundConstructor"
        private const val DATA_BOUND_SETTER = "org.kohsuke.stapler.DataBoundSetter"
        private const val SYMBOL_ANNOTATION = "org.jenkinsci.Symbol"
    }

    /**
     * Scans the classpath for classes that extend a given superclass.
     *
     * @param superclassName Fully qualified name of the superclass to find subclasses of
     * @param packages Package prefixes to scan (empty means scan nothing for safety)
     * @return List of ScannedStep objects representing found Step classes
     */
    fun scanClasspath(superclassName: String, packages: List<String>): List<ScannedStep> {
        if (packages.isEmpty()) {
            logger.debug("No packages specified, returning empty list")
            return emptyList()
        }
        return scan {
            acceptPackages(*packages.toTypedArray())
        }
    }

    /**
     * Scans a specific JAR file for Step classes.
     *
     * @param jarPath Path to the JAR file to scan
     * @return List of ScannedStep objects found in the JAR
     */
    fun scanJar(jarPath: java.nio.file.Path): List<ScannedStep> = scan {
        overrideClasspath(jarPath.toString())
    }

    private fun scan(configure: ClassGraph.() -> Unit): List<ScannedStep> = try {
        ClassGraph()
            .enableAllInfo()
            .apply(configure)
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(STEP_CLASS)
                    .filter { !it.isAbstract }
                    .map { classInfo -> toScannedStep(classInfo) }
                    .sortedBy { it.className }
            }
    } catch (e: Exception) {
        logger.warn("Failed to scan for steps: ${e.message}")
        emptyList()
    }

    /**
     * Extracts constructor parameters for a given class.
     *
     * @param className Fully qualified class name
     * @param classLoader ClassLoader to use for resolution
     * @return List of ExtractedParam from @DataBoundConstructor, empty if class not found
     */
    fun extractConstructorParams(className: String, classLoader: ClassLoader): List<ExtractedParam> {
        return try {
            ClassGraph()
                .enableAllInfo()
                .overrideClassLoaders(classLoader)
                .acceptClasses(className)
                .scan()
                .use { scanResult ->
                    val classInfo = scanResult.getClassInfo(className) ?: return emptyList()
                    extractDataBoundConstructorParams(classInfo)
                }
        } catch (e: Exception) {
            logger.debug("Failed to extract constructor params for $className: ${e.message}")
            emptyList()
        }
    }

    private fun toScannedStep(classInfo: ClassInfo): ScannedStep {
        val constructorParams = extractDataBoundConstructorParams(classInfo)
        val setterParams = extractDataBoundSetterParams(classInfo)
        val functionName = extractFunctionName(classInfo)
        val takesBlock = extractTakesBlock(classInfo)

        return ScannedStep(
            className = classInfo.name,
            simpleName = classInfo.simpleName,
            functionName = functionName,
            takesBlock = takesBlock,
            constructorParams = constructorParams,
            setterParams = setterParams,
        )
    }

    private fun extractDataBoundConstructorParams(classInfo: ClassInfo): List<ExtractedParam> {
        val constructor = classInfo.declaredConstructorInfo
            .firstOrNull { it.hasAnnotation(DATA_BOUND_CONSTRUCTOR) }
            ?: return emptyList()

        return constructor.parameterInfo.mapIndexed { index, param ->
            ExtractedParam(
                name = param.name ?: "arg$index",
                type = param.typeSignatureOrTypeDescriptor?.toString() ?: "Object",
                isRequired = true,
            )
        }
    }

    private fun extractDataBoundSetterParams(classInfo: ClassInfo): List<ExtractedParam> {
        return classInfo.declaredMethodInfo
            .filter { it.hasAnnotation(DATA_BOUND_SETTER) }
            .filter { it.name.startsWith("set") && it.parameterInfo.size == 1 }
            .map { setter ->
                val param = setter.parameterInfo[0]
                ExtractedParam(
                    name = setter.name.removePrefix("set").replaceFirstChar { it.lowercase() },
                    type = param.typeSignatureOrTypeDescriptor?.toString() ?: "Object",
                    isRequired = false,
                )
            }
            .sortedBy { it.name } // Deterministic ordering
    }

    private fun extractFunctionName(classInfo: ClassInfo): String? {
        // Strategy 1: Look for @Symbol annotation
        val symbolAnnotation = classInfo.annotationInfo
            .firstOrNull { it.name == SYMBOL_ANNOTATION }

        if (symbolAnnotation != null) {
            val value = symbolAnnotation.parameterValues.getValue("value")
            if (value is Array<*> && value.isNotEmpty()) {
                return value[0].toString()
            }
        }

        // Strategy 2: Look for inner DescriptorImpl class with @Symbol
        val descriptorClass = classInfo.innerClasses
            .firstOrNull { it.simpleName == "DescriptorImpl" }

        if (descriptorClass != null) {
            val descSymbol = descriptorClass.annotationInfo
                .firstOrNull { it.name == SYMBOL_ANNOTATION }
            if (descSymbol != null) {
                val value = descSymbol.parameterValues.getValue("value")
                if (value is Array<*> && value.isNotEmpty()) {
                    return value[0].toString()
                }
            }
        }

        // Fallback: Derive from class name
        return classInfo.simpleName
            .removeSuffix("Step")
            .replaceFirstChar { it.lowercase() }
    }

    private fun extractTakesBlock(classInfo: ClassInfo): Boolean {
        // Look for inner DescriptorImpl class and check takesImplicitBlockArgument
        // This is tricky without executing code, so we use heuristics.
        // Limitation: May report true if the override returns false; static analysis
        // cannot determine the actual return value without code execution.
        val descriptorClass = classInfo.innerClasses
            .firstOrNull { it.simpleName == "DescriptorImpl" }
            ?: return false

        // Check if the descriptor overrides takesImplicitBlockArgument
        // If it does, it likely returns true (otherwise why override?)
        val method = descriptorClass.declaredMethodInfo
            .firstOrNull { it.name == "takesImplicitBlockArgument" }

        return method != null
    }
}
