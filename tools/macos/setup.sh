#!/bin/bash
set -e

echo "🔧 Setting up macOS Development Environment with SDKMAN..."

# Function to check if a command exists
command_exists() {
    type "$1" &> /dev/null
}

if command_exists sdk; then
    echo "✅ SDKMAN already available in PATH"
else
    # Try to find SDKMAN in standard locations and source it
    SDKMAN_INIT_SCRIPT=""
    if [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        SDKMAN_INIT_SCRIPT="$HOME/.sdkman/bin/sdkman-init.sh"
        export SDKMAN_DIR="$HOME/.sdkman"
    elif command_exists brew && brew list sdkman-cli >/dev/null 2>&1 && [ -s "$(brew --prefix sdkman-cli)/libexec/bin/sdkman-init.sh" ]; then
        SDKMAN_INIT_SCRIPT="$(brew --prefix sdkman-cli)/libexec/bin/sdkman-init.sh"
        export SDKMAN_DIR="$(brew --prefix sdkman-cli)/libexec"
    fi

    if [ -n "$SDKMAN_INIT_SCRIPT" ]; then
        echo "✅ Found SDKMAN installation. Sourcing for this script..."
        source "$SDKMAN_INIT_SCRIPT"
    else
        echo "📦 SDKMAN not found. Installing via Homebrew..."
        if ! command_exists brew; then
            echo "❌ Homebrew is not installed. Please install it first."
            exit 1
        fi
        brew tap sdkman/tap
        brew install sdkman-cli
        
        export SDKMAN_DIR="$(brew --prefix sdkman-cli)/libexec"
        if [ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]; then
            source "$SDKMAN_DIR/bin/sdkman-init.sh"
        else
            echo "❌ Failed to source SDKMAN after installation."
            exit 1
        fi
    fi
fi

# Install Java version from .sdkmanrc
if [ -f ".sdkmanrc" ]; then
    echo "📦 Installing SDKs from .sdkmanrc..."
    sdk env install
else
    echo "❌ Error: .sdkmanrc not found! This project requires an .sdkmanrc file to define the Java version."
    exit 1
fi

# Install direnv if missing
if ! command_exists direnv; then
    echo "📦 Installing direnv..."
    brew install direnv
else
    echo "✅ direnv already installed"
fi

echo ""
echo "🎉 Setup complete!"
echo ""
echo "⚠️  NOTE: The 'sdk' command is not yet available in your current shell."
echo ""
echo "👉 OPTION 1: To use 'sdk' immediately, run this command:"
echo "   export SDKMAN_DIR=\$(brew --prefix sdkman-cli)/libexec && source \"\$SDKMAN_DIR/bin/sdkman-init.sh\""
echo ""
echo "👉 OPTION 2: To enable automatic JAVA_HOME switching (Recommended):"
echo "   direnv allow"
echo ""
echo "   (If direnv doesn't work, ensure you have hooked it into your shell: https://direnv.net/docs/hook.html)"
