#!/bin/bash
set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

DEVCONTAINER_JSON=".devcontainer/devcontainer.json"

if [ ! -f "$DEVCONTAINER_JSON" ]; then
    echo -e "${RED}Error: $DEVCONTAINER_JSON not found.${NC}"
    exit 1
fi

echo -e "${BLUE}Checking for tool updates...${NC}"

# Helper to get current version from devcontainer.json
get_current_version() {
    local key=$1
    grep "\"$key\"" "$DEVCONTAINER_JSON" | cut -d: -f2 | tr -d ' ",'
}

# Helper to get latest version from GitHub
get_latest_version() {
    local repo=$1
    local tag=$(gh release list --repo "$repo" --limit 1 | cut -f3)
    # Remove 'v' prefix or 'jq-' prefix if present
    echo "$tag" | sed 's/^v//' | sed 's/^jq-//'
}

# Helper to fetch or calculate SHA256
get_sha256() {
    local repo=$1
    local version=$2
    local asset_name=$3
    local checksum_file=$4 # Optional: name of checksum file in release

    local url="https://github.com/$repo/releases/download"
    
    # Construct full URL based on repo conventions
    if [[ "$repo" == "jqlang/jq" ]]; then
         # jq has a different URL structure
         url="$url/jq-$version/$asset_name"
    elif [[ "$repo" == "junegunn/fzf" ]]; then
         url="$url/v$version/$asset_name"
    else
         # Standard "vX.Y.Z" tag for most
         url="$url/v$version/$asset_name"
         # ripgrep uses raw version number in tag for some reason? No, usually vX.Y.Z but let's check.
         if [[ "$repo" == "BurntSushi/ripgrep" ]]; then
             url="https://github.com/$repo/releases/download/$version/$asset_name"
         fi
    fi

    # Strategy:
    # 1. If checksum_file provided, try to fetch it and grep for asset
    # 2. If that fails or not provided, download asset and calc sha256
    
    if [[ -n "$checksum_file" ]]; then
        local checksum_url
        if [[ "$repo" == "jqlang/jq" ]]; then
            checksum_url="https://github.com/$repo/releases/download/jq-$version/$checksum_file"
        elif [[ "$repo" == "junegunn/fzf" ]]; then
             checksum_url="https://github.com/$repo/releases/download/v$version/$checksum_file"
        else
             checksum_url="https://github.com/$repo/releases/download/v$version/$checksum_file"
        fi
        
        local checksum_content
        checksum_content=$(curl -sL "$checksum_url")
        
        if [[ $? -eq 0 && -n "$checksum_content" ]]; then
             local hash=$(echo "$checksum_content" | grep "$asset_name" | awk '{print $1}')
             if [[ -n "$hash" ]]; then
                 echo "$hash"
                 return
             fi
        fi
    fi

    # Fallback: Download and calc
    # echo -e "${YELLOW}Downloading $asset_name to calculate SHA...${NC}" >&2
    curl -sL "$url" | sha256sum | awk '{print $1}'
}

check_tool() {
    local name=$1
    local key=$2
    local repo=$3
    local checksum_file=$4 # Optional
    
    # Asset templates for x64 and arm64
    local asset_x64=$5
    local asset_arm64=$6

    local current=$(get_current_version "$key")
    local latest=$(get_latest_version "$repo")
    
    if [ "$current" == "$latest" ]; then
        echo -e "${GREEN}✔ $name is up to date: $current${NC}"
    else
        echo -e "${YELLOW}➜ $name update available: $current -> $latest${NC}"
        echo -e "${BLUE}Calculating new checksums for $name $latest...${NC}"
        
        # Interpolate version into asset names
        local ax64=${asset_x64//VERSION/$latest}
        local aarm64=${asset_arm64//VERSION/$latest}

        local sha_x64=$(get_sha256 "$repo" "$latest" "$ax64" "$checksum_file")
        local sha_arm64=$(get_sha256 "$repo" "$latest" "$aarm64" "$checksum_file")

        echo "  \"${key}\": \"$latest\","
        echo "  \"${key/VERSION/SHA256}_X64\": \"$sha_x64\","
        echo "  \"${key/VERSION/SHA256}_ARM64\": \"$sha_arm64\","
    fi
}

# Check Tools
# Args: Name, ConfigKey, Repo, ChecksumFile, AssetTemplateX64, AssetTemplateARM64

# JQ
check_tool "jq" "JQ_VERSION" "jqlang/jq" "sha256sum.txt" \
    "jq-linux-amd64" \
    "jq-linux-arm64"

# Ripgrep
# Note: aarch64 uses -gnu suffix instead of -musl
check_tool "ripgrep" "RG_VERSION" "BurntSushi/ripgrep" "" \
    "ripgrep-VERSION-x86_64-unknown-linux-musl.tar.gz" \
    "ripgrep-VERSION-aarch64-unknown-linux-gnu.tar.gz"

# FD
check_tool "fd" "FD_VERSION" "sharkdp/fd" "" \
    "fd-vVERSION-x86_64-unknown-linux-musl.tar.gz" \
    "fd-vVERSION-aarch64-unknown-linux-gnu.tar.gz"

# Bat
check_tool "bat" "BAT_VERSION" "sharkdp/bat" "" \
    "bat-vVERSION-x86_64-unknown-linux-musl.tar.gz" \
    "bat-vVERSION-aarch64-unknown-linux-gnu.tar.gz"

# FZF
check_tool "fzf" "FZF_VERSION" "junegunn/fzf" "fzf_VERSION_checksums.txt" \
    "fzf-VERSION-linux_amd64.tar.gz" \
    "fzf-VERSION-linux_arm64.tar.gz"

# Starship
check_tool "starship" "STARSHIP_VERSION" "starship/starship" "" \
    "starship-x86_64-unknown-linux-musl.tar.gz" \
    "starship-aarch64-unknown-linux-musl.tar.gz"

# GitHub CLI
check_tool "gh" "GH_VERSION" "cli/cli" "gh_VERSION_checksums.txt" \
    "gh_VERSION_linux_amd64.tar.gz" \
    "gh_VERSION_linux_arm64.tar.gz"

