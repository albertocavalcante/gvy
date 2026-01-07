import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { GradleExecutionService } from './GradleExecutionService';
import { MavenExecutionService } from './MavenExecutionService';
import { GroovyTestController } from './GroovyTestController';
import { TestService, BuildToolInfo } from './TestService';
import { CoverageService } from './CoverageService';
import { getClient } from '../../server/client';

type BuildToolType = 'gradle' | 'maven' | 'bsp' | 'unknown';

/**
 * Fallback build tool detection when LSP is not available.
 * Prefer using TestService.getBuildToolInfo() when LSP is ready.
 */
function detectBuildToolFallback(workspacePath: string): BuildToolType {
  // Check for Gradle
  const gradleFiles = [
    'build.gradle',
    'build.gradle.kts',
    'settings.gradle',
    'settings.gradle.kts',
  ];
  if (gradleFiles.some((f) => fs.existsSync(path.join(workspacePath, f)))) {
    return 'gradle';
  }

  // Check for Maven
  if (fs.existsSync(path.join(workspacePath, 'pom.xml'))) {
    return 'maven';
  }

  return 'unknown';
}

/**
 * Create execution service based on build tool type.
 */
function createExecutionService(
  buildTool: BuildToolType,
  logger: vscode.OutputChannel,
  extensionPath: string,
) {
  switch (buildTool) {
    case 'maven':
      return new MavenExecutionService(logger);
    case 'gradle':
    case 'bsp':
    default:
      // Default to Gradle for unknown/BSP (BSP uses Gradle commands internally)
      return new GradleExecutionService(logger, extensionPath);
  }
}

export function registerTestingFeatures(
  context: vscode.ExtensionContext,
  logger: vscode.OutputChannel,
) {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  const workspacePath = workspaceFolder?.uri.fsPath ?? '';
  const workspaceUri = workspaceFolder?.uri.toString() ?? '';

  // Get the LanguageClient for test discovery and LSP-based build tool detection
  const client = getClient();
  const testService = client ? new TestService(client) : undefined;

  // Use synchronous fallback detection initially
  // LSP-based detection will be used when available
  let buildTool = detectBuildToolFallback(workspacePath);
  logger.appendLine(`[Testing] Initial build tool detection (fallback): ${buildTool}`);

  // Create initial execution service
  let executionService = createExecutionService(buildTool, logger, context.extensionPath);

  // Create coverage service (only works with Gradle for now)
  let coverageService = buildTool === 'gradle' ? new CoverageService(logger) : undefined;

  // The controller registers itself with context.subscriptions in constructor
  const controller = new GroovyTestController(
    context,
    executionService,
    testService,
    coverageService,
  );

  // Async: Query LSP for authoritative build tool info once client is ready
  if (testService && workspaceUri) {
    // Use a small delay to let LSP initialize
    setTimeout(async () => {
      try {
        const lspBuildToolInfo: BuildToolInfo = await testService.getBuildToolInfo(workspaceUri);
        if (lspBuildToolInfo.detected) {
          const lspBuildTool = lspBuildToolInfo.name as BuildToolType;
          if (lspBuildTool !== buildTool) {
            logger.appendLine(
              `[Testing] LSP detected different build tool: ${lspBuildTool} ` +
              `(fallback was: ${buildTool}). Using LSP result.`,
            );
            buildTool = lspBuildTool;
            // Note: The execution service is already created with the fallback.
            // For a full solution, we'd need to update the controller.
            // For now, log the discrepancy. The LSP's groovy/runTest handles this correctly.
          } else {
            logger.appendLine(`[Testing] LSP confirmed build tool: ${lspBuildTool}`);
          }

          // Log capabilities
          logger.appendLine(
            `[Testing] Build tool capabilities: ` +
            `testExecution=${lspBuildToolInfo.supportsTestExecution}, ` +
            `debug=${lspBuildToolInfo.supportsDebug}, ` +
            `coverage=${lspBuildToolInfo.supportsCoverage}`,
          );
        }
      } catch (error) {
        logger.appendLine(`[Testing] Failed to get LSP build tool info: ${error}`);
        // Continue with fallback detection
      }
    }, 2000); // Wait 2 seconds for LSP to initialize
  }

  if (buildTool === 'unknown') {
    logger.appendLine(
      '[Testing] Warning: No supported build tool detected. Test execution may not work.',
    );
  }
}
