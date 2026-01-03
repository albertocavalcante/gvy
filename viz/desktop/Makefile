# Makefile for Groovy AST Visualizer Desktop App

GRADLE_ARGS ?=

.PHONY: run build clean package dmg install

# Run the app locally
run:
	../../gradlew :viz:desktop:run $(GRADLE_ARGS)

# Build the app
build:
	../../gradlew :viz:desktop:build $(GRADLE_ARGS)

# Clean build artifacts
clean:
	../../gradlew :viz:desktop:clean $(GRADLE_ARGS)

# Package the app for the current OS
package:
	../../gradlew :viz:desktop:packageDistributionForCurrentOS $(GRADLE_ARGS)

# Create DMG installer (macOS only)
dmg:
	../../gradlew :viz:desktop:packageDmg $(GRADLE_ARGS)

# Install the app to /Applications (macOS)
# Assumes the app is built to the standard location
APP_NAME := Groovy AST Visualizer.app
BUILD_APP_PATH := build/compose/binaries/main/app/$(APP_NAME)
INSTALL_DIR := /Applications

install: package
	@if [ "$$(uname)" != "Darwin" ]; then \
		echo "❌ Install is only supported on macOS"; \
		exit 1; \
	fi
	@echo "Installing to $(INSTALL_DIR)... (may require sudo password)"
	@if [ -d "$(BUILD_APP_PATH)" ]; then \
		sudo rm -rf "$(INSTALL_DIR)/$(APP_NAME)" && \
		sudo cp -R "$(BUILD_APP_PATH)" "$(INSTALL_DIR)/" && \
		echo "✅ Installed '$(APP_NAME)' to $(INSTALL_DIR)"; \
	else \
		echo "❌ App bundle not found at $(BUILD_APP_PATH). Build might have failed."; \
		exit 1; \
	fi
