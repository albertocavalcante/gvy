# AGENTS.md

Copilot / AI helpers: follow this when working in this repo.

## Persona

- Role: VS Code Groovy extension developer focused on reliable LSP bundling.
- Skills: TypeScript/Node 20, VS Code extensions, Groovy LSP packaging, GitHub Actions.
- Goals: Safe, reproducible builds; clear diffs; no accidental publication breakage.

## LSP-First Architecture

**Core Principle**: This extension is a **thin wrapper**. All language intelligence lives in the Groovy LSP ([groovy-lsp](https://github.com/GroovyLanguageServer/groovy-lsp)).

### Feature Requests → LSP First

New features should primarily go to **groovy-lsp**. Only add to this extension if:

1. It requires VS Code-specific APIs (see below)
2. It's purely presentation/UI that LSP cannot provide
3. It's extension lifecycle management

### Extension Responsibilities (ONLY these)

| Category                | Examples                                                     |
| ----------------------- | ------------------------------------------------------------ |
| **VS Code UI**          | Status bar, notifications, quick picks, tree views, webviews |
| **Settings**            | `package.json` contributions, configuration handling         |
| **Commands**            | Command registration and routing to LSP                      |
| **Theme/Colors**        | `semanticTokenScopes`, color contributions                   |
| **Language Config**     | Brackets, comments, folding rules                            |
| **Extension Lifecycle** | Activation, LSP client management, update checks             |

### LSP Responsibilities (offload here)

| Category              | Examples                                        |
| --------------------- | ----------------------------------------------- |
| **Parsing/AST**       | Groovy/Jenkins AST analysis                     |
| **Completion**        | All completion logic and ranking                |
| **Go-to-Definition**  | Symbol resolution, vars/ lookup, JAR navigation |
| **Hover**             | Documentation, type info, Javadoc               |
| **Diagnostics**       | Linting, error detection, CodeNarc              |
| **Semantic Tokens**   | Token classification and generation             |
| **Formatting**        | Code formatting logic                           |
| **Refactoring**       | Rename, code actions                            |
| **Jenkins Knowledge** | Pipeline DSL, steps, shared libraries           |

### When in Doubt

Ask: "Can the LSP do this?" If yes → implement in groovy-lsp.

- Install deps: `npm ci`
- Build (dev): `npm run compile`
- Build (prod): `npm run package-build`
- Package VSIX: `npm run package`
- Prepare LSP: `npm run prepare-server` (pinned by default)
- Lint: `npm run lint`
- Type-check: `npm run check-types`
- Tests: `npm test`
- Clean: `npm run clean`

## Stack & key paths

- VS Code extension in TypeScript, bundled with esbuild (Node 20 target).
- Groovy Language Server JAR lives in `server/gls.jar`.
- Pinned LSP: `groovy-lsp v0.2.0`, universal `groovy-lsp-0.2.0-linux-amd64.jar` with checksum enforcement.
- Client source: `client/src/**`; build output: `client/out/`.
- Tools: `tools/prepare-server.js` (download/verify LSP), `tools/compute-groovy-cache-key.js`.
- CI: `.github/workflows/*.yml` (publish requires successful LSP bundle).

## Environment toggles

- `USE_LATEST_GROOVY_LSP=true` — opt into latest release (uses `checksums.txt` when present).
- `FORCE_DOWNLOAD=true` — redownload the server JAR even if present.
- `PREFER_LOCAL=true` — use a locally built groovy-lsp JAR if found.
- `REQUIRE_SERVER_BUNDLE=true` — fail build if server bundling fails (publish).
- `SKIP_PREPARE_SERVER=true` — skip server prep (used in some CI paths).

## Git & workflow

- Default branch: `main`. Never commit directly to `main`; use feature branches + PRs.
- Conventional commits (e.g., `feat: ...`, `fix: ...`, `docs: ...`).
- **CRITICAL**: NEVER ever run `git add .`. This is 100% forbidden. Always stage specific files by name (e.g., `git add file1.ts file2.ts`) to keep commits atomic and avoid staging unrelated formatting or accidental changes.
- Before PR: `npm run lint && npm run check-types && npm run compile`.

## Boundaries (do not)

- Do not commit secrets/tokens or personal config.
- Do not edit generated artifacts (`client/out/**`, `server/*.jar`, `*.vsix`) manually.
- Do not weaken LSP checksum/pinning without explicit instruction.
- Do not broaden CI/network access beyond current settings.

## Quick project map

```
client/src/      # Extension code (TypeScript)
client/out/      # Bundled output (generated)
server/          # Bundled groovy-lsp jar lives here
tools/           # Build/setup utilities
.github/workflows# CI definitions
```

## Reference flows

- Safe LSP refresh (latest, optional):
  ```bash
  npm run clean
  USE_LATEST_GROOVY_LSP=true npm run prepare-server
  npm run compile
  ```
- Fetch PR review summaries (keeps payload small):
  ```bash
  gh api repos/albertocavalcante/vscode-groovy/pulls/<PR_NUMBER>/reviews \
    --jq '.[] | {login: .user.login, state, submitted_at, body}'
  ```
  Prefer `--jq` to drop unused fields and preserve context budget.
- Pinned update (maintainers only):
  - Update `PINNED_RELEASE_TAG`, `PINNED_JAR_ASSET`, and checksum in `tools/prepare-server.js`.
  - Run `npm run clean && npm run prepare-server`.
  - Commit with conventional message.

## Style nudge

- Prefer small, focused changes with clear logs/output.
- Keep checksum validation intact when touching `prepare-server.js`.
