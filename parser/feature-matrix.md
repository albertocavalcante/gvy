# Parser Feature Matrix

This matrix summarizes the documented feature coverage of the two parser modules. It is not an exhaustive parity
guarantee; items marked "not documented" need verification before relying on them for API or product decisions.

Legend: yes, no, partial, not documented

| Feature                                 | parser/native                | parser/core                        | Notes                                                                                 |
| --------------------------------------- | ---------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------- |
| AST model                               | yes (Groovy compiler AST)    | yes (custom AST)                   | `parser/README.md`, `kb/ARCHITECTURE.md`                                              |
| Error recovery parsing                  | yes                          | not documented                     | `parser/README.md`, `kb/ARCHITECTURE.md`                                              |
| Parent/child tracking                   | yes                          | not documented                     | `parser/README.md`, `kb/ARCHITECTURE.md`                                              |
| Position-based node lookup              | yes                          | partial (positions only)           | `parser/README.md`                                                                    |
| Comment preservation                    | not documented               | yes                                | `parser/README.md`                                                                    |
| Visitor APIs                            | custom recursive visitor     | yes (VoidVisitor/GroovyVisitor)    | `parser/README.md`                                                                    |
| Symbol resolution                       | partial (local symbol table) | yes (symbol resolver, type solver) | `parser/README.md`, `kb/ARCHITECTURE.md`                                              |
| Jenkins CPS analysis                    | not documented               | yes                                | `parser/README.md`                                                                    |
| Groovy macro support                    | yes                          | not documented                     | `parser/README.md`                                                                    |
| Multi-version Groovy support            | not documented               | yes (2.4–5.x)                      | `parser/README.md`                                                                    |
| Token classification (comments/strings) | yes (GroovyTokenIndex)       | not documented                     | `parser/native/src/main/kotlin/com/github/albertocavalcante/nativeapi/ParseResult.kt` |
| LSP internal usage                      | yes                          | not documented                     | `parser/README.md`                                                                    |
| External/public API focus               | no                           | yes                                | `parser/README.md`                                                                    |

If you rely on a "not documented" or "partial" row, confirm the capability in code and update this matrix with a
concrete reference.
