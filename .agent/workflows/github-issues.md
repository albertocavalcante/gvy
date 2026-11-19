---
description: GitHub issue creation patterns and label conventions
---

// turbo

# GitHub Issues

## Project Variables

| Variable     | Value                   |
| ------------ | ----------------------- |
| `OWNER/REPO` | `albertocavalcante/gvy` |

## Quick Issue Creation

```bash
gh issue create -R OWNER/REPO \
  --title "[lsp/completion] Add method signatures" \
  --body-file github-issues/issue.md \
  --label "enhancement" --label "lsp/completion" --label "P1-must" --label "size/M"
```

## Readability and visuals

Follow the [visual writing guide](../../docs/visual-writing.md). Lead with the concrete problem, user impact and
proposed outcome. Use a compact diagram, example or comparison when it clarifies the issue. Keep acceptance criteria and
blockers visible; put long technical detail and source catalogues in expandable sections. Cite evidence near claims and
distinguish inspected code from executed tests. Check the actual target surface before claiming that an SVG, Mermaid
diagram or other rich format renders correctly. Small issues can stay brief.

## Label Formula: Type + Area + Priority + Size

### Type Labels

- `bug` - Something isn't working
- `enhancement` - New feature or request
- `documentation` - Documentation improvements
- `architecture` - Architectural changes
- `tech-debt` - Technical debt cleanup

### Area Labels

- `lsp/completion` - Completion features
- `lsp/navigation` - Navigation features
- `lsp/diagnostics` - Diagnostic features
- `lsp/hover` - Hover information
- `lsp/symbols` - Symbol features
- `viz` - AST visualizer desktop app, DTOs, converters, and related integrations

### Priority Labels

- `P0-critical` - Must fix immediately
- `P1-must` - Must have for next release
- `P2-should` - Should have
- `P3-nice` - Nice to have

### Size Labels

- `size/XS` - Extra small (< 1 hour)
- `size/S` - Small (< 4 hours)
- `size/M` - Medium (1-2 days)
- `size/L` - Large (3-5 days)
- `size/XL` - Extra large (> 1 week)

## Create Labels

```bash
gh label create "lsp/completion" -c "c2e0c6" -d "Completion features"
```

## Visualizer issues

Use `viz` for visualizer work, alongside one existing type, priority, and size label. Keep the component in the title
and source references; the shared label covers `viz/desktop`, `viz/ast-model`, and related integrations.

Check [#604](https://github.com/albertocavalcante/gvy/issues/604) and existing `[viz]` issues before creating a new
issue. Give each actionable finding its own reproduction or evidence, proposed scope, acceptance criteria, and
validation limits. Link child issues from the tracker; keep platform proposals separate from correctness fixes.

```bash
gh issue create --repo albertocavalcante/gvy \
  --title "[viz/desktop] Brief description" --body-file issue.md \
  --label bug --label viz --label P2-should --label size/S
```

The versioned label definition is `tools/github-issues/github-labels.json`. Preview the `viz` entry, then sync only that
label using its saved metadata:

```bash
viz_label=$(jq -ce '.[] | select(.name == "viz")' tools/github-issues/github-labels.json)
gh label create viz --repo albertocavalcante/gvy --force \
  --color "$(jq -r .color <<<"$viz_label")" \
  --description "$(jq -r .description <<<"$viz_label")"
```

Avoid the legacy broad setup script described below.

The legacy interactive wizard currently recognizes only `lsp/*` labels as areas;
[#1636](https://github.com/albertocavalcante/gvy/issues/1636) tracks `viz` support. Use the direct CLI workflow above
until that issue is resolved.

## Generate an issue prompt

To turn a GitHub issue into a fenced XML prompt for a coding agent, run from the repository root:

```bash
./tools/github-issues/generate-xml-prompt.sh ISSUE_NUMBER [--model MODEL]
```

Replace `ISSUE_NUMBER` with the issue number. The optional `--model` selects the Codex model; the script defaults to
`gpt-5-codex`. It requires authenticated `gh`, the Codex CLI with access to the selected model, and `jq`. The default
operation prints the generated prompt locally. Review the output before using it; a generated prompt does not override
repository instructions or authorize external actions.

The script also accepts `--comment`, which posts the result to the issue. Use that option only when posting the comment
is explicitly authorized. Run the script with `--help` for its options.

## Dependency bug labels

Follow [the dependency bug policy](../../docs/dependency-bugs.md), including release/fix searches and candidate-version
tests before introducing a workaround. Add `dependency-bug` and the relevant `dep/<name>` label. Add `upstream/reported`
for a linked upstream report and `upstream/fix-released` for a released fix covering the issue's defect scope. Keep
exact versions, test results, and gvy adoption status in the issue body.

The versioned source is `tools/github-issues/github-labels.json`. Preview this task's additive label set with:

```bash
jq '[.[] | select(.name == "dependency-bug" or (.name | startswith("dep/")) or (.name | startswith("upstream/")))]' \
  tools/github-issues/github-labels.json
```

Apply only that set to gvy, preserving the existing label taxonomy:

```bash
(
  set -euo pipefail
  jq -c '.[] | select(.name == "dependency-bug" or (.name | startswith("dep/")) or (.name | startswith("upstream/")))' \
    tools/github-issues/github-labels.json |
    while IFS= read -r entry; do
      label_name=$(jq -r '.name' <<<"$entry")
      label_color=$(jq -r '.color' <<<"$entry")
      label_description=$(jq -r '.description' <<<"$entry")
      gh label create "$label_name" --repo albertocavalcante/gvy --force \
        --color "$label_color" --description "$label_description" || exit 1
    done
)
```

Use this scoped sync for these labels. The legacy `setup-labels.sh` targets former repository names and has broader side
effects; [#1614](https://github.com/albertocavalcante/gvy/issues/1614) tracks its replacement.
