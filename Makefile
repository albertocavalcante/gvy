# Groovy LSP Makefile
# Quick development commands for common tasks

# Allow injecting Gradle arguments (e.g. make build GRADLE_ARGS="--info")
GRADLE_ARGS ?=

.PHONY: help build jar test clean lint format fix-imports quality run-stdio run-socket version retest rebuild e2e e2e-single

# Default target
help:
	@echo "Available targets:"
	@echo "  jar        - Build fat JAR without running tests (quick refresh)"
	@echo "  build      - Full build including tests"
	@echo "  rebuild    - Force a full rebuild including tests"
	@echo "  test       - Run all tests"
	@echo "  retest     - Force re-run of all tests"
	@echo "  clean      - Clean build artifacts"
	@echo "  lint       - Run code quality checks"
	@echo "  format     - Format source code and fix auto-correctable issues"
	@echo "  fix-imports - Auto-fix unused imports and other detekt auto-correctable issues"
	@echo "  quality    - Run all quality checks including coverage"
	@echo "  e2e        - Run end-to-end LSP scenarios"
	@echo "  e2e-single - Run a single E2E scenario (usage: make e2e-single SCENARIO=name)"
	@echo "  run-stdio  - Run server in stdio mode"
	@echo "  run-socket - Run server in socket mode (port 8080)"
	@echo "  version    - Show version information"

# Quick JAR build without tests (most common during development)
jar:
	./gradlew build -x test -x koverVerify -x detekt -x spotlessKotlinCheck -x spotlessMarkdownCheck $(GRADLE_ARGS)

# Full build with tests
build:
	./gradlew build $(GRADLE_ARGS)

# Force a full rebuild
rebuild:
	./gradlew build --rerun-tasks $(GRADLE_ARGS)

# Run tests only
test:
	./gradlew test $(GRADLE_ARGS)

# Force re-run of tests
retest:
	./gradlew test --rerun-tasks $(GRADLE_ARGS)

# Clean build artifacts
clean:
	./gradlew clean $(GRADLE_ARGS)

# Code quality
lint:
	./gradlew lint $(GRADLE_ARGS)

format:
	./gradlew lintFix $(GRADLE_ARGS)

# Auto-fix specific issues
fix-imports:
	./gradlew detektAutoCorrect --parallel $(GRADLE_ARGS)

quality:
	./gradlew quality $(GRADLE_ARGS)

e2e:
	GRADLE_USER_HOME=$(CURDIR)/.gradle ./gradlew --info --console=plain e2eTest $(GRADLE_ARGS)

e2e-single:
	GRADLE_USER_HOME=$(CURDIR)/.gradle ./gradlew --info --console=plain :tests:e2eTest -Dgroovy.lsp.e2e.filter="$(SCENARIO)" $(GRADLE_ARGS)

# Run the language server
run-stdio: jar
	java -jar build/libs/groovy-lsp-*-SNAPSHOT.jar

run-socket: jar
	java -jar build/libs/groovy-lsp-*-SNAPSHOT.jar socket 8080

# Version information
version:
	./gradlew printVersion $(GRADLE_ARGS)
