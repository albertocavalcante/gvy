@file:Suppress(
    "TooGenericExceptionCaught", // Classpath scanning requires catch-all for resilience
    "ReturnCount", // Step name resolution has multiple early returns
    "MagicNumber", // Array index 3 is self-documenting in annotation context
)

package com.github.albertocavalcante.groovyjenkins.scanning

import com.github.albertocavalcante.groovycommon.text.extractSymbolName
import com.github.albertocavalcante.groovycommon.text.toLowerCamelCase
import com.github.albertocavalcante.groovycommon.text.toPropertyName
import com.github.albertocavalcante.groovycommon.text.toStepName
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.ScanResult
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Scans the classpath for Jenkins steps and global variables using ClassGraph.
 *
 * This allows dynamic discovery of steps from Jenkins plugins and core JARs
 * present in the project's resolved classpath.
 */
class JenkinsClasspathScanner {
    private val logger = LoggerFactory.getLogger(JenkinsClasspathScanner::class.java)

    companion object {
        // Core Jenkins types to look for
        private const val STEP_DESCRIPTOR_CLASS = "org.jenkinsci.plugins.workflow.steps.StepDescriptor"
        private const val GLOBAL_VARIABLE_CLASS = "org.jenkinsci.plugins.workflow.cps.GlobalVariable"
        private const val SYMBOL_ANNOTATION = "org.jenkinsci.Symbol"
        private const val DATA_BOUND_CONSTRUCTOR = "org.kohsuke.stapler.DataBoundConstructor"
        private const val DATA_BOUND_SETTER = "org.kohsuke.stapler.DataBoundSetter"
    }

    /**
     * Scan the provided classpath for Jenkins definitions.
     */
    fun scan(classpath: List<Path>): BundledJenkinsMetadata {
        logger.info("Scanning {} classpath entries for Jenkins definitions", classpath.size)

        // If classpath is empty, return empty metadata
        if (classpath.isEmpty()) {
            return BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap())
        }

        val stepMetadata = mutableMapOf<String, JenkinsStepMetadata>()
        val globalVariableMetadata = mutableMapOf<String, GlobalVariableMetadata>()

        try {
            // Configure ClassGraph
            val classGraph = ClassGraph()
                .overrideClasspath(classpath)
                .enableClassInfo()
                .enableMethodInfo()
                .enableFieldInfo()
                .enableAnnotationInfo()
                .ignoreClassVisibility() // We need to see package-private/protected stuff sometimes

            classGraph.scan().use { scanResult ->
                scanSteps(scanResult, stepMetadata)
                scanGlobalVariables(scanResult, globalVariableMetadata)
            }
        } catch (e: Exception) {
            logger.error("Failed to scan classpath for Jenkins definitions", e)
        }

        logger.info("Scanned ${stepMetadata.size} steps and ${globalVariableMetadata.size} global variables")
        return BundledJenkinsMetadata(
            steps = stepMetadata,
            globalVariables = globalVariableMetadata,
        )
    }

    private fun scanSteps(scanResult: ScanResult, steps: MutableMap<String, JenkinsStepMetadata>) {
        // Find all subclasses of StepDescriptor
        val stepDescriptors = scanResult.getSubclasses(STEP_DESCRIPTOR_CLASS)

        stepDescriptors.forEach { descriptorInfo ->
            try {
                // Find the Step class associated with this descriptor
                // 1. If Descriptor is inner class, outer is the Step
                // 2. If not, try to find class with same package/name pattern?
                var stepClassInfo: ClassInfo? = null
                if (descriptorInfo.isInnerClass) {
                    stepClassInfo = descriptorInfo.outerClasses.firstOrNull()
                }

                // Determine step name (function name)
                // Check descriptor first, then the step class itself
                val stepName = getStepName(descriptorInfo, stepClassInfo) ?: return@forEach

                // Determine plugin name (approximate from package or jar)
                val pluginName = getPluginName(descriptorInfo)

                // Determine documentation (javadoc if available)
                val doc = getDocumentation(descriptorInfo)

                // Extract parameters from the Step class
                val parameters = mutableMapOf<String, StepParameter>()
                var positionalParams: List<String> = emptyList()
                if (stepClassInfo != null) {
                    positionalParams = extractParameters(stepClassInfo, parameters)
                }

                steps[stepName] = JenkinsStepMetadata(
                    name = stepName,
                    plugin = pluginName,
                    positionalParams = positionalParams,
                    parameters = parameters,
                    documentation = doc,
                )
            } catch (e: Exception) {
                logger.debug("Error processing step descriptor ${descriptorInfo.name}", e)
            }
        }
    }

    private fun scanGlobalVariables(scanResult: ScanResult, globals: MutableMap<String, GlobalVariableMetadata>) {
        val globalVars = scanResult.getSubclasses(GLOBAL_VARIABLE_CLASS)

        globalVars.forEach { globalInfo ->
            try {
                // Name usually from @Symbol or class name convention
                // Name usually from @Symbol or class name convention
                // PR #3: Use shared extraction logic
                val symbol = extractSymbol(globalInfo)
                var name = symbol

                if (name == null) {
                    // Fallback: use class name, decapitalized
                    name = globalInfo.simpleName.toLowerCamelCase()
                        .removeSuffix("GlobalVariable")
                        .removeSuffix("Impl")
                }

                val doc = getDocumentation(globalInfo)

                globals[name] = GlobalVariableMetadata(
                    name = name,
                    type = "java.lang.Object", // Hard to infer exact type statically without method logic
                    documentation = doc,
                )
            } catch (e: Exception) {
                logger.debug("Error processing global variable ${globalInfo.name}", e)
            }
        }
    }

    private fun extractParameters(stepClass: ClassInfo, parameters: MutableMap<String, StepParameter>): List<String> {
        val positionalParams = mutableListOf<String>()

        // 1. Find @DataBoundConstructor
        // There should be exactly one, but we search all just in case
        stepClass.constructorInfo.forEach { method ->
            if (method.hasAnnotation(DATA_BOUND_CONSTRUCTOR)) {
                method.parameterInfo.forEach { param ->
                    val name = param.name ?: "arg${parameters.size}"
                    // We rely on -parameters flag or debug info for names. ClassGraph handles it usually.

                    parameters[name] = StepParameter(
                        name = name,
                        type = param.typeSignatureOrTypeDescriptor.toString(),
                        required = true,
                        documentation = null,
                    )
                    positionalParams.add(name)
                }
            }
        }

        // 2. Find @DataBoundSetters (optional parameters)
        // These can be setters `setFoo(RawType foo)` OR public fields

        // 2a. Methods
        stepClass.methodInfo.forEach { method ->
            if (method.hasAnnotation(DATA_BOUND_SETTER) && method.name.startsWith("set")) {
                val propName = method.name.toPropertyName()

                if (method.parameterInfo.isNotEmpty()) {
                    val param = method.parameterInfo[0]
                    parameters[propName] = StepParameter(
                        name = propName,
                        type = param.typeSignatureOrTypeDescriptor.toString(),
                        required = false,
                        documentation = null,
                    )
                }
            }
        }

        // 2b. Fields
        stepClass.fieldInfo.forEach { field ->
            if (field.hasAnnotation(DATA_BOUND_SETTER)) {
                parameters[field.name] = StepParameter(
                    name = field.name,
                    type = field.typeSignatureOrTypeDescriptor.toString(),
                    required = false,
                    documentation = null,
                )
            }
        }
        return positionalParams
    }

    private fun getStepName(descriptorInfo: ClassInfo, stepClassInfo: ClassInfo?): String? {
        // 1. Check for @Symbol on Descriptor
        extractSymbol(descriptorInfo)?.let { return it }

        // 2. Check for @Symbol on the Step class (if known)
        if (stepClassInfo != null) {
            extractSymbol(stepClassInfo)?.let { return it }
        }

        // 3. Fallback? If implicit, Jenkins typically requires explicit @Symbol or getFunctionNameOverride
        // Some old steps might not use @Symbol.
        // We can try to guess from the step class name (EchoStep -> echo)
        if (stepClassInfo != null) {
            val simpleName = stepClassInfo.simpleName
            if (simpleName.endsWith("Step")) {
                return simpleName.toStepName()
            }
        }

        return null
    }

    /**
     * Helper to extract @Symbol value from ClassInfo using shared logic.
     */
    private fun extractSymbol(classInfo: ClassInfo): String? {
        val symbolValue = classInfo.getAnnotationInfo(SYMBOL_ANNOTATION)
            ?.parameterValues
            ?.getValue("value")
        return extractSymbolName(symbolValue)
    }

    private fun getPluginName(classInfo: ClassInfo): String {
        // Try to guess from jar name
        return classInfo.classpathElementFile?.nameWithoutExtension ?: "unknown"
    }

    private fun getDocumentation(classInfo: ClassInfo): String? {
        // ClassGraph can extract javadoc if bundled, but often it's not.
        // We can just use the class name formatted for now.
        return "Loaded from ${classInfo.name}"
    }
}
