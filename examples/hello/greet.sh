#!/usr/bin/env bash
# Simple greeting script to demonstrate sh_binary rule
set -euo pipefail

NAME="${1:-World}"
echo "Hello, ${NAME}! This script was built with Buck2."
