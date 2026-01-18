# Gemini CLI Custom Slash Commands

Custom slash commands for the [Gemini CLI](https://github.com/google-gemini/gemini-cli).

## Documentation

- **Official Docs:** <https://geminicli.com/docs/cli/custom-commands/>
- **Blog Post:** <https://cloud.google.com/blog/topics/developers-practitioners/gemini-cli-custom-slash-commands>

## Syntax Reference

| Syntax     | Purpose                                         | Example                             |
| ---------- | ----------------------------------------------- | ----------------------------------- |
| `{{args}}` | Argument placeholder - replaced with user input | `prompt = "Review PR: {{args}}"`    |
| `!{...}`   | Shell command execution                         | `!{cat .agent/workflows/review.md}` |
| `@{...}`   | File content injection                          | `@{AGENTS.md}`                      |

### Argument Placeholder: `{{args}}`

Replaced with everything the user types after the command name.

```toml
# /review 123 → {{args}} becomes "123"
description = "Review a PR"
prompt = "Please review PR: {{args}}"
```

### Shell Command: `!{...}`

Executes a shell command and injects the output into the prompt.

```toml
prompt = """
Here is the workflow to follow:
!{cat .agent/workflows/review.md}
"""
```

### File Content: `@{...}`

Injects file contents directly (simpler than `!{cat ...}`).

```toml
prompt = """
Follow these rules:
@{AGENTS.md}
"""
```

## File Structure

Commands are `.toml` files in `.gemini/commands/`:

```
.gemini/commands/
├── README.md           # This file
├── review.toml         # /review command
├── ship.toml           # /ship command
├── lint.toml           # /lint command
├── lint/
│   └── fix.toml        # /lint:fix subcommand
└── qa/
    ├── pr.toml         # /qa:pr subcommand
    └── health.toml     # /qa:health subcommand
```

Nested directories create subcommands with `:` separator (e.g., `lint/fix.toml` → `/lint:fix`).

## TOML Schema

```toml
# Required
description = "Short description shown in /help"
prompt = "The prompt template"

# Optional
# (none currently documented - check official docs for updates)
```
