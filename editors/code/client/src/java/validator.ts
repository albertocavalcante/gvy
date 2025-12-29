import { workspace, window, commands } from 'vscode';
import { findJava, findJavaSync, JavaResolution, MINIMUM_JAVA_VERSION } from './finder';

export interface JavaValidationResult {
    isValid: boolean;
    resolution?: JavaResolution;
    error?: string;
}

/**
 * Validates Java installation and version compatibility using jdk-utils.
 * Falls back to login shell for lazy-loading patterns (SDKMAN, etc.)
 */
export async function validateJava(): Promise<JavaValidationResult> {
    try {
        const resolution = await findJava();

        if (!resolution) {
            return {
                isValid: false,
                error: 'No Java installation found'
            };
        }

        if (resolution.version < MINIMUM_JAVA_VERSION) {
            return {
                isValid: false,
                resolution,
                error: `Java ${MINIMUM_JAVA_VERSION}+ required, found Java ${resolution.version}`
            };
        }

        return {
            isValid: true,
            resolution
        };
    } catch (error) {
        return {
            isValid: false,
            error: `Failed to validate Java: ${error instanceof Error ? error.message : 'Unknown error'}`
        };
    }
}

/**
 * Gets the Java executable path for starting the language server.
 * Returns the path from resolution if available, otherwise falls back to sync finder.
 */
export function getJavaExecutable(resolution?: JavaResolution): string {
    if (resolution?.path) {
        const executableFile = process.platform === 'win32' ? 'java.exe' : 'java';
        return `${resolution.path}/bin/${executableFile}`;
    }
    return findJavaSync();
}

/**
 * Shows appropriate error message for Java validation failure with platform-specific guidance.
 */
export async function showJavaError(result: JavaValidationResult): Promise<void> {
    if (result.isValid) return;

    const resolution = result.resolution;
    const platform = process.platform;

    // Special case: Java found via login shell but won't work in non-interactive context
    if (resolution?.source === 'login_shell') {
        await showLoginShellMessage(resolution);
        return;
    }

    // Version too low
    if (resolution && resolution.version < MINIMUM_JAVA_VERSION) {
        await showVersionError(resolution, platform);
        return;
    }

    // Configured path is invalid
    const settingsJavaHome = workspace.getConfiguration('groovy').get<string>('java.home');
    if (settingsJavaHome && result.error) {
        await showConfiguredPathError(settingsJavaHome, result.error);
        return;
    }

    // Java not found at all
    await showNotFoundError(platform);
}

/**
 * Shows message when Java was found via login shell (SDKMAN lazy-loading, etc.)
 */
async function showLoginShellMessage(resolution: JavaResolution): Promise<void> {
    const message = `Java ${resolution.version} found, but requires shell initialization`;

    const detail = [
        'Java is managed by a tool (like SDKMAN) that requires shell setup.',
        'VS Code cannot access it directly without configuration.',
        '',
        'To fix this permanently, click "Use This Path" to set:',
        `  groovy.java.home = "${resolution.path}"`
    ].join('\n');

    const selection = await window.showWarningMessage(
        message,
        { modal: false, detail },
        'Use This Path',
        'Open Settings'
    );

    if (selection === 'Use This Path') {
        await workspace.getConfiguration('groovy').update('java.home', resolution.path, true);
        const reloadSelection = await window.showInformationMessage(
            `groovy.java.home set to "${resolution.path}". Reload window to apply?`,
            'Reload Now',
            'Later'
        );
        if (reloadSelection === 'Reload Now') {
            commands.executeCommand('workbench.action.reloadWindow');
        }
    } else if (selection === 'Open Settings') {
        commands.executeCommand('workbench.action.openSettings', 'groovy.java.home');
    }
}

/**
 * Shows error when Java version is too low
 */
async function showVersionError(resolution: JavaResolution, platform: string): Promise<void> {
    const message = `Java ${resolution.version} found, but Java ${MINIMUM_JAVA_VERSION}+ is required`;

    const installHints = getInstallHints(platform);
    const sourceHint = getSourceHint(resolution);

    const detail = [
        sourceHint,
        '',
        'Update your Java installation:',
        ...installHints,
        '',
        `Or configure a different Java in Settings → groovy.java.home`
    ].join('\n');

    const selection = await window.showErrorMessage(
        message,
        { modal: false, detail },
        'Download Java',
        'Open Settings'
    );

    handleErrorAction(selection);
}

/**
 * Shows error when configured groovy.java.home path is invalid
 */
async function showConfiguredPathError(configuredPath: string, error: string): Promise<void> {
    const message = `The configured Java path is invalid`;

    const detail = [
        `groovy.java.home is set to: ${configuredPath}`,
        '',
        `Error: ${error}`,
        '',
        'Please verify the path points to a valid JDK installation,',
        'or remove the setting to use auto-detection.'
    ].join('\n');

    const selection = await window.showErrorMessage(
        message,
        { modal: false, detail },
        'Open Settings',
        'Download Java'
    );

    handleErrorAction(selection);
}

/**
 * Shows error when no Java installation is found
 */
async function showNotFoundError(platform: string): Promise<void> {
    const message = `Java ${MINIMUM_JAVA_VERSION}+ is required but could not be found`;

    const installHints = getInstallHints(platform);

    const detail = [
        'Install Java using one of these methods:',
        ...installHints,
        '',
        'After installing, either:',
        '  • Restart VS Code, or',
        '  • Set the path in Settings → groovy.java.home'
    ].join('\n');

    const selection = await window.showErrorMessage(
        message,
        { modal: false, detail },
        'Download Java',
        'Open Settings'
    );

    handleErrorAction(selection);
}

/**
 * Returns platform-specific installation hints
 */
function getInstallHints(platform: string): string[] {
    switch (platform) {
        case 'darwin':
            return [
                '  • Homebrew: brew install --cask temurin',
                '  • SDKMAN: sdk install java 21-tem',
                '  • Download from adoptium.net'
            ];
        case 'linux':
            return [
                '  • apt: sudo apt install openjdk-21-jdk',
                '  • dnf: sudo dnf install java-21-openjdk',
                '  • SDKMAN: sdk install java 21-tem',
                '  • Download from adoptium.net'
            ];
        case 'win32':
            return [
                '  • winget: winget install EclipseAdoptium.Temurin.21.JDK',
                '  • choco: choco install temurin21',
                '  • Download from adoptium.net'
            ];
        default:
            return ['  • Download from adoptium.net'];
    }
}

/**
 * Returns a hint about where the Java was found
 */
function getSourceHint(resolution: JavaResolution): string {
    switch (resolution.source) {
        case 'setting':
            return `Found via groovy.java.home setting: ${resolution.path}`;
        case 'java_home':
            return `Found via JAVA_HOME: ${resolution.path}`;
        case 'jdk_manager':
            return `Found via JDK manager (SDKMAN/jEnv/etc.): ${resolution.path}`;
        case 'login_shell':
            return `Found via shell initialization: ${resolution.path}`;
        case 'system':
        default:
            return `Found on system: ${resolution.path}`;
    }
}

/**
 * Handles common error action buttons
 */
function handleErrorAction(selection: string | undefined): void {
    if (selection === 'Open Settings') {
        commands.executeCommand('workbench.action.openSettings', 'groovy.java.home');
    } else if (selection === 'Download Java') {
        commands.executeCommand('vscode.open', 'https://adoptium.net/');
    }
}
