import * as vscode from 'vscode';
import { TestService } from './TestService';

interface TestInfo {
    name: string;
    line: number;
}

interface ClassInfo {
    name: string;
    packageName: string;
    line: number;
    isTestClass: boolean;
}

export class TestCodeLensProvider implements vscode.CodeLensProvider {
    constructor(private testService: TestService | undefined) {}

    async provideCodeLenses(
        document: vscode.TextDocument,
        _token: vscode.CancellationToken
    ): Promise<vscode.CodeLens[]> {
        const codeLenses: vscode.CodeLens[] = [];
        const text = document.getText();

        if (!text || text.trim() === '') {
            return codeLenses;
        }

        // Parse the document to find test class and methods
        const classInfo = this.extractClassInfo(text);
        if (!classInfo || !classInfo.isTestClass) {
            return codeLenses;
        }

        const tests = this.extractTests(text);
        const fqn = classInfo.packageName
            ? `${classInfo.packageName}.${classInfo.name}`
            : classInfo.name;

        // Add CodeLens for the test class (Run All | Debug All)
        const classPosition = new vscode.Position(classInfo.line, 0);
        const classRange = new vscode.Range(classPosition, classPosition);

        codeLenses.push(
            new vscode.CodeLens(classRange, {
                title: 'Run All Tests',
                command: 'groovy.test.run',
                arguments: [{ uri: document.uri.toString(), suite: fqn, test: '*' }],
            })
        );

        codeLenses.push(
            new vscode.CodeLens(classRange, {
                title: 'Debug All Tests',
                command: 'groovy.test.debug',
                arguments: [{ uri: document.uri.toString(), suite: fqn, test: '*' }],
            })
        );

        // Add CodeLens for each test method
        for (const test of tests) {
            const testPosition = new vscode.Position(test.line, 0);
            const testRange = new vscode.Range(testPosition, testPosition);

            codeLenses.push(
                new vscode.CodeLens(testRange, {
                    title: 'Run Test',
                    command: 'groovy.test.run',
                    arguments: [{ uri: document.uri.toString(), suite: fqn, test: test.name }],
                })
            );

            codeLenses.push(
                new vscode.CodeLens(testRange, {
                    title: 'Debug Test',
                    command: 'groovy.test.debug',
                    arguments: [{ uri: document.uri.toString(), suite: fqn, test: test.name }],
                })
            );
        }

        return codeLenses;
    }

    /**
     * Extract test class information from the document text.
     */
    private extractClassInfo(text: string): ClassInfo | null {
        const lines = text.split('\n');
        let packageName = '';
        let className = '';
        let classLine = -1;
        let isTestClass = false;

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];

            // Extract package
            const packageMatch = line.match(/^\s*package\s+([\w.]+)/);
            if (packageMatch) {
                packageName = packageMatch[1];
                continue;
            }

            // Check for Spock Specification
            const spockMatch = line.match(/^\s*class\s+(\w+)\s+extends\s+Specification/);
            if (spockMatch) {
                className = spockMatch[1];
                classLine = i;
                isTestClass = true;
                break;
            }

            // Check for JUnit test class (has @Test annotation somewhere in the file)
            const classMatch = line.match(/^\s*class\s+(\w+)/);
            if (classMatch) {
                className = classMatch[1];
                classLine = i;
                // Check if this file has @Test annotations
                isTestClass = text.includes('@Test');
                break;
            }
        }

        if (!className || classLine === -1) {
            return null;
        }

        return {
            name: className,
            packageName,
            line: classLine,
            isTestClass,
        };
    }

    /**
     * Extract test methods from the document text.
     * Supports both Spock-style (def "test name"()) and JUnit-style (@Test) tests.
     */
    private extractTests(text: string): TestInfo[] {
        const tests: TestInfo[] = [];
        const lines = text.split('\n');

        // Pattern for Spock tests: def "test name"() or def 'test name'()
        const spockPattern = /^\s*def\s+["'](.+?)["']\s*\(\s*\)/;

        // Pattern for JUnit tests: @Test followed by method definition
        let hasTestAnnotation = false;

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];

            // Check for @Test annotation
            if (line.trim() === '@Test' || line.match(/^\s*@Test\s*$/)) {
                hasTestAnnotation = true;
                continue;
            }

            // Spock test method
            const spockMatch = line.match(spockPattern);
            if (spockMatch) {
                tests.push({
                    name: spockMatch[1],
                    line: i,
                });
                continue;
            }

            // JUnit test method (must follow @Test annotation)
            if (hasTestAnnotation) {
                const junitMatch = line.match(/^\s*(?:void|def)\s+(\w+)\s*\(/);
                if (junitMatch) {
                    tests.push({
                        name: junitMatch[1],
                        line: i,
                    });
                    hasTestAnnotation = false; // Reset for next test
                }
            }
        }

        return tests;
    }
}
