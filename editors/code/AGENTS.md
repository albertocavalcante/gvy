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
- Pinned LSP: `groovy-lsp v0.4.8`, universal `gls-0.4.8.jar` with checksum enforcement.
- Client source: `client/src/**`; build output: `client/out/`.
- Tools: `tools/prepare-server.js` (download/verify LSP), `tools/compute-groovy-cache-key.js`.
- CI: `.github/workflows/*.yml` (publish requires successful LSP bundle).

## Environment toggles

**Version Selection** (new defaults as of v0.4.9):
- **Default**: Fetches latest stable release from GitHub
- `GLS_TAG=v0.4.8` — Use specific version
- `GLS_CHANNEL=nightly` — Use latest nightly build
- `GLS_CHANNEL=pinned` — Use pinned v0.4.8 (stable fallback)
- `GLS_USE_PINNED=true` — Alternative to GLS_CHANNEL=pinned

**Local Development** (auto-detected in monorepo):
- Monorepo: Automatically uses `../groovy-lsp/build/libs/` if available
- `PREFER_LOCAL=true` — Force local build search
- Override: `GLS_CHANNEL=release` to force download

**Other Toggles**:
- `FORCE_DOWNLOAD=true` — Redownload the server JAR even if present
- `GLS_ALLOW_PINNED_FALLBACK=true` — Fall back to pinned on network failure
- `REQUIRE_SERVER_BUNDLE=true` — Fail build if server bundling fails (publish)
- `SKIP_PREPARE_SERVER=true` — Skip server prep (used in some CI paths)

**Migration from v0.4.8**:
- Old: `USE_LATEST_GLS=true` to get latest
- New: Latest is default, use `GLS_USE_PINNED=true` for pinned

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
