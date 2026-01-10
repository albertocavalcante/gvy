# Groovy Test Utils

This module provides shared test infrastructure and utilities for the Groovy LSP project.

## What's Included

### GroovySourceFixture

Parsing helpers for tests that provide convenient methods for parsing Groovy source code into AST representations.

### DiagnosticFixture

Diagnostic creation helpers for testing diagnostic-related functionality.

### MockPatterns

Common MockK patterns for mocking services consistently across test suites.

## Jenkins Test Stubs

**Note:** This module previously contained duplicate Jenkins stub classes and annotations.

Those stubs have been removed to avoid duplication. Jenkins stub classes exist in `groovy-jenkins/src/test/kotlin/` but
are **NOT** currently exposed as consumable test fixtures.

**Current state:** Test sources from groovy-jenkins are NOT accessible to other modules. To use Jenkins stubs in your
tests:

1. Copy the needed stub classes locally to your test sources, OR
2. Request that groovy-jenkins enables the java-test-fixtures plugin to expose these stubs as consumable test fixtures

Example stub classes that exist (but are not consumable):

- `hudson.model.Descriptor` (in groovy-jenkins/src/test/kotlin)
- `org.jenkinsci.Symbol`
- `org.jenkinsci.plugins.workflow.cps.GlobalVariable`
- `org.jenkinsci.plugins.workflow.steps.Step`
- `org.jenkinsci.plugins.workflow.steps.StepDescriptor`
- `org.kohsuke.stapler.DataBoundConstructor`
- `org.kohsuke.stapler.DataBoundSetter`

## Usage

Add this module as a test dependency in your `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation(project(":groovy-test-utils"))
}
```

The test frameworks (JUnit, MockK, AssertJ, etc.) are exposed as transitive dependencies, so you don't need to declare
them separately in your consuming module.
