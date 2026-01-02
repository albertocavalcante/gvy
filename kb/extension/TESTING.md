# Testing with VS Code Groovy Extension

This extension provides integrated test running for Groovy and Spock tests.

## How It Works

The test runner uses a **Gradle init script** to capture test events in real-time:

1. When you run tests, the extension spawns `./gradlew test --init-script test-events.init.gradle`
2. The init script injects a `TestListener` that emits JSON events to stdout
3. The extension parses these events and updates the VS Code Test Explorer in real-time

## Spock `@Unroll` Support

For data-driven Spock tests using `@Unroll`, iterations are created dynamically:

```groovy
@Unroll
def "maximum of #a and #b is #c"() {
    expect:
    Math.max(a, b) == c

    where:
    a | b | c
    1 | 3 | 3
    7 | 4 | 7
}
```

Each iteration appears as a child test in the Test Explorer:

- `maximum of 1 and 3 is 3` ✅
- `maximum of 7 and 4 is 7` ✅

## Requirements

- A Gradle wrapper (`gradlew`) in your project root
- JDK configured via `groovy.java.home` or `JAVA_HOME`

## Troubleshooting

### Tests not appearing

Ensure the Groovy Language Server is running and has indexed your test files.

### Gradle wrapper not found

The extension looks for `gradlew` (or `gradlew.bat` on Windows) in your workspace root.
