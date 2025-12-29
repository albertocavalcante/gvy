import * as path from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';
import { workspace } from 'vscode';
import { findRuntimes, getRuntime, getSources, IJavaRuntime } from 'jdk-utils';

const execAsync = promisify(exec);

// Minimum supported Java version for Groovy Language Server
export const MINIMUM_JAVA_VERSION = 17;

export type JavaSource = 'setting' | 'java_home' | 'jdk_manager' | 'system' | 'login_shell';

export interface JavaResolution {
    path: string;
    version: number;
    source: JavaSource;
}

/**
 * Finds Java installation from multiple sources using jdk-utils.
 * Falls back to login shell for lazy-loading shell functions (SDKMAN, etc.)
 */
export async function findJava(): Promise<JavaResolution | null> {
    // 1. Check groovy.java.home setting first (highest priority)
    const configuredHome = workspace.getConfiguration('groovy').get<string>('java.home');
    if (configuredHome) {
        const expandedPath = expandHomeDir(configuredHome);
        const runtime = await getRuntime(expandedPath, { withVersion: true });
        if (runtime?.version?.major && runtime.version.major >= MINIMUM_JAVA_VERSION) {
            return {
                path: runtime.homedir,
                version: runtime.version.major,
                source: 'setting'
            };
        }
        // Setting configured but invalid - we'll report this later
        if (runtime?.version?.major) {
            return {
                path: runtime.homedir,
                version: runtime.version.major,
                source: 'setting'
            };
        }
    }

    // 2. Use jdk-utils to scan JAVA_HOME, PATH, SDKMAN, jEnv, jabba, asdf, common paths
    try {
        const runtimes = await findRuntimes({ checkJavac: true, withVersion: true, withTags: true });
        const validRuntimes = runtimes.filter(r => r.version?.major && r.version.major >= MINIMUM_JAVA_VERSION);

        if (validRuntimes.length > 0) {
            // Sort by source priority (env vars > JDK managers > common paths)
            validRuntimes.sort((a, b) => getSourcePriority(a) - getSourcePriority(b));
            const best = validRuntimes[0];
            return {
                path: best.homedir,
                version: best.version!.major,
                source: categorizeSource(getSources(best))
            };
        }

        // Also check if there's any Java at all (even if version is too low)
        if (runtimes.length > 0) {
            const anyRuntime = runtimes[0];
            if (anyRuntime.version?.major) {
                return {
                    path: anyRuntime.homedir,
                    version: anyRuntime.version.major,
                    source: categorizeSource(getSources(anyRuntime))
                };
            }
        }
    } catch {
        // jdk-utils failed, continue to login shell fallback
    }

    // 3. Login shell fallback for lazy-loading shell functions (SDKMAN lazy init, etc.)
    const loginShellResult = await tryLoginShell();
    if (loginShellResult) {
        return { ...loginShellResult, source: 'login_shell' };
    }

    return null;
}

/**
 * Legacy function for backward compatibility - returns just the Java executable path
 */
export function findJavaSync(): string {
    const executableFile = process.platform === 'win32' ? 'java.exe' : 'java';

    // 1. Check configuration setting first
    const javaHome = workspace.getConfiguration('groovy').get<string>('java.home');
    if (javaHome) {
        const javaPath = path.join(expandHomeDir(javaHome), 'bin', executableFile);
        return javaPath;
    }

    // 2. Check JAVA_HOME environment variable
    const envJavaHome = process.env.JAVA_HOME;
    if (envJavaHome) {
        return path.join(envJavaHome, 'bin', executableFile);
    }

    // 3. Fallback to system PATH
    return 'java';
}

/**
 * Tries to find Java via login shell.
 * This handles lazy-loading shell functions like SDKMAN's lazy init pattern.
 */
async function tryLoginShell(): Promise<{ path: string; version: number } | null> {
    // Windows doesn't use login shells the same way
    if (process.platform === 'win32') return null;

    const shell = process.env.SHELL || '/bin/bash';
    try {
        // Login shell (-l) loads user's config files (.zshrc, .bashrc, etc.)
        // This triggers lazy-loading shell functions like SDKMAN
        const { stdout } = await execAsync(`${shell} -l -c "which java 2>/dev/null"`, {
            timeout: 10000 // 10 second timeout
        });
        const javaPath = stdout.trim();
        if (!javaPath || javaPath.includes('not found') || javaPath.includes('no java')) {
            return null;
        }

        // Resolve symlinks and get the actual JAVA_HOME
        const { stdout: realPath } = await execAsync(`${shell} -l -c "readlink -f '${javaPath}' 2>/dev/null || realpath '${javaPath}' 2>/dev/null || echo '${javaPath}'"`, {
            timeout: 5000
        });
        const resolvedPath = realPath.trim();

        // Get JAVA_HOME (go up two directories from bin/java)
        const javaHome = path.dirname(path.dirname(resolvedPath));

        // Validate with jdk-utils
        const runtime = await getRuntime(javaHome, { withVersion: true });
        if (runtime?.version?.major) {
            return { path: runtime.homedir, version: runtime.version.major };
        }

        // Fallback: try to get version directly
        const { stdout: versionOut, stderr: versionErr } = await execAsync(`${shell} -l -c "java -version 2>&1"`, {
            timeout: 10000
        });
        const versionOutput = versionOut || versionErr;
        const versionMatch = versionOutput.match(/version "(\d+)(?:\.(\d+))?/);
        if (versionMatch) {
            const majorVersion = parseInt(versionMatch[1], 10);
            if (!isNaN(majorVersion)) {
                return { path: javaHome, version: majorVersion };
            }
        }
    } catch {
        // Login shell approach failed
    }
    return null;
}

/**
 * Expands ~ to home directory
 */
function expandHomeDir(p: string): string {
    if (p.startsWith('~')) {
        return path.join(process.env.HOME || process.env.USERPROFILE || '', p.slice(1));
    }
    return p;
}

/**
 * Returns priority score for JDK source (lower is better)
 */
function getSourcePriority(runtime: IJavaRuntime): number {
    const sources = getSources(runtime);
    const envVars = ['JDK_HOME', 'JAVA_HOME', 'PATH'];
    const jdkManagers = ['SDKMAN', 'jEnv', 'jabba', 'asdf'];

    // Check environment variables first (highest priority)
    for (let i = 0; i < envVars.length; i++) {
        if (sources.includes(envVars[i])) {
            return i;
        }
    }

    // JDK managers next
    if (sources.some(source => jdkManagers.includes(source))) {
        return envVars.length + 1;
    }

    // Common system paths
    if (sources.length === 0) {
        return envVars.length + 2;
    }

    // Other sources
    return envVars.length + 3;
}

/**
 * Categorizes JDK source for display purposes
 */
function categorizeSource(sources: string[]): JavaSource {
    if (sources.includes('JAVA_HOME') || sources.includes('JDK_HOME')) {
        return 'java_home';
    }
    if (sources.some(s => ['SDKMAN', 'jEnv', 'jabba', 'asdf'].includes(s))) {
        return 'jdk_manager';
    }
    if (sources.includes('PATH')) {
        return 'system';
    }
    return 'system';
}
