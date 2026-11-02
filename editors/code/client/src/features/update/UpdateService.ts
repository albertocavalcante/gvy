import { Memento, window, env, Uri, workspace } from "vscode";
import {
  UpdateChecker,
  UpdateCheckResult,
  VersionCache,
  SystemClock,
} from "./index";
import { GitHubReleaseProvider } from "./GitHubReleaseProvider";
import {
  getUpdateConfiguration,
  UpdateNotificationLevel,
} from "../../configuration/settings";

const MAX_TIMEOUT_MS = 2 ** 31 - 1;

/**
 * Service that wires UpdateChecker to VS Code.
 * Handles automatic and manual update checks with proper scheduling.
 */
export class UpdateService {
  private readonly checker: UpdateChecker;
  private checkTimeout: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;

  /**
   * @param currentVersion returns the installed server version; it is read at
   *   every check because the running server reports it only after starting.
   */
  constructor(currentVersion: string | (() => string), memento: Memento) {
    const cache = new VersionCache(
      memento,
      getUpdateConfiguration().checkIntervalHours,
    );
    const provider = new GitHubReleaseProvider();
    this.checker = new UpdateChecker(
      currentVersion,
      cache,
      provider,
      new SystemClock(),
    );
  }

  /**
   * Activates automatic update checking on startup.
   * Respects user settings for check interval and notifications.
   */
  async activate(): Promise<void> {
    const config = getUpdateConfiguration();

    if (!config.checkOnStartup) {
      return;
    }

    // Perform initial check
    const result = await this.checker.checkForUpdate();
    await this.handleResult(result, config.notifications);

    // Schedule next check
    this.scheduleNextCheck(config.checkIntervalHours);
  }

  /**
   * Performs a manual update check (bypasses cache).
   * Always shows result to user regardless of notification settings.
   */
  async checkNow(): Promise<void> {
    const result = await this.checker.checkForUpdateNow();
    await this.showResultToUser(result);
  }

  /**
   * Schedules the next automatic check.
   */
  private scheduleNextCheck(intervalHours: number): void {
    if (this.checkTimeout) {
      clearTimeout(this.checkTimeout);
    }

    if (this.disposed) {
      return;
    }

    // setTimeout fires after ~1 ms for delays above 2^31-1 ms (~24.8 days),
    // which turned large intervals into a tight loop.
    const intervalMs = Math.min(
      Math.max(1, intervalHours) * 60 * 60 * 1000,
      MAX_TIMEOUT_MS,
    );
    this.checkTimeout = setTimeout(async () => {
      const config = getUpdateConfiguration();
      try {
        const result = await this.checker.checkForUpdate();
        if (!this.disposed) {
          await this.handleResult(result, config.notifications);
        }
      } catch (error) {
        console.warn("Scheduled update check failed:", error);
      }
      this.scheduleNextCheck(config.checkIntervalHours);
    }, intervalMs);
  }

  /**
   * Handles the result based on notification settings.
   */
  private async handleResult(
    result: UpdateCheckResult,
    notifications: UpdateNotificationLevel,
  ): Promise<void> {
    if (notifications === "off") {
      return;
    }

    if (
      notifications === "onlyWhenOutdated" &&
      result.status !== "update-available"
    ) {
      return;
    }

    await this.showResultToUser(result);
  }

  /**
   * Shows the update check result to the user.
   */
  private async showResultToUser(result: UpdateCheckResult): Promise<void> {
    switch (result.status) {
      case "update-available":
        await this.showUpdateAvailable(result);
        break;
      case "up-to-date":
        window.showInformationMessage(
          `Groovy Language Server ${result.currentVersion} is up to date.`,
        );
        break;
      case "error":
        window.showWarningMessage(
          `Failed to check for updates: ${result.error || "Unknown error"}`,
        );
        break;
      case "unknown":
        // Silent for version detection issues
        break;
    }
  }

  /**
   * Shows update available notification with actions.
   */
  private async showUpdateAvailable(result: UpdateCheckResult): Promise<void> {
    const latestVersion = result.latestRelease?.version || "unknown";
    const releaseUrl = result.latestRelease?.releaseUrl;
    const downloadUrl = result.latestRelease?.downloadUrl;

    const openReleaseAction = "Open Release";
    const downloadAction = "Download";
    const dontShowAgainAction = "Don't Show Again";

    const actions: string[] = [];
    if (releaseUrl) actions.push(openReleaseAction);
    if (downloadUrl) actions.push(downloadAction);
    actions.push(dontShowAgainAction);

    const action = await window.showInformationMessage(
      `Groovy Language Server ${latestVersion} is available! (current: ${result.currentVersion})`,
      ...actions,
    );

    if (action === openReleaseAction && releaseUrl) {
      await env.openExternal(Uri.parse(releaseUrl));
    } else if (action === downloadAction && downloadUrl) {
      await env.openExternal(Uri.parse(downloadUrl));
    } else if (action === dontShowAgainAction) {
      await workspace
        .getConfiguration("groovy")
        .update("update.notifications", "off", true);
      window.showInformationMessage(
        "Update notifications disabled. Re-enable in Settings.",
      );
    }
  }

  /**
   * Disposes scheduled checks.
   */
  dispose(): void {
    this.disposed = true;
    if (this.checkTimeout) {
      clearTimeout(this.checkTimeout);
      this.checkTimeout = null;
    }
  }
}
