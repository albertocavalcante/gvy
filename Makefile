# Groovy LSP Makefile
# Quick development commands for common tasks

.PHONY: help build jar test clean lint format fix-imports quality run-stdio run-socket version

# Default target
help:
	@echo "Available targets:"
	@echo "  jar        - Build fat JAR without running tests (quick refresh)"
	@echo "  build      - Full build including tests"
	@echo "  test       - Run all tests"
	@echo "  clean      - Clean build artifacts"
	@echo "  lint       - Run code quality checks"
	@echo "  format     - Format source code and fix auto-correctable issues"
	@echo "  fix-imports - Auto-fix unused imports and other detekt auto-correctable issues"
	@echo "  quality    - Run all quality checks including coverage"
	@echo "  run-stdio  - Run server in stdio mode"
	@echo "  run-socket - Run server in socket mode (port 8080)"
	@echo "  version    - Show version information"

# Quick JAR build without tests (most common during development)
jar:
	./gradlew build -x test -x koverVerify -x detekt -x spotlessKotlinCheck -x spotlessMarkdownCheck

# Full build with tests
build:
	./gradlew build

# Run tests only
test:
	./gradlew test

# Clean build artifacts
clean:
	./gradlew clean

# Code quality
lint:
	./gradlew lint

format:
	./gradlew lintFix

# Auto-fix specific issues
fix-imports:
	./gradlew detektAutoCorrect --parallel

quality:
	./gradlew quality

# Run the language server
run-stdio: jar
	java -jar build/libs/groovy-lsp-*-SNAPSHOT.jar

run-socket: jar
	java -jar build/libs/groovy-lsp-*-SNAPSHOT.jar socket 8080

# Version information
version:
	./gradlew printVersion