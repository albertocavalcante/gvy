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
install: package
	@echo "Installing to /Applications..."
	@if [ -d "build/compose/binaries/main/app/Groovy AST Visualizer.app" ]; then \
		rm -rf "/Applications/Groovy AST Visualizer.app"; \
		cp -R "build/compose/binaries/main/app/Groovy AST Visualizer.app" /Applications/; \
		echo "✅ Installed 'Groovy AST Visualizer' to /Applications"; \
	else \
		echo "❌ App bundle not found. Build might have failed."; \
		exit 1; \
	fi
