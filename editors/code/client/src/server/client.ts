import * as vscode from 'vscode';
import { workspace, ExtensionContext, window, commands } from 'vscode';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    State
} from 'vscode-languageclient/node';
import { validateJava, showJavaError, getJavaExecutable } from '../java/validator';
import { setClient, getStatusBarManager } from '../ui/statusBar';
import { getConfiguration } from '../configuration/settings';
import { ServerResolver } from '../services/ServerResolver';

/**
 * Jenkins shared library configuration
 */
interface JenkinsSharedLibrary {
    name: string;
    jar: string;
    sourcesJar?: string;
}

/**
 * LSP Server initialization options.
 *
 * The Groovy LSP expects a flat map of string keys to values (not a nested object).
 * See groovy-lsp `ServerConfiguration.fromMap(...)` / `JenkinsConfiguration.fromMap(...)`.
 */
type LspInitializationOptions = Record<string, unknown>;

let client: LanguageClient | undefined;
let context: ExtensionContext | undefined;

export function initializeClient(extensionContext: ExtensionContext) {
    context = extensionContext;
}

/**
 * Gets the current Language Client instance
 */
export function getClient(): LanguageClient | undefined {
    return client;
}

/**
 * Gets the path to the Groovy Language Server JAR
 */
async function getServerJarPath(): Promise<string> {
    if (!context) {
        throw new Error('Extension context not initialized');
    }

    const config = getConfiguration();
    const resolver = new ServerResolver();

    try {
        return await resolver.resolve(context, { serverPath: config.serverPath });
    } catch (error) {
        const message =
            `Groovy Language Server JAR not found.\n\n` +
            `The JAR should be automatically downloaded during extension installation. ` +
            `If you have a custom path configured, please verify it exists. ` +
            `If this error persists, please report it as an issue.`;

        window.showErrorMessage(message, 'Open Issues').then(selection => {
            if (selection === 'Open Issues') {
                commands.executeCommand('vscode.open', 'https://github.com/albertocavalcante/gvy/issues');
            }
        });

        throw error;
    }
}

/**
 * Creates server options for launching the Groovy Language Server
 */
async function createServerOptions(): Promise<ServerOptions> {
    const jarPath = await getServerJarPath();
    const javaValidation = await validateJava();

    if (!javaValidation.isValid) {
        await showJavaError(javaValidation);
        throw new Error(`Java validation failed: ${javaValidation.error}`);
    }

    const javaExecutable = getJavaExecutable(javaValidation.resolution);

    // Server launch options
    const serverOptions: ServerOptions = {
        run: {
            command: javaExecutable,
            args: [
                '-jar',
                jarPath
            ],
            options: {
                env: process.env,
                cwd: workspace.workspaceFolders?.[0]?.uri.fsPath
            }
        },
        debug: {
            command: javaExecutable,
            args: [
                '-jar',
                jarPath
            ],
            options: {
                env: process.env,
                cwd: workspace.workspaceFolders?.[0]?.uri.fsPath
            }
        }
    };

    return serverOptions;
}

/**
 * Builds a flat settings map for the Groovy LSP server.
 *
 * Note: VS Code settings are namespaced under `groovy.*`, but the Groovy LSP uses:
 * - `groovy.*` keys for core server settings
 * - `jenkins.*` keys for Jenkins configuration
 */
export function buildServerSettingsMap(): Record<string, unknown> {
    const config = workspace.getConfiguration('groovy');

    const settings: Record<string, unknown> = {
        'groovy.server.maxNumberOfProblems': config.get<number>('server.maxNumberOfProblems', 100),
        'groovy.trace.server': config.get<'off' | 'messages' | 'verbose'>('trace.server', 'off'),

        'groovy.compilation.mode': config.get<'workspace' | 'single-file'>('compilation.mode', 'workspace'),
        'groovy.compilation.incrementalThreshold': config.get<number>('compilation.incrementalThreshold', 50),
        'groovy.compilation.maxWorkspaceFiles': config.get<number>('compilation.maxWorkspaceFiles', 500),

        'groovy.repl.enabled': config.get<boolean>('repl.enabled', true),
        'groovy.repl.maxSessions': config.get<number>('repl.maxSessions', 10),
        'groovy.repl.sessionTimeoutMinutes': config.get<number>('repl.sessionTimeoutMinutes', 60),

        'groovy.codenarc.enabled': config.get<boolean>('codenarc.enabled', true),
        'groovy.codenarc.autoDetect': config.get<boolean>('codenarc.autoDetect', true),
        'groovy.format.enable': config.get<boolean>('format.enable', true),

        'jenkins.filePatterns': config.get<string[]>('jenkins.filePatterns', ['Jenkinsfile', 'vars/*.groovy']),
        'jenkins.sharedLibraries': config.get<JenkinsSharedLibrary[]>('jenkins.sharedLibraries', []),
        'jenkins.gdslPaths': config.get<string[]>('jenkins.gdslPaths', []),
        'jenkins.gdslExecution.enabled': config.get<boolean>('jenkins.gdslExecution.enabled', false),
        'jenkins.plugins': config.get<string[]>('jenkins.plugins', []),
        'jenkins.includeDefaultPlugins': config.get<boolean>('jenkins.includeDefaultPlugins', true),
    };

    const javaHome = config.get<string>('java.home');
    if (typeof javaHome === 'string' && javaHome.trim().length > 0) {
        settings['groovy.java.home'] = javaHome;
    }

    const codeNarcPropertiesFile = config.get<string>('codenarc.propertiesFile');
    if (typeof codeNarcPropertiesFile === 'string' && codeNarcPropertiesFile.trim().length > 0) {
        settings['groovy.codenarc.propertiesFile'] = codeNarcPropertiesFile;
    }

    const pluginsTxtPath = config.get<string>('jenkins.pluginsTxtPath');
    if (typeof pluginsTxtPath === 'string' && pluginsTxtPath.trim().length > 0) {
        settings['jenkins.pluginsTxtPath'] = pluginsTxtPath;
    }

    return settings;
}

/**
 * Builds initialization options from workspace configuration
 */
function buildInitializationOptions(): LspInitializationOptions {
    return buildServerSettingsMap();
}

function createClientOptions(outputChannel?: vscode.OutputChannel): LanguageClientOptions {
    const clientOptions: LanguageClientOptions = {
        // Register the server for Groovy and Jenkinsfile documents
        documentSelector: [
            { scheme: 'file', language: 'groovy' },
            { scheme: 'file', language: 'jenkinsfile' },
            { scheme: 'untitled', language: 'groovy' },
            { scheme: 'untitled', language: 'jenkinsfile' }
        ],
        synchronize: {
            // Notify the server about file changes to Groovy files in the workspace
            fileEvents: workspace.createFileSystemWatcher('**/*.{groovy,gvy,gy,gsh,gradle,Jenkinsfile}')
        },
        initializationOptions: buildInitializationOptions(),
        outputChannel: outputChannel,
        outputChannelName: outputChannel ? undefined : 'Groovy Language Server',
        traceOutputChannel: workspace.getConfiguration('groovy').get('trace.server') !== 'off'
            ? window.createOutputChannel('Groovy Language Server Trace')
            : undefined,
        // URI converters for Windows compatibility
        uriConverters: {
            code2Protocol: (value) => {
                if (/^win32/.test(process.platform)) {
                    // Drive letters on Windows are encoded with %3A instead of :
                    // but Java doesn't treat them the same
                    return value.toString().replace("%3A", ":");
                }
                return value.toString();
            },
            protocol2Code: (value) => vscode.Uri.parse(value)
        }
    };

    return clientOptions;
}

/**
 * Starts the Language Client
 */
export async function startClient(outputChannel?: vscode.OutputChannel): Promise<void> {
    if (client && client.state === State.Running) {
        return; // Already running
    }

    try {
        const serverOptions = await createServerOptions();
        const clientOptions = createClientOptions(outputChannel);

        client = new LanguageClient(
            'groovyLanguageServer',
            'Groovy Language Server',
            serverOptions,
            clientOptions
        );

        // Update status bar with new client
        setClient(client);

        if (outputChannel) {
            getStatusBarManager()?.setOutputChannel(outputChannel);
        }

        await client.start();
        console.log('Groovy Language Server started successfully');

    } catch (error) {
        const message = `Failed to start Groovy Language Server: ${error instanceof Error ? error.message : 'Unknown error'}`;
        console.error(message);
        window.showErrorMessage(message);

        // Update status bar to reflect failure
        setClient(undefined);
        throw error;
    }
}

/**
 * Stops the Language Client
 */
export async function stopClient(): Promise<void> {
    if (!client) {
        return;
    }

    try {
        if (client.state === State.Running) {
            await client.stop();
        }
        client = undefined;
        setClient(undefined);
        console.log('Groovy Language Server stopped');
    } catch (error) {
        console.error('Error stopping Language Server:', error);
        client = undefined;
        setClient(undefined);
    }
}

/**
 * Restarts the Language Client
 */
export async function restartClient(): Promise<void> {
    try {
        await stopClient();
        await startClient();
        window.showInformationMessage('Groovy Language Server restarted successfully');
    } catch (error) {
        const message = `Failed to restart Groovy Language Server: ${error instanceof Error ? error.message : 'Unknown error'}`;
        console.error(message);
        window.showErrorMessage(message);
    }
}
