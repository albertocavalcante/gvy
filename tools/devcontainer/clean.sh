#!/bin/bash
set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

WORKSPACE_NAME=$(basename "$PWD")
echo -e "${BLUE}🧹 Cleaning up devcontainer resources for: ${WORKSPACE_NAME}${NC}"

# 1. Remove Containers
echo -e "${YELLOW}Removing containers...${NC}"
# Filter by label matching devcontainer (try both standard CLI label and VS Code label)
CONTAINERS=$(docker ps -a --filter "label=devcontainer.local_folder=$PWD" -q)
if [ -z "$CONTAINERS" ]; then
    CONTAINERS=$(docker ps -a --filter "label=vsch.local.folder=$PWD" -q)
fi

if [ -n "$CONTAINERS" ]; then
    docker rm -f $CONTAINERS
    echo -e "${GREEN}✔ Removed containers: $CONTAINERS${NC}"
else
    echo "No containers found."
fi

# 2. Remove Volumes (Aggressive)
echo -e "${YELLOW}Removing volumes...${NC}"
# Standard pattern: vsc-<folder>-<hash>
# We can't easily guess the hash, but we can filter by name prefix if it follows convention.
# Safer approach: filter volumes that are not attached to any running container? No, we just killed them.
# Let's try to match volume names.
VOLUMES=$(docker volume ls -q | grep "^vsc-${WORKSPACE_NAME}-")

if [ -n "$VOLUMES" ]; then
    echo -e "${YELLOW}Found volumes:${NC}"
    echo "$VOLUMES"
    echo -e "${RED}⚠️  This will delete all data in these volumes. Are you sure? [y/N]${NC}"
    read -r response
    if [[ "$response" =~ ^([yY][eE][sS]|[yY])+$ ]]; then
        docker volume rm $VOLUMES
        echo -e "${GREEN}✔ Removed volumes.${NC}"
    else
        echo "Skipping volume removal."
    fi
else
    echo "No matching volumes found."
fi

echo -e "${GREEN}✨ Cleanup complete. You can now 'Reopen in Container' to get a fresh environment.${NC}"

