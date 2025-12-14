# Implementation Plan

## Phase 1: Core Infrastructure and Whitespace Fixes

- [x] 1. Set up core infrastructure with TDD
  - [x] 1.1 Write tests for FixContext data class
    - Create `FixContextTest.kt` with tests for data class construction and properties
    - _Requirements: 1.4_
  - [x] 1.2 Write tests for FixHandlerRegistry
    - Create `FixHandlerRegistryTest.kt` with tests for handler lookup
    - Test registered handler returns non-null
    - Test unregistered handler returns null
    - **Property 1: Registry Lookup Consistency**
    - **Validates: Requirements 1.1, 1.2**
    - _Requirements: 1.1, 1.2_
  - [x] 1.3 Implement FixContext and FixHandlerRegistry
    - Create `FixContext.kt` data class
    - Create `FixHandlerRegistry.kt` object with empty handlers map
    - _Requirements: 1.1, 1.4_
  - [x] 1.4 Write property test for source filtering
    - **Property 2: Source Filtering**
    - **Validates: Requirements 1.3**
    - _Requirements: 1.3_
  - [x] 1.5 Update LintFixAction to use registry pattern
    - Modify `createFixForDiagnostic` to use registry lookup
    - Add source filtering for "CodeNarc" diagnostics only
    - _Requirements: 1.3, 1.5_

- [x] 2. Implement TrailingWhitespace fix with TDD
  - [x] 2.1 Write property test for trailing whitespace removal
    - **Property 3: Trailing Whitespace Removal**
    - **Validates: Requirements 2.1**
    - _Requirements: 2.1_
  - [x] 2.2 Write unit test for TrailingWhitespace handler
    - Test with line "def x = 1   " should produce "def x = 1"
    - Test with line containing only whitespace
    - _Requirements: 2.1_
  - [x] 2.3 Implement fixTrailingWhitespace handler
    - Register handler in FixHandlerRegistry
    - Handler removes trailing whitespace from diagnostic line
    - _Requirements: 2.1_

- [x] 3. Implement UnnecessarySemicolon fix with TDD
  - [x] 3.1 Write property test for semicolon removal
    - **Property 4: Semicolon Removal**
    - **Validates: Requirements 2.2**
    - _Requirements: 2.2_
  - [x] 3.2 Write unit test for UnnecessarySemicolon handler
    - Test with "def x = 1;" should produce "def x = 1"
    - Test with "def x = 1;  " should produce "def x = 1  "
    - _Requirements: 2.2_
  - [x] 3.3 Implement fixUnnecessarySemicolon handler
    - Register handler in FixHandlerRegistry
    - Handler removes semicolon while preserving trailing whitespace
    - _Requirements: 2.2_

- [x] 4. Implement ConsecutiveBlankLines fix with TDD
  - [x] 4.1 Write property test for consecutive blank lines reduction
    - **Property 5: Consecutive Blank Lines Reduction**
    - **Validates: Requirements 2.3**
    - _Requirements: 2.3_
  - [x] 4.2 Write unit test for ConsecutiveBlankLines handler
    - Test with 3 blank lines should reduce to 1
    - Test with 2 blank lines should reduce to 1
    - _Requirements: 2.3_
  - [x] 4.3 Implement fixConsecutiveBlankLines handler
    - Register handler in FixHandlerRegistry
    - Handler reduces multiple blank lines to single blank line
    - _Requirements: 2.3_

- [x] 5. Implement BlankLineBeforePackage fix with TDD
  - [x] 5.1 Write unit test for BlankLineBeforePackage handler
    - Test with blank lines before package statement
    - _Requirements: 2.4_
  - [x] 5.2 Implement fixBlankLineBeforePackage handler
    - Register handler in FixHandlerRegistry
    - Handler removes blank lines before package statement
    - _Requirements: 2.4_

- [x] 6. Checkpoint - Phase 1 Complete
  - Ensure all tests pass, ask the user if questions arise.

## Phase 2: Import Cleanup Fixes

- [ ] 7. Implement import line removal fixes with TDD
  - [ ] 7.1 Write property test for import line removal
    - **Property 6: Import Line Removal**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - [ ] 7.2 Write unit tests for import removal handlers
    - Test UnusedImport removes entire line including newline
    - Test DuplicateImport removes entire line
    - Test UnnecessaryGroovyImport removes entire line
    - Test ImportFromSamePackage removes entire line
    - _Requirements: 3.1, 3.2, 3.3, 3.4_
  - [ ] 7.3 Implement fixRemoveImportLine handler
    - Create shared handler for all import removal rules
    - Register for UnusedImport, DuplicateImport, UnnecessaryGroovyImport, ImportFromSamePackage
    - Handler removes entire line including newline character
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 8. Checkpoint - Phase 2 Complete
  - Ensure all tests pass, ask the user if questions arise.

## Phase 3: Convention Fixes

- [ ] 9. Implement UnnecessaryPublicModifier fix with TDD
  - [ ] 9.1 Write property test for public modifier removal
    - **Property 7: Public Modifier Removal**
    - **Validates: Requirements 4.1**
    - _Requirements: 4.1_
  - [ ] 9.2 Write unit test for UnnecessaryPublicModifier handler
    - Test "public class Foo" becomes "class Foo"
    - Test "public void method()" becomes "void method()"
    - _Requirements: 4.1_
  - [ ] 9.3 Implement fixUnnecessaryPublicModifier handler
    - Register handler in FixHandlerRegistry
    - Handler removes "public " from declaration
    - _Requirements: 4.1_

- [ ] 10. Implement UnnecessaryDef fix with TDD
  - [ ] 10.1 Write property test for def keyword removal
    - **Property 8: Def Keyword Removal**
    - **Validates: Requirements 4.2**
    - _Requirements: 4.2_
  - [ ] 10.2 Write unit test for UnnecessaryDef handler
    - Test "def String x" becomes "String x"
    - _Requirements: 4.2_
  - [ ] 10.3 Implement fixUnnecessaryDef handler
    - Register handler in FixHandlerRegistry
    - Handler removes "def " when type is present
    - _Requirements: 4.2_

- [ ] 11. Implement UnnecessaryGetter fix with TDD
  - [ ] 11.1 Write property test for getter to property access
    - **Property 9: Getter to Property Access**
    - **Validates: Requirements 4.3**
    - _Requirements: 4.3_
  - [ ] 11.2 Write unit test for UnnecessaryGetter handler
    - Test "obj.getName()" becomes "obj.name"
    - Test "obj.getURL()" becomes "obj.URL" (preserve case for acronyms)
    - _Requirements: 4.3_
  - [ ] 11.3 Implement fixUnnecessaryGetter handler
    - Register handler in FixHandlerRegistry
    - Handler converts getter call to property access
    - _Requirements: 4.3_

- [ ] 12. Implement UnnecessarySetter fix with TDD
  - [ ] 12.1 Write property test for setter to property assignment
    - **Property 10: Setter to Property Assignment**
    - **Validates: Requirements 4.4**
    - _Requirements: 4.4_
  - [ ] 12.2 Write unit test for UnnecessarySetter handler
    - Test "obj.setName(value)" becomes "obj.name = value"
    - _Requirements: 4.4_
  - [ ] 12.3 Implement fixUnnecessarySetter handler
    - Register handler in FixHandlerRegistry
    - Handler converts setter call to property assignment
    - _Requirements: 4.4_

- [ ] 13. Implement UnnecessaryDotClass fix with TDD
  - [ ] 13.1 Write unit test for UnnecessaryDotClass handler
    - Test "String.class" becomes "String"
    - _Requirements: 4.5_
  - [ ] 13.2 Implement fixUnnecessaryDotClass handler
    - Register handler in FixHandlerRegistry
    - Handler removes ".class" suffix
    - _Requirements: 4.5_

- [ ] 14. Checkpoint - Phase 3 Complete
  - Ensure all tests pass, ask the user if questions arise.

## Phase 4: Safety, Validation, and Integration

- [ ] 15. Implement range validation with TDD
  - [ ] 15.1 Write property test for range bounds validation
    - **Property 11: Range Bounds Validation**
    - **Validates: Requirements 5.2, 5.3**
    - _Requirements: 5.2, 5.3_
  - [ ] 15.2 Write unit tests for edge cases
    - Test out-of-bounds line number returns null
    - Test invalid range returns null
    - _Requirements: 5.2, 5.3_
  - [ ] 15.3 Add range validation to LintFixAction
    - Validate line numbers before invoking handlers
    - Log warnings for invalid ranges
    - _Requirements: 5.1, 5.2, 5.3_

- [ ] 16. Implement CodeAction structure validation with TDD
  - [ ] 16.1 Write property test for CodeAction structure
    - **Property 12: Code Action Structure Validity**
    - **Validates: Requirements 6.1, 6.2, 6.3**
    - _Requirements: 6.1, 6.2, 6.3_
  - [ ] 16.2 Write property test for multiple diagnostics handling
    - **Property 13: Multiple Diagnostics Handling**
    - **Validates: Requirements 6.4**
    - _Requirements: 6.4_
  - [ ] 16.3 Write integration test with CodeActionProvider
    - Test end-to-end flow from diagnostic to code action
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 17. Final Checkpoint - All Tests Passing
  - Ensure all tests pass, ask the user if questions arise.
