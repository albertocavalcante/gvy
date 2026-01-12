package com.github.albertocavalcante.groovyjenkins.extraction

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Extracts the return value of `getFunctionName()` from StepDescriptor bytecode.
 *
 * Jenkins pipeline step names are defined by `StepDescriptor.getFunctionName()`, which is an
 * abstract method that concrete descriptors MUST implement. The vast majority of implementations
 * simply return a constant string:
 *
 * ```java
 * @Override
 * public String getFunctionName() {
 *     return "echo";  // <-- This is what we extract
 * }
 * ```
 *
 * This compiles to bytecode:
 * ```
 * LDC "echo"    // Load constant string
 * ARETURN       // Return object reference
 * ```
 *
 * Using ASM, we can read this constant directly from bytecode without executing the code.
 * This provides the **exact same value** Jenkins uses at runtime, making extraction deterministic.
 *
 * @see <a href="https://github.com/albertocavalcante/gvy/issues/834">Issue #834</a>
 */
class FunctionNameExtractor {

    private val logger = KotlinLogging.logger {}

    companion object {
        private const val GET_FUNCTION_NAME = "getFunctionName"
        private const val STRING_DESCRIPTOR = "()Ljava/lang/String;"
    }

    /**
     * Extract the function name from a StepDescriptor class within a JAR.
     *
     * @param jarPath Path to the plugin JAR file
     * @param descriptorClassName Fully qualified class name of the StepDescriptor
     *   (e.g., "com.example.MyStep$DescriptorImpl")
     * @return The function name if found via LDC constant, null otherwise
     */
    fun extractFromJar(jarPath: Path, descriptorClassName: String): String? {
        if (!jarPath.toFile().exists()) {
            logger.debug { "JAR does not exist: $jarPath" }
            return null
        }

        return runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                // Convert class name to JAR entry path
                val entryPath = descriptorClassName.replace('.', '/') + ".class"
                val entry = jar.getEntry(entryPath)

                if (entry == null) {
                    logger.debug { "Class not found in JAR: $descriptorClassName" }
                    return@use null
                }

                jar.getInputStream(entry).use { inputStream ->
                    extractFromBytecode(inputStream.readBytes(), descriptorClassName)
                }
            }
        }.onFailure { throwable ->
            if (throwable is Error) throw throwable
            logger.debug(throwable) { "Failed to extract function name from $descriptorClassName in $jarPath" }
        }.getOrNull()
    }

    /**
     * Extract the function name from raw class bytecode.
     *
     * @param bytecode The class file bytes
     * @param className Class name for logging (optional)
     * @return The function name if found via LDC constant, null otherwise
     */
    fun extractFromBytecode(bytecode: ByteArray, className: String? = null): String? {
        val extractor = FunctionNameMethodVisitor()

        return runCatching {
            val classReader = ClassReader(bytecode)
            classReader.accept(
                FunctionNameClassVisitor(extractor),
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            extractor.functionName
        }.onFailure { throwable ->
            if (throwable is Error) throw throwable
            logger.debug(throwable) { "Failed to parse bytecode for ${className ?: "unknown class"}" }
        }.getOrNull()
    }

    /**
     * ClassVisitor that locates the getFunctionName method and delegates to MethodVisitor.
     */
    private class FunctionNameClassVisitor(private val methodVisitor: FunctionNameMethodVisitor) :
        ClassVisitor(Opcodes.ASM9) {

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            // Look for: public String getFunctionName()
            if (name == GET_FUNCTION_NAME && descriptor == STRING_DESCRIPTOR) {
                return methodVisitor
            }
            return null
        }
    }

    /**
     * MethodVisitor that captures the LDC constant before ARETURN.
     *
     * We look for the pattern:
     * ```
     * LDC "stepName"   <- capture this
     * ARETURN          <- triggers extraction
     * ```
     *
     * This handles the common case of `return "constant"`. More complex cases
     * (computed values, string concatenation) will return null, which callers
     * should handle by falling back to @Symbol or class-name derivation.
     */
    private class FunctionNameMethodVisitor : MethodVisitor(Opcodes.ASM9) {

        var functionName: String? = null
            private set

        private var lastStringConstant: String? = null

        override fun visitLdcInsn(value: Any?) {
            // Capture string constants
            if (value is String) {
                lastStringConstant = value
            }
        }

        override fun visitInsn(opcode: Int) {
            // When we see ARETURN, the last string constant was the return value.
            // Capture only the first such value to avoid overwriting in methods with
            // multiple constant returns.
            if (opcode == Opcodes.ARETURN && lastStringConstant != null && functionName == null) {
                functionName = lastStringConstant
            }
        }

        override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
            // If we see a GETSTATIC (field access), clear the last constant
            // This handles cases like `return SOME_CONSTANT;` which we can't resolve
            lastStringConstant = null
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            // If we see a method call before ARETURN, clear the constant
            // This handles cases like `return computeName();` which we can't resolve
            lastStringConstant = null
        }
    }
}
