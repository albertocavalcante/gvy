import * as vscode from "vscode";

/**
 * Events emitted by the Gradle init script.
 */
export type TestEventType =
  "suiteStarted" | "suiteFinished" | "testStarted" | "testFinished";

export interface TestEvent {
  event: TestEventType;
  id: string;
  name?: string;
  parent?: string | null;
  result?: "SUCCESS" | "FAILURE" | "SKIPPED";
  duration?: number;
  message?: string;
  stackTrace?: string;
  testCount?: number;
  successfulCount?: number;
  failedCount?: number;
  skippedCount?: number;
}

/** Only consume objects matching the event envelope emitted by the init script. */
function isTestEvent(value: unknown): value is TestEvent {
  if (typeof value !== "object" || value === null || Array.isArray(value))
    return false;
  const event = value as Record<string, unknown>;
  if (typeof event.id !== "string") return false;
  // Reject invalid types for any recognized field, including optional fields.
  for (const key of ["name", "message", "stackTrace"]) {
    if (event[key] !== undefined && typeof event[key] !== "string")
      return false;
  }
  if (
    event.parent !== undefined &&
    event.parent !== null &&
    typeof event.parent !== "string"
  )
    return false;
  if (
    event.duration !== undefined &&
    (typeof event.duration !== "number" || !Number.isFinite(event.duration))
  )
    return false;
  for (const key of [
    "testCount",
    "successfulCount",
    "failedCount",
    "skippedCount",
  ]) {
    const count = event[key];
    if (
      count !== undefined &&
      (typeof count !== "number" || !Number.isSafeInteger(count) || count < 0)
    )
      return false;
  }
  const validResult =
    event.result === "SUCCESS" ||
    event.result === "FAILURE" ||
    event.result === "SKIPPED";
  if (event.result !== undefined && !validResult) return false;
  switch (event.event) {
    case "suiteStarted":
      return typeof event.name === "string";
    case "testStarted":
      return (
        typeof event.name === "string" &&
        (typeof event.parent === "string" || event.parent === null)
      );
    case "testFinished":
      return (
        typeof event.name === "string" &&
        validResult &&
        typeof event.duration === "number"
      );
    case "suiteFinished":
      return (
        validResult &&
        typeof event.duration === "number" &&
        [
          event.testCount,
          event.successfulCount,
          event.failedCount,
          event.skippedCount,
        ].every((count) => typeof count === "number")
      );
    default:
      return false;
  }
}

/**
 * Consumes JSON test events from Gradle and updates the VS Code TestRun.
 * Supports dynamic subtest creation for Spock @Unroll iterations.
 */
export class TestEventConsumer {
  private readonly testItems: Map<string, vscode.TestItem>;

  constructor(
    private readonly run: vscode.TestRun,
    private readonly logger: vscode.OutputChannel,
    private readonly testController?: vscode.TestController,
  ) {
    this.testItems = new Map();
  }

  /**
   * Register a TestItem for tracking by its ID.
   */
  public registerTestItem(id: string, item: vscode.TestItem): void {
    this.testItems.set(id, item);
  }

  /**
   * Clear all registered test items to prevent memory leaks.
   * Should be called when the consumer is done processing test events.
   */
  public clear(): void {
    this.testItems.clear();
  }

  /**
   * Find a test item by name when exact ID match fails.
   * @param name The name/label of the test to find.
   * @param parentId The ID of the parent suite, if available, to narrow the search.
   */
  private findItemByName(
    name: string,
    parentId?: string,
  ): vscode.TestItem | undefined {
    // Prioritize matches within the same suite if parentId is known
    if (parentId) {
      for (const [id, item] of this.testItems) {
        if (
          id.startsWith(parentId) &&
          (item.label === name || id.endsWith(`.${name}`))
        ) {
          return item;
        }
      }
    }

    // First, prefer exact matches to avoid false positives
    for (const [id, item] of this.testItems) {
      if (item.label === name || id === name) {
        return item;
      }
    }

    // Fallback: try a narrower suffix match using only the ID
    for (const [id, item] of this.testItems) {
      if (id.endsWith(name)) {
        this.logger.appendLine(
          `[WARN] Ambiguous fallback match for test "${name}". Applying to ${item.id}.`,
        );
        return item;
      }
    }
    return undefined;
  }

  /**
   * Process a single line of output from Gradle.
   * Returns true if the line was a valid JSON event, false otherwise.
   */
  public processLine(line: string): boolean {
    const trimmed = line.trim();
    if (!trimmed.startsWith("{")) {
      // Not JSON, could be regular Gradle output
      this.logger.appendLine(line);
      return false;
    }

    try {
      const event: unknown = JSON.parse(trimmed);
      if (isTestEvent(event)) {
        this.handleEvent(event);
        return true;
      }
      this.logger.appendLine(line);
      return false;
    } catch {
      // Not valid JSON, log as regular output
      this.logger.appendLine(line);
      return false;
    }
  }

  private handleEvent(event: TestEvent): void {
    let item = this.testItems.get(event.id);

    // Fallback: try to find by name if exact ID match fails
    if (!item && event.name) {
      item = this.findItemByName(event.name, event.parent ?? undefined);
    }

    // For Spock @Unroll: dynamically create subtest if not found
    if (!item && event.parent && this.testController) {
      item = this.createDynamicSubtest(event);
    }

    switch (event.event) {
      case "testStarted":
        if (item) {
          this.run.started(item);
        }
        break;

      case "testFinished":
        if (item) {
          this.reportTestResult(item, event);
        }
        if (event.result !== "SUCCESS" && event.result !== "SKIPPED") {
          this.logger.appendLine(
            `[TEST] Finished: ${event.name} - ${event.result} (${event.duration}ms)`,
          );
        }
        break;

      case "suiteStarted":
        break;

      case "suiteFinished": {
        const counts = [
          event.testCount,
          event.successfulCount,
          event.failedCount,
          event.skippedCount,
        ];
        const suite = event.name ?? event.id;
        if (
          counts.every(
            (count) =>
              typeof count === "number" &&
              Number.isSafeInteger(count) &&
              count >= 0,
          )
        ) {
          this.logger.appendLine(
            `[SUITE] ${suite}: ${event.testCount} total, ${event.successfulCount} passed, ${event.failedCount} failed, ${event.skippedCount} skipped`,
          );
        } else {
          this.logger.appendLine(`[SUITE] Finished: ${suite}`);
        }
        break;
      }
    }
  }

  /**
   * Dynamically create a child TestItem for Spock @Unroll iterations.
   * These appear at runtime with names like "test #0" or "maximum of 1 and 3 is 3".
   */
  private createDynamicSubtest(event: TestEvent): vscode.TestItem | undefined {
    if (!this.testController || !event.parent) {
      return undefined;
    }

    const parentItem = this.testItems.get(event.parent);
    if (!parentItem) {
      // Parent not found, log and skip
      this.logger.appendLine(
        `[WARN] Parent not found for dynamic subtest: ${event.id}`,
      );
      return undefined;
    }

    // Create child TestItem under the parent
    const childItem = this.testController.createTestItem(
      event.id,
      event.name ?? event.id,
      parentItem.uri,
    );

    // Add to parent's children
    parentItem.children.add(childItem);

    // Register for future lookups
    this.testItems.set(event.id, childItem);

    // Mark as enqueued then started
    this.run.enqueued(childItem);

    return childItem;
  }

  private reportTestResult(item: vscode.TestItem, event: TestEvent): void {
    switch (event.result) {
      case "SUCCESS":
        this.run.passed(item, event.duration);
        break;

      case "FAILURE":
        this.run.failed(
          item,
          new vscode.TestMessage(event.message ?? "Test failed"),
          event.duration,
        );
        break;

      case "SKIPPED":
        this.run.skipped(item);
        break;

      default:
        // Unknown result type, treat as error
        this.run.errored(
          item,
          new vscode.TestMessage(`Unknown test result: ${event.result}`),
        );
        break;
    }
  }

  // TODO(#715): These helpers are for hacky Maven support.
  // Proper implementation should parse Surefire XML reports.

  /**
   * Get all registered test items.
   */
  public getAllRegisteredItems(): vscode.TestItem[] {
    return Array.from(this.testItems.values());
  }

  /**
   * Mark a test as passed.
   */
  public markPassed(item: vscode.TestItem): void {
    this.run.passed(item);
  }

  /**
   * Mark a test as failed with a message.
   */
  public markFailed(item: vscode.TestItem, message: string): void {
    this.run.failed(item, new vscode.TestMessage(message));
  }
}
