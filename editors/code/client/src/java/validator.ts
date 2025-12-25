import { exec } from 'child_process';
import { promisify } from 'util';
import { workspace, window, commands } from 'vscode';
import { findJava } from './finder';

const execAsync = promisify(exec);

// Minimum supported Java version for Groovy Language Server
const MINIMUM_JAVA_VERSION = 17;

export interface JavaValidationResult {
    isValid: boolean;
    version?: number;
    path?: string;
    error?: string;
}

/**
 * Validates Java installation and version compatibility
 */
export async function validateJava(): Promise<JavaValidationResult> {
    const javaPath = findJava();

    try {
        const { stdout, stderr } = await execAsync(`"${javaPath}" -version`);
        const versionOutput = stdout || stderr;
        const versionMatch = versionOutput.match(/version "(\d+)(?:\.(\d+))?/);

        if (!versionMatch) {
            return {
                isValid: false,
                error: 'Failed to determine Java version'
            };
        }

        const majorVersion = parseInt(versionMatch[1], 10);

        if (isNaN(majorVersion) || majorVersion < MINIMUM_JAVA_VERSION) {
            return {
                isValid: false,
                version: majorVersion,
                path: javaPath,
                error: `Java ${MINIMUM_JAVA_VERSION}+ required, found Java ${majorVersion}`
            };
        }

        return {
            isValid: true,
            version: majorVersion,
            path: javaPath
        };

    } catch (error) {
        return {
            isValid: false,
            error: `Failed to execute Java: ${error instanceof Error ? error.message : 'Unknown error'}`
        };
    }
}

/**
 * Shows appropriate error message for Java validation failure
 */
export async function showJavaError(result: JavaValidationResult): Promise<void> {
    if (result.isValid) return;

    const settingsJavaHome = workspace.getConfiguration('groovy').get<string>('java.home');
    const openSettingsButtonText = 'Open Settings';
    const downloadJavaButtonText = 'Download Java';

    let message: string;
    const actions: string[] = [openSettingsButtonText];

    if (settingsJavaHome && result.error?.includes('Failed to execute')) {
        message = `The groovy.java.home setting does not point to a valid Java installation: ${result.error}`;
    } else if (result.version && result.version < MINIMUM_JAVA_VERSION) {
        message = `Groovy Language Server requires Java ${MINIMUM_JAVA_VERSION}+ but found Java ${result.version}. Please update your Java installation or configure a different Java path.`;
        actions.push(downloadJavaButtonText);
    } else {
        message = `Could not locate valid Java ${MINIMUM_JAVA_VERSION}+ installation. ${result.error || ''}`;
        actions.push(downloadJavaButtonText);
    }

    const selection = await window.showErrorMessage(message, ...actions);

    if (selection === openSettingsButtonText) {
        commands.executeCommand('workbench.action.openSettings', 'groovy.java.home');
    } else if (selection === downloadJavaButtonText) {
        commands.executeCommand('vscode.open', 'https://adoptium.net/');
    }
}