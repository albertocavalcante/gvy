# Groovy LSP Makefile
# Quick development commands for common tasks

# Allow injecting Gradle arguments (e.g. make build GRADLE_ARGS="--info")
GRADLE_ARGS ?=

# macOS: Check for JAVA_HOME, trust environment (direnv/sdkman)
ifeq ($(shell uname),Darwin)
    ifndef JAVA_HOME
        $(warning JAVA_HOME is not set!)
        $(warning Run 'sdk env' or 'direnv allow' to activate the environment.)
        $(warning If you haven't set up the tools yet, run: ./tools/macos/setup.sh)
    endif
endif

.PHONY: help build jar test clean lint format fix-imports quality run-stdio run-socket version retest rebuild e2e e2e-single ext-install ext-build ext-test ext-package

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
	@echo ""
	@echo "VS Code Extension (editors/code/):"
	@echo "  ext-install - Install extension dependencies"
	@echo "  ext-build   - Build the extension"
	@echo "  ext-test    - Run extension tests"
	@echo "  ext-package - Package the extension (.vsix)"


# Quick JAR build without tests (most common during development)
jar:
	./gradlew build -x test -x koverVerify -x detekt -x spotlessCheck $(GRADLE_ARGS)

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

# VS Code Extension (editors/code/)
EXT_DIR := editors/code

# Smart install: Only runs pnpm install if package.json/lock has changed or node_modules is missing
$(EXT_DIR)/node_modules: $(EXT_DIR)/package.json $(EXT_DIR)/pnpm-lock.yaml
	cd $(EXT_DIR) && pnpm install
	@touch $@

# Explicit clean install
ext-install:
	cd $(EXT_DIR) && pnpm install --frozen-lockfile

ext-build: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm run compile

ext-test: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm test

ext-package: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm run package

# Release
release: release-build

release-build:
	./tools/release.sh build --version v0.0.0-dryrun --dry-run
	@echo "Run 'make release-publish' to perform real build"

release-publish:
	./tools/release.sh build --version $(shell ./gradlew printVersion -q)
