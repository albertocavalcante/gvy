#!/bin/bash
# tools/github/actions/pin.sh
#
# Fetches the latest release tag and corresponding SHA for GitHub Actions used in this project.
# Helps maintain secure, pinned action versions in workflows.

set -e

# List of actions to check
declare -a actions=(
    "actions/checkout"
    "actions/setup-java"
    "gradle/actions" # corresponds to gradle/actions/setup-gradle
    "actions/setup-node"
    "actions/upload-artifact"
    "actions/download-artifact"
    "softprops/action-gh-release"
    "mislav/bump-homebrew-formula-action"
)

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}Resolving latest versions for pinned actions...${NC}"

for repo in "${actions[@]}"; do
    # 1. Try getting the latest release tag
    TAG=$(gh release list --repo "$repo" --limit 1 --exclude-drafts --exclude-pre-releases --json tagName --jq '.[0].tagName')
    
    # 2. Fallback: try getting the latest tag if no formal release
    if [ -z "$TAG" ] || [ "$TAG" == "null" ]; then
        TAG=$(gh api "repos/$repo/tags" --jq '.[0].name')
    fi
    
    if [ -n "$TAG" ] && [ "$TAG" != "null" ]; then
        # 3. Get the commit SHA for the tag
        SHA=$(gh api "repos/$repo/commits/$TAG" --jq .sha)
        
        # Output in a format easy to copy-paste into workflows
        # Format: uses: owner/repo@SHA # tag
        echo -e "${GREEN}${repo}${NC}@${SHA} # ${TAG}"
    else
        echo -e "${repo}: ${RED}Could not find tag${NC}"
    fi
done
