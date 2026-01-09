# Contributing to rewrite-codenarc

Thank you for your interest in contributing! This guide will help you get started.

## Development Setup

```bash
# Clone the repository
git clone https://github.com/albertocavalcante/rewrite-codenarc.git
cd rewrite-codenarc

# Build the project
./gradlew build

# Run tests
./gradlew test
```

## Adding a New Recipe

Each recipe corresponds to a CodeNarc rule. Follow these steps:

### 1. Identify the CodeNarc Rule

Find the rule at [codenarc.org/codenarc-rules](https://codenarc.org/codenarc-rules.html) and understand:

- What violation it detects
- What the fix should be
- Edge cases to handle

### 2. Write Tests First (TDD)

Create a test class in the appropriate category:

```kotlin
// src/test/kotlin/.../codenarc/<category>/YourRecipeTest.kt
class YourRecipeTest : RewriteTest {

    override fun defaults(spec: RecipeSpec) {
        spec.recipe(YourRecipe())
    }

    @Test
    fun `fixes the violation`() = rewriteRun(
        groovy(
            // Before
            """
            def broken = something
            """,
            // After
            """
            def fixed = something
            """
        )
    )

    @Test
    fun `no change when already correct`() = rewriteRun(
        groovy(
            """
            def alreadyCorrect = something
            """
        )
    )
}
```

### 3. Implement the Recipe

Create the recipe class:

```kotlin
// src/main/kotlin/.../codenarc/<category>/YourRecipe.kt
class YourRecipe : Recipe() {

    override fun getDisplayName() = "Your Recipe Display Name"

    override fun getDescription() = "Fixes XYZ violation by doing ABC."

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return object : GroovyIsoVisitor<ExecutionContext>() {
            // Override visit methods as needed
        }
    }
}
```

### 4. Register the Recipe (Optional)

If the recipe should run as part of the full fix, add it to `FixAllCodeNarcIssues.kt`:

```kotlin
YourRecipe::class.java.name,
```

### 5. Update Documentation

Add the recipe to the README under the appropriate category.

## Recipe Categories

| Category      | Description                      |
| ------------- | -------------------------------- |
| `formatting`  | Whitespace, spacing, indentation |
| `unnecessary` | Remove redundant code            |
| `braces`      | Brace style enforcement          |
| `groovyism`   | Idiomatic Groovy patterns        |
| `convention`  | Code conventions                 |
| `bugs`        | Potential bug fixes              |

## Code Style

- Use Kotlin idioms (prefer `any` over `findFirst().isPresent`)
- Use imports over fully qualified names
- Add meaningful comments for complex logic
- Follow existing patterns in the codebase

## Testing Guidelines

- Test the transformation (before → after)
- Test no-op cases (already correct code)
- Test edge cases (empty bodies, nested structures)
- Document any parser limitations

## Pull Request Process

1. Fork the repository
2. Create a feature branch (`feat/your-recipe-name`)
3. Write tests and implementation
4. Ensure all tests pass: `./gradlew test`
5. Submit a PR with a clear description

## Questions?

Open an issue or start a discussion on the repository.
