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
	@echo ""
	@echo "Install Extension to Editors:"
	@echo "  ext-vscode    - Install to VS Code"
	@echo "  ext-cursor    - Install to Cursor"
	@echo "  ext-windsurf  - Install to Windsurf"
	@echo "  ext-agy       - Install to Antigravity"
	@echo "  ext-kiro      - Install to Kiro"
	@echo "  ext-editors   - Install to all available editors"


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

# Smart install: Runs pnpm install if package.json/lock has changed (root OR client) or node_modules is missing
# Note: postinstall in root package.json handles client deps via "cd client && pnpm install"
$(EXT_DIR)/node_modules: $(EXT_DIR)/package.json $(EXT_DIR)/pnpm-lock.yaml $(EXT_DIR)/client/package.json $(EXT_DIR)/client/pnpm-lock.yaml
	cd $(EXT_DIR) && pnpm install
	@touch $@

# Explicit clean install (force reinstall all deps)
ext-install:
	cd $(EXT_DIR) && pnpm install

ext-build: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm run compile

ext-test: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm test

ext-package: $(EXT_DIR)/node_modules
	cd $(EXT_DIR) && pnpm run package

# Install extension to editors (VS Code forks)
# Usage: make ext-vscode, make ext-cursor, make ext-agy, make ext-kiro, make ext-editors
# Note: EXT_VSIX uses = (not :=) for deferred evaluation after ext-package creates the file
EXT_VSIX = $(shell ls -t $(EXT_DIR)/gvy-*.vsix 2>/dev/null | head -1)

define VSIX_CHECK
	@if [ -z "$(EXT_VSIX)" ]; then echo "❌ No VSIX found. Run 'make ext-package' first."; exit 1; fi
endef

ext-vscode: ext-package
	$(VSIX_CHECK)
	code --install-extension $(EXT_VSIX) --force
	@echo "✅ Installed to VS Code"

ext-cursor: ext-package
	$(VSIX_CHECK)
	cursor --install-extension $(EXT_VSIX) --force
	@echo "✅ Installed to Cursor"

ext-agy: ext-package
	$(VSIX_CHECK)
	agy --install-extension $(EXT_VSIX) --force
	@echo "✅ Installed to Antigravity"

ext-kiro: ext-package
	$(VSIX_CHECK)
	kiro --install-extension $(EXT_VSIX) --force
	@echo "✅ Installed to Kiro"

ext-windsurf: ext-package
	$(VSIX_CHECK)
	@if command -v windsurf >/dev/null 2>&1; then \
		windsurf --install-extension $(EXT_VSIX) --force; \
	elif command -v surf >/dev/null 2>&1; then \
		surf --install-extension $(EXT_VSIX) --force; \
	else \
		echo "❌ Neither 'windsurf' nor 'surf' command found"; exit 1; \
	fi
	@echo "✅ Installed to Windsurf"

ext-editors: ext-package
	$(VSIX_CHECK)
	@echo "Installing $(EXT_VSIX) to all available editors..."
	@command -v code >/dev/null 2>&1 && code --install-extension $(EXT_VSIX) --force && echo "✅ VS Code" || echo "⏭️  VS Code not found"
	@command -v cursor >/dev/null 2>&1 && cursor --install-extension $(EXT_VSIX) --force && echo "✅ Cursor" || echo "⏭️  Cursor not found"
	@if command -v windsurf >/dev/null 2>&1; then \
		windsurf --install-extension $(EXT_VSIX) --force && echo "✅ Windsurf"; \
	elif command -v surf >/dev/null 2>&1; then \
		surf --install-extension $(EXT_VSIX) --force && echo "✅ Windsurf"; \
	else \
		echo "⏭️  Windsurf not found"; \
	fi
	@command -v agy >/dev/null 2>&1 && agy --install-extension $(EXT_VSIX) --force && echo "✅ Antigravity" || echo "⏭️  Antigravity not found"
	@command -v kiro >/dev/null 2>&1 && kiro --install-extension $(EXT_VSIX) --force && echo "✅ Kiro" || echo "⏭️  Kiro not found"

# Release
release: release-build

release-build:
	./tools/release.sh build --version v0.0.0-dryrun --dry-run
	@echo "Run 'make release-publish' to perform real build"

release-publish:
	./tools/release.sh build --version $(shell ./gradlew printVersion -q)
