import * as vscode from 'vscode';
import { GradleExecutionService } from './GradleExecutionService';
import { GroovyTestController } from './GroovyTestController';
import { TestService } from './TestService';
import { CoverageService } from './CoverageService';
import { getClient } from '../../server/client';

export function registerTestingFeatures(
  context: vscode.ExtensionContext,
  logger: vscode.OutputChannel,
) {
  const executionService = new GradleExecutionService(
    logger,
    context.extensionPath,
  );

  // Get the LanguageClient for test discovery
  const client = getClient();
  const testService = client ? new TestService(client) : undefined;

  // Create coverage service
  const coverageService = new CoverageService(logger);

  // The controller registers itself with context.subscriptions in constructor
  const _controller = new GroovyTestController(
    context,
    executionService,
    testService,
    coverageService,
  );
  void _controller; // Side-effect instantiation - controller self-registers
}
