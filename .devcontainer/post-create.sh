#!/bin/bash
set -e

VSIX_PATH="/usr/local/share/vscode-extensions/kotlin.vsix"

echo "🔍 Checking for Kotlin VSIX at $VSIX_PATH..."

if [ ! -f "$VSIX_PATH" ]; then
    echo "❌ Error: VSIX file not found!"
    exit 1
fi

echo "🚀 Attempting to install Kotlin VSIX..."

# Function to try installing
install_vsix() {
    local cmd=$1
    if command -v "$cmd" &> /dev/null; then
        echo "👉 Found '$cmd', installing..."
        if "$cmd" --install-extension "$VSIX_PATH"; then
            echo "✅ Successfully installed Kotlin VSIX using '$cmd'."
            return 0
        else
            echo "⚠️  Failed to install using '$cmd'."
            return 1
        fi
    fi
    return 1
}

# Try standard 'code' (VS Code)
if install_vsix "code"; then exit 0; fi

# Try 'cursor' (Cursor IDE)
if install_vsix "cursor"; then exit 0; fi

# Try 'code-server' (Web version)
if install_vsix "code-server"; then exit 0; fi

echo "❌ Could not find a compatible IDE binary (code/cursor) to install the extension."
echo "ℹ️  Please install it manually: code --install-extension $VSIX_PATH"
exit 1

