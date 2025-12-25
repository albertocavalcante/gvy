import * as path from 'path';
import * as fs from 'fs';
import { workspace } from 'vscode';

/**
 * Finds Java executable from multiple sources
 */
export function findJava(): string {
    const executableFile = process.platform === 'win32' ? 'java.exe' : 'java';

    // 1. Check configuration setting first
    const javaHome = workspace.getConfiguration('groovy').get<string>('java.home');
    if (javaHome) {
        const javaPath = path.join(javaHome, 'bin', executableFile);
        if (validateJavaPath(javaPath)) {
            return javaPath;
        }
    }

    // 2. Check JAVA_HOME environment variable
    const envJavaHome = process.env.JAVA_HOME;
    if (envJavaHome) {
        const javaPath = path.join(envJavaHome, 'bin', executableFile);
        if (validateJavaPath(javaPath)) {
            return javaPath;
        }
    }

    // 3. Check PATH
    if (process.env.PATH) {
        const paths = process.env.PATH.split(path.delimiter);
        for (const p of paths) {
            const javaPath = path.join(p, executableFile);
            if (validateJavaPath(javaPath)) {
                return javaPath;
            }
        }
    }

    // 4. Fallback to system PATH
    return 'java';
}

function validateJavaPath(javaPath: string): boolean {
    return fs.existsSync(javaPath) && fs.statSync(javaPath).isFile();
}