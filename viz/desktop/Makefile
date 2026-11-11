# Makefile for Groovy AST Visualizer Desktop App

GRADLE_ARGS ?=

.PHONY: run build clean package dmg app-image install test-install

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

# Build the application image needed for a local install.
app-image:
	../../gradlew :viz:desktop:createDistributable $(GRADLE_ARGS)

# Install the app to /Applications (macOS)
# The destination must exist and be writable by the current user.
# Override INSTALL_DIR to use a different directory.
APP_NAME := Groovy AST Visualizer.app
BUILD_APP_PATH := build/compose/binaries/main/app/$(APP_NAME)
INSTALL_DIR ?= /Applications
export BUILD_APP_PATH INSTALL_DIR

install: app-image
	@if [ "$$(uname)" != "Darwin" ]; then \
		echo "❌ Install is only supported on macOS"; \
		exit 1; \
	fi
	@sh ./install-app.sh "$$BUILD_APP_PATH" "$$INSTALL_DIR"

# Exercise installation safety without changing /Applications.
test-install:
	@sh ./tests/install-app-test.sh
