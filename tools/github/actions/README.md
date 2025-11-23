# GitHub Actions Utilities

This directory contains utility scripts for managing GitHub Actions workflows and runners.

## Scripts

### `setup-macos-runner.sh`

Automates the setup of a self-hosted runner on macOS. This is useful for running the heavy test suite (like E2E tests) locally or on a dedicated Mac mini to avoid cloud runner costs or time limits.

#### Prerequisites

1.  A GitHub repository admin must generate a **Runner Token**.
    *   Go to: [Settings > Actions > Runners > New self-hosted runner](https://github.com/albertocavalcante/groovy-lsp/settings/actions/runners/new)
    *   Select **macOS**.
    *   Copy the token from the "Configure" section.

#### Usage

```bash
./tools/github/actions/setup-macos-runner.sh <RUNNER_TOKEN> [RUNNER_NAME]
```

*   `<RUNNER_TOKEN>`: The token obtained from GitHub.
*   `[RUNNER_NAME]`: (Optional) A custom name for this runner. Defaults to your computer's hostname.

**Example:**

```bash
./tools/github/actions/setup-macos-runner.sh A1B2C3D4E5F6... my-macbook-pro
```

#### Post-Setup

After the script completes, you can start the runner in two ways:

1.  **Interactive Mode:**
    ```bash
    cd ~/actions-runner
    ./run.sh
    ```

2.  **Background Service:**
    ```bash
    cd ~/actions-runner
    ./svc.sh install
    ./svc.sh start
    ```

#### Workflow Integration

Once the runner is active, you can target it in your CI workflows:

*   **Manual Dispatch:** Run the "CI" workflow and set `runner_label` to `self-hosted`.
*   **Automatic:** Set the repository variable `CI_RUNNER_LABEL` to `self-hosted` to force all CI runs to this runner.
