# dsl/

DSL descriptor format parsers and executors.

## Modules

- `gdsl/` - IntelliJ GDSL (Groovy DSL Descriptor) format support
- `dsld/` - Eclipse DSLD (DSL Descriptor) format support (planned)

## Background

Different IDEs use different formats for describing custom DSLs:

| Format | IDE                      | Documentation                                                                        |
| ------ | ------------------------ | ------------------------------------------------------------------------------------ |
| GDSL   | IntelliJ IDEA            | [JetBrains Docs](https://www.jetbrains.com/help/idea/gdsl-reference.html)            |
| DSLD   | Eclipse (Groovy-Eclipse) | [Groovy-Eclipse Wiki](https://github.com/groovy/groovy-eclipse/wiki/DSL-Descriptors) |

## Examples

Jenkins provides both formats:

- [IntelliJ.gdsl](https://github.com/jenkinsci/jenkins/blob/master/core/src/main/resources/dsld/IntelliJ.gdsl)
- [eclipse.dsld](https://github.com/jenkinsci/jenkins/blob/master/core/src/main/resources/dsld/eclipse.dsld)
