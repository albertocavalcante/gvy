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
