/**
 * Utilities for enhancing the Report Issue command with smart log extraction
 * and system information gathering.
 */

/**
 * System information for bug reports
 */
export interface SystemInfo {
    extensionVersion: string;
    serverVersion: string;
    vscodeVersion: string;
    javaVersion?: string;
    gradleVersion?: string;
    osInfo?: string;
    logs?: string;
    errorCode?: string;
    errorMessage?: string;
}

/**
 * Priority patterns for log extraction.
 * Lines matching these patterns are prioritized in the extracted logs.
 */
const PRIORITY_PATTERNS = [
    // Java stacktraces 
    /^\s+at\s+[\w.$]+\([\w.$:]+\)/,
    /^[\w.$]+Exception:/,
    /^[\w.$]+Error:/,
    /^Caused by:/,

    // Gradle/JDK compatibility errors
    /Unsupported class file major version/,
    /Could not determine java version/,
    /incompatible with/i,
    /GRADLE_JDK_INCOMPATIBLE/,

    // Error/warning indicators
    /^ERROR[:\s]/,
    /^WARN[:\s]/,
    /\[ERROR\]/,
    /\[WARN\]/,
];

/**
 * Extracts the most relevant portions of log output for bug reports.
 * 
 * Strategy:
 * 1. Prioritize stacktraces (Java exceptions)
 * 2. Include ERROR and WARN lines
 * 3. Include Gradle/JDK related errors
 * 4. Fall back to last N characters if no priority content found
 * 
 * @param logs The full log output
 * @param maxLength Maximum length of extracted logs (default 3KB)
 * @returns Extracted relevant log content
 */
export function extractRelevantLogs(logs: string, maxLength: number = 3000): string {
    if (!logs || logs.length === 0) {
        return '';
    }

    const lines = logs.split('\n');
    const contextLines: Map<number, string> = new Map();

    // Find priority lines and their surrounding context
    lines.forEach((line, index) => {
        const isPriority = PRIORITY_PATTERNS.some(pattern => pattern.test(line));

        if (isPriority) {
            // Include 2 lines before for context
            for (let i = Math.max(0, index - 2); i <= index; i++) {
                if (!contextLines.has(i)) {
                    contextLines.set(i, lines[i]);
                }
            }
            // Include 2 lines after for context
            for (let i = index; i <= Math.min(lines.length - 1, index + 2); i++) {
                if (!contextLines.has(i)) {
                    contextLines.set(i, lines[i]);
                }
            }
        }
    });

    // If we have priority content, use it
    if (contextLines.size > 0) {
        // Sort by line number and reconstruct
        const sortedIndices = [...contextLines.keys()].sort((a, b) => a - b);
        let result = '';
        let lastIndex = -1;

        for (const index of sortedIndices) {
            // Add ellipsis if there's a gap
            if (lastIndex !== -1 && index > lastIndex + 1) {
                result += '\n...\n';
            }
            result += contextLines.get(index) + '\n';
            lastIndex = index;
        }

        // Trim to maxLength if needed
        if (result.length > maxLength) {
            return result.substring(0, maxLength - 3) + '...';
        }

        return result.trim();
    }

    // Fallback: return last N characters
    if (logs.length <= maxLength) {
        return logs;
    }

    return logs.substring(logs.length - maxLength);
}

/**
 * Builds a pre-filled GitHub issue body with system information.
 * 
 * @param info System information to include
 * @returns Formatted markdown issue body
 */
export function buildIssueBody(info: SystemInfo): string {
    const sections: string[] = [];

    // Description section
    sections.push(`## Description

<!-- Please describe the issue you're experiencing -->

`);

    // Environment section
    const envLines = [
        `Extension: ${info.extensionVersion}`,
        `Server: ${info.serverVersion}`,
        `VS Code: ${info.vscodeVersion}`,
    ];
    if (info.javaVersion) envLines.push(`Java: ${info.javaVersion}`);
    if (info.gradleVersion) envLines.push(`Gradle: ${info.gradleVersion}`);
    if (info.osInfo) envLines.push(`OS: ${info.osInfo}`);

    sections.push(`## Environment

\`\`\`
${envLines.join('\n')}
\`\`\`
`);

    // Error details section (if applicable)
    if (info.errorCode || info.errorMessage) {
        sections.push(`## Error Details

${info.errorCode ? `**Error Code:** \`${info.errorCode}\`\n` : ''}
${info.errorMessage ? `**Message:** ${info.errorMessage}\n` : ''}
`);
    }

    // Logs section (if applicable)
    if (info.logs) {
        sections.push(`## Logs

<details>
<summary>Click to expand logs</summary>

\`\`\`
${info.logs}
\`\`\`

</details>
`);
    }

    return sections.join('\n').replace(/\n{3,}/g, '\n\n').trim();
}
