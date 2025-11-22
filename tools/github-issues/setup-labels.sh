#!/bin/bash

# GitHub Labels Setup Script
# This script creates comprehensive labels for both groovy-lsp and vscode-groovy repositories

set -e

# Configuration
GROOVY_LSP_REPO="albertocavalcante/groovy-lsp"
VSCODE_GROOVY_REPO="albertocavalcante/vscode-groovy"
LABELS_FILE="$(dirname "${BASH_SOURCE[0]}")/github-labels.json"
DRY_RUN=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Help function
show_help() {
    cat << EOF
GitHub Labels Setup Script

Usage: $0 [OPTIONS]

OPTIONS:
    --dry-run           Preview labels without creating them
    --groovy-lsp-only   Only setup labels for groovy-lsp repo
    --vscode-only       Only setup labels for vscode-groovy repo
    --help              Show this help message

EXAMPLES:
    $0                  Setup labels for both repositories
    $0 --dry-run        Preview all labels without creating
    $0 --groovy-lsp-only    Setup labels only for groovy-lsp

PREREQUISITES:
    - GitHub CLI (gh) must be installed and authenticated
    - Must have admin access to both repositories
EOF
}

# Parse command line arguments
GROOVY_LSP_ONLY=false
VSCODE_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --groovy-lsp-only)
            GROOVY_LSP_ONLY=true
            shift
            ;;
        --vscode-only)
            VSCODE_ONLY=true
            shift
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Utility functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if gh CLI is installed
    if ! command -v gh &> /dev/null; then
        log_error "GitHub CLI (gh) is not installed. Please install it first."
        log_info "Visit: https://cli.github.com/"
        exit 1
    fi

    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        log_error "jq is not installed. Please install it first."
        log_info "Visit: https://stedolan.github.io/jq/"
        exit 1
    fi

    # Check if authenticated
    if ! gh auth status &> /dev/null; then
        log_error "GitHub CLI is not authenticated. Please run 'gh auth login' first."
        exit 1
    fi

    # Check if labels file exists
    if [[ ! -f "$LABELS_FILE" ]]; then
        log_error "Labels file not found: $LABELS_FILE"
        exit 1
    fi

    log_success "Prerequisites check passed"
}

# Create labels for a repository
setup_labels_for_repo() {
    local repo="$1"
    local repo_name=$(basename "$repo")

    log_info "Setting up labels for $repo..."

    if [[ "$DRY_RUN" == "true" ]]; then
        log_info "DRY RUN - Would setup labels for: $repo"
        return 0
    fi

    # Check repository access
    if ! gh repo view "$repo" &> /dev/null; then
        log_error "Cannot access repository '$repo'. Please check the repository name and your permissions."
        return 1
    fi

    # Delete default labels
    log_info "Deleting default labels..."
    local labels_to_delete=("duplicate" "invalid" "wontfix" "question")
    local existing_labels
    existing_labels=$(gh label list --repo "$repo" --limit 500 --json name | jq -r '.[].name')

    for label in "${labels_to_delete[@]}"; do
        if echo "$existing_labels" | grep -q -x "$label"; then
            if gh label delete "$label" --repo "$repo" --yes &> /dev/null; then
                log_info "Deleted label: $label"
            else
                log_warning "Failed to delete label: $label"
            fi
        fi
    done

    local created_count=0
    local updated_count=0
    local skipped_count=0

    # Pre-fetch existing labels for faster checking
    local existing_labels
    existing_labels=$(gh label list --repo "$repo" --limit 500 --json name | jq -r '.[].name')

    # Read and process each label from the JSON file
    while IFS= read -r label_json; do
        # Extract label properties using jq (REQUIRED)
        local name=$(echo "$label_json" | jq -r '.name')
        local color=$(echo "$label_json" | jq -r '.color')
        local description=$(echo "$label_json" | jq -r '.description')

        if [[ -n "$name" && -n "$color" ]]; then
            # Check if label already exists using cached list
            if echo "$existing_labels" | grep -q -x "$name"; then
                # Update existing label
                if gh label edit "$name" --repo "$repo" --color "$color" --description "$description" &> /dev/null; then
                    log_info "Updated label: $name"
                    ((updated_count++))
                else
                    log_warning "Failed to update label: $name"
                fi
            else
                # Create new label
                if gh label create "$name" --repo "$repo" --color "$color" --description "$description" &> /dev/null; then
                    log_success "Created label: $name"
                    ((created_count++))
                else
                    log_warning "Failed to create label: $name"
                fi
            fi
        else
            ((skipped_count++))
        fi
    done < <(jq -c '.[]' "$LABELS_FILE")

    log_info "=== $repo_name SUMMARY ==="
    log_success "Created: $created_count labels"
    log_info "Updated: $updated_count labels"
    if [[ $skipped_count -gt 0 ]]; then
        log_warning "Skipped: $skipped_count labels"
    fi
}

# Main execution function
main() {
    log_info "Starting GitHub labels setup"
    log_info "Dry run: $DRY_RUN"

    check_prerequisites

    if [[ "$VSCODE_ONLY" == "true" ]]; then
        setup_labels_for_repo "$VSCODE_GROOVY_REPO"
    elif [[ "$GROOVY_LSP_ONLY" == "true" ]]; then
        setup_labels_for_repo "$GROOVY_LSP_REPO"
    else
        setup_labels_for_repo "$GROOVY_LSP_REPO"
        echo
        setup_labels_for_repo "$VSCODE_GROOVY_REPO"
    fi

    echo
    log_info "=== NEXT STEPS ==="
    log_info "1. Update existing issues with new label format"
    log_info "2. Create issue templates with label requirements"
    log_info "3. Update contribution guidelines with labeling conventions"
    log_info "4. Consider setting up label automation workflows"
}

# Script entry point
main "$@"
