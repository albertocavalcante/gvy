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
const FOOJAY_PLUGIN_VERSION = "1.0.0";

// Pre-compiled regex patterns for detecting foojay plugin in settings files
const FOOJAY_PLUGIN_ID_ESCAPED = FOOJAY_PLUGIN_ID.replace(/\./g, "\\.");
const GROOVY_PLUGIN_PATTERN = new RegExp(
  `plugins\\s*\\{[\\s\\S]*?id\\s+['"]${FOOJAY_PLUGIN_ID_ESCAPED}['"]`,
);
const KOTLIN_PLUGIN_PATTERN = new RegExp(
  `plugins\\s*\\{[\\s\\S]*?id\\s*\\(\\s*["']${FOOJAY_PLUGIN_ID_ESCAPED}["']\\s*\\)`,
);

/**
 * Command: groovy.configureJava
 *
 * Main entry point for Java configuration. Shows a purpose picker to let the user
 * choose between configuring LSP Runtime (Java 17+ for language server) or
 * Project Build JDK (for Maven/Gradle test execution).
 *
 * @param requiredVersion Optional version to prioritize (e.g., from toolchain error)
 * @param purpose Optional purpose to skip picker ("lsp" or "project")
 * @returns true if a JDK was selected and configured, false otherwise
 */
export async function configureJava(
  requiredVersion?: number,
  purpose?: "lsp" | "project",
): Promise<boolean> {
  if (!purpose) {
    const picked = await vscode.window.showQuickPick(
      [
        {
          label: "$(server) Language Server Runtime",
          description: "Java for running the Groovy LSP (requires 17+)",
          detail: "Sets groovy.languageServer.javaHome",
          purpose: "lsp" as const,
        },
        {
          label: "$(tools) Project Build JDK",
          description: "Java for Maven/Gradle test and build execution",
          detail: "Sets groovy.project.javaHome",
          purpose: "project" as const,
        },
      ],
      {
        title: "Configure Java Runtime",
        placeHolder: "What do you want to configure?",
      },
    );

    if (!picked) return false;
    purpose = picked.purpose;
  }

  if (purpose === "lsp") {
    return await configureLspJava(requiredVersion);
  } else {
    return await configureProjectJava(requiredVersion);
  }
}

/**
 * Configures the Language Server runtime Java.
 * Shows a picker with detected JDKs and allows manual path selection.
 * Sets the selected JDK path in workspace settings (groovy.languageServer.javaHome).
 *
 * @param requiredVersion Optional version to prioritize (e.g., from toolchain error)
 * @returns true if a JDK was selected and configured, false otherwise
 */
async function configureLspJava(requiredVersion?: number): Promise<boolean> {
  // Find all JDKs with progress indicator
  const jdks = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: "Detecting installed JDKs...",
      cancellable: false,
    },
    async () => findAllJdks(undefined, requiredVersion),
  );

  // Build QuickPick items (includes Browse option even if no JDKs found)
  const items = buildQuickPickItems(jdks, requiredVersion);

  // Show picker with clear messaging about LSP requirements
  const selected = await vscode.window.showQuickPick(items, {
    placeHolder: requiredVersion
      ? `Select a JDK (LSP needs 17+, project targets Java ${requiredVersion})`
      : `Select a JDK for the Groovy Language Server (requires Java ${MINIMUM_JAVA_VERSION}+)`,
    title: "Configure Language Server Runtime",
  });

  if (!selected || selected.kind === vscode.QuickPickItemKind.Separator) {
    return false;
  }

  // Handle browse action
  if (selected.action === "browse") {
    return await browseAndSetLspJavaHome();
  }

  // Handle JDK selection
  const jdk = selected.jdk;
  if (!jdk) {
    return false;
  }

  return await setLspJavaHomeAndRestart(jdk.path, jdk.version);
}

/**
 * Configures the Project Build JDK.
 * Shows all detected JDKs without LSP filtering - any JDK version can be used
 * for project builds. Sets groovy.project.javaHome in workspace settings.
 *
 * @param minimumVersion Optional minimum version required by the project
 * @returns true if a JDK was selected and configured, false otherwise
 */
async function configureProjectJava(minimumVersion?: number): Promise<boolean> {
  const jdks = await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: "Detecting JDKs...",
    },
    () => findAllJdks(),
  );

  const items = buildProjectJdkItems(jdks, minimumVersion);

  const selected = await vscode.window.showQuickPick(items, {
    title: "Select Project Build JDK",
    placeHolder: minimumVersion
      ? `Project requires at least Java ${minimumVersion}`
      : "Select JDK for Maven/Gradle execution",
  });

  if (!selected || selected.kind === vscode.QuickPickItemKind.Separator) {
    return false;
  }

  // Handle browse action
  if (selected.action === "browse") {
    return await browseAndSetProjectJavaHome();
  }

  // Handle JDK selection
  const jdk = selected.jdk;
  if (!jdk) {
    return false;
  }

  return await setProjectJavaHome(jdk.path, jdk.version);
}

/**
 * Opens a folder browser for manual LSP JDK selection.
 * Validates the selected path is a valid JDK and warns if below minimum version.
 *
 * @returns true if a valid JDK was selected and configured, false otherwise
 */
async function browseAndSetLspJavaHome(): Promise<boolean> {
  const { getRuntime } = await import("jdk-utils");

  const selected = await vscode.window.showOpenDialog({
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
    openLabel: "Select JDK",
    title: "Select Language Server Java Home",
  });

  if (!selected || selected.length === 0) {
    return false;
  }

  const jdkPath = selected[0].fsPath;

  // Validate the selected path is a valid JDK
  try {
    const runtime = await getRuntime(jdkPath, { withVersion: true });
    if (!runtime?.version?.major) {
      vscode.window.showErrorMessage(
        `The selected folder is not a valid JDK: ${jdkPath}`,
      );
      return false;
    }

    // Warn if version is below minimum - LSP cannot run
    if (runtime.version.major < MINIMUM_JAVA_VERSION) {
      const proceed = await vscode.window.showWarningMessage(
        `Java ${runtime.version.major} cannot run the Groovy Language Server (requires Java ${MINIMUM_JAVA_VERSION}+). The LSP will fail to start. Continue anyway?`,
        "Yes, use anyway",
        "No, choose another",
      );
      if (proceed !== "Yes, use anyway") {
        return false;
      }
    }

    return await setLspJavaHomeAndRestart(
      runtime.homedir,
      runtime.version.major,
    );
  } catch (error) {
    vscode.window.showErrorMessage(
      `Failed to validate JDK at ${jdkPath}: ${error instanceof Error ? error.message : String(error)}`,
    );
    return false;
  }
}

/**
 * Opens a folder browser for manual Project Build JDK selection.
 * Validates the selected path is a valid JDK (any version).
 *
 * @returns true if a valid JDK was selected and configured, false otherwise
 */
async function browseAndSetProjectJavaHome(): Promise<boolean> {
  const { getRuntime } = await import("jdk-utils");

  const selected = await vscode.window.showOpenDialog({
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
    openLabel: "Select JDK",
    title: "Select Project Build Java Home",
  });

  if (!selected || selected.length === 0) {
    return false;
  }

  const jdkPath = selected[0].fsPath;

  // Validate the selected path is a valid JDK
  try {
    const runtime = await getRuntime(jdkPath, { withVersion: true });
    if (!runtime?.version?.major) {
      vscode.window.showErrorMessage(
        `The selected folder is not a valid JDK: ${jdkPath}`,
      );
      return false;
    }

    return await setProjectJavaHome(runtime.homedir, runtime.version.major);
  } catch (error) {
    vscode.window.showErrorMessage(
      `Failed to validate JDK at ${jdkPath}: ${error instanceof Error ? error.message : String(error)}`,
    );
    return false;
  }
}

/**
 * Sets the LSP Java home in workspace settings and offers to restart the server.
 * Updates groovy.languageServer.javaHome in .vscode/settings.json (workspace scope).
 *
 * @param jdkPath Absolute path to the JDK home directory
 * @param version Major Java version number
 * @returns true if settings were updated successfully
 */
async function setLspJavaHomeAndRestart(
  jdkPath: string,
  version: number,
): Promise<boolean> {
  const config = vscode.workspace.getConfiguration("groovy");
  await config.update(
    "languageServer.javaHome",
    jdkPath,
    vscode.ConfigurationTarget.Workspace,
  );

  const restartAction = "Restart Server";
  const result = await vscode.window.showInformationMessage(
    `Language Server Java set to Java ${version} in workspace settings. Restart the server to apply.`,
    restartAction,
  );

  if (result === restartAction) {
    await vscode.commands.executeCommand("groovy.restartServer");
  }

  return true;
}

/**
 * Sets the Project Build Java home in workspace settings.
 * Updates groovy.project.javaHome in .vscode/settings.json (workspace scope).
 *
 * @param jdkPath Absolute path to the JDK home directory
 * @param version Major Java version number
 * @returns true if settings were updated successfully
 */
async function setProjectJavaHome(
  jdkPath: string,
  version: number,
): Promise<boolean> {
  const config = vscode.workspace.getConfiguration("groovy");
  await config.update(
    "project.javaHome",
    jdkPath,
    vscode.ConfigurationTarget.Workspace,
  );

  vscode.window.showInformationMessage(
    `Project build JDK set to Java ${version}. Tests will use this JDK.`,
  );
  return true;
}

interface JdkQuickPickItem extends vscode.QuickPickItem {
  jdk: JavaResolutionExtended;
  kind?: undefined;
  action?: undefined;
}

interface SeparatorItem extends vscode.QuickPickItem {
  kind: vscode.QuickPickItemKind.Separator;
  jdk?: undefined;
  action?: undefined;
}

interface ActionItem extends vscode.QuickPickItem {
  action: "browse";
  jdk?: undefined;
  kind?: undefined;
}

type QuickPickItem = JdkQuickPickItem | SeparatorItem | ActionItem;

/**
 * Builds QuickPick items from found JDKs.
 * Groups JDKs into sections: Recommended (matches required version),
 * Compatible (>= Java 17), Incompatible (< Java 17).
 * Always includes a "Browse..." option at the end.
 *
 * @param jdks Array of detected JDKs
 * @param requiredVersion Optional version to mark as "Recommended"
 * @returns Array of QuickPickItem including separators and browse option
 */
function buildQuickPickItems(
  jdks: JavaResolutionExtended[],
  requiredVersion?: number,
): QuickPickItem[] {
  const items: QuickPickItem[] = [];

  // Group by:
  // - recommended: matches required version AND can run LSP (>= 17)
  // - compatible: can run LSP (>= 17) but doesn't match project version
  // - projectOnly: matches project version but cannot run LSP (< 17)
  // - incompatible: cannot run LSP and doesn't match project
  const recommended: JavaResolutionExtended[] = [];
  const compatible: JavaResolutionExtended[] = [];
  const projectOnly: JavaResolutionExtended[] = [];
  const incompatible: JavaResolutionExtended[] = [];

  for (const jdk of jdks) {
    const canRunLsp = jdk.version >= MINIMUM_JAVA_VERSION;
    const matchesProject = requiredVersion && jdk.version === requiredVersion;

    if (canRunLsp && matchesProject) {
      // Best case: can run LSP AND matches project
      recommended.push(jdk);
    } else if (canRunLsp) {
      // Can run LSP but doesn't match project
      compatible.push(jdk);
    } else if (matchesProject) {
      // Matches project but cannot run LSP
      projectOnly.push(jdk);
    } else {
      // Cannot run LSP and doesn't match project
      incompatible.push(jdk);
    }
  }

  // Add recommended section (matches project's required version AND is LSP compatible)
  // Note: recommended can only have items when requiredVersion is defined (see matchesProject check above)
  if (recommended.length > 0) {
    items.push({
      label: `Best Match (Java ${requiredVersion} - runs LSP & matches project)`,
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of recommended) {
      items.push(createJdkItem(jdk, true, false, requiredVersion));
    }
  }

  // Add compatible section (can run LSP, Java 17+)
  if (compatible.length > 0) {
    items.push({
      label: `LSP Compatible (Java ${MINIMUM_JAVA_VERSION}+)`,
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of compatible) {
      items.push(createJdkItem(jdk, false, false, requiredVersion));
    }
  }

  // Add project-only section (matches project but cannot run LSP)
  // Note: projectOnly can only have items when requiredVersion is defined (see matchesProject check above)
  if (projectOnly.length > 0) {
    items.push({
      label: `Project Target Only (Java ${requiredVersion} - cannot run LSP)`,
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of projectOnly) {
      items.push(createJdkItem(jdk, false, true, requiredVersion));
    }
  }

  // Add incompatible section (cannot run LSP and doesn't match project)
  if (incompatible.length > 0) {
    items.push({
      label: `Cannot Run LSP (Java < ${MINIMUM_JAVA_VERSION})`,
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of incompatible) {
      items.push(createJdkItem(jdk, false, true, requiredVersion));
    }
  }

  // Add browse option at the end
  if (items.length > 0) {
    items.push({
      label: "",
      kind: vscode.QuickPickItemKind.Separator,
    });
  }
  items.push({
    label: "$(folder) Browse...",
    detail: "Select a JDK folder manually",
    action: "browse",
  });

  return items;
}

/**
 * Creates a QuickPick item for a JDK with appropriate icons and labels.
 *
 * @param jdk The JDK to create an item for
 * @param isRecommended Whether to mark with a star icon
 * @param isIncompatible Whether to show incompatibility warning (cannot run LSP)
 * @param projectVersion Optional project target version for context
 * @returns Formatted QuickPickItem
 */
function createJdkItem(
  jdk: JavaResolutionExtended,
  isRecommended: boolean,
  isIncompatible: boolean = false,
  projectVersion?: number,
): JdkQuickPickItem {
  const label = `$(coffee) Java ${jdk.version}${isRecommended ? " $(star-full)" : ""}`;
  const description = jdk.path;
  let detail = `From: ${jdk.sourceDescription}`;

  if (isIncompatible) {
    detail += ` $(error) Cannot run LSP (requires Java ${MINIMUM_JAVA_VERSION}+)`;
  } else if (projectVersion && jdk.version > projectVersion) {
    // LSP compatible but newer than project target - show info
    detail += ` $(info) Newer than project target (Java ${projectVersion})`;
  }

  return {
    label,
    description,
    detail,
    jdk,
  };
}

/**
 * Builds QuickPick items for Project Build JDK selection.
 * Groups JDKs into two sections: those meeting minimum version (if specified)
 * and those below minimum. Shows all JDKs regardless of version since any
 * JDK can be used for project builds (unlike LSP which requires Java 17+).
 *
 * @param jdks Array of detected JDKs
 * @param minimumVersion Optional minimum version required by the project
 * @returns Array of QuickPickItem including separators and browse option
 */
function buildProjectJdkItems(
  jdks: JavaResolutionExtended[],
  minimumVersion?: number,
): QuickPickItem[] {
  const items: QuickPickItem[] = [];

  // Group JDKs: meets minimum vs below minimum
  const meetsMinimum = minimumVersion
    ? jdks.filter((j) => j.version >= minimumVersion)
    : jdks;
  const belowMinimum = minimumVersion
    ? jdks.filter((j) => j.version < minimumVersion)
    : [];

  if (meetsMinimum.length > 0) {
    if (minimumVersion) {
      items.push({
        label: `Meets Project Requirement (Java ${minimumVersion}+)`,
        kind: vscode.QuickPickItemKind.Separator,
      });
    }
    for (const jdk of meetsMinimum) {
      items.push({
        label: `$(coffee) Java ${jdk.version}`,
        description: jdk.path,
        detail: `From: ${jdk.sourceDescription}`,
        jdk,
      });
    }
  }

  if (belowMinimum.length > 0) {
    items.push({
      label: `Below Project Minimum (Java < ${minimumVersion})`,
      kind: vscode.QuickPickItemKind.Separator,
    });
    for (const jdk of belowMinimum) {
      items.push({
        label: `$(coffee) Java ${jdk.version} $(warning)`,
        description: jdk.path,
        detail: `From: ${jdk.sourceDescription} - Below project minimum`,
        jdk,
      });
    }
  }

  // Browse option
  if (items.length > 0) {
    items.push({ label: "", kind: vscode.QuickPickItemKind.Separator });
  }
  items.push({
    label: "$(folder) Browse...",
    detail: "Select a JDK folder manually",
    action: "browse",
  });

  return items;
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
 * Used when no settings.gradle exists in the workspace.
 *
 * @param workspaceUri URI of the workspace folder
 * @returns true if the file was created successfully
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
 * Handles both Groovy (.gradle) and Kotlin (.gradle.kts) syntax.
 * Adds to existing plugins block or creates a new one.
 *
 * @param settingsUri URI of the settings.gradle file
 * @returns true if the plugin was added successfully
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
      "groovy.configureJava",
      (requiredVersion?: number, purpose?: "lsp" | "project") =>
        configureJava(requiredVersion, purpose),
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.addFoojayResolver", () =>
      addFoojayResolver(),
    ),
  );
}
