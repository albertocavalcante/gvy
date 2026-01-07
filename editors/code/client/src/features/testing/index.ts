import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { GradleExecutionService } from './GradleExecutionService';
import { MavenExecutionService } from './MavenExecutionService';
import { GroovyTestController } from './GroovyTestController';
import { TestService } from './TestService';
import { CoverageService } from './CoverageService';
import { getClient } from '../../server/client';

/**
 * TODO(#715): This build tool detection is a hacky workaround.
 * Proper implementation should move detection to LSP via groovy/getBuildToolInfo.
 * See: https://github.com/albertocavalcante/gvy/issues/715
 */
type BuildToolType = 'gradle' | 'maven' | 'unknown';

function detectBuildTool(workspacePath: string): BuildToolType {
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

export function registerTestingFeatures(
  context: vscode.ExtensionContext,
  logger: vscode.OutputChannel,
) {
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  const workspacePath = workspaceFolder?.uri.fsPath ?? '';
  const buildTool = detectBuildTool(workspacePath);

  logger.appendLine(`[Testing] Detected build tool: ${buildTool}`);

  // TODO(#715): Use a common ITestExecutionService interface
  const executionService =
    buildTool === 'maven'
      ? new MavenExecutionService(logger)
      : new GradleExecutionService(logger, context.extensionPath);

  if (buildTool === 'unknown') {
    logger.appendLine(
      '[Testing] Warning: No supported build tool detected. Test execution may not work.',
    );
  }

  // Get the LanguageClient for test discovery
  const client = getClient();
  const testService = client ? new TestService(client) : undefined;

  // Create coverage service (only works with Gradle for now)
  const coverageService =
    buildTool === 'gradle' ? new CoverageService(logger) : undefined;

  // The controller registers itself with context.subscriptions in constructor
  const _controller = new GroovyTestController(
    context,
    executionService,
    testService,
    coverageService,
  );
  void _controller; // Side-effect instantiation - controller self-registers
}
