import * as vscode from "vscode";
import { TestService } from "./TestService";

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
    _token: vscode.CancellationToken,
  ): Promise<vscode.CodeLens[]> {
    const codeLenses: vscode.CodeLens[] = [];

    // Check if CodeLens is enabled in settings
    const config = vscode.workspace.getConfiguration("groovy");
    const isEnabled = config.get<boolean>("codelens.test.enabled", true);

    if (!isEnabled) {
      return codeLenses;
    }

    // Check CodeLens source setting
    const codeLensSource = config.get<string>("codelens.test.source", "lsp");
    if (codeLensSource === "lsp") {
      return codeLenses; // Let LSP provide CodeLens
    }

    const text = document.getText();

    if (!text || text.trim() === "") {
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

    // Add CodeLens for the test class (Run All | Debug All | Coverage)
    const classPosition = new vscode.Position(classInfo.line, 0);
    const classRange = new vscode.Range(classPosition, classPosition);

    const classCommands = [
      { title: "$(play) Run All Tests", command: "groovy.test.run" },
      { title: "$(debug) Debug All Tests", command: "groovy.test.debug" },
      { title: "$(beaker) Coverage", command: "groovy.test.runWithCoverage" },
    ];
    for (const cmd of classCommands) {
      codeLenses.push(
        new vscode.CodeLens(classRange, {
          ...cmd,
          arguments: [{ uri: document.uri.toString(), suite: fqn, test: "*" }],
        }),
      );
    }

    // Add CodeLens for each test method
    const methodCommands = [
      { title: "$(play) Run", command: "groovy.test.run" },
      { title: "$(debug) Debug", command: "groovy.test.debug" },
      { title: "$(beaker) Coverage", command: "groovy.test.runWithCoverage" },
    ];
    for (const test of tests) {
      const testPosition = new vscode.Position(test.line, 0);
      const testRange = new vscode.Range(testPosition, testPosition);

      for (const cmd of methodCommands) {
        codeLenses.push(
          new vscode.CodeLens(testRange, {
            ...cmd,
            arguments: [
              { uri: document.uri.toString(), suite: fqn, test: test.name },
            ],
          }),
        );
      }
    }

    return codeLenses;
  }

  /**
   * Extract test class information from the document text.
   * When multiple classes exist in the same file, finds the first test class.
   */
  private extractClassInfo(text: string): ClassInfo | null {
    const lines = text.split("\n");
    let packageName = "";

    // First pass: extract package name
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const packageMatch = line.match(/^\s*package\s+([\w.]+)/);
      if (packageMatch) {
        packageName = packageMatch[1];
        break;
      }
    }

    // Second pass: find all classes and check which one is a test class
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // Check for Spock Specification
      const spockMatch = line.match(
        /^\s*class\s+(\w+)\s+extends\s+Specification/,
      );
      if (spockMatch) {
        return {
          name: spockMatch[1],
          packageName,
          line: i,
          isTestClass: true,
        };
      }

      // Check for JUnit test class (has @Test annotation AFTER the class declaration)
      const classMatch = line.match(/^\s*class\s+(\w+)/);
      if (classMatch) {
        // Find the next class or end of file
        let nextClassIndex = lines.length;
        for (let j = i + 1; j < lines.length; j++) {
          if (lines[j].match(/^\s*class\s+\w+/)) {
            nextClassIndex = j;
            break;
          }
        }

        // Check if there are @Test annotations within this class's scope
        const classBody = lines.slice(i + 1, nextClassIndex).join("\n");
        const hasTests = this.hasTestAnnotation(classBody);

        if (hasTests) {
          return {
            name: classMatch[1],
            packageName,
            line: i,
            isTestClass: true,
          };
        }
      }
    }

    return null;
  }

  /**
   * Check if text contains a valid @Test annotation (not in comments or strings).
   */
  private hasTestAnnotation(text: string): boolean {
    const lines = text.split("\n");

    for (const line of lines) {
      // Skip comments
      const trimmed = line.trim();
      if (
        trimmed.startsWith("//") ||
        trimmed.startsWith("*") ||
        trimmed.startsWith("/*")
      ) {
        continue;
      }

      // Check for @Test annotation (not inside strings)
      // Simple heuristic: if the line contains @Test and doesn't have quotes before it
      if (trimmed.match(/^\s*@Test(\s*\([^)]*\))?\s*(void|def)?/)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Extract test methods from the document text.
   * Supports both Spock-style (def "test name"()) and JUnit-style (@Test) tests.
   */
  private extractTests(text: string): TestInfo[] {
    const tests: TestInfo[] = [];
    const lines = text.split("\n");

    // Pattern for Spock tests: def "test name"() or def 'test name'()
    const spockPattern = /^\s*def\s+["'](.+?)["']\s*\(\s*\)/;

    // Pattern for JUnit tests: @Test followed by method definition
    let hasTestAnnotation = false;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const trimmed = line.trim();

      // Skip comments
      if (
        trimmed.startsWith("//") ||
        trimmed.startsWith("*") ||
        trimmed.startsWith("/*")
      ) {
        continue;
      }

      // Check for @Test annotation (with or without parameters)
      // Pattern: @Test or @Test(timeout = 1000) or @Test void inlineTest()
      // Note: The regex [^)]* prevents catastrophic backtracking but does not handle
      // nested parentheses in annotations (e.g., @Test(expected = Exception.class, timeout = timeout()))
      const testAnnotationMatch = trimmed.match(
        /^\s*@Test(\s*\([^)]*\))?(\s+(void|def)\s+(\w+)\s*\()?/,
      );
      if (testAnnotationMatch) {
        hasTestAnnotation = true;

        // Check if method is on the same line (inline)
        if (testAnnotationMatch[4]) {
          // Extract method name from the inline declaration
          tests.push({
            name: testAnnotationMatch[4],
            line: i,
          });
          hasTestAnnotation = false; // Reset since we found the method
        }
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

      // JUnit test method (must follow @Test annotation on previous line)
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
