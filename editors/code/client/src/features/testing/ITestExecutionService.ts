/**
 * TODO(#715): This interface is part of the hacky Maven workaround.
 * See: https://github.com/albertocavalcante/gvy/issues/715
 */
import * as vscode from "vscode";

/**
 * Common interface for test execution services (Gradle, Maven, etc).
 */
export interface ITestExecutionService {
  runTests(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
  ): Promise<void>;

  debugTests(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
  ): Promise<void>;

  runTestsWithCoverage?(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
    coverageService: unknown,
  ): Promise<void>;
}
