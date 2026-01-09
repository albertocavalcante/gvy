# rewrite-codenarc

[OpenRewrite](https://docs.openrewrite.org/) recipes for [CodeNarc](https://codenarc.org/) rules - automated Groovy code
fixes.

## Overview

OpenRewrite recipes that automatically fix violations reported by CodeNarc, the static analysis tool for Groovy. Each
recipe corresponds to a specific CodeNarc rule and applies safe, deterministic transformations to your code.

## Available Recipes

### Formatting

- `AddSpaceAfterComma` - Adds space after commas in method calls, lists, and maps
- `AddSpaceAroundOperator` - Adds spaces around operators (+, -, =, etc.)
- `AddSpaceBeforeOpeningBrace` - Ensures opening braces are preceded by a space

### Unnecessary Code Removal

- `RemoveUnnecessarySemicolon` - Removes trailing semicolons (not required in Groovy)
- `RemoveUnnecessaryGString` - Converts GStrings without interpolation to regular strings
- `RemoveUnnecessaryDotClass` - Removes unnecessary `.class` references
- `RemoveUnnecessaryGetter` - Converts `getName()` to property access `name`
- `RemoveUnnecessaryPublicModifier` - Removes redundant `public` modifiers
- `RemoveUnnecessaryDefInFieldDeclaration` - Removes `def` from typed field declarations
- `RemoveUnnecessaryDefInMethodDeclaration` - Removes `def` from typed method declarations
- `RemoveUnnecessaryReturnKeyword` - Removes unnecessary `return` statements
- `RemoveUnnecessaryToString` - Removes redundant `toString()` calls
- `RemoveUnnecessaryTernaryExpression` - Simplifies boolean ternary expressions
- `RemoveUnnecessaryNullCheckBeforeInstanceOf` - Simplifies null checks with instanceof
- `RemoveUnnecessaryElseStatement` - Removes else when if returns

### Bug Fixes

- `FixBrokenNullCheck` - Fixes broken null checks (|| → &&)

### Braces

- `AddBracesToIfStatement` - Wraps single-line if statements in braces
- `AddBracesToForLoop` - Wraps single-line for loops in braces
- `AddBracesToWhileLoop` - Wraps single-line while loops in braces

### Groovyisms

- `SimplifyExplicitArrayListInstantiation` - `new ArrayList()` → `[]`
- `SimplifyExplicitHashMapInstantiation` - `new HashMap()` → `[:]`
- `SimplifyExplicitHashSetInstantiation` - `new HashSet()` → `[] as Set`
- `MoveClosureAsLastMethodParameter` - `each({ })` → `each { }`
- `SimplifyTernaryToElvis` - `x ? x : y` → `x ?: y`

### Conventions

- `ConvertIfToElvis` - Converts null/false checks to Elvis operator
- `UninvertIfElse` - Inverts negated if-else conditions

## Usage

### With Gradle

Add the dependency to your `build.gradle.kts`:

```kotlin
plugins {
    id("org.openrewrite.rewrite") version "latest.release"
}

dependencies {
    rewrite("com.github.albertocavalcante:rewrite-codenarc:VERSION")
}

rewrite {
    activeRecipe("com.github.albertocavalcante.refactor.FixAllCodeNarcIssues")
}
```

Then run:

```bash
./gradlew rewriteRun
```

### Running Specific Recipes

```bash
./gradlew rewriteRun -Drewrite.activeRecipe=com.github.albertocavalcante.refactor.codenarc.unnecessary.RemoveUnnecessarySemicolon
```

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

## Related Projects

- [OpenRewrite](https://github.com/openrewrite/rewrite) - Automated code refactoring
- [CodeNarc](https://github.com/CodeNarc/CodeNarc) - Static analysis for Groovy
- [gvy](https://github.com/albertocavalcante/gvy) - Groovy Language Server and tools
