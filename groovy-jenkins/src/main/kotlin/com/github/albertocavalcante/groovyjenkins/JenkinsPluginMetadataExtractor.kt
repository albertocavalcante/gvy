@file:Suppress(
    "TooGenericExceptionCaught", // JAR/ClassGraph scanning uses catch-all for resilience
    "NestedBlockDepth", // JAR extraction has inherent nesting
    "LongMethod", // extractGlobalVariables needs comprehensive scanning logic
    "CyclomaticComplexMethod", // extractGlobalVariables handles multiple extraction paths
    "UnusedParameter", // sourcesJarPath reserved for future Javadoc extraction
)

package com.github.albertocavalcante.groovyjenkins

import com.github.albertocavalcante.groovycommon.text.extractSymbolName
import com.github.albertocavalcante.groovycommon.text.simpleClassName
import com.github.albertocavalcante.groovycommon.text.toPropertyName
import com.github.albertocavalcante.groovycommon.text.toStepName
import com.github.albertocavalcante.groovyjenkins.extraction.FunctionNameExtractor
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Extracts Jenkins step and global variable metadata from plugin JARs using ClassGraph.
 *
 * Looks for:
 * - Steps: Classes annotated with @Symbol or extending StepDescriptor
 * - Global Variables: Classes implementing GlobalVariable
 * - Service Descriptors: META-INF/services entries
 */
class JenkinsPluginMetadataExtractor {

    private val logger = KotlinLogging.logger {}
    private val functionNameExtractor = FunctionNameExtractor()

    /**
     * Extract all step definitions from a plugin JAR.
     *
     * @param jarPath Path to the plugin JAR file
     * @param pluginId Plugin identifier for metadata attribution
     * @return List of extracted step metadata
     */
    fun extractFromJar(jarPath: Path, pluginId: String): List<JenkinsStepMetadata> {
        logger.debug { "Extracting step metadata from: $jarPath" }

        if (!jarPath.toFile().exists()) {
            logger.warn { "JAR file does not exist: $jarPath" }
            return emptyList()
        }

        val steps = mutableListOf<JenkinsStepMetadata>()

        try {
            ClassGraph()
                .overrideClasspath(jarPath.toString())
                .enableAnnotationInfo()
                .enableMethodInfo()
                .ignoreParentClassLoaders()
                .scan()
                .use { scanResult ->
                    // Find classes with @Symbol annotation (primary approach)
                    val symbolClasses = scanResult.getClassesWithAnnotation(SYMBOL_ANNOTATION)

                    symbolClasses.forEach { classInfo ->
                        extractStepFromSymbolClass(classInfo, jarPath, pluginId)?.let { steps.add(it) }
                    }

                    // Find StepDescriptor implementations (fallback for older plugins)
                    val descriptors = scanResult.getSubclasses(STEP_DESCRIPTOR)
                    descriptors.forEach { classInfo ->
                        if (!classInfo.isAbstract) {
                            extractStepFromDescriptor(classInfo, jarPath, pluginId)?.let { steps.add(it) }
                        }
                    }
                }

            logger.info { "Extracted ${steps.size} steps from ${jarPath.fileName}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract metadata from JAR: $jarPath" }
        }

        return steps
    }

    /**
     * Extract step metadata from a class annotated with @Symbol.
     *
     * Resolution priority (fixes #834):
     * 1. **Bytecode extraction**: Extract `getFunctionName()` return value from StepDescriptor bytecode
     * 2. **@Symbol annotation**: Fall back to @Symbol annotation value
     * 3. **Class name derivation**: Last resort - derive from class name
     *
     * Handles several cases:
     * 1. Direct @Symbol on Step class: @Symbol("sh") class ShellStep
     * 2. @Symbol on DescriptorImpl: class MyStep { @Symbol("myStep") class DescriptorImpl }
     * 3. Array values: @Symbol({"step1", "step2"})
     */
    private fun extractStepFromSymbolClass(
        classInfo: ClassInfo,
        jarPath: Path,
        pluginId: String,
    ): JenkinsStepMetadata? {
        var result: JenkinsStepMetadata? = null

        val symbolAnnotation = classInfo.getAnnotationInfo(SYMBOL_ANNOTATION)
        if (symbolAnnotation != null) {
            // Strategy 1: Try bytecode extraction first (MOST RELIABLE - fixes #834)
            var stepName = extractFunctionNameFromBytecode(classInfo, jarPath)

            // Strategy 2: Parse @Symbol value - can be String, String[], or wrapped types
            if (stepName == null) {
                val symbolValues = symbolAnnotation.parameterValues
                    .find { it.name == "value" }
                    ?.value
                stepName = extractSymbolName(symbolValues)
            }

            // Strategy 3: Derive from class name if @Symbol value is problematic
            if (shouldSkipSymbol(stepName)) {
                stepName = deriveStepNameFromDescriptor(classInfo)
                if (stepName == null) {
                    logger.debug { "Skipping DescriptorImpl with no derivable step name: ${classInfo.name}" }
                }
            }

            val validStepName = stepName?.takeUnless { shouldSkipSymbol(it) }
            if (validStepName != null) {
                logger.debug { "Found step: $validStepName in class ${classInfo.name}" }

                val parameters = extractParameters(classInfo)

                result = JenkinsStepMetadata(
                    name = validStepName,
                    plugin = pluginId,
                    parameters = parameters,
                    documentation = extractDocumentation(classInfo),
                )
            }
        }

        return result
    }

    /**
     * Extract function name by analyzing the bytecode of the descriptor's getFunctionName() method.
     * This is the most reliable method as it reads the exact same value Jenkins uses at runtime.
     */
    private fun extractFunctionNameFromBytecode(classInfo: ClassInfo, jarPath: Path): String? {
        fun isDescriptor(info: ClassInfo) =
            info.simpleName.endsWith("DescriptorImpl") || info.simpleName.endsWith("Descriptor")

        if (isDescriptor(classInfo)) {
            return functionNameExtractor.extractFromJar(jarPath, classInfo.name)
        }

        return classInfo.innerClasses
            .firstOrNull(::isDescriptor)
            ?.let { functionNameExtractor.extractFromJar(jarPath, it.name) }
    }

    // Symbol name extraction is now handled by com.github.albertocavalcante.groovycommon.text.extractSymbolName

    /**
     * Derive step name from a DescriptorImpl class by looking at its enclosing class.
     *
     * For example:
     * - ShellStep$DescriptorImpl -> "sh" (would need mapping)
     * - LockStep$DescriptorImpl -> "lock"
     *
     * TODO: This is a heuristic. For accurate mapping, we would need to:
     * 1. Find the StepDescriptor.getFunctionName() method return value (requires execution)
     * 2. Or maintain a static mapping of known steps
     */
    private fun deriveStepNameFromDescriptor(classInfo: ClassInfo): String? {
        val className = classInfo.name

        // Check if it's a nested DescriptorImpl class
        if (!className.contains("\$DescriptorImpl") && !className.endsWith("Descriptor")) {
            return null
        }

        // Extract the parent class name and convert to step name
        val parentClassName = className
            .substringBefore("\$DescriptorImpl")
            .substringBefore("\$Descriptor")
            .simpleClassName()

        // Convert to lowerCamelCase step name using shared utility
        val stepName = parentClassName.toStepName()

        // Validate it's a reasonable step name
        return if (stepName.isNotBlank() && stepName.length > 1) {
            logger.debug { "Derived step name '$stepName' from DescriptorImpl: $className" }
            stepName
        } else {
            null
        }
    }

    companion object {
        const val SYMBOL_ANNOTATION = "org.jenkinsci.Symbol"
        const val DATA_BOUND_CONSTRUCTOR = "org.kohsuke.stapler.DataBoundConstructor"
        const val DATA_BOUND_SETTER = "org.kohsuke.stapler.DataBoundSetter"
        const val STEP_DESCRIPTOR = "org.jenkinsci.plugins.workflow.steps.StepDescriptor"
        const val GLOBAL_VARIABLE = "org.jenkinsci.plugins.workflow.cps.GlobalVariable"
        const val GLOBAL_VARIABLE_SERVICE = "META-INF/services/org.jenkinsci.plugins.workflow.cps.GlobalVariable"

        // Symbols to skip (not actual pipeline steps) - lowercase for case-insensitive matching
        private val SKIP_SYMBOLS = setOf(
            "descriptorimpl",
            "stepdescriptorimpl",
        )

        /** Check if a symbol name should be skipped (case-insensitive) */
        private fun shouldSkipSymbol(name: String?): Boolean = name.isNullOrBlank() || name.lowercase() in SKIP_SYMBOLS
    }

    /**
     * Extract step metadata from a StepDescriptor implementation.
     *
     * Resolution priority:
     * 1. **Bytecode extraction**: Extract `getFunctionName()` return value from bytecode
     * 2. **Class name derivation**: Derive from class name (e.g., EchoStep$DescriptorImpl → echo)
     */
    private fun extractStepFromDescriptor(
        classInfo: ClassInfo,
        jarPath: Path,
        pluginId: String,
    ): JenkinsStepMetadata? {
        // Strategy 1: Extract function name from bytecode (MOST RELIABLE)
        var stepName = functionNameExtractor.extractFromJar(jarPath, classInfo.name)

        // Strategy 2: Derive from class name as fallback
        if (stepName == null) {
            val className = classInfo.simpleName
            stepName = className
                .removeSuffix("Descriptor")
                .toStepName()
        }

        if (stepName.isNullOrBlank()) return null

        logger.debug { "Found StepDescriptor: ${classInfo.name} -> $stepName" }

        return JenkinsStepMetadata(
            name = stepName,
            plugin = pluginId,
            parameters = emptyMap(), // Would need runtime execution to get parameters
            documentation = "Jenkins pipeline step from $pluginId",
        )
    }

    /**
     * Extract parameters from @DataBoundConstructor and @DataBoundSetter methods.
     */
    private fun extractParameters(classInfo: ClassInfo): Map<String, StepParameter> {
        val parameters = mutableMapOf<String, StepParameter>()

        // Find @DataBoundConstructor for required parameters
        val constructors = classInfo.declaredConstructorInfo
        constructors.forEach { constructor ->
            if (constructor.hasAnnotation(DATA_BOUND_CONSTRUCTOR)) {
                constructor.parameterInfo.forEach { param ->
                    val paramName = param.name ?: "arg${parameters.size}"
                    parameters[paramName] = StepParameter(
                        name = paramName,
                        type = param.typeSignatureOrTypeDescriptor.toString().simpleClassName(),
                        required = true,
                        documentation = null,
                    )
                }
            }
        }

        // Find @DataBoundSetter for optional parameters
        val methods = classInfo.declaredMethodInfo
        methods.forEach { method ->
            if (method.hasAnnotation(DATA_BOUND_SETTER) && method.name.startsWith("set")) {
                val paramName = method.name.toPropertyName()
                val paramType = method.parameterInfo.firstOrNull()
                    ?.typeSignatureOrTypeDescriptor
                    ?.toString()
                    ?.simpleClassName()
                    ?: "Object"

                parameters[paramName] = StepParameter(
                    name = paramName,
                    type = paramType,
                    required = false,
                    documentation = null,
                )
            }
        }

        return parameters
    }

    /**
     * Extract documentation from Javadoc comments if available.
     * Note: This requires the sources JAR, not the main JAR.
     */
    private fun extractDocumentation(classInfo: ClassInfo): String? {
        // ClassGraph doesn't provide access to Javadoc
        // Documentation extraction from sources would be done separately
        return "Jenkins pipeline step: ${classInfo.simpleName}"
    }

    /**
     * Extract documentation from source files.
     *
     * @param sourcesJarPath Path to the sources JAR
     * @param className Fully qualified class name
     * @return Documentation string or null
     */
    fun extractDocumentationFromSources(sourcesJarPath: Path, className: String): String? {
        // TODO: Implement source parsing for Javadoc extraction
        // This would parse .java files in the sources JAR
        logger.debug { "Source documentation extraction not yet implemented for: $className" }
        return null
    }

    /**
     * Extract global variables from a plugin JAR.
     *
     * Scans:
     * 1. META-INF/services/org.jenkinsci.plugins.workflow.cps.GlobalVariable
     * 2. Classes implementing GlobalVariable with @Symbol annotation
     *
     * @param jarPath Path to the plugin JAR file
     * @param pluginId Plugin identifier for metadata attribution
     * @return List of extracted global variable metadata
     */
    fun extractGlobalVariables(jarPath: Path, pluginId: String): List<GlobalVariableMetadata> {
        logger.debug { "Extracting global variables from: $jarPath" }

        if (!jarPath.toFile().exists()) {
            return emptyList()
        }

        val variables = mutableListOf<GlobalVariableMetadata>()

        try {
            // 1. Scan META-INF/services file
            JarFile(jarPath.toFile()).use { jar ->
                val entry = jar.getEntry(GLOBAL_VARIABLE_SERVICE)
                if (entry != null) {
                    jar.getInputStream(entry).bufferedReader().use { reader ->
                        reader.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .forEach { className ->
                                logger.debug { "Found GlobalVariable in services: $className" }
                                // Variable name is typically the simple class name in lowerCamelCase
                                val varName = className.simpleClassName()
                                    .removeSuffix("Global")
                                    .removeSuffix("Variable")
                                    .toStepName()

                                variables.add(
                                    GlobalVariableMetadata(
                                        name = varName,
                                        type = className,
                                        documentation = "Global variable from $pluginId",
                                    ),
                                )
                            }
                    }
                }
            }

            // 2. Use ClassGraph for @Symbol-annotated GlobalVariable implementations
            ClassGraph()
                .overrideClasspath(jarPath.toString())
                .enableAnnotationInfo()
                .ignoreParentClassLoaders()
                .scan()
                .use { scanResult ->
                    val globalVarClasses = scanResult.getClassesImplementing(GLOBAL_VARIABLE)
                    globalVarClasses.forEach { classInfo ->
                        val symbolAnnotation = classInfo.getAnnotationInfo(SYMBOL_ANNOTATION)
                        if (symbolAnnotation != null) {
                            val symbolValue = symbolAnnotation.parameterValues
                                .find { it.name == "value" }
                                ?.value

                            val varName = extractSymbolName(symbolValue)

                            if (varName != null && variables.none { it.name == varName }) {
                                logger.debug { "Found @Symbol GlobalVariable: $varName -> ${classInfo.name}" }
                                variables.add(
                                    GlobalVariableMetadata(
                                        name = varName,
                                        type = classInfo.name,
                                        documentation = "Global variable from $pluginId",
                                    ),
                                )
                            }
                        }
                    }
                }

            logger.info { "Extracted ${variables.size} global variables from ${jarPath.fileName}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extract global variables from JAR: $jarPath" }
        }

        return variables
    }
}
