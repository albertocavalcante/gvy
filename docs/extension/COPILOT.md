# GitHub Copilot coding agent setup

This repository includes `.github/workflows/copilot-setup-steps.yml` to pre-provision the agent’s GitHub Actions
environment (Node.js, Java 17, npm dependencies, Groovy language server download, and GitHub CLI).

## Required repository configuration

- Create an environment named `copilot` (Repository settings → Environments).
- Add a fine-grained PAT secret `COPILOT_GH_TOKEN` scoped only to `albertocavalcante/vscode-groovy` with:
  - `contents:write`
  - `pull_requests:write`
  - `actions:read` This token is exported to the workflow as `GH_TOKEN`/`GH_HOST` for the `gh` CLI.
- Keep the workflow on the **default branch**; Copilot only reads `copilot-setup-steps` from default. You can manually
  run it from the Actions tab to validate before letting Copilot work.

## Workflow notes

- Job name must stay `copilot-setup-steps`.
- Allowed customizations per GitHub docs: `steps`, `permissions`, `runs-on`, `services`, `snapshot`, `timeout-minutes`
  (≤59).
- The workflow currently:
  - Checks out the repo with `persist-credentials: false`.
  - Sets up Node 20 (npm cache), Java 17, runs `npm ci`, and `npm run prepare-server:download`.
  - Ensures `gh` CLI is installed and authenticated with `GH_TOKEN`.

## Runners and networking

- Copilot coding agent only supports Ubuntu x64 runners.
- For heavier workloads, change `runs-on` to a larger runner label. If you switch to ARC self-hosted runners, disable
  the Copilot repository firewall per
  [GitHub guidance](https://docs.github.com/en/copilot/customizing-copilot/customizing-or-disabling-the-firewall-for-copilot-coding-agent)
  and ensure outbound access to:
  - `api.githubcopilot.com`
  - `uploads.github.com`
  - `user-images.githubusercontent.com`

## Official references

- Customizing the agent environment:
  https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/customize-the-agent-environment
- Copilot firewall settings:
  https://docs.github.com/en/copilot/customizing-copilot/customizing-or-disabling-the-firewall-for-copilot-coding-agent
