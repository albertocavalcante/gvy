import { workspace, ConfigurationChangeEvent } from 'vscode';

export interface GroovyConfiguration {
    javaHome: string | undefined;
    traceServer: 'off' | 'messages' | 'verbose';
    maxNumberOfProblems: number;
    serverPath: string | undefined;
}

/**
 * Gets the current Groovy configuration
 */
export function getConfiguration(): GroovyConfiguration {
    const config = workspace.getConfiguration('groovy');

    return {
        javaHome: config.get<string>('java.home'),
        traceServer: config.get<'off' | 'messages' | 'verbose'>('trace.server', 'off'),
        maxNumberOfProblems: config.get<number>('server.maxNumberOfProblems', 100),
        serverPath: config.get<string>('server.path')
    };
}

/**
 * Checks if a configuration change affects the specified section
 */
export function affectsConfiguration(event: ConfigurationChangeEvent, section: string): boolean {
    return event.affectsConfiguration(`groovy.${section}`);
}

/**
 * Checks if a configuration change affects Java settings
 */
export function affectsJavaConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'java.home');
}

/**
 * Checks if a configuration change affects server settings that require a restart
 */
export function affectsServerConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'trace.server') ||
        affectsConfiguration(event, 'server.maxNumberOfProblems') ||
        affectsConfiguration(event, 'server.path');
}

/**
 * Checks if a configuration change affects compilation settings
 */
export function affectsCompilationConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'compilation.mode') ||
        affectsConfiguration(event, 'compilation.incrementalThreshold') ||
        affectsConfiguration(event, 'compilation.maxWorkspaceFiles');
}

/**
 * Checks if a configuration change affects CodeNarc settings
 */
export function affectsCodeNarcConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'codenarc.enabled') ||
        affectsConfiguration(event, 'codenarc.propertiesFile') ||
        affectsConfiguration(event, 'codenarc.autoDetect');
}

/**
 * Checks if a configuration change affects Jenkins settings
 */
export function affectsJenkinsConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'jenkins.filePatterns') ||
        affectsConfiguration(event, 'jenkins.sharedLibraries') ||
        affectsConfiguration(event, 'jenkins.gdslPaths') ||
        affectsConfiguration(event, 'jenkins.gdslExecution.enabled') ||
        affectsConfiguration(event, 'jenkins.pluginsTxtPath') ||
        affectsConfiguration(event, 'jenkins.plugins') ||
        affectsConfiguration(event, 'jenkins.includeDefaultPlugins');
}

/**
 * Checks if a configuration change affects REPL settings
 */
export function affectsReplConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'repl.enabled') ||
        affectsConfiguration(event, 'repl.maxSessions') ||
        affectsConfiguration(event, 'repl.sessionTimeoutMinutes');
}

/**
 * Checks if a configuration change requires a server restart
 * These are critical settings that need a full restart to take effect
 */
export function requiresServerRestart(event: ConfigurationChangeEvent): boolean {
    return affectsJavaConfiguration(event) ||
        affectsConfiguration(event, 'server.path') ||
        affectsConfiguration(event, 'compilation.mode') ||
        affectsReplConfiguration(event);
}

/**
 * Checks if a configuration change can be applied dynamically via didChangeConfiguration
 * These settings can be updated without restarting the server
 */
export function canBeAppliedDynamically(event: ConfigurationChangeEvent): boolean {
    return affectsJenkinsConfiguration(event) ||
        affectsCodeNarcConfiguration(event) ||
        affectsConfiguration(event, 'format.enable') ||
        affectsConfiguration(event, 'server.maxNumberOfProblems') ||
        affectsConfiguration(event, 'trace.server') ||
        affectsConfiguration(event, 'compilation.incrementalThreshold') ||
        affectsConfiguration(event, 'compilation.maxWorkspaceFiles') ||
        affectsUpdateConfiguration(event);
}

/**
 * Update notification level
 */
export type UpdateNotificationLevel = 'off' | 'onlyWhenOutdated' | 'always';

/**
 * Update configuration
 */
export interface UpdateConfiguration {
    checkOnStartup: boolean;
    checkIntervalHours: number;
    notifications: UpdateNotificationLevel;
}

/**
 * Gets the update configuration
 */
export function getUpdateConfiguration(): UpdateConfiguration {
    const config = workspace.getConfiguration('groovy');

    return {
        checkOnStartup: config.get<boolean>('update.checkOnStartup', true),
        checkIntervalHours: Math.max(1, config.get<number>('update.checkIntervalHours', 24)),
        notifications: config.get<UpdateNotificationLevel>('update.notifications', 'onlyWhenOutdated')
    };
}

/**
 * Checks if a configuration change affects update settings
 */
export function affectsUpdateConfiguration(event: ConfigurationChangeEvent): boolean {
    return affectsConfiguration(event, 'update.checkOnStartup') ||
        affectsConfiguration(event, 'update.checkIntervalHours') ||
        affectsConfiguration(event, 'update.notifications');
}
