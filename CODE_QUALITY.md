# Code Quality

This project uses multiple tools to maintain high code quality and consistent formatting.

## Tools Overview

### Detekt

Static analysis for Kotlin code that detects code smells, complexity issues, and style violations.

### Spotless + ktlint

Code formatting and style enforcement following Kotlin official style guidelines.

### Kover

Native Kotlin code coverage tracking with 70% line coverage requirement.

### EditorConfig

IDE-agnostic configuration for consistent formatting across different editors.

## Running Quality Checks

```bash
# Run all linting (Detekt + Spotless)
make lint

# Or run directly via Gradle (force rerun to avoid up-to-date hiding findings)
./gradlew lint --rerun-tasks

# Run individual tools
./gradlew detekt
./gradlew spotlessCheck
./gradlew koverVerify

# Auto-fix formatting issues
./gradlew spotlessApply

# Generate coverage report
./gradlew koverHtmlReport
```

## Quality Rules

### Wildcard Imports

Prohibited via triple enforcement:

- .editorconfig settings
- Spotless/ktlint rules
- Detekt configuration

Replace wildcard imports with explicit imports:

```kotlin
// Bad
import kotlinx.coroutines.*

// Good
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
```

### Code Coverage

- Minimum 70% line coverage required
- Reports generated at `build/reports/kover/html/index.html`

### Code Style

- Maximum line length: 120 characters
- 4-space indentation
- No trailing whitespace
- Final newline required

### Complexity Limits

- Cyclomatic complexity: ≤15 per method
- Method length: ≤60 lines
- Parameter count: ≤6 for functions, ≤7 for constructors
- Class size: ≤600 lines

## CI Integration

Quality checks run automatically on:

- Push to main/master
- Pull requests
- Workflow dispatch

All checks must pass before merging.

## Common Fixes

### Magic Numbers

Replace magic numbers with named constants:

```kotlin
// Bad
if (items.size > 5) { ... }

// Good
private val MAX_ITEMS = 5
if (items.size > MAX_ITEMS) { ... }
```

### Complex Methods

Break down complex methods:

```kotlin
// Bad - high cyclomatic complexity
fun processData(data: List<String>): Result {
    if (data.isEmpty()) return Result.empty()
    if (data.size > 100) return Result.error("Too large")
    // ... many more conditions
}

// Good - extracted helper methods
fun processData(data: List<String>): Result {
    return when {
        isEmpty(data) -> Result.empty()
        isTooLarge(data) -> Result.error("Too large")
        else -> doProcessing(data)
    }
}
```

### Return Count

Limit return statements per method (max 2):

```kotlin
// Bad - multiple returns
fun validate(input: String): Boolean {
    if (input.isEmpty()) return false
    if (input.length < 3) return false
    if (!input.matches(regex)) return false
    return true
}

// Good - single return
fun validate(input: String): Boolean {
    return input.isNotEmpty() &&
           input.length >= 3 &&
           input.matches(regex)
}
```

## Configuration Files

- `tools/lint/detekt.yml` - Detekt rules and thresholds
- `.editorconfig` - IDE formatting settings
- `build.gradle.kts` - Plugin configurations and tasks

Current issue threshold: 100 (to be gradually reduced)
