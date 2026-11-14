# Edit Spock tests

**Prerequisites:** install gvy and open a project that supplies Groovy and Spock dependencies. For example, in an
existing Spock project open `src/test/groovy/ExampleSpec.groovy`:

```groovy
import spock.lang.Specification

class ExampleSpec extends Specification {
    def 'addition works'() {
        expect:
        1 + 1 == 2
    }
}
```

The editor should recognize the Groovy file; project dependency resolution supplies context for Spock types. Run the
project's normal test task to verify the test. Detection and test lenses depend on the server version and project
configuration, and editor analysis is not a substitute for executing tests. If `Specification` is unresolved, check
[Gradle](gradle.md) and [troubleshooting](troubleshooting.md).
