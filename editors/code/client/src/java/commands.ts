/**
 * Java-related VS Code commands for the Groovy extension.
 *
 * These commands help users configure Java for Gradle projects,
 * especially when encountering toolchain provisioning errors.
 *
 * TODO(tech-debt): This module has responsibilities that should ideally be
 * handled by the build tool layer (e.g., via BSP - Build Server Protocol).
 * The foojay plugin management and JDK detection for toolchain resolution
 * are build tool concerns. Consider refactoring once BSP support is implemented.
 * Track: https://github.com/albertocavalcante/gvy/issues/785
 */

import * as vscode from "vscode";
import {
  findAllJdks,
  MINIMUM_JAVA_VERSION,
  JavaResolutionExtended,
} from "./finder";

// Foojay resolver plugin constants
const FOOJAY_PLUGIN_ID = "org.gradle.toolchains.foojay-resolver-convention";
const FOOJAY_PLUGIN_VERSION = "0.9.0";

// Pre-compiled regex patterns for detecting foojay plugin in settings files
const FOOJAY_PLUGIN_ID_ESCAPED = FOOJAY_PLUGIN_ID.replace(/\./g, "\\.");
const GROOVY_PLUGIN_PATTERN = new RegExp(
  `plugins\\s*\\{[\\s\\S]*?id\\s+['"]${FOOJAY_PLUGIN_ID_ESCAPED}['"]`,
);
const KOTLIN_PLUGIN_PATTERN = new RegExp(
  `plugins\\s*\\{[\\s\\S]*?id\\s*\\(\\s*["']${FOOJAY_PLUGIN_ID_ESCAPED}["']\\s*\\)`,
);

/**
 * Command: groovy.detectAndSetJavaHome
 *
 * Detects all installed JDKs and presents a picker for the user to select one.
 * Sets the selected JDK path in the groovy.java.home setting.
 *
 * @param requiredVersion Optional version to prioritize (e.g., from toolchain error)
 * @returns true if a JDK was selected and configured, false otherwise
 */
export async function detectAndSetJavaHome(
  requiredVersion?: number,
): Promise<boolean> {
  return vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: "Detecting installed JDKs...",
      cancellable: false,
    },
    async () => {
      // Find all JDKs, prioritizing the required version
      const jdks = await findAllJdks(undefined, requiredVersion);

      if (jdks.length === 0) {
        await showNoJdksFoundMessage(requiredVersion);
        return false;
      }

      // Build QuickPick items
      const items = buildQuickPickItems(jdks, requiredVersion);

      // Show picker
      const selected = await vscode.window.showQuickPick(items, {
        placeHolder: requiredVersion
          ? `Select a JDK (Java ${requiredVersion} recommended)`
          : "Select a JDK to use for Groovy projects",
        title: "Select Java Home",
      });

      if (!selected || selected.kind === vscode.QuickPickItemKind.Separator) {
        return false;
      }

      // Type guard: after excluding separator, we know jdk exists
      const jdk = selected.jdk;
      if (!jdk) {
        return false;
      }

      // Set the selected JDK
      const config = vscode.workspace.getConfiguration("groovy");
      await config.update(
        "java.home",
        jdk.path,
        vscode.ConfigurationTarget.Global,
      );

      // Show success message
      const restartAction = "Restart Server";
      const result = await vscode.window.showInformationMessage(
        `Java home set to Java ${jdk.version} (${jdk.path}). Restart the server to apply changes.`,
        restartAction,
      );

      if (result === restartAction) {
        await vscode.commands.executeCommand("groovy.restartServer");
      }

      return true;
    },
  );
}

interface JdkQuickPickItem extends vscode.QuickPickItem {
  jdk: JavaResolutionExtended;
  kind?: undefined;
}

interface SeparatorItem extends vscode.QuickPickItem {
  kind: vscode.QuickPickItemKind.Separator;
  jdk?: undefined;
}

type QuickPickItem = JdkQuickPickItem | SeparatorItem;

/**
 * Builds QuickPick items from found JDKs.
 */
function buildQuickPickItems(
  jdks: JavaResolutionExtended[],
  requiredVersion?: number,
): QuickPickItem[] {
  const items: QuickPickItem[] = [];

  // Group by: recommended (matches required version), compatible (>= 17), other
  const recommended: JavaResolutionExtended[] = [];
  const compatible: JavaResolutionExtended[] = [];
  const other: JavaResolutionExtended[] = [];

  for (const jdk of jdks) {
    if (requiredVersion && jdk.version === requiredVersion) {
      recommended.push(jdk);
    } else if (jdk.version >= MINIMUM_JAVA_VERSION) {
      compatible.push(jdk);
    } else {
      other.push(jdk);
    }
  }

  // Add recommended section
  if (recommended.length > 0) {
    items.push({
      label: "Recommended",
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of recommended) {
      items.push(createJdkItem(jdk, true));
    }
  }

  // Add compatible section
  if (compatible.length > 0) {
    if (items.length > 0) {
      items.push({
        label: "Compatible",
        kind: vscode.QuickPickItemKind.Separator,
      });
    }
    for (const jdk of compatible) {
      items.push(createJdkItem(jdk, false));
    }
  }

  // Add other section (incompatible JDKs)
  if (other.length > 0) {
    items.push({
      label: "Incompatible (Java < 17)",
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of other) {
      items.push(createJdkItem(jdk, false, true));
    }
  }

  return items;
}

/**
 * Creates a QuickPick item for a JDK.
 */
function createJdkItem(
  jdk: JavaResolutionExtended,
  isRecommended: boolean,
  isIncompatible: boolean = false,
): JdkQuickPickItem {
  const label = `$(coffee) Java ${jdk.version}${isRecommended ? " $(star-full)" : ""}`;
  const description = jdk.path;
  let detail = `From: ${jdk.sourceDescription}`;

  if (isIncompatible) {
    detail += " $(warning) Not compatible (Java 17+ required)";
  }

  return {
    label,
    description,
    detail,
    jdk,
  };
}

/**
 * Shows a message when no JDKs are found.
 */
async function showNoJdksFoundMessage(requiredVersion?: number): Promise<void> {
  const versionStr = requiredVersion ? `Java ${requiredVersion}` : "Java 17+";
  const installAction = "Install Instructions";
  const manualAction = "Set Manually";

  const result = await vscode.window.showWarningMessage(
    `No compatible JDKs found. ${versionStr} is required for this project.`,
    installAction,
    manualAction,
  );

  if (result === installAction) {
    // Open adoptium.net for JDK downloads
    await vscode.env.openExternal(
      vscode.Uri.parse("https://adoptium.net/temurin/releases/"),
    );
  } else if (result === manualAction) {
    // Open settings to groovy.java.home
    await vscode.commands.executeCommand(
      "workbench.action.openSettings",
      "groovy.java.home",
    );
  }
}

/**
 * Command: groovy.addFoojayResolver
 *
 * Adds the foojay-resolver plugin to settings.gradle(.kts) to enable
 * automatic JDK toolchain downloads.
 *
 * @returns true if the plugin was added, false otherwise
 */
export async function addFoojayResolver(): Promise<boolean> {
  // Find settings.gradle or settings.gradle.kts in workspace
  const workspaceFolders = vscode.workspace.workspaceFolders;
  if (!workspaceFolders || workspaceFolders.length === 0) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return false;
  }

  // Search for settings.gradle files
  const settingsFiles = await vscode.workspace.findFiles(
    "**/settings.gradle{,.kts}",
    "**/build/**",
    5,
  );

  if (settingsFiles.length === 0) {
    const createAction = "Create settings.gradle";
    const result = await vscode.window.showWarningMessage(
      "No settings.gradle file found in workspace.",
      createAction,
    );

    if (result === createAction) {
      return await createSettingsGradleWithFoojay(workspaceFolders[0].uri);
    }
    return false;
  }

  // If multiple settings.gradle files, let user pick
  let settingsUri: vscode.Uri;
  if (settingsFiles.length > 1) {
    const picked = await vscode.window.showQuickPick(
      settingsFiles.map((uri) => ({
        label: vscode.workspace.asRelativePath(uri),
        uri,
      })),
      { placeHolder: "Select settings.gradle file to modify" },
    );
    if (!picked) return false;
    settingsUri = picked.uri;
  } else {
    settingsUri = settingsFiles[0];
  }

  return await insertFoojayPlugin(settingsUri);
}

/**
 * Creates a new settings.gradle file with foojay-resolver plugin.
 */
async function createSettingsGradleWithFoojay(
  workspaceUri: vscode.Uri,
): Promise<boolean> {
  const settingsPath = vscode.Uri.joinPath(workspaceUri, "settings.gradle");
  const workspaceFolder = vscode.workspace.getWorkspaceFolder(workspaceUri);
  const rootProjectName = workspaceFolder?.name || "project";

  const content = `plugins {
    id '${FOOJAY_PLUGIN_ID}' version '${FOOJAY_PLUGIN_VERSION}'
}

rootProject.name = '${rootProjectName}'
`;

  const edit = new vscode.WorkspaceEdit();
  edit.createFile(settingsPath, { ignoreIfExists: true });
  edit.insert(settingsPath, new vscode.Position(0, 0), content);

  const success = await vscode.workspace.applyEdit(edit);
  if (success) {
    await vscode.window.showTextDocument(settingsPath);
    vscode.window.showInformationMessage(
      "Created settings.gradle with foojay-resolver plugin for automatic JDK downloads.",
    );
  }
  return success;
}

/**
 * Inserts the foojay-resolver plugin into an existing settings.gradle file.
 */
async function insertFoojayPlugin(settingsUri: vscode.Uri): Promise<boolean> {
  const document = await vscode.workspace.openTextDocument(settingsUri);
  const text = document.getText();
  const isKotlin = settingsUri.path.endsWith(".kts");

  // Check if foojay resolver plugin is already declared in a plugins block
  if (GROOVY_PLUGIN_PATTERN.test(text) || KOTLIN_PLUGIN_PATTERN.test(text)) {
    vscode.window.showInformationMessage(
      "The foojay-resolver plugin is already configured in this file.",
    );
    return true;
  }

  // Build the plugin block
  const pluginLine = isKotlin
    ? `    id("${FOOJAY_PLUGIN_ID}") version "${FOOJAY_PLUGIN_VERSION}"`
    : `    id '${FOOJAY_PLUGIN_ID}' version '${FOOJAY_PLUGIN_VERSION}'`;

  const edit = new vscode.WorkspaceEdit();

  // Check if there's an existing plugins block
  const pluginsBlockMatch = text.match(/plugins\s*\{/);

  if (pluginsBlockMatch && pluginsBlockMatch.index !== undefined) {
    // Insert into existing plugins block
    const insertPos = pluginsBlockMatch.index + pluginsBlockMatch[0].length;
    const position = document.positionAt(insertPos);
    edit.insert(settingsUri, position, `\n${pluginLine}`);
  } else {
    // Add new plugins block at the beginning
    const pluginsBlock = isKotlin
      ? `plugins {\n${pluginLine}\n}\n\n`
      : `plugins {\n${pluginLine}\n}\n\n`;
    edit.insert(settingsUri, new vscode.Position(0, 0), pluginsBlock);
  }

  const success = await vscode.workspace.applyEdit(edit);
  if (success) {
    await vscode.window.showTextDocument(document);
    const saveAction = "Save File";
    const result = await vscode.window.showInformationMessage(
      "Added foojay-resolver plugin for automatic JDK toolchain downloads.",
      saveAction,
    );
    if (result === saveAction) {
      await document.save();
    }
  }
  return success;
}

/**
 * Registers all Java-related commands with VS Code.
 */
export function registerJavaCommands(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.commands.registerCommand(
      "groovy.detectAndSetJavaHome",
      (requiredVersion?: number) => detectAndSetJavaHome(requiredVersion),
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.addFoojayResolver", () =>
      addFoojayResolver(),
    ),
  );
}
