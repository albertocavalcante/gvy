# Contributing to Groovy Language Server

Thanks for your interest in contributing!

## How to Contribute

1. **Fork the repository**
2. **Create a feature branch** (`git checkout -b feature/amazing-feature`)
3. **Make your changes**
4. **Run tests** (`./gradlew test`)
5. **Commit your changes** (`git commit -m 'Add amazing feature'`)
6. **Push to your branch** (`git push origin feature/amazing-feature`)
7. **Open a Pull Request**

## Development Setup

### Requirements

- JDK 17 or higher
- Kotlin 2.0+

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

The root `test` task includes JVM unit tests and KMP JVM tests. Run the protocol suite with `./gradlew :tests:e2eTest`.

Tests use Java 17 by default, so Gradle needs a Java 17 toolchain. It can use a locally installed JDK or download one
through the configured Foojay resolver when provisioning is available. If provisioning is unavailable, install JDK 17
locally.

If only JDK 21 is available, select it explicitly with the following command. This also provides an additional
compatibility check; it does not validate the minimum Java 17 runtime:

```bash
./gradlew test -PtestJavaVersion=21
```

Changing the test runtime leaves compilation toolchains and the Java 17 bytecode target unchanged.

Run `./gradlew lint` for Detekt, Spotless and the formatter target regression. To check only target selection, run
`./gradlew verifyKotlinFormattingTargets`. It checks tracked source layouts and briefly creates generated Kotlin probes
under each selected project's build directory, then removes them. Existing probe files cause the check to fail.

## Pull Request Guidelines

- Include tests for new functionality
- Update documentation as needed
- Follow existing code style
- Keep commits focused and atomic
- Write clear commit messages

## Reporting Issues

Please use GitHub Issues to report bugs or request features. Include:

- Clear description
- Steps to reproduce (for bugs)
- Expected behavior
- Actual behavior
- Environment details (OS, Java version, etc.)

## Questions?

Feel free to open an issue for discussion or questions about contributing.

## Dependency bugs and upstream contributions

Every dependency can have bugs, including OpenRewrite, `rules_kotlin`, and the Groovy compiler. Using the compiler as
the language authority does not make its implementation infallible. Investigate failures in both gvy and its
dependencies.

Track suspected and confirmed upstream bugs in [gvy's issue board](https://github.com/albertocavalcante/gvy/issues),
including evidence, affected versions, local mitigations, and follow-up ownership. A local workaround does not resolve
the upstream defect.

Prepare minimal repros and useful patches as part of being a good upstream contributor. Agents must keep submissions
local for human review until a maintainer explicitly approves the specific material and target. Check upstream's
contribution and AI policies before submitting issues, pull requests, or comments. This avoids sending unverified or
low-quality AI-generated material to maintainers.

See the [dependency bug policy](docs/dependency-bugs.md) for evidence, tracking, and submission requirements.
