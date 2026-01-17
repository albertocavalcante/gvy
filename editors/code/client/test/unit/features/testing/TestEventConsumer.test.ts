import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("TestEventConsumer", () => {
  let TestEventConsumer: any;
  let consumer: any;
  let runMock: any;
  let loggerMock: any;
  let testControllerMock: any;
  let vscodeMock: any;
  let sandbox: sinon.SinonSandbox;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock VS Code API
    vscodeMock = {
      TestMessage: class TestMessage {
        constructor(public message: string) {}
      },
    };

    // Mock TestRun
    runMock = {
      started: sandbox.stub(),
      passed: sandbox.stub(),
      failed: sandbox.stub(),
      skipped: sandbox.stub(),
      errored: sandbox.stub(),
      enqueued: sandbox.stub(),
    };

    // Mock Logger
    loggerMock = {
      appendLine: sandbox.stub(),
    };

    // Mock TestController
    testControllerMock = {
      createTestItem: sandbox
        .stub()
        .callsFake((id: string, label: string, uri: any) => ({
          id,
          label,
          uri,
          children: {
            add: sandbox.stub(),
          },
        })),
    };

    // Use proxyquire to inject mocks
    const module = (proxyquire as any).noCallThru()(
      "../../../../src/features/testing/TestEventConsumer",
      {
        vscode: vscodeMock,
      },
    );
    TestEventConsumer = module.TestEventConsumer;
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("registerTestItem", () => {
    it("should register a test item", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };

      consumer.registerTestItem("test.Class.method", testItem);

      // Verify item was registered by processing an event for it
      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "test.Class.method",
          name: "method",
        }),
      );

      assert.ok(runMock.started.calledOnce);
    });
  });

  describe("clear", () => {
    it("should clear all registered test items", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem1 = { id: "test.Class.method1", label: "method1" };
      const testItem2 = { id: "test.Class.method2", label: "method2" };

      consumer.registerTestItem("test.Class.method1", testItem1);
      consumer.registerTestItem("test.Class.method2", testItem2);

      consumer.clear();

      // After clearing, events for these items should not be processed
      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "test.Class.method1",
          name: "method1",
        }),
      );

      // Should not call started because items were cleared
      assert.ok(runMock.started.notCalled);
    });

    it("should not throw when clearing empty consumer", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      assert.doesNotThrow(() => {
        consumer.clear();
      });
    });
  });

  describe("findItemByName", () => {
    it("should find item by exact label match", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.testMethod", label: "testMethod" };

      consumer.registerTestItem("test.Class.testMethod", testItem);

      // Process event with different ID but matching name
      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "different.id",
          name: "testMethod",
        }),
      );

      // Should find by name fallback and call started
      assert.ok(runMock.started.calledOnce);
    });

    it("should find item by ID suffix match", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.testMethod", label: "Test Method" };

      consumer.registerTestItem("test.Class.testMethod", testItem);

      // Process event with name that matches ID suffix
      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "other.id",
          name: "testMethod",
        }),
      );

      // Should find by ID suffix fallback
      assert.ok(runMock.started.calledOnce);
    });

    it("should return undefined when no match found", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method1", label: "method1" };

      consumer.registerTestItem("test.Class.method1", testItem);

      // Process event with completely different name
      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "different.id",
          name: "nonexistent",
        }),
      );

      // Should not call started because no match found
      assert.ok(runMock.started.notCalled);
    });
  });

  describe("processLine", () => {
    it("should return false for non-JSON lines", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      const result = consumer.processLine("Some regular output line");

      assert.strictEqual(result, false);
      assert.ok(loggerMock.appendLine.calledWith("Some regular output line"));
    });

    it("should return false for invalid JSON", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      const result = consumer.processLine("{ invalid json");

      assert.strictEqual(result, false);
      assert.ok(loggerMock.appendLine.calledWith("{ invalid json"));
    });

    it("should return true and process valid test event", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };
      consumer.registerTestItem("test.Class.method", testItem);

      const result = consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "test.Class.method",
          name: "method",
        }),
      );

      assert.strictEqual(result, true);
      assert.ok(runMock.started.calledOnce);
    });

    it("should handle testFinished with SUCCESS result", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };
      consumer.registerTestItem("test.Class.method", testItem);

      consumer.processLine(
        JSON.stringify({
          event: "testFinished",
          id: "test.Class.method",
          name: "method",
          result: "SUCCESS",
          duration: 100,
        }),
      );

      assert.ok(runMock.passed.calledOnce);
      assert.ok(runMock.passed.calledWith(testItem, 100));
    });

    it("should handle testFinished with FAILURE result", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };
      consumer.registerTestItem("test.Class.method", testItem);

      consumer.processLine(
        JSON.stringify({
          event: "testFinished",
          id: "test.Class.method",
          name: "method",
          result: "FAILURE",
          message: "Assertion failed",
          duration: 50,
        }),
      );

      assert.ok(runMock.failed.calledOnce);
    });

    it("should handle testFinished with SKIPPED result", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };
      consumer.registerTestItem("test.Class.method", testItem);

      consumer.processLine(
        JSON.stringify({
          event: "testFinished",
          id: "test.Class.method",
          name: "method",
          result: "SKIPPED",
        }),
      );

      assert.ok(runMock.skipped.calledOnce);
      assert.ok(runMock.skipped.calledWith(testItem));
    });

    it("should handle unknown test result", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };
      consumer.registerTestItem("test.Class.method", testItem);

      consumer.processLine(
        JSON.stringify({
          event: "testFinished",
          id: "test.Class.method",
          name: "method",
          result: "UNKNOWN",
        }),
      );

      assert.ok(runMock.errored.calledOnce);
    });

    it("should handle suiteStarted event", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      consumer.processLine(
        JSON.stringify({
          event: "suiteStarted",
          id: "test.Suite",
          name: "Suite",
        }),
      );

      assert.ok(loggerMock.appendLine.calledWith("[SUITE] Started: Suite"));
    });

    it("should handle suiteFinished event", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      consumer.processLine(
        JSON.stringify({
          event: "suiteFinished",
          id: "test.Suite",
          name: "Suite",
        }),
      );

      assert.ok(loggerMock.appendLine.calledWith("[SUITE] Finished: Suite"));
    });

    it("should dynamically create subtest for Spock @Unroll iterations", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const parentItem = {
        id: "test.Class.unrollTest",
        label: "unrollTest",
        uri: "file:///test.groovy",
        children: {
          add: sandbox.stub(),
        },
      };
      consumer.registerTestItem("test.Class.unrollTest", parentItem);

      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "test.Class.unrollTest[0]",
          name: "maximum of 1 and 3 is 3",
          parent: "test.Class.unrollTest",
        }),
      );

      assert.ok(testControllerMock.createTestItem.calledOnce);
      assert.ok(parentItem.children.add.calledOnce);
      assert.ok(runMock.enqueued.calledOnce);
      assert.ok(runMock.started.calledOnce);
    });

    it("should handle dynamic subtest without parent found", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      consumer.processLine(
        JSON.stringify({
          event: "testStarted",
          id: "test.Class.unrollTest[0]",
          name: "iteration 0",
          parent: "test.Class.unrollTest",
        }),
      );

      assert.ok(
        loggerMock.appendLine.calledWith(
          "[WARN] Parent not found for dynamic subtest: test.Class.unrollTest[0]",
        ),
      );
    });
  });

  describe("getAllRegisteredItems", () => {
    it("should return all registered items", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem1 = { id: "test.Class.method1", label: "method1" };
      const testItem2 = { id: "test.Class.method2", label: "method2" };

      consumer.registerTestItem("test.Class.method1", testItem1);
      consumer.registerTestItem("test.Class.method2", testItem2);

      const items = consumer.getAllRegisteredItems();

      assert.strictEqual(items.length, 2);
      assert.ok(items.includes(testItem1));
      assert.ok(items.includes(testItem2));
    });

    it("should return empty array when no items registered", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);

      const items = consumer.getAllRegisteredItems();

      assert.strictEqual(items.length, 0);
    });
  });

  describe("markPassed", () => {
    it("should mark item as passed", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };

      consumer.markPassed(testItem);

      assert.ok(runMock.passed.calledOnce);
      assert.ok(runMock.passed.calledWith(testItem));
    });
  });

  describe("markFailed", () => {
    it("should mark item as failed with message", () => {
      consumer = new TestEventConsumer(runMock, loggerMock, testControllerMock);
      const testItem = { id: "test.Class.method", label: "method" };

      consumer.markFailed(testItem, "Test failed");

      assert.ok(runMock.failed.calledOnce);
    });
  });
});
