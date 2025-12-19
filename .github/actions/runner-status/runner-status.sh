#!/usr/bin/env bash
# runner-status.sh - Check, wait for, or verify GitHub Actions runner status
#
# Modes:
#   check        - Single check, returns has_runners boolean and runner_count
#   wait-online  - Poll until a runner with label is online (fails on timeout)
#   verify-absent - Check that no runners with label exist (warns but succeeds)
#
# Required environment variables:
#   GITHUB_TOKEN      - GitHub PAT with repo scope
#   MODE              - Operation mode (check, wait-online, verify-absent)
#   LABEL_NAME        - Runner label to check for
#   GITHUB_REPOSITORY - Owner/repo (automatically set by GitHub Actions)
#
# Optional (for wait-online mode):
#   MAX_WAIT_MINUTES  - Maximum wait time in minutes (default: 10)
#   POLL_INTERVAL     - Seconds between API calls (default: 10)
#
# Outputs (via GITHUB_OUTPUT):
#   runner_count  - Number of runners found
#   has_runners   - true/false
#   runner_name   - Name of online runner (wait-online mode)
#   runner_status - Status of runner (wait-online mode)

set -euo pipefail

# ============================================================================
# Configuration
# ============================================================================
OWNER="${GITHUB_REPOSITORY%/*}"
REPO="${GITHUB_REPOSITORY#*/}"
API_URL="https://api.github.com/repos/${OWNER}/${REPO}/actions/runners?per_page=100"

# ============================================================================
# Helper Functions
# ============================================================================

# Fetch runners from GitHub API
fetch_runners() {
  curl -sS --fail-with-body \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$API_URL" 2>&1
}

# Get runners with specified label
get_labeled_runners() {
  local response="$1"
  echo "$response" | jq --arg label "$LABEL_NAME" \
    '[.runners[] | select(any(.labels[]?; .name == $label))]'
}

# Get online runners with specified label
get_online_runners() {
  local response="$1"
  echo "$response" | jq --arg label "$LABEL_NAME" \
    '[.runners[] | select(any(.labels[]?; .name == $label) and .status == "online")]'
}

# Write outputs to GITHUB_OUTPUT
set_output() {
  echo "$1=$2" >> "$GITHUB_OUTPUT"
}

# Write job summary
write_summary() {
  echo "$1" >> "$GITHUB_STEP_SUMMARY"
}

# ============================================================================
# Mode: check
# ============================================================================
mode_check() {
  echo "::group::🔍 Checking for runners with label '${LABEL_NAME}'"
  
  local response
  response=$(fetch_runners) || {
    echo "::error::Failed to fetch runners: ${response}"
    exit 1
  }
  
  local runners count
  runners=$(get_labeled_runners "$response")
  count=$(echo "$runners" | jq 'length')
  
  echo "Found ${count} runner(s) with label '${LABEL_NAME}'"
  
  if [ "$count" -gt 0 ]; then
    echo "$runners" | jq '.[] | {name: .name, status: .status}'
    set_output "has_runners" "true"
  else
    set_output "has_runners" "false"
  fi
  
  set_output "runner_count" "$count"
  echo "::endgroup::"
  
  # Job summary
  write_summary "## 🔍 Runner Check"
  write_summary ""
  write_summary "| Label | Count |"
  write_summary "| :--- | :--- |"
  write_summary "| \`${LABEL_NAME}\` | ${count} |"
}

# ============================================================================
# Mode: wait-online
# ============================================================================
mode_wait_online() {
  local max_attempts
  max_attempts=$((MAX_WAIT_MINUTES * 60 / POLL_INTERVAL))
  
  echo "::group::⚙️ Configuration"
  echo "  Repository:    ${OWNER}/${REPO}"
  echo "  Label:         ${LABEL_NAME}"
  echo "  Max wait:      ${MAX_WAIT_MINUTES} minutes (${max_attempts} attempts)"
  echo "  Poll interval: ${POLL_INTERVAL} seconds"
  echo "::endgroup::"
  
  local attempt=1
  while [ "$attempt" -le "$max_attempts" ]; do
    echo "⏳ Attempt ${attempt}/${max_attempts}..."
    
    local response
    response=$(fetch_runners) || {
      echo "::warning::GitHub API request failed: ${response}"
      sleep "$POLL_INTERVAL"
      attempt=$((attempt + 1))
      continue
    }
    
    # Check for API error messages
    if echo "$response" | jq -e '.message' > /dev/null 2>&1; then
      local error_msg
      error_msg=$(echo "$response" | jq -r '.message')
      echo "::warning::GitHub API error: ${error_msg}"
      sleep "$POLL_INTERVAL"
      attempt=$((attempt + 1))
      continue
    fi
    
    local online_runners runner_info
    online_runners=$(get_online_runners "$response")
    
    if [ "$(echo "$online_runners" | jq 'length')" -gt 0 ]; then
      runner_info=$(echo "$online_runners" | jq '.[0]')
      local runner_name runner_status
      runner_name=$(echo "$runner_info" | jq -r '.name')
      runner_status=$(echo "$runner_info" | jq -r '.status')
      
      echo ""
      echo "✅ Found online runner with label '${LABEL_NAME}'!"
      echo ""
      echo "::group::📋 Runner Details"
      echo "$runner_info" | jq '{name: .name, status: .status, labels: [.labels[].name], busy: .busy}'
      echo "::endgroup::"
      
      set_output "runner_name" "$runner_name"
      set_output "runner_status" "$runner_status"
      set_output "runner_count" "$(echo "$online_runners" | jq 'length')"
      set_output "has_runners" "true"
      
      # Job summary
      write_summary "## 🏃 Runner Ready"
      write_summary ""
      write_summary "| Property | Value |"
      write_summary "| :--- | :--- |"
      write_summary "| **Name** | \`${runner_name}\` |"
      write_summary "| **Status** | ${runner_status} |"
      write_summary "| **Label** | \`${LABEL_NAME}\` |"
      write_summary "| **Wait Time** | ~$((attempt * POLL_INTERVAL)) seconds |"
      
      exit 0
    fi
    
    echo "  Runner not ready yet. Sleeping for ${POLL_INTERVAL}s..."
    sleep "$POLL_INTERVAL"
    attempt=$((attempt + 1))
  done
  
  # Timeout
  echo ""
  echo "❌ Timeout: No online runner found with label '${LABEL_NAME}' after ${MAX_WAIT_MINUTES} minutes"
  echo ""
  
  set_output "runner_count" "0"
  set_output "has_runners" "false"
  
  write_summary "## ❌ Runner Wait Timeout"
  write_summary ""
  write_summary "No runner with label \`${LABEL_NAME}\` came online within ${MAX_WAIT_MINUTES} minutes."
  write_summary ""
  write_summary "**Troubleshooting:**"
  write_summary "- Check if the Terraform apply completed successfully"
  write_summary "- Verify the runner VM has network connectivity"
  write_summary "- Check runner registration logs in Magalu Cloud console"
  
  exit 1
}

# ============================================================================
# Mode: verify-absent
# ============================================================================
mode_verify_absent() {
  echo "::group::🔍 Verifying runners with label '${LABEL_NAME}' are removed"
  
  # Give GitHub API time to reflect the change
  echo "Waiting 10s for API to sync..."
  sleep 10
  
  local response
  response=$(fetch_runners) || {
    echo "::warning::Failed to fetch runners: ${response}"
    set_output "runner_count" "unknown"
    set_output "has_runners" "unknown"
    echo "::endgroup::"
    exit 0  # Don't fail, just warn
  }
  
  local runners count
  runners=$(get_labeled_runners "$response")
  count=$(echo "$runners" | jq 'length')
  
  set_output "runner_count" "$count"
  
  if [ "$count" -eq 0 ]; then
    echo "✅ All runners with label '${LABEL_NAME}' successfully removed"
    set_output "has_runners" "false"
    
    write_summary "## ✅ Runners Removed"
    write_summary ""
    write_summary "All runners with label \`${LABEL_NAME}\` have been successfully deregistered."
  else
    echo "⚠️ Warning: ${count} runner(s) with label '${LABEL_NAME}' still registered"
    echo "   (They may take additional time to fully deregister)"
    echo "$runners" | jq '.[] | {name: .name, status: .status}'
    set_output "has_runners" "true"
    
    write_summary "## ⚠️ Runners Still Registered"
    write_summary ""
    write_summary "${count} runner(s) with label \`${LABEL_NAME}\` are still registered."
    write_summary "They may take additional time to fully deregister."
  fi
  
  echo "::endgroup::"
  
  # Always exit 0 - this is a verification step, not a gate
  exit 0
}

# ============================================================================
# Main
# ============================================================================
case "$MODE" in
  check)
    mode_check
    ;;
  wait-online)
    mode_wait_online
    ;;
  verify-absent)
    mode_verify_absent
    ;;
  *)
    echo "::error::Unknown mode: ${MODE}. Valid modes: check, wait-online, verify-absent"
    exit 1
    ;;
esac
