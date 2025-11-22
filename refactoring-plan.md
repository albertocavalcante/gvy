# Groovy Parser Refactoring Plan

## Investigation Summary

The initial request was to evaluate the effectiveness of the current handcrafted parser and consider alternatives like
ANTLR, tree-sitter, or other existing libraries.

An investigation of the codebase revealed that the project is **not** building a parser from scratch. Instead, the
`groovy-parser` module is a well-designed facade around the official, battle-tested Groovy compiler
(`org.codehaus.groovy.control.CompilationUnit`).

This is a sound and effective strategy because:

- It guarantees 100% compatibility with the Groovy language.
- It leverages the performance and robustness of the official compiler.
- It avoids the massive, unnecessary effort of building and maintaining a complex parser from scratch.

**Conclusion:** No course correction is needed. The current approach of wrapping the official Groovy compiler is the
best path forward.

## Refactoring Plan

The refactoring to spin off the `groovy-parser` module is already in progress. The `GroovyCompilationService` in the
main `groovy-lsp` module already uses the `GroovyParserFacade`.

The next step is to complete the separation by moving the remaining parser-related logic from the `groovy-lsp` module to
the `groovy-parser` module.

The plan is to:

1.  Move the AST analysis and symbol table generation logic to the `groovy-parser` module.
2.  Enhance `GroovyParserFacade` to perform this analysis and return the results (including the symbol table).
3.  Refactor `GroovyCompilationService` to be a pure consumer of the `groovy-parser` module, removing all direct AST
    manipulation and caching.

This will result in a clean separation of concerns, with `groovy-parser` being a self-contained, LSP-agnostic Groovy
parsing and analysis library, and `groovy-lsp` being the consumer that bridges the gap to the Language Server Protocol.

## TODO List

- [ ] Move AST analysis and Symbol Table generation from `groovy-lsp` to `groovy-parser` module
- [ ] Update `GroovyParserFacade` to return the symbol table
- [ ] Refactor `GroovyCompilationService` to use the updated `GroovyParserFacade`
- [ ] Remove `CompilationCache` and direct `CompilationUnit` dependencies from `groovy-lsp`
- [ ] Build the project and run tests to ensure everything works as expected
