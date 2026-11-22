// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { workspace, Disposable, window } from "vscode";
import { requiresServerRestart, canBeAppliedDynamically } from "./settings";
import {
  restartClient,
  getClient,
  buildServerSettingsMap,
} from "../server/client";

/**
 * Sets up configuration change watchers
 */
export function setupConfigurationWatcher(): Disposable {
  // At most one restart prompt at a time: editing several restart-required
  // settings must not stack prompts or queue several restarts.
  let restartPromptPending = false;

  const promptForRestart = async (): Promise<void> => {
    if (restartPromptPending) {
      return;
    }
    restartPromptPending = true;
    try {
      const answer = await window.showInformationMessage(
        "Groovy configuration changed. The language server needs to be restarted for changes to take effect.",
        "Restart Now",
        "Restart Later",
      );

      if (answer === "Restart Now") {
        console.log("Configuration changed, restarting server...");
        await restartClient();
      }
    } finally {
      restartPromptPending = false;
    }
  };

  return workspace.onDidChangeConfiguration(async (event) => {
    // Check if any groovy.* configuration changed
    if (!event.affectsConfiguration("groovy")) {
      return;
    }

    // Apply dynamic settings first and independently of the restart check, so
    // "Restart Later" does not leave them unapplied on the running server.
    if (canBeAppliedDynamically(event)) {
      const client = getClient();
      // A stopped server picks up the current settings on its next start.
      if (client?.isRunning()) {
        try {
          await client.sendNotification("workspace/didChangeConfiguration", {
            settings: buildServerSettingsMap(),
          });
        } catch (error) {
          console.error(
            "Failed to send workspace/didChangeConfiguration:",
            error,
          );
        }
      }
    }

    // Handle settings that require a server restart
    if (requiresServerRestart(event)) {
      await promptForRestart();
    }
  });
}
