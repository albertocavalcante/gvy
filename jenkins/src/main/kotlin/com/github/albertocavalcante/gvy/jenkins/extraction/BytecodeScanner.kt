package com.github.albertocavalcante.gvy.jenkins.extraction

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

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
 *
 * Function name resolution priority:
 * 1. **Bytecode extraction**: Extract `getFunctionName()` return value from StepDescriptor bytecode (deterministic)
 * 2. **@Symbol annotation**: Fall back to @Symbol annotation value
 * 3. **Class name derivation**: Last resort - derive from class name (e.g., EchoStep → echo)
 *
 * Note: Bytecode extraction of `getFunctionName()` is only performed when scanning specific JAR
 * files via [scanJar], which provides a concrete JAR path to [extractFunctionName]. When scanning
 * the classpath via [scanClasspath], no JAR path is available (`jarPath` is null), so bytecode
 * extraction is skipped and function names are resolved solely from @Symbol annotation or derived
 * from the class name.
 */
class BytecodeScanner {

    private val logger = KotlinLogging.logger {}
    private val functionNameExtractor = FunctionNameExtractor()

    companion object {
        private const val STEP_CLASS = "org.jenkinsci.plugins.workflow.steps.Step"
        private const val DATA_BOUND_CONSTRUCTOR = "org.kohsuke.stapler.DataBoundConstructor"
        private const val DATA_BOUND_SETTER = "org.kohsuke.stapler.DataBoundSetter"
        private const val SYMBOL_ANNOTATION = "org.jenkinsci.Symbol"

        /**
         * Check if a class name matches the Descriptor naming pattern
         * (e.g., DescriptorImpl, MyDescriptorImpl, SomeDescriptor).
         */
        private fun isDescriptorClass(simpleName: String): Boolean =
            simpleName.endsWith("DescriptorImpl") || simpleName.endsWith("Descriptor")
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
            logger.debug { "No packages specified, returning empty list" }
            return emptyList()
        }
        return scan(superclassName) {
            packages.forEach { acceptPackages(it) }
        }
    }

    /**
     * Scans a specific JAR file for Step classes.
     *
     * @param jarPath Path to the JAR file to scan
     * @return List of ScannedStep objects found in the JAR
     */
    fun scanJar(jarPath: Path): List<ScannedStep> = scanWithJar(STEP_CLASS, jarPath) {
        overrideClasspath(jarPath.toString())
    }

    private fun scan(superclassName: String, configure: ClassGraph.() -> Unit): List<ScannedStep> =
        scanWithJar(superclassName, null, configure)

    private fun scanWithJar(
        superclassName: String,
        jarPath: Path?,
        configure: ClassGraph.() -> Unit,
    ): List<ScannedStep> = runCatching {
        ClassGraph()
            .enableAllInfo()
            .apply(configure)
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(superclassName)
                    .filter { !it.isAbstract }
                    .map { classInfo -> toScannedStep(classInfo, jarPath) }
                    .sortedBy { it.className }
            }
    }.onFailure { throwable ->
        if (throwable is Error) throw throwable
        logger.warn(throwable) { "Failed to scan for steps: ${throwable.message}" }
    }.getOrDefault(emptyList())

    /**
     * Extracts constructor parameters for a given class.
     *
     * @param className Fully qualified class name
     * @param classLoader ClassLoader to use for resolution
     * @return List of ExtractedParam from @DataBoundConstructor, empty if class not found
     */
    fun extractConstructorParams(className: String, classLoader: ClassLoader): List<ExtractedParam> {
        return runCatching {
            ClassGraph()
                .enableAllInfo()
                .overrideClassLoaders(classLoader)
                .acceptClasses(className)
                .scan()
                .use { scanResult ->
                    val classInfo = scanResult.getClassInfo(className) ?: return@use emptyList()
                    extractDataBoundConstructorParams(classInfo)
                }
        }.onFailure { throwable ->
            if (throwable is Error) throw throwable
            logger.debug(throwable) { "Failed to extract constructor params for $className: ${throwable.message}" }
        }.getOrDefault(emptyList())
    }

    private fun toScannedStep(classInfo: ClassInfo, jarPath: Path? = null): ScannedStep {
        val constructorParams = extractDataBoundConstructorParams(classInfo)
        val setterParams = extractDataBoundSetterParams(classInfo)
        val functionName = extractFunctionName(classInfo, jarPath)
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

    @Suppress("ReturnCount") // Multiple returns represent distinct resolution strategies
    private fun extractFunctionName(classInfo: ClassInfo, jarPath: Path? = null): String? {
        // Strategy 1: Extract getFunctionName() return value from bytecode (MOST RELIABLE)
        // This is the same source of truth Jenkins uses at runtime.
        if (jarPath != null) {
            val descriptorClass = classInfo.innerClasses
                .firstOrNull { isDescriptorClass(it.simpleName) }

            if (descriptorClass != null) {
                val bytecodeResult = functionNameExtractor.extractFromJar(jarPath, descriptorClass.name)
                if (bytecodeResult != null) {
                    logger.debug { "Extracted function name from bytecode: $bytecodeResult for ${classInfo.name}" }
                    return bytecodeResult
                }
            }
        }

        // Strategy 2: Look for @Symbol annotation on Step class
        val symbolAnnotation = classInfo.annotationInfo
            .firstOrNull { it.name == SYMBOL_ANNOTATION }

        if (symbolAnnotation != null) {
            val value = symbolAnnotation.parameterValues.getValue("value")
            if (value is Array<*> && value.isNotEmpty()) {
                val result = value[0].toString()
                logger.debug { "Extracted function name from @Symbol on Step: $result for ${classInfo.name}" }
                return result
            }
        }

        // Strategy 3: Look for inner Descriptor class with @Symbol
        val descriptorClass = classInfo.innerClasses
            .firstOrNull { isDescriptorClass(it.simpleName) }

        if (descriptorClass != null) {
            val descSymbol = descriptorClass.annotationInfo
                .firstOrNull { it.name == SYMBOL_ANNOTATION }
            if (descSymbol != null) {
                val value = descSymbol.parameterValues.getValue("value")
                if (value is Array<*> && value.isNotEmpty()) {
                    val result = value[0].toString()
                    logger.debug { "Extracted function name from @Symbol on Descriptor: $result for ${classInfo.name}" }
                    return result
                }
            }
        }

        // Strategy 4 (FALLBACK): Derive from class name
        val derivedName = classInfo.simpleName
            .removeSuffix("Step")
            .replaceFirstChar { it.lowercase() }
        logger.debug { "Derived function name from class name: $derivedName for ${classInfo.name}" }
        return derivedName
    }

    private fun extractTakesBlock(classInfo: ClassInfo): Boolean {
        // Look for inner Descriptor class and check takesImplicitBlockArgument
        // Heuristic: assumes presence of override means it returns true.
        // Limitation: false positives if the method overrides but returns false.
        // Static analysis cannot determine actual return value without code execution.
        // TODO(#XXX): Consider ASM-based constant analysis for better accuracy.
        val descriptorClass = classInfo.innerClasses
            .firstOrNull { isDescriptorClass(it.simpleName) }
            ?: return false

        // Check if the descriptor overrides takesImplicitBlockArgument
        // If it does, it likely returns true (otherwise why override?)
        val method = descriptorClass.declaredMethodInfo
            .firstOrNull { it.name == "takesImplicitBlockArgument" }

        return method != null
    }
}
