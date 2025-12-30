import * as assert from 'assert';
import { extractRelevantLogs, buildIssueBody, SystemInfo } from '../../src/utils/reportIssue';

describe('Report Issue Utils', () => {
    describe('extractRelevantLogs', () => {
        it('should return empty string for empty input', () => {
            assert.strictEqual(extractRelevantLogs(''), '');
        });

        it('should prioritize stacktraces', () => {
            const logs = `INFO: Server starting
DEBUG: Loading config
ERROR: Something failed
java.lang.NullPointerException: Cannot invoke method on null
    at com.example.Foo.bar(Foo.java:42)
    at com.example.Main.main(Main.java:10)
INFO: Continuing anyway`;

            const result = extractRelevantLogs(logs, 500);

            // Should contain the stacktrace
            assert.ok(result.includes('NullPointerException'));
            assert.ok(result.includes('at com.example.Foo.bar'));
        });

        it('should prioritize Gradle/JDK errors', () => {
            const logs = `INFO: Starting server
DEBUG: Config loaded
ERROR: Unsupported class file major version 69
INFO: Some other info`;

            const result = extractRelevantLogs(logs, 300);

            assert.ok(result.includes('Unsupported class file major version'));
        });

        it('should fall back to last N chars if no priority content', () => {
            const logs = 'A'.repeat(1000) + 'LAST_CONTENT';

            const result = extractRelevantLogs(logs, 100);

            assert.ok(result.includes('LAST_CONTENT'));
            assert.ok(result.length <= 100);
        });

        it('should include ERROR and WARN lines', () => {
            const logs = `INFO: Normal info
WARN: This is a warning
INFO: More info
ERROR: This is an error
INFO: Even more info`;

            const result = extractRelevantLogs(logs, 500);

            assert.ok(result.includes('WARN: This is a warning'));
            assert.ok(result.includes('ERROR: This is an error'));
        });

        it('should respect maxLength parameter', () => {
            const longLogs = 'X'.repeat(10000);
            const result = extractRelevantLogs(longLogs, 3000);

            assert.ok(result.length <= 3000);
        });
    });

    describe('buildIssueBody', () => {
        const baseInfo: SystemInfo = {
            extensionVersion: '0.4.8',
            serverVersion: '0.4.8-SNAPSHOT',
            javaVersion: '21.0.1',
            gradleVersion: '8.5',
            osInfo: 'macOS 14.0',
            vscodeVersion: '1.85.0'
        };

        it('should include all system info fields', () => {
            const body = buildIssueBody(baseInfo);

            assert.ok(body.includes('Extension: 0.4.8'));
            assert.ok(body.includes('Server: 0.4.8-SNAPSHOT'));
            assert.ok(body.includes('Java: 21.0.1'));
            assert.ok(body.includes('Gradle: 8.5'));
            assert.ok(body.includes('macOS'));
        });

        it('should include logs when provided', () => {
            const info: SystemInfo = {
                ...baseInfo,
                logs: 'ERROR: Something broke'
            };

            const body = buildIssueBody(info);

            assert.ok(body.includes('ERROR: Something broke'));
            assert.ok(body.includes('Logs'));
        });

        it('should include error details when provided', () => {
            const info: SystemInfo = {
                ...baseInfo,
                errorCode: 'GRADLE_JDK_INCOMPATIBLE',
                errorMessage: 'Gradle 7.0 is incompatible with JDK 21'
            };

            const body = buildIssueBody(info);

            assert.ok(body.includes('GRADLE_JDK_INCOMPATIBLE'));
            assert.ok(body.includes('Gradle 7.0 is incompatible'));
        });

        it('should handle missing optional fields gracefully', () => {
            const minimalInfo: SystemInfo = {
                extensionVersion: '0.4.8',
                serverVersion: 'unknown',
                vscodeVersion: '1.85.0'
            };

            const body = buildIssueBody(minimalInfo);

            assert.ok(body.includes('Extension: 0.4.8'));
            assert.ok(!body.includes('undefined'));
        });

        it('should format as valid markdown', () => {
            const body = buildIssueBody(baseInfo);

            // Should have headers
            assert.ok(body.includes('## '));
            // Should have code blocks
            assert.ok(body.includes('```'));
        });
    });
});
