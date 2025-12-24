#!/bin/bash
set -e

echo "🔧 Setting up macOS Development Environment..."

# --- 1. Detect and/or Install SDKMAN ---

SDKMAN_DIR=""
SDKMAN_INIT=""

# Check standard location
if [ -d "$HOME/.sdkman" ]; then
    SDKMAN_DIR="$HOME/.sdkman"
    SDKMAN_INIT="$SDKMAN_DIR/bin/sdkman-init.sh"
# Check Homebrew location
elif command -v brew >/dev/null && brew list sdkman-cli >/dev/null 2>&1; then
    SDKMAN_PREFIX="$(brew --prefix sdkman-cli)"
    SDKMAN_DIR="$SDKMAN_PREFIX/libexec"
    SDKMAN_INIT="$SDKMAN_DIR/bin/sdkman-init.sh"
fi

# Install if missing
if [ -z "$SDKMAN_INIT" ] || [ ! -f "$SDKMAN_INIT" ]; then
    echo "📦 SDKMAN not found. Installing via Homebrew..."
    if ! command -v brew >/dev/null; then
        echo "❌ Homebrew is required but not installed."
        exit 1
    fi
    brew install sdkman-cli
    SDKMAN_PREFIX="$(brew --prefix sdkman-cli)"
    SDKMAN_DIR="$SDKMAN_PREFIX/libexec"
    SDKMAN_INIT="$SDKMAN_DIR/bin/sdkman-init.sh"
fi

echo "✅ SDKMAN located at $SDKMAN_DIR"

# Source SDKMAN for this script execution
# We need to temporarily disable the 'set -e' because sdkman-init might return non-zero in some edge cases or internal logic
set +e
source "$SDKMAN_INIT"
set -e

# --- 2. Install Java Version ---

if [ -f ".sdkmanrc" ]; then
    echo "📦 Ensuring Java version from .sdkmanrc is installed..."
    sdk env install
else
    echo "⚠️  No .sdkmanrc found. Skipping Java installation."
fi

# --- 3. Shell Configuration (Idempotent) ---

SHELL_RC=""
case "$SHELL" in
    */zsh) SHELL_RC="$HOME/.zshrc" ;;
    */bash) SHELL_RC="$HOME/.bashrc" ;;
    *) echo "⚠️  Unknown shell: $SHELL. Please manually configure SDKMAN." ;;
esac

if [ -n "$SHELL_RC" ]; then
    # Check if SDKMAN init is sourced in RC file
    if ! grep -q "sdkman-init.sh" "$SHELL_RC"; then
        echo "🔧 Adding SDKMAN to $SHELL_RC..."
        echo "" >> "$SHELL_RC"
        echo "# SDKMAN" >> "$SHELL_RC"
        echo "export SDKMAN_DIR=\"$SDKMAN_DIR\"" >> "$SHELL_RC"
        echo "[[ -s \"$SDKMAN_DIR/bin/sdkman-init.sh\" ]] && source \"$SDKMAN_DIR/bin/sdkman-init.sh\"" >> "$SHELL_RC"
        echo "✅ Added SDKMAN to $SHELL_RC"
    else
        echo "✅ SDKMAN already configured in $SHELL_RC"
    fi
fi

# --- 4. Direnv Setup ---

if ! command -v direnv >/dev/null; then
    echo "📦 Installing direnv..."
    brew install direnv
fi

DIRENV_HOOKED=false
if [ -n "$SHELL_RC" ]; then
    if ! grep -q "direnv hook" "$SHELL_RC"; then
        echo "🔧 Adding direnv hook to $SHELL_RC..."
        echo "" >> "$SHELL_RC"
        echo "# direnv" >> "$SHELL_RC"
        if [[ "$SHELL" == */zsh ]]; then
            echo 'eval "$(direnv hook zsh)"' >> "$SHELL_RC"
        elif [[ "$SHELL" == */bash ]]; then
             echo 'eval "$(direnv hook bash)"' >> "$SHELL_RC"
        fi
        echo "✅ Added direnv hook to $SHELL_RC"
        DIRENV_HOOKED=true
    else
        echo "✅ direnv already configured in $SHELL_RC"
    fi
fi

# Allow .envrc if it exists
if [ -f ".envrc" ]; then
    if command -v direnv >/dev/null; then
        echo "🔓 Allowing .envrc..."
        direnv allow
    fi
fi

echo ""
echo "🎉 Setup complete!"
echo ""

if [ "$DIRENV_HOOKED" = true ]; then
    echo "🚨 IMPORTANT: Direnv hook was just added."
    echo "   You MUST restart your terminal or run the following to activate it:"
    if [[ "$SHELL" == */zsh ]]; then
        echo "   eval \"\$(direnv hook zsh)\""
    elif [[ "$SHELL" == */bash ]]; then
        echo "   eval \"\$(direnv hook bash)\""
    fi
    echo ""
fi

echo "👉 Please restart your terminal or run: source $SHELL_RC"
