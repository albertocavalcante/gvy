# GroovyParser-Core: Feature Completion Plan

> **Goal**: Address all TODOs and make `groovyparser-core` feature-complete for LSP integration.

## Current State

| Component | Status | Coverage |
|-----------|--------|----------|
| AST Nodes | ✅ Complete | All expressions, statements, declarations |
| Parser | ✅ Complete | Multi-version support (2.4-5.x) |
| Visitors | ✅ Complete | VoidVisitor, GroovyVisitor<R> |
| Comments | ✅ Complete | Line, Block, Javadoc |
| Error Recovery | ✅ Complete | Lenient mode + problem reports |
| Type System | ✅ Complete | Primitives, references, arrays, wildcards |
| Type Solvers | ✅ Complete | Reflection, JAR, Combined |
| Type Inference | ✅ Robust | LUB for generics (List, Map, Closure) |
| Symbol Resolution | ⚠️ Partial | 3 TODOs remaining |
| Printers | ✅ Complete | XML, YAML, DOT, Pretty |

## Outstanding TODOs

### 1. Nested Class Resolution
**File**: `ClassContext.kt:43`
```kotlin
// TODO: Check nested classes
```

**Scope**: When resolving a type name within a class, check if it matches a nested/inner class before delegating to parent context.

### 2. Local Variable Resolution  
**File**: `MethodContext.kt:36`
```kotlin
// TODO: Check local variables in method body
```

**Scope**: Track variable declarations within method bodies for symbol resolution.

### 3. Method Type Parameters
**File**: `MethodContext.kt:56`
```kotlin
// TODO: Check method type parameters
```

**Scope**: Resolve generic type parameters declared on methods (e.g., `<T> T foo(T arg)`).

---

## Phase 1: Local Variable Tracking

**Priority**: High (most impactful for LSP features like hover, go-to-definition)

### 1.1 Design

Track variables declared in:
- Method parameters (already done)
- `def x = ...` declarations
- For-each loop variables (`for (item in list)`)
- Catch clause parameters
- Closure parameters

### 1.2 Implementation

#### Test First (TDD)
```kotlin
// MethodContextTest.kt
@Test
fun `resolves local variable declared in method body`() {
    val code = """
        class Foo {
            void bar() {
                def x = 42
                println(x)
            }
        }
    """.trimIndent()
    
    val cu = StaticGroovyParser.parse(code)
    val resolver = GroovySymbolResolver(ReflectionTypeSolver())
    resolver.inject(cu)
    
    // Find the VariableExpr 'x' in println(x)
    val printlnCall = // ... navigate to it
    val xRef = printlnCall.arguments[0] as VariableExpr
    
    val resolved = resolver.resolve(xRef)
    assertTrue(resolved.isSolved)
    assertEquals("int", resolved.getDeclaration().type.describe())
}
```

#### Implementation Steps

1. **Add `LocalVariableDeclaration` tracking to `MethodContext`**
   ```kotlin
   class MethodContext(...) : Context {
       private val localVariables = mutableMapOf<String, ResolvedValueDeclaration>()
       
       fun registerLocalVariable(name: String, declaration: ResolvedValueDeclaration) {
           localVariables[name] = declaration
       }
       
       override fun solveSymbol(name: String): SymbolReference<ResolvedValueDeclaration> {
           // Check parameters first (existing)
           // Then check local variables
           localVariables[name]?.let { return SymbolReference.solved(it) }
           // Then delegate to parent
       }
   }
   ```

2. **Populate locals during AST traversal**
   - Hook into `DeclarationExpr` conversion
   - Track scope entry/exit for blocks

3. **Handle shadowing correctly**
   - Inner scope shadows outer scope
   - Use a stack of scopes within method

### 1.3 Acceptance Criteria

- [ ] Test: Resolve `def x = 1` variable reference
- [ ] Test: Resolve for-each loop variable
- [ ] Test: Resolve catch clause parameter
- [ ] Test: Inner block shadows outer variable
- [ ] Test: Closure parameter resolution

---

## Phase 2: Nested Class Resolution

**Priority**: Medium (needed for inner class references)

### 2.1 Design

When resolving type `Inner` inside class `Outer`:
1. Check if `Outer` has a nested class named `Inner`
2. Check if any superclass of `Outer` has accessible nested class `Inner`
3. Then delegate to parent context (imports, etc.)

### 2.2 Implementation

#### Test First
```kotlin
@Test
fun `resolves nested class reference`() {
    val code = """
        class Outer {
            class Inner {}
            
            void foo() {
                Inner x = new Inner()
            }
        }
    """.trimIndent()
    
    val cu = StaticGroovyParser.parse(code)
    val resolver = GroovySymbolResolver(ReflectionTypeSolver())
    resolver.inject(cu)
    
    val innerRef = // find the Inner type reference
    val resolved = resolver.resolveType(innerRef)
    assertTrue(resolved.isSolved)
    assertEquals("Outer.Inner", resolved.getDeclaration().qualifiedName)
}
```

#### Implementation Steps

1. **Extend `ClassContext.solveType()`**
   ```kotlin
   override fun solveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
       // Check nested classes
       classDeclaration.getDeclaredNestedClasses().find { it.name == name }?.let {
           return SymbolReference.solved(it)
       }
       
       // Check inherited nested classes
       classDeclaration.getAncestors().forEach { ancestor ->
           ancestor.declaration.getDeclaredNestedClasses()
               .find { it.name == name && it.isAccessibleFrom(classDeclaration) }
               ?.let { return SymbolReference.solved(it) }
       }
       
       return parent.solveType(name)
   }
   ```

2. **Add `getDeclaredNestedClasses()` to `ResolvedClassDeclaration`**

3. **Handle static vs instance nested classes**

### 2.3 Acceptance Criteria

- [ ] Test: Resolve simple nested class
- [ ] Test: Resolve inherited nested class
- [ ] Test: Static nested class accessible from static context
- [ ] Test: Instance inner class requires outer instance

---

## Phase 3: Method Type Parameters

**Priority**: Medium (needed for generic method calls)

### 3.1 Design

Methods can declare type parameters:
```groovy
<T> T identity(T value) { return value }
```

When resolving `T` inside the method body, check method's type parameters.

### 3.2 Implementation

#### Test First
```kotlin
@Test
fun `resolves method type parameter`() {
    val code = """
        class Foo {
            <T> T identity(T value) {
                T local = value
                return local
            }
        }
    """.trimIndent()
    
    val cu = StaticGroovyParser.parse(code)
    val resolver = GroovySymbolResolver(ReflectionTypeSolver())
    resolver.inject(cu)
    
    val tRef = // find the T type in "T local"
    val resolved = resolver.resolveType(tRef)
    assertTrue(resolved.isSolved)
    assertTrue(resolved.getDeclaration() is ResolvedTypeParameterDeclaration)
}
```

#### Implementation Steps

1. **Extend `MethodContext.solveGenericType()`**
   ```kotlin
   override fun solveGenericType(name: String): ResolvedType? {
       // Check method type parameters
       methodDeclaration.getTypeParameters().find { it.name == name }?.let {
           return ResolvedTypeVariable(it)
       }
       
       return parent.solveGenericType(name)
   }
   ```

2. **Ensure `MethodDeclaration` exposes type parameters**

### 3.3 Acceptance Criteria

- [ ] Test: Resolve method type parameter in return type
- [ ] Test: Resolve method type parameter in parameter type
- [ ] Test: Resolve method type parameter in local variable
- [ ] Test: Method type param shadows class type param

---

## Phase 4: Flow-Sensitive Type Narrowing (Optional Enhancement)

**Priority**: Low (nice-to-have for smart completions)

### 4.1 Design

After `instanceof` check, narrow the type:
```groovy
if (obj instanceof String) {
    obj.length()  // obj is String here
}
```

### 4.2 Scope

This is a significant undertaking requiring:
- Control flow graph construction
- Type state tracking per branch
- Merge logic at join points

**Recommendation**: Defer to future PR. Create GitHub issue to track.

---

## Execution Plan

| Phase | Effort | PR |
|-------|--------|-----|
| Phase 1: Local Variables | 2-3 hours | `feat/parser-local-vars` |
| Phase 2: Nested Classes | 1-2 hours | `feat/parser-nested-classes` |
| Phase 3: Method Type Params | 1 hour | `feat/parser-method-generics` |
| Phase 4: Flow Narrowing | 4+ hours | Defer to issue |

### PR Strategy

Each phase should be a separate PR:
1. Self-contained with tests
2. Builds on previous (merge in order)
3. Can be reviewed independently

---

## Success Metrics

After completion:
- [ ] All 3 TODOs resolved
- [ ] 100% of symbol resolution tests pass
- [ ] No regressions in existing 95+ tests
- [ ] LSP hover works for local variables
- [ ] LSP go-to-definition works for nested classes
