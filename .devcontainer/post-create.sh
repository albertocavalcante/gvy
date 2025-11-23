#!/bin/bash
set -e

# -----------------------------------------------------------------------------
# Environment Verification
# -----------------------------------------------------------------------------
echo "Verifying Environment Tools..."
echo "  - Java:    $(java -version 2>&1 | head -n 1)"
echo "  - Ripgrep: $(rg --version | head -n 1)"
echo "  - fd:      $(fd --version | head -n 1)"
echo "  - bat:     $(bat --version | head -n 1)"
echo "  - jq:      $(jq --version)"
echo "  - gh:      $(gh --version | head -n 1 2>/dev/null || echo 'Not found')"
echo "--------------------------------------------------"

# -----------------------------------------------------------------------------
# Starship Configuration
# -----------------------------------------------------------------------------
if command -v starship &> /dev/null; then
    echo "Configuring Starship..."
    # Configure shells if not already configured
    if ! grep -q "starship init zsh" ~/.zshrc 2>/dev/null; then
        echo 'eval "$(starship init zsh)"' >> ~/.zshrc
    fi
    if ! grep -q "starship init bash" ~/.bashrc 2>/dev/null; then
        echo 'eval "$(starship init bash)"' >> ~/.bashrc
    fi
    echo "Starship configured."
else
    echo "Warning: Starship not found. Shell prompt will be default."
fi

# -----------------------------------------------------------------------------
# Kotlin VSIX Installation
# -----------------------------------------------------------------------------
VSIX_PATH="/usr/local/share/vscode-extensions/kotlin.vsix"

echo "Checking for Kotlin VSIX at $VSIX_PATH..."

if [ ! -f "$VSIX_PATH" ]; then
    echo "Error: VSIX file not found!"
    exit 1
fi

echo "Attempting to install Kotlin VSIX..."

# Function to try installing
install_vsix() {
    local cmd=$1
    if command -v "$cmd" &> /dev/null; then
        echo "Found '$cmd', installing..."
        
        # Retry loop for VS Code Server initialization
        local retries=5
        local count=0
        while [ $count -lt $retries ]; do
            if "$cmd" --install-extension "$VSIX_PATH"; then
                echo "Successfully installed Kotlin VSIX using '$cmd'."
                return 0
            else
                echo "Install attempt failed. Retrying in 5 seconds..."
                sleep 5
                count=$((count + 1))
            fi
        done
        echo "Failed to install using '$cmd' after $retries attempts."
        return 1
    fi
    return 1
}

# Try standard 'code' (VS Code)
if install_vsix "code"; then exit 0; fi

# Try 'cursor' (Cursor IDE)
if install_vsix "cursor"; then exit 0; fi

# Try 'code-server' (Web version)
if install_vsix "code-server"; then exit 0; fi

echo "Could not find a compatible IDE binary (code/cursor) to install the extension."
echo "Please install it manually: code --install-extension $VSIX_PATH"
exit 1
