import type { OutputChannel } from "vscode";
import {
  ErrorDetails,
  GradleJdkIncompatibleError,
  GroovyJdkIncompatibleError,
  ToolchainProvisioningError,
  GenericError,
  ProjectJdkNewerWarning,
} from "./statusUtils";

/**
 * Handles showing VS Code notifications for error details with actionable suggestions.
 *
 * This module displays user-friendly error messages with up to 3 action buttons.
 * For toolchain provisioning errors, smart action buttons are provided (not derived
 * from LSP suggestions). For other errors, buttons are derived from LSP suggestions.
 */

// Smart action button labels for toolchain provisioning errors
const DETECT_AND_SET_JAVA = "Detect & Set Java";
const ADD_FOOJAY_PLUGIN = "Add Auto-Download Plugin";

// Smart action button labels for JDK version mismatch warnings
const CONFIGURE_JAVA = "Configure Java";
const DISMISS = "Dismiss";
const DONT_SHOW_AGAIN = "Don't Show Again";

// TODO(tech-debt): The extension is taking on responsibilities that ideally belong
// to the build tool layer (foojay plugin management, JDK detection for toolchain resolution).
// Once BSP (Build Server Protocol) support is implemented, consider moving these
// responsibilities to the server side where they can be handled more appropriately.
// Track: https://github.com/albertocavalcante/gvy/issues/785

/**
 * Shows a VS Code notification for the given error details.
 * Displays up to 3 action buttons total (VS Code UX guideline).
 * For toolchain errors, shows smart actions. For other errors, shows suggestions
 * plus optional "Show Details" and "Retry Resolution" buttons.
 *
 * @param errorCode The error code (e.g., "GRADLE_JDK_INCOMPATIBLE")
 * @param errorDetails The detailed error information with suggestions
 * @param outputChannel Optional output channel to show when "Show Details" is clicked
 * @param retryCommand Optional command to execute when "Retry Resolution" is clicked
 */
export async function showErrorNotification(
  errorCode: string,
  errorDetails: ErrorDetails,
  outputChannel?: OutputChannel,
  retryCommand?: string,
): Promise<void> {
  // Lazy import vscode to avoid breaking unit tests
  const vscode = await import("vscode");

  // Handle JDK newer warning specially - it has unique action buttons
  if (errorDetails.type === "PROJECT_JDK_NEWER_WARNING") {
    await showJdkNewerWarning(
      errorDetails as ProjectJdkNewerWarning,
      outputChannel,
    );
    return;
  }

  if (!errorDetails.suggestions || errorDetails.suggestions.length === 0) {
    // No suggestions - show basic error message with optional retry/details
    const basicActions: string[] = [];
    if (retryCommand) basicActions.push("Retry Resolution");
    if (outputChannel) basicActions.push("Show Details");

    if (basicActions.length > 0) {
      void (async () => {
        try {
          const selected = await vscode.window.showErrorMessage(
            `Groovy Language Server Error: ${errorCode}`,
            ...basicActions,
          );
          await handleSpecialActions(selected, outputChannel, retryCommand);
        } catch (err: unknown) {
          console.error("Error showing notification:", err);
        }
      })();
    } else {
      vscode.window.showErrorMessage(
        `Groovy Language Server Error: ${errorCode}`,
      );
    }
    return;
  }

  const message = buildErrorMessage(errorCode, errorDetails);

  // Build actions based on error type
  const actions = buildActionsForError(
    errorDetails,
    retryCommand,
    outputChannel,
  );

  // Show notification with action buttons
  if (
    errorDetails.type === "GRADLE_JDK_INCOMPATIBLE" ||
    errorDetails.type === "GROOVY_JDK_INCOMPATIBLE" ||
    errorDetails.type === "TOOLCHAIN_PROVISIONING_FAILED"
  ) {
    void (async () => {
      try {
        const selected = await vscode.window.showErrorMessage(
          message,
          ...actions,
        );
        if (selected) {
          const handled = await handleSpecialActions(
            selected,
            outputChannel,
            retryCommand,
          );
          if (!handled) {
            await handleActionClick(selected, errorDetails);
          }
        }
      } catch (err: unknown) {
        console.error("Error showing error notification:", err);
      }
    })();
  } else {
    // For less critical errors, use warning instead
    void (async () => {
      try {
        const selected = await vscode.window.showWarningMessage(
          message,
          ...actions,
        );
        if (selected) {
          const handled = await handleSpecialActions(
            selected,
            outputChannel,
            retryCommand,
          );
          if (!handled) {
            await handleActionClick(selected, errorDetails);
          }
        }
      } catch (err: unknown) {
        console.error("Error showing warning notification:", err);
      }
    })();
  }
}

/**
 * Builds action buttons based on error type.
 * For toolchain errors, provides smart actions instead of raw suggestions.
 */
function buildActionsForError(
  errorDetails: ErrorDetails,
  retryCommand?: string,
  outputChannel?: OutputChannel,
): string[] {
  const actions: string[] = [];

  // For toolchain provisioning errors, use smart action buttons
  if (errorDetails.type === "TOOLCHAIN_PROVISIONING_FAILED") {
    // Primary action: Detect & Set Java
    actions.push(DETECT_AND_SET_JAVA);

    // Secondary action: Add foojay plugin for auto-download
    if (actions.length < 3) {
      actions.push(ADD_FOOJAY_PLUGIN);
    }

    // Show Details if room available
    if (actions.length < 3 && outputChannel) {
      actions.push("Show Details");
    }
  } else {
    // Default behavior for other error types
    if (retryCommand) {
      actions.push("Retry Resolution");
    }

    // Add first suggestion if room available (max 3 total)
    if (actions.length < 3 && errorDetails.suggestions.length > 0) {
      actions.push(errorDetails.suggestions[0]);
    }

    // Add Show Details if room available (max 3 total)
    if (actions.length < 3 && outputChannel) {
      actions.push("Show Details");
    }
  }

  return actions;
}

/**
 * Handles the special "Show Details" and "Retry Resolution" action buttons.
 * Returns true if the action was handled, false otherwise.
 */
async function handleSpecialActions(
  action: string | undefined,
  outputChannel?: OutputChannel,
  retryCommand?: string,
): Promise<boolean> {
  if (!action) return false;

  if (action === "Show Details" && outputChannel) {
    outputChannel.show(true);
    return true;
  }

  if (action === "Retry Resolution" && retryCommand) {
    const vscode = await import("vscode");
    await vscode.commands.executeCommand(retryCommand);
    return true;
  }

  return false;
}

/**
 * Builds a user-friendly error message based on the error type and details.
 */
function buildErrorMessage(
  errorCode: string,
  errorDetails: ErrorDetails,
): string {
  switch (errorDetails.type) {
    case "GRADLE_JDK_INCOMPATIBLE": {
      const err = errorDetails as GradleJdkIncompatibleError;
      return `Gradle ${err.gradleVersion} is not compatible with JDK ${err.jdkVersion}. ${errorDetails.suggestions[0] || "Please check your configuration."}`;
    }

    case "GROOVY_JDK_INCOMPATIBLE": {
      const err = errorDetails as GroovyJdkIncompatibleError;
      const groovyVer = err.groovyVersion || "current";
      return `Groovy ${groovyVer} is not compatible with JDK ${err.jdkVersion}. ${errorDetails.suggestions[0] || "Please update Groovy or JDK version."}`;
    }

    case "TOOLCHAIN_PROVISIONING_FAILED": {
      const err = errorDetails as ToolchainProvisioningError;
      const version = err.requiredVersion
        ? `Java ${err.requiredVersion}`
        : "required Java";
      const platform = err.platform || "your platform";
      return `Cannot find ${version} for ${platform}. ${errorDetails.suggestions[0] || "Please install the required JDK."}`;
    }

    case "GENERIC": {
      const err = errorDetails as GenericError;
      return `${err.errorCode}: ${errorDetails.suggestions[0] || "An error occurred."}`;
    }

    default:
      return `${errorCode}: ${errorDetails.suggestions[0] || "An error occurred."}`;
  }
}

/**
 * Handles action button clicks by interpreting the suggestion text
 * and executing the appropriate VS Code command.
 */
async function handleActionClick(
  action: string,
  errorDetails?: ErrorDetails,
): Promise<void> {
  // Lazy import vscode to avoid breaking unit tests
  const vscode = await import("vscode");

  // Handle smart actions for toolchain provisioning errors
  if (action === DETECT_AND_SET_JAVA) {
    // Extract required version from error details if available
    const requiredVersion =
      errorDetails?.type === "TOOLCHAIN_PROVISIONING_FAILED"
        ? (errorDetails as ToolchainProvisioningError).requiredVersion
        : undefined;

    // Execute the configureJava command with the required version
    await vscode.commands.executeCommand(
      "groovy.configureJava",
      requiredVersion,
    );
    return;
  }

  if (action === ADD_FOOJAY_PLUGIN) {
    // Execute the addFoojayResolver command
    await vscode.commands.executeCommand("groovy.addFoojayResolver");
    return;
  }

  const lowerAction = action.toLowerCase();

  // Check for settings-related actions
  if (
    lowerAction.includes("settings") ||
    lowerAction.includes("groovy.gradle.javaHome")
  ) {
    // Open settings to groovy.gradle.javaHome
    vscode.commands.executeCommand(
      "workbench.action.openSettings",
      "groovy.gradle.javaHome",
    );
    return;
  }

  // Check for install/download actions
  if (lowerAction.includes("install") || lowerAction.includes("download")) {
    // Check if the action contains a URL
    const urlMatch = action.match(/(https?:\/\/[^\s]+)/);
    if (urlMatch) {
      const url = urlMatch[1];
      vscode.window
        .showInformationMessage(`Open ${url} in browser?`, "Open")
        .then((choice) => {
          if (choice === "Open") {
            vscode.env.openExternal(vscode.Uri.parse(url));
          }
        });
      return;
    }
    // Show info message with instructions
    vscode.window
      .showInformationMessage(
        `To install the required JDK: ${action}`,
        "Open Settings",
      )
      .then((selected) => {
        if (selected === "Open Settings") {
          vscode.commands.executeCommand(
            "workbench.action.openSettings",
            "groovy.gradle.javaHome",
          );
        }
      });
    return;
  }

  // Check for Gradle wrapper actions
  if (
    lowerAction.includes("gradle wrapper") ||
    lowerAction.includes("gradlew")
  ) {
    // Extract command from suggestion if possible
    const commandMatch = action.match(/`([^`]+)`/);
    const command = commandMatch
      ? commandMatch[1]
      : "./gradlew wrapper --gradle-version";

    // Show terminal command suggestion
    vscode.window
      .showInformationMessage(
        `Run this command in your terminal: ${command}`,
        "Copy Command",
      )
      .then((selected) => {
        if (selected === "Copy Command") {
          vscode.env.clipboard.writeText(command);
          vscode.window.showInformationMessage("Command copied to clipboard");
        }
      });
    return;
  }

  // Check for URLs in the action and offer to open in browser
  const urlMatch = action.match(/(https?:\/\/[^\s]+)/);
  if (urlMatch) {
    const url = urlMatch[1];
    vscode.window
      .showInformationMessage(action, "Open in Browser")
      .then((selected) => {
        if (selected === "Open in Browser") {
          vscode.env.openExternal(vscode.Uri.parse(url));
        }
      });
    return;
  }

  // Default: just show the suggestion text
  vscode.window.showInformationMessage(action);
}

/**
 * Shows a warning notification for JDK version mismatch (LSP newer than project target).
 *
 * This warning allows users to:
 * - Configure Java to use a compatible JDK version
 * - Dismiss the warning for this session
 * - Permanently suppress the warning via settings
 *
 * @param warning The structured warning details
 * @param outputChannel Optional output channel for showing logs
 */
async function showJdkNewerWarning(
  warning: ProjectJdkNewerWarning,
  _outputChannel?: OutputChannel,
): Promise<void> {
  const vscode = await import("vscode");

  // Check if warning is suppressed
  const config = vscode.workspace.getConfiguration("groovy");
  const suppressWarning = config.get<boolean>(
    "jdk.suppressMismatchWarning",
    false,
  );

  if (suppressWarning) {
    // Warning is suppressed, just log it
    console.log(
      `JDK mismatch warning suppressed: LSP JDK ${warning.runningJdkVersion} > project target ${warning.targetJdkVersion}`,
    );
    return;
  }

  const message =
    `LSP JDK ${warning.runningJdkVersion} is newer than project target ${warning.targetJdkVersion} ` +
    `(from ${warning.configurationSource}). You may see 'Unsupported class file major version' errors.`;

  void (async () => {
    try {
      const selected = await vscode.window.showWarningMessage(
        message,
        CONFIGURE_JAVA,
        DISMISS,
        DONT_SHOW_AGAIN,
      );

      if (selected === CONFIGURE_JAVA) {
        // Open JDK picker with target version as preferred
        await vscode.commands.executeCommand(
          "groovy.configureJava",
          warning.targetJdkVersion,
        );
      } else if (selected === DONT_SHOW_AGAIN) {
        // Set workspace setting to suppress future warnings
        await config.update(
          "jdk.suppressMismatchWarning",
          true,
          vscode.ConfigurationTarget.Workspace,
        );
        vscode.window.showInformationMessage(
          "JDK version mismatch warnings will be suppressed for this workspace. " +
            "You can re-enable them in settings: groovy.jdk.suppressMismatchWarning",
        );
      }
      // DISMISS does nothing - just closes the notification
    } catch (err: unknown) {
      console.error("Error showing JDK warning notification:", err);
    }
  })();
}
