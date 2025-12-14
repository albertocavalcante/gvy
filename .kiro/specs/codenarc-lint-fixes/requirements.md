# Requirements Document

## Introduction

This specification defines the implementation of deterministic CodeNarc lint fix actions for the Groovy LSP. The feature enhances the existing `LintFixAction` placeholder to provide automatic quick-fix code actions for common, safe-to-fix CodeNarc violations.

The implementation is organized into three phases for incremental delivery:
- **Phase 1**: Core whitespace and formatting fixes (simplest, lowest risk)
- **Phase 2**: Import cleanup fixes
- **Phase 3**: Convention and Groovy idiom fixes (more complex transformations)

This approach allows for incremental PRs with focused scope, easier code review, and progressive validation.

## Glossary

- **CodeNarc**: A static analysis tool for Groovy (version 3.7.0) that identifies potential bugs, code smells, and style violations
- **LSP (Language Server Protocol)**: A protocol for communication between code editors and language servers
- **Code Action**: An LSP feature that provides quick fixes or refactoring suggestions for diagnostics
- **Diagnostic**: An LSP message indicating an error, warning, or information about code
- **Quick Fix**: A code action that automatically resolves a diagnostic issue
- **Deterministic Fix**: A transformation that always produces the same correct result for a given input
- **TextEdit**: An LSP structure representing a text modification with a range and replacement text
- **Fix Handler**: A function that creates a TextEdit for a specific CodeNarc rule violation

## Requirements

### Requirement 1: Core Infrastructure

**User Story:** As a developer, I want the lint fix system to be extensible and maintainable, so that new fix types can be added without modifying existing code.

#### Acceptance Criteria

1. WHEN a new CodeNarc rule fix is implemented THEN the system SHALL use a registry pattern mapping rule names to fix handler functions
2. WHEN a diagnostic has no registered fix handler THEN the system SHALL return null without errors
3. WHEN processing diagnostics THEN the system SHALL only process diagnostics with source "CodeNarc"
4. WHEN a fix handler is invoked THEN the system SHALL pass the diagnostic, source content, and source lines to the handler
5. WHEN the diagnostic code is available THEN the system SHALL use it to look up the fix handler by rule name

### Requirement 2: Phase 1 - Whitespace and Formatting Fixes

**User Story:** As a developer, I want whitespace and formatting violations to be automatically fixable, so that I can quickly clean up my code style.

#### Acceptance Criteria

1. WHEN a CodeNarc `TrailingWhitespace` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove trailing whitespace" that removes all trailing whitespace from the affected line
2. WHEN a CodeNarc `UnnecessarySemicolon` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove unnecessary semicolon" that removes the trailing semicolon
3. WHEN a CodeNarc `ConsecutiveBlankLines` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove consecutive blank lines" that reduces multiple blank lines to a single blank line
4. WHEN a CodeNarc `BlankLineBeforePackage` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove blank lines before package" that removes blank lines before the package statement

### Requirement 3: Phase 2 - Import Cleanup Fixes

**User Story:** As a developer, I want import-related violations to be automatically fixable, so that I can keep my import statements clean and organized.

#### Acceptance Criteria

1. WHEN a CodeNarc `UnusedImport` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove unused import" that removes the entire import line including the newline
2. WHEN a CodeNarc `DuplicateImport` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove duplicate import" that removes the duplicate import line
3. WHEN a CodeNarc `UnnecessaryGroovyImport` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove unnecessary import" that removes imports for classes in java.lang, java.util, java.io, java.net, groovy.lang, or groovy.util packages
4. WHEN a CodeNarc `ImportFromSamePackage` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove same-package import" that removes the import

### Requirement 4: Phase 3 - Convention Fixes

**User Story:** As a developer, I want convention violations to be automatically fixable, so that I can write more idiomatic Groovy code.

#### Acceptance Criteria

1. WHEN a CodeNarc `UnnecessaryPublicModifier` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove unnecessary 'public'" that removes the public keyword
2. WHEN a CodeNarc `UnnecessaryDefInVariableDeclaration` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove unnecessary 'def'" that removes the def keyword when a type is specified
3. WHEN a CodeNarc `UnnecessaryGetter` diagnostic is present THEN the system SHALL provide a quick fix titled "Use property access" that converts `obj.getProperty()` to `obj.property`
4. WHEN a CodeNarc `UnnecessarySetter` diagnostic is present THEN the system SHALL provide a quick fix titled "Use property assignment" that converts `obj.setProperty(value)` to `obj.property = value`
5. WHEN a CodeNarc `UnnecessaryDotClass` diagnostic is present THEN the system SHALL provide a quick fix titled "Remove '.class'" that removes the .class suffix from class literals

### Requirement 5: Safety and Validation

**User Story:** As a developer, I want lint fixes to be safe and predictable, so that I can trust the automated transformations.

#### Acceptance Criteria

1. WHEN a fix is applied THEN the system SHALL only modify the specific range indicated by the diagnostic
2. WHEN the diagnostic range is invalid or out of bounds THEN the system SHALL skip the fix and log a warning
3. WHEN the diagnostic line number exceeds the source line count THEN the system SHALL return null for that fix
4. WHEN creating a line removal fix THEN the system SHALL include the newline character in the removal range

### Requirement 6: LSP Integration

**User Story:** As a developer, I want lint fix actions to integrate seamlessly with the existing code action system, so that fixes appear alongside other quick fixes.

#### Acceptance Criteria

1. WHEN code actions are requested THEN the LintFixAction SHALL return actions with kind `CodeActionKind.QuickFix`
2. WHEN a lint fix action is created THEN the action SHALL include the original diagnostic in its diagnostics list
3. WHEN a lint fix action is created THEN the action SHALL have a descriptive title indicating the fix being applied
4. WHEN multiple diagnostics are present THEN the system SHALL return fix actions for all diagnostics that have registered handlers

### Requirement 7: Empty Block Handling (Deferred)

**User Story:** As a developer, I want guidance on empty blocks, so that I can address potential code quality issues.

#### Acceptance Criteria

1. WHEN a CodeNarc `EmptyCatchBlock` diagnostic is present THEN the system MAY provide a quick fix titled "Add TODO comment" that inserts `// TODO: Handle exception` inside the empty block
2. WHEN implementing empty block fixes THEN the system SHALL clearly indicate in the fix title that a TODO is being added
3. WHEN the user has intentionally empty blocks THEN the system SHALL NOT automatically apply fixes without user confirmation

**Note:** Empty block fixes are lower priority and may be deferred to a future phase, as the intent (intentional vs. oversight) is ambiguous.
