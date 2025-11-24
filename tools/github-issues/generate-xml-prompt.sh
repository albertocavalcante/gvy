#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
generate-xml-prompt.sh ISSUE_NUMBER [--comment] [--model MODEL]

Reads a GitHub issue with gh, asks Codex to generate an XML prompt for coding
agents, and either prints it or posts it as a comment on the issue.

Requirements:
  - gh (logged in and authorized for the repo)
  - codex CLI (with access to the chosen model)
  - jq (for parsing JSON output)

Options:
  --comment       Post the generated XML as a GitHub comment (default: print to stdout)
  --model MODEL   Codex model to use (default: gpt-5-codex)
EOF
}

POST_COMMENT=false
MODEL="gpt-5-codex"
ISSUE_NUMBER=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --comment)
      POST_COMMENT=true
      shift
      ;;
    --model)
      MODEL="${2:?--model requires a model name}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$ISSUE_NUMBER" ]]; then
        echo "Error: More than one issue number provided." >&2
        usage >&2
        exit 1
      fi
      ISSUE_NUMBER="$1"
      shift
      ;;
  esac
done

if [[ -z "$ISSUE_NUMBER" ]]; then
  echo "Error: ISSUE_NUMBER is required." >&2
  usage >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "Error: gh not found in PATH" >&2
  exit 1
fi

if ! command -v codex >/dev/null 2>&1; then
  echo "Error: codex CLI not found in PATH" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq not found in PATH" >&2
  exit 1
fi

ISSUE_JSON="$(gh issue view "$ISSUE_NUMBER" --json number,title,body,labels,url \
  --jq '{number:.number,title:.title,body:.body,url:.url,labels:(.labels | map(.name) | join(", "))}')"

ISSUE_TITLE="$(echo "$ISSUE_JSON" | jq -r '.title')"
ISSUE_BODY="$(echo "$ISSUE_JSON" | jq -r '.body')"
ISSUE_URL="$(echo "$ISSUE_JSON" | jq -r '.url')"
ISSUE_LABELS="$(echo "$ISSUE_JSON" | jq -r '.labels')"

read -r -d '' PROMPT <<EOF || true
<generation-request>
  <role>You are a prompt engineer. Your job is to generate ONE XML prompt block for a coding agent.</role>
  <repo-context>
    <project>Groovy LSP implemented in Kotlin, plus a VS Code extension in TypeScript.</project>
    <scope>LSP features for Groovy: navigation, completion, diagnostics, formatting, and related server/editor integration.</scope>
    <rules>Do NOT mention or reference any other repositories. Stay within this project.</rules>
  </repo-context>
  <issue>
    <url>$ISSUE_URL</url>
    <title>$ISSUE_TITLE</title>
    <labels>$ISSUE_LABELS</labels>
    <body><![CDATA[
$ISSUE_BODY
]]></body>
  </issue>
  <output>
    <format>
      <root>prompt</root>
      <children>
        <child name="role">Primary role and domain expertise needed to implement the issue.</child>
        <child name="project-context">Relevant architecture/components for THIS repo only.</child>
        <child name="task">Actionable objective derived strictly from the issue.</child>
        <child name="constraints">Technical constraints, non-goals, and things to avoid (e.g., don’t touch unrelated features; no external repos; enforce TDD on all implementation work).</child>
        <child name="acceptance">Concrete acceptance criteria for completion.</child>
        <child name="execution-notes">Guidance on approach, risk areas, and test/validation expectations.</child>
        <child name="steps">Ordered multi-step plan (step id/name/instruction) breaking the work into clear phases; include when possible based on the issue.</child>
      </children>
    </format>
    <rules>
      <rule>Return exactly ONE fenced code block: start with ```xml, then a pretty-printed XML document with root &lt;prompt&gt; and the children above, end with ```.</rule>
      <rule>Indent and line-break the XML for readability; no extra prose before or after the fence.</rule>
      <rule>Be succinct but specific so an agent can act without further clarification.</rule>
      <rule>Do not invent external requirements; stay grounded in the issue and repo context.</rule>
    </rules>
  </output>
</generation-request>
EOF

TMP_XML_FILE="$(mktemp)"
trap 'rm -f "$TMP_XML_FILE"' EXIT

# codex prompt now requires a TTY; use exec to capture the response in non-interactive scripts.
printf "%s" "$PROMPT" | codex exec -m "$MODEL" --output-last-message "$TMP_XML_FILE" >/dev/null
XML_OUTPUT="$(cat "$TMP_XML_FILE")"

if $POST_COMMENT; then
  gh issue comment "$ISSUE_NUMBER" --body "$XML_OUTPUT"
  echo "Posted XML prompt to issue #$ISSUE_NUMBER"
else
  printf "%s\n" "$XML_OUTPUT"
fi
