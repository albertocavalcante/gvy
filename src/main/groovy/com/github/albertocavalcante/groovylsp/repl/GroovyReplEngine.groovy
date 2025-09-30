package com.github.albertocavalcante.groovylsp.repl

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.codehaus.groovy.control.customizers.SecureASTCustomizer
import org.codehaus.groovy.tools.shell.util.Logger

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Native Groovy REPL engine that provides interactive evaluation capabilities
 * with persistent state and workspace integration.
 */
@CompileStatic
class GroovyReplEngine {

    private GroovyShell shell
    private Binding binding
    private CompilerConfiguration config
    private List<String> commandHistory
    private Map<String, Object> metadata
    private Map<String, Long> executionTimes
    private SecurityManager originalSecurityManager
    private boolean sandboxingEnabled
    private long executionTimeout = 30_000L // 30 seconds default

    /**
     * Configuration for REPL session
     */
    static class ReplConfiguration {
        List<String> autoImports = []
        long executionTimeout = 30_000L
        String maxMemory = "512m"
        boolean sandboxing = false
        int historySize = 1000
        boolean enableMetaClassModifications = true
        Set<String> allowedPackages = []
        Set<String> disallowedMethods = []
    }

    /**
     * Result of code evaluation
     */
    static class ReplResult {
        boolean success
        Object value
        String type
        String output
        long duration
        Map<String, VariableInfo> bindings
        List<String> diagnostics
        SideEffects sideEffects
        Throwable error

        static ReplResult success(Object value, long duration, String output = "",
                                  Map<String, VariableInfo> bindings = [:],
                                  SideEffects sideEffects = new SideEffects()) {
            new ReplResult(
                success: true,
                value: value,
                type: value?.getClass()?.name ?: "null",
                output: output,
                duration: duration,
                bindings: bindings,
                sideEffects: sideEffects,
                diagnostics: []
            )
        }

        static ReplResult compilationError(List<String> errors, long duration = 0) {
            new ReplResult(
                success: false,
                diagnostics: errors,
                duration: duration,
                bindings: [:]
            )
        }

        static ReplResult runtimeError(Throwable error, long duration, String output = "") {
            new ReplResult(
                success: false,
                error: error,
                output: output,
                duration: duration,
                bindings: [:]
            )
        }

        static ReplResult timeout(long duration) {
            new ReplResult(
                success: false,
                diagnostics: ["Execution timed out after ${duration}ms"],
                duration: duration,
                bindings: [:]
            )
        }
    }

    /**
     * Information about a variable in the REPL session
     */
    static class VariableInfo {
        String name
        String value
        String type
        boolean isNull
        Integer size

        VariableInfo(String name, Object obj) {
            this.name = name
            this.isNull = obj == null
            this.type = obj?.getClass()?.name ?: "null"
            this.value = formatValue(obj)
            if (obj instanceof Collection) {
                this.size = ((Collection) obj).size()
            } else if (obj?.getClass()?.isArray()) {
                this.size = ((Object[]) obj).length
            }
        }

        private static String formatValue(Object obj) {
            if (obj == null) return "null"
            if (obj instanceof String) return "\"$obj\""
            if (obj instanceof Collection || obj.getClass().isArray()) {
                return obj.toString()
            }
            String str = obj.toString()
            return str.length() > 100 ? str.substring(0, 97) + "..." : str
        }
    }

    /**
     * Side effects captured during evaluation
     */
    static class SideEffects {
        String printOutput = ""
        String errorOutput = ""
        List<String> imports = []
        List<String> classesLoaded = []
        Map<String, String> systemPropertyChanges = [:]
    }

    /**
     * Type information for introspection
     */
    static class TypeInfo {
        String typeName
        List<String> hierarchy
        List<MethodInfo> methods
        List<PropertyInfo> properties
        String documentation

        static class MethodInfo {
            String name
            String signature
            String returnType
            boolean isStatic
            String visibility
        }

        static class PropertyInfo {
            String name
            String type
            boolean hasGetter
            boolean hasSetter
            boolean isStatic
        }
    }

    /**
     * Initialize the REPL engine with configuration
     */
    GroovyReplEngine(ReplConfiguration configuration = new ReplConfiguration()) {
        this.config = createCompilerConfiguration(configuration)
        this.binding = new Binding()
        this.shell = new GroovyShell(binding, config)
        this.commandHistory = []
        this.metadata = new ConcurrentHashMap<>()
        this.executionTimes = new ConcurrentHashMap<>()
        this.sandboxingEnabled = configuration.sandboxing
        this.executionTimeout = configuration.executionTimeout

        // Initialize with auto-imports
        configuration.autoImports.each { importStmt ->
            addImport(importStmt)
        }

        setupSandboxing(configuration)
    }

    /**
     * Evaluate Groovy code and return result
     */
    ReplResult evaluate(String code) {
        if (!code?.trim()) {
            return ReplResult.success(null, 0)
        }

        long startTime = System.currentTimeMillis()
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream()
        SideEffects sideEffects = new SideEffects()

        try {
            // Capture System.out and System.err
            PrintStream originalOut = System.out
            PrintStream originalErr = System.err
            System.setOut(new PrintStream(outputStream))
            System.setErr(new PrintStream(errorStream))

            Object result
            try {
                // Record execution time per line for debugging
                executionTimes[code] = startTime

                // Add to history before execution
                commandHistory.add(code)
                if (commandHistory.size() > 1000) { // Keep history manageable
                    commandHistory.remove(0)
                }

                // Execute the code
                result = shell.evaluate(code)

                // Capture side effects
                sideEffects.printOutput = outputStream.toString("UTF-8")
                sideEffects.errorOutput = errorStream.toString("UTF-8")

                long duration = System.currentTimeMillis() - startTime
                String output = sideEffects.printOutput + sideEffects.errorOutput

                return ReplResult.success(
                    result,
                    duration,
                    output,
                    getCurrentBindings(),
                    sideEffects
                )

            } finally {
                // Restore streams
                System.setOut(originalOut)
                System.setErr(originalErr)
            }

        } catch (MultipleCompilationErrorsException e) {
            long duration = System.currentTimeMillis() - startTime
            List<String> errors = extractCompilationErrors(e)
            return ReplResult.compilationError(errors, duration)

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            String output = outputStream.toString("UTF-8") + errorStream.toString("UTF-8")
            return ReplResult.runtimeError(e, duration, output)
        }
    }

    /**
     * Get current variable bindings
     */
    Map<String, VariableInfo> getCurrentBindings() {
        Map<String, VariableInfo> result = [:]
        binding.variables.each { entry ->
            String name = entry.key
            Object value = entry.value
            if (!name.startsWith('_')) { // Skip internal variables
                result[name] = new VariableInfo(name, value)
            }
        }
        return result
    }

    /**
     * Reset the REPL state
     */
    void resetState() {
        binding.variables.clear()
        commandHistory.clear()
        executionTimes.clear()
        // Keep imports - they're part of the shell configuration
    }

    /**
     * Add an import statement
     */
    void addImport(String importStatement) {
        try {
            // Execute import as regular code to add it to the shell
            String code = importStatement.startsWith('import ') ? importStatement : "import $importStatement"
            shell.evaluate(code)
        } catch (Exception e) {
            // Ignore import errors - they'll be caught during evaluation
        }
    }

    /**
     * Get completion candidates for partial input
     */
    List<String> getCompletionCandidates(String partial) {
        Set<String> candidates = []

        try {
            // Add variable names
            binding.variables.keySet().each { Object nameObj ->
                String name = nameObj.toString()
                if (name.startsWith(partial) && !name.startsWith('_')) {
                    candidates.add(name)
                }
            }

            // Add Groovy keywords and common methods
            def groovyKeywords = [
                'def', 'class', 'interface', 'enum', 'trait',
                'if', 'else', 'for', 'while', 'switch', 'case', 'default',
                'try', 'catch', 'finally', 'throw', 'throws',
                'public', 'private', 'protected', 'static', 'final',
                'abstract', 'synchronized', 'volatile', 'transient',
                'import', 'package', 'return', 'break', 'continue',
                'new', 'this', 'super', 'null', 'true', 'false'
            ]

            groovyKeywords.each { keyword ->
                if (keyword.startsWith(partial)) {
                    candidates.add(keyword)
                }
            }

            // For method calls (partial contains '.')
            if (partial.contains('.')) {
                String[] parts = partial.split('\\.')
                if (parts.length >= 2) {
                    String objectName = parts[0]
                    String methodPrefix = parts[1]

                    Object obj = binding.getVariable(objectName)
                    if (obj != null) {
                        obj.metaClass.methods.each { method ->
                            if (method.name.startsWith(methodPrefix)) {
                                candidates.add("${objectName}.${method.name}".toString())
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            // Ignore completion errors
        }

        return candidates.toList().sort()
    }

    /**
     * Get type information for a variable
     */
    TypeInfo getTypeInfo(String variableName) {
        Object obj = binding.getVariable(variableName)
        if (obj == null) return null

        TypeInfo typeInfo = new TypeInfo()
        Class<?> clazz = obj.getClass()

        typeInfo.typeName = clazz.name
        typeInfo.hierarchy = getClassHierarchy(clazz)
        typeInfo.methods = getMethodInfo(clazz)
        typeInfo.properties = getPropertyInfo(clazz)

        return typeInfo
    }

    /**
     * Get available methods for an object
     */
    List<String> getAvailableMethods(String objectName) {
        Object obj = binding.getVariable(objectName)
        if (obj == null) return []

        return obj.metaClass.methods.collect { it.name }.unique().sort()
    }

    /**
     * Get command history
     */
    List<String> getHistory(int limit = 50) {
        int size = commandHistory.size()
        int start = Math.max(0, size - limit)
        return commandHistory.subList(start, size)
    }

    // Private helper methods

    private CompilerConfiguration createCompilerConfiguration(ReplConfiguration config) {
        CompilerConfiguration compilerConfig = new CompilerConfiguration()

        // Add import customizer
        ImportCustomizer importCustomizer = new ImportCustomizer()
        config.autoImports.each { importStmt ->
            if (importStmt.endsWith('.*')) {
                importCustomizer.addStarImports(importStmt.substring(0, importStmt.length() - 2))
            } else {
                importCustomizer.addImports(importStmt)
            }
        }
        compilerConfig.addCompilationCustomizers(importCustomizer)

        // Add security customizer if sandboxing is enabled
        if (config.sandboxing) {
            SecureASTCustomizer secureCustomizer = new SecureASTCustomizer()
            secureCustomizer.methodDefinitionAllowed = false
            secureCustomizer.packageAllowed = false
            secureCustomizer.importsWhitelist = []
            secureCustomizer.staticImportsWhitelist = []
            secureCustomizer.staticStarImportsWhitelist = []

            if (config.disallowedMethods) {
                secureCustomizer.methodDefinitionAllowed = false
            }

            compilerConfig.addCompilationCustomizers(secureCustomizer)
        }

        return compilerConfig
    }

    private void setupSandboxing(ReplConfiguration config) {
        if (!config.sandboxing) return

        // Note: Full SecurityManager implementation would go here
        // For now, we rely on SecureASTCustomizer for basic protection
    }

    private List<String> extractCompilationErrors(MultipleCompilationErrorsException e) {
        List<String> errors = []
        e.errorCollector.errors.each { error ->
            errors.add(error.toString())
        }
        return errors
    }

    private List<String> getClassHierarchy(Class<?> clazz) {
        List<String> hierarchy = []
        Class<?> current = clazz
        while (current != null) {
            hierarchy.add(current.name)
            current = current.superclass
        }
        return hierarchy
    }

    private List<TypeInfo.MethodInfo> getMethodInfo(Class<?> clazz) {
        return clazz.methods.collect { method ->
            new TypeInfo.MethodInfo(
                name: method.name,
                signature: method.toString(),
                returnType: method.returnType.name,
                isStatic: java.lang.reflect.Modifier.isStatic(method.modifiers),
                visibility: getVisibility(method.modifiers)
            )
        }
    }

    private List<TypeInfo.PropertyInfo> getPropertyInfo(Class<?> clazz) {
        List<TypeInfo.PropertyInfo> properties = []

        // Get properties via Groovy MetaClass
        clazz.metaClass.properties.each { prop ->
            properties.add(new TypeInfo.PropertyInfo(
                name: prop.name,
                type: prop.type.name,
                hasGetter: true, // MetaClass properties always have getters
                hasSetter: true, // Simplified - would need proper inspection
                isStatic: false // Instance properties
            ))
        }

        return properties
    }

    private String getVisibility(int modifiers) {
        if (java.lang.reflect.Modifier.isPublic(modifiers)) return "public"
        if (java.lang.reflect.Modifier.isProtected(modifiers)) return "protected"
        if (java.lang.reflect.Modifier.isPrivate(modifiers)) return "private"
        return "package"
    }
}
