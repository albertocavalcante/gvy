# Troubleshooting

This guide contains common issues, solutions, and debugging techniques for building and developing the Groovy LSP.

## Table of Contents

- [Build Failures](#build-failures)
- [Debugging Techniques](#debugging-techniques)
- [AST & Compilation Issues](#ast--compilation-issues)
- [Test Debugging](#test-debugging)
- [Common Pitfalls](#common-pitfalls)

---

## Build Failures

### Gradle Configuration Cache Issues

**Symptoms:**
- Build fails with `Spotless error! All target files must be within the project dir.`
- Error message shows path mismatch (e.g., `/workspaces/...` vs `/Users/...`).
- Occurs after switching environments (e.g., from Codespaces/Docker to local machine).

**Cause:**
Gradle's configuration cache may retain environment-specific paths (like the project root directory) from a previous build in a different environment. When you run the build in a new environment, these cached paths are invalid.

**Solution:**

1. **Stop the Gradle Daemon** to clear in-memory cache:
   ```bash
   ./gradlew --stop
   ```

2. **Clean build artifacts** and the local `.gradle` cache:
   ```bash
   rm -rf .gradle
   find . -name "build" -type d -prune -exec rm -rf {} +
   ```

3. **Run build with configuration cache disabled** (once) to force a refresh:
   ```bash
   ./gradlew build --no-configuration-cache
   ```

4. **Resume normal building**:
   ```bash
   make build
   ```

### Lint/Formatting Issues

**Symptoms:**
- Build fails with `Spotless error!` or `Detekt error`.

**Solution:**
Run the auto-formatter to fix most issues automatically:
```bash
make format
```

### Java Runtime Not Found

**Symptoms:**
- `The operation couldn't be completed. Unable to locate a Java Runtime.`

**Cause:**
Shell environment doesn't have Java configured. Common when using direnv or sdkman.

**Solution:**
Source your environment before running commands:
```bash
eval "$(direnv export bash)"
# or
source ~/.sdkman/bin/sdkman-init.sh
```

---

## Debugging Techniques

### Groovy AST Experimentation

When investigating Groovy AST behavior, **run quick `groovy` scripts** to verify assumptions. This is faster than writing tests and helps understand compiler internals.

**Basic AST Inspection:**
```bash
groovy -e '
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.control.*

def code = """
def method1(param) { println param }
def method2(param) { println param }
"""

def ast = new CompilationUnit().tap {
    addSource("test", code)
    compile(Phases.SEMANTIC_ANALYSIS)
}.ast.modules[0]

def m1 = ast.classes[0].methods.find { it.name == "method1" }
def m2 = ast.classes[0].methods.find { it.name == "method2" }

println "m1.param line: ${m1.parameters[0].lineNumber}"
println "m2.param line: ${m2.parameters[0].lineNumber}"
println "Same object? ${m1.parameters[0].is(m2.parameters[0])}"
'
```

**Check accessedVariable Resolution:**
```bash
groovy -e '
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.control.*

def code = """
def method(x) {
    println x
}
"""

def unit = new CompilationUnit()
unit.addSource("test", code)
unit.compile(Phases.SEMANTIC_ANALYSIS)

def module = unit.ast.modules[0]
def method = module.classes[0].methods.find { it.name == "method" }

// Find VariableExpression for x
method.code.visit(new org.codehaus.groovy.ast.CodeVisitorSupport() {
    void visitVariableExpression(VariableExpression expr) {
        if (expr.name == "x") {
            println "Variable: ${expr.name} at ${expr.lineNumber}:${expr.columnNumber}"
            println "accessedVariable: ${expr.accessedVariable}"
            println "accessedVariable class: ${expr.accessedVariable?.class?.simpleName}"
            println "Is same as param? ${expr.accessedVariable.is(method.parameters[0])}"
        }
    }
})
'
```

**Useful for understanding:**
- What fields AST nodes actually have
- Whether identity (`===`) vs equals (`==`) matters
- How Groovy's compiler populates positions, types, etc.
- Which compilation phase sets specific fields (e.g., `accessedVariable` is set in SEMANTIC_ANALYSIS)

### Compilation Phases Reference

Groovy compiles in phases. Understanding them is critical for debugging:

| Phase | Value | Description |
|-------|-------|-------------|
| INITIALIZATION | 1 | Setup |
| PARSING | 2 | Source to syntax tree |
| CONVERSION | 3 | Syntax tree to AST |
| **SEMANTIC_ANALYSIS** | 4 | Variable scope, type resolution, **sets accessedVariable** |
| CANONICALIZATION | 5 | Normalize AST |
| INSTRUCTION_SELECTION | 6 | Prepare for bytecode |
| CLASS_GENERATION | 7 | Generate bytecode |
| OUTPUT | 8 | Write class files |
| FINALIZATION | 9 | Cleanup |

**Key insight:** If you compile only to CONVERSION (phase 3), `accessedVariable` will be null because SEMANTIC_ANALYSIS (phase 4) hasn't run.

---

## AST & Compilation Issues

### accessedVariable is null

**Symptoms:**
- `VariableExpression.accessedVariable` returns null
- Symbol resolution falls back to symbol table
- Wrong definition returned for same-named variables

**Cause:**
Compilation stopped before SEMANTIC_ANALYSIS phase (phase 4).

**Debug:**
```kotlin
// Add to your code temporarily
if (node is VariableExpression) {
    val av = node.accessedVariable
    System.err.println("VarExpr ${node.name}: accessedVariable=$av, isASTNode=${av is ASTNode}")
}
```

**Common triggers:**
- `shouldRetryAtConversion` in `GroovyParserFacade` triggering for scripts
- Explicit compilation at `Phases.CONVERSION`

### Script vs Class Detection

**Symptoms:**
- Classes incorrectly detected as scripts
- Semantic analysis lost after retry

**Cause:**
`shouldRetryAtConversion` triggers when a class extends `groovy.lang.Script`, but this also matches intentional scripts.

**Solution:**
Check for `class` keyword in source:
```kotlin
val hasClassKeyword = content.contains(Regex("""\bclass\s+\w+"""))
```

### Parameter Identity Issues

**Symptoms:**
- Same-named parameters in different methods match incorrectly
- 3 highlights when expecting 2

**Debug with identity hash codes:**
```kotlin
System.err.println(
    "node=${node::class.simpleName} at ${node.lineNumber}:${node.columnNumber}, " +
    "nodeId=${System.identityHashCode(node)}, " +
    "defId=${System.identityHashCode(definition)}"
)
```

**Key insight:** Groovy reuses the same Parameter object for declaration and all references (via `accessedVariable`). Use identity comparison (`===`), not equals (`==`).

---

## Test Debugging

### Adding Debug Output

JUnit captures stdout/stderr. To see debug output:

**Option 1: Use System.err.println**
```kotlin
System.err.println("DEBUG: value=$value")
```

**Option 2: Check test reports**
```bash
# Run test
./gradlew :groovy-lsp:test --tests "*.MyTest.my test name"

# Check XML output
cat groovy-lsp/build/test-results/test/*.xml | grep "DEBUG"

# Or check HTML report
open groovy-lsp/build/reports/tests/test/index.html
```

### Running Specific Tests

```bash
# Single test by name
./gradlew :groovy-lsp:test --tests "*.DocumentHighlightProviderTest.test highlight parameter references with scoping"

# All tests in a class
./gradlew :groovy-lsp:test --tests "*.DocumentHighlightProviderTest"

# Clean build (clear cache issues)
./gradlew :groovy-lsp:clean :groovy-lsp:test --tests "*.MyTest"
```

### Test Cache Issues

**Symptoms:**
- Test passes locally but fails in CI
- Test fails locally but passes after clean

**Solution:**
```bash
./gradlew :groovy-lsp:clean :groovy-lsp:test --tests "*.MyTest"
```

---

## Common Pitfalls

### Symbol Table Non-Determinism

**Issue:** The symbol table stores variables by name only. Multiple variables with the same name overwrite each other.

```kotlin
// Bug: Last registered "param" wins
storage.variableDeclarations[uri]?.get("param")
```

**Mitigation:** Rely on `accessedVariable` (set by semantic analysis) instead of symbol table fallback for parameters.

### Line Number Indexing

**Issue:** Different systems use different indexing:
- Groovy AST: 1-indexed
- LSP Protocol: 0-indexed
- Test assertions: Check which one you're using!

**Debug:**
```kotlin
println("Groovy line: ${node.lineNumber}, LSP line: ${node.lineNumber - 1}")
```

### ConcurrentHashMap Iteration Order

**Issue:** When using `ConcurrentHashMap`, iteration order is undefined.

**Impact:** If you iterate to find "the first" match, you may get different results across runs.

**Solution:** Use deterministic selection criteria (e.g., position, identity) not iteration order.

### Closure vs Method Parameters

**Issue:** Closures have their own parameter scope. A parameter name inside a closure may refer to:
- The closure's parameter
- An outer method's parameter
- A captured variable

**Debug:**
```bash
groovy -e '
def method(x) {
    def closure = { y ->
        println x  // refers to method param
        println y  // refers to closure param
    }
}
'
```

### Git Worktree State

**Issue:** Changes in main worktree affect all worktrees.

**Check:**
```bash
git worktree list
git status  # In your current worktree
```

---

## Quick Reference

| Problem | First Step |
|---------|------------|
| Build fails | `make format && make build` |
| Test fails in CI only | `./gradlew clean test --tests "*.MyTest"` |
| Wrong definition resolved | Check `accessedVariable` with `groovy -e` |
| accessedVariable is null | Check compilation phase (needs SEMANTIC_ANALYSIS) |
| Same-named param collision | Use identity comparison (`===`) |
| Debug output not visible | Use `System.err.println`, check test XML |
