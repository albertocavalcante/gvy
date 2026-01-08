import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { State } from 'vscode-languageclient/node';
import { GradleExecutionService } from './GradleExecutionService';
import { MavenExecutionService } from './MavenExecutionService';
import { GroovyTestController } from './GroovyTestController';
import { TestService, BuildToolInfo, BuildToolName } from './TestService';
import { CoverageService } from './CoverageService';
import { TestCodeLensProvider } from './TestCodeLensProvider';
import { getClient } from '../../server/client';

/**
 * Fallback build tool detection when LSP is not available.
 * Prefer using TestService.getBuildToolInfo() when LSP is ready.
 */
function detectBuildToolFallback(workspacePath: string): BuildToolName {
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
  buildTool: BuildToolName,
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
  // LSP-based detection will be used when available via groovy/runTest
  const buildTool = detectBuildToolFallback(workspacePath);
  logger.appendLine(`[Testing] Initial build tool detection (fallback): ${buildTool}`);

  // Create initial execution service (LSP's groovy/runTest handles actual detection)
  const executionService = createExecutionService(buildTool, logger, context.extensionPath);

  // Create coverage service (only works with Gradle for now)
  const coverageService = buildTool === 'gradle' ? new CoverageService(logger) : undefined;

  // The controller registers itself with context.subscriptions in constructor
  new GroovyTestController(
    context,
    executionService,
    testService,
    coverageService,
  );

  // Query LSP for build tool info once client is in Running state
  if (client && testService && workspaceUri) {
    const queryBuildToolInfo = async () => {
      try {
        const lspBuildToolInfo: BuildToolInfo = await testService.getBuildToolInfo(workspaceUri);
        if (lspBuildToolInfo.detected) {
          if (lspBuildToolInfo.name !== buildTool) {
            logger.appendLine(
              `[Testing] LSP detected build tool: ${lspBuildToolInfo.name} ` +
              `(fallback was: ${buildTool}). LSP's groovy/runTest will use correct tool.`,
            );
          } else {
            logger.appendLine(`[Testing] LSP confirmed build tool: ${lspBuildToolInfo.name}`);
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
      }
    };

    // If client is already running, query immediately; otherwise wait for it
    if (client.state === State.Running) {
      void queryBuildToolInfo();
    } else {
      const disposable = client.onDidChangeState((e) => {
        if (e.newState === State.Running) {
          void queryBuildToolInfo();
          disposable.dispose();
        }
      });
      context.subscriptions.push(disposable);
    }
  }

  if (buildTool === 'unknown') {
    logger.appendLine(
      '[Testing] Warning: No supported build tool detected. Test execution may not work.',
    );
  }

  // Register CodeLens provider for test Run|Debug buttons
  const codeLensProvider = new TestCodeLensProvider(testService);
  context.subscriptions.push(
    vscode.languages.registerCodeLensProvider(
      [{ language: 'groovy' }, { language: 'jenkinsfile' }],
      codeLensProvider
    )
  );
}
