# Groovy LSP Makefile
# Quick development commands for common tasks

# Allow injecting Gradle arguments (e.g. make build GRADLE_ARGS="--info")
GRADLE_ARGS ?=
export CARGO_BUILD_JOBS ?= 2

# macOS: Check for JAVA_HOME, trust environment (direnv/sdkman)
ifeq ($(shell uname),Darwin)
    ifndef JAVA_HOME
        $(warning JAVA_HOME is not set!)
        $(warning Run 'sdk env' or 'direnv allow' to activate the environment.)
        $(warning If you haven't set up the tools yet, run: ./tools/macos/setup.sh)
    endif
endif

.PHONY: help build jar test clean lint format fix-imports quality run-stdio run-socket version retest rebuild harness-test e2e e2e-single ext-install ext-build ext-test ext-package ext-latest ext-rollback ext-latest-test

.PHONY: zed-check zed-test zed-build zed-format

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
	@echo "  harness-test - Run focused harness controls without fat JAR/distribution assembly"
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
	@echo "  ext-latest  - Install the newest green origin/main build (DRY_RUN=1 to preview)"
	@echo "  ext-rollback - Reinstall the previous kept VSIX"
	@echo ""
	@echo "Zed adapter (editors/zed/):"
	@echo "  zed-check  - Check Rust formatting, lint and host tests"
	@echo "  zed-test   - Run adapter host tests"
	@echo "  zed-build  - Build the pinned WASM adapter"
	@echo "  zed-format - Format adapter Rust source"
	@echo ""
	@echo "Install Extension to Editors:"
	@echo "  ext-vscode    - Install to VS Code"
	@echo "  ext-cursor    - Install to Cursor"
	@echo "  ext-windsurf  - Install to Windsurf"
	@echo "  ext-agy       - Install to Antigravity"
	@echo "  ext-kiro      - Install to Kiro"
	@echo "  ext-editors   - Install to all available editors"
	@echo ""
	@echo "Viz Desktop App (viz/desktop/):"
	@echo "  viz-run       - Run the desktop app"
	@echo "  viz-install   - Install the desktop app to /Applications (macOS)"
	@echo ""
	@echo "Bazel (experimental dual build system):"
	@echo "  bazel-build   - Build all Bazel targets"
	@echo "  bazel-test    - Run all Bazel tests"
	@echo "  bazel-jar     - Build the fat JAR with Bazel"
	@echo "  bazel-lint    - Run linting with Bazel"
	@echo "  bazel-sync    - Update Maven dependencies lock file"
	@echo "  bazel-clean   - Clean Bazel outputs (expunge)"
	@echo "  verify-builds - Compare Gradle and Bazel outputs"



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
	./gradlew lint --rerun-tasks $(GRADLE_ARGS)

format:
	./gradlew lintFix $(GRADLE_ARGS)
	dprint fmt

# Auto-fix specific issues
fix-imports:
	./gradlew detektAutoCorrect --parallel $(GRADLE_ARGS)

quality:
	./gradlew quality $(GRADLE_ARGS)

harness-test:
	./gradlew :tests:harnessTest --console=plain $(GRADLE_ARGS)

e2e:
	./gradlew --info --console=plain e2eTest $(GRADLE_ARGS)

e2e-single:
	./gradlew --info --console=plain :tests:e2eTest -Dgroovy.lsp.e2e.filter="$(SCENARIO)" $(GRADLE_ARGS)

# Run the language server
run-stdio: jar
	java -jar build/libs/gls-*-all.jar

run-socket: jar
	java -jar build/libs/gls-*-all.jar socket 8080

# Version information
version:
	./gradlew printVersion $(GRADLE_ARGS)

# VS Code Extension (editors/code/)
EXT_DIR := editors/code

# Run inside the extension so rustup selects its scoped rust-toolchain.toml.
zed-check:
	cd editors/zed && cargo fmt --all -- --check
	cd editors/zed && cargo clippy --locked --all-targets -- -D warnings
	cd editors/zed && cargo clippy --locked --target wasm32-wasip2 -- -D warnings
	$(MAKE) zed-test

zed-test:
	cd editors/zed && cargo test --locked

zed-build:
	cd editors/zed && cargo build --locked --release --target wasm32-wasip2

zed-format:
	cd editors/zed && cargo fmt --all

# Smart install: the root workspace install includes client dependencies.
$(EXT_DIR)/node_modules: $(EXT_DIR)/package.json $(EXT_DIR)/pnpm-lock.yaml $(EXT_DIR)/pnpm-workspace.yaml $(EXT_DIR)/client/package.json
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

# Install the newest origin/main build whose CI is green (#1576).
# Builds in a dedicated cache clone, keeps the last VSIX files for rollback.
# Usage: make ext-latest [DRY_RUN=1] [FORCE=1] [EXT_EDITORS=code,cursor]; make ext-rollback
# Knobs and details: editors/code/README.md ("Run the latest green main").
ext-latest:
	DRY_RUN=$(DRY_RUN) FORCE=$(FORCE) tools/ext-latest.sh latest

ext-rollback:
	DRY_RUN=$(DRY_RUN) tools/ext-latest.sh rollback $(ROLLBACK_SHA)

ext-latest-test:
	bash tools/ext-latest_test.sh

# Viz Desktop App
viz-run:
	$(MAKE) -C viz/desktop run

viz-install:
	$(MAKE) -C viz/desktop install


# Release
.PHONY: release release-build release-publish

release: release-build

release-build:
	./tools/release.sh build --version "$(if $(strip $(RELEASE_VERSION)),$(RELEASE_VERSION),v0.0.0-dryrun)" --dry-run
	@echo "Run 'make release-publish RELEASE_VERSION=vX.Y.Z' to build release artifacts"

release-publish:
	$(if $(strip $(RELEASE_VERSION)),,$(error Set RELEASE_VERSION=vX.Y.Z, for example make release-publish RELEASE_VERSION=v0.5.4))
	./tools/release.sh build --version "$(RELEASE_VERSION)"

# =============================================================================
# BAZEL TARGETS (experimental, dual build system)
# =============================================================================
.PHONY: bazel-build bazel-test bazel-clean bazel-lint bazel-jar bazel-sync

bazel-build:
	bazel build //...

bazel-test:
	bazel test //...

bazel-lint:
	bazel build --config=lint //...

bazel-clean:
	bazel clean --expunge

bazel-jar:
	bazel build //groovy-lsp:gls_deploy_deploy.jar

bazel-sync:
	bazel run @maven//:pin

# Verify both build systems produce equivalent output
.PHONY: verify-builds
verify-builds: jar bazel-jar
	@echo "Comparing Gradle and Bazel outputs..."
	@echo "Gradle JAR: $$(ls -la build/libs/*.jar 2>/dev/null || echo 'not found')"
	@echo "Bazel JAR: $$(ls -la bazel-bin/groovy-lsp/*.jar 2>/dev/null || echo 'not found')"

# Explicit source installation: build and verify both components from this checkout.
.PHONY: ext-vscode-source ext-package-source ext-doctor
ext-vscode-source:
	node editors/code/tools/install-extension.js --from-source --installer code

ext-package-source:
	node editors/code/tools/install-extension.js --from-source --package-only

ext-doctor:
	node editors/code/tools/install-extension.js --doctor
