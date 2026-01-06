import * as assert from 'assert';
import * as sinon from 'sinon';
import proxyquire from 'proxyquire';

describe('GroovyTestController', () => {
    let GroovyTestController: any;
    let controller: any;
    let contextMock: any;
    let executionServiceMock: any;
    let testServiceMock: any;
    let vscodeMock: any;
    let testControllerMock: any;
    let sandbox: sinon.SinonSandbox;

    beforeEach(() => {
        sandbox = sinon.createSandbox();

        // Mock test items
        const testItemsMock = new Map();

        // Mock TestController
        testControllerMock = {
            createTestItem: sandbox.stub().callsFake((id: string, label: string, uri?: any) => {
                const childrenMap = new Map();
                return {
                    id,
                    label,
                    uri,
                    range: undefined,
                    children: {
                        add: sandbox.stub().callsFake((item: any) => {
                            childrenMap.set(item.id, item);
                        }),
                        get: sandbox.stub().callsFake((childId: string) => childrenMap.get(childId)),
                        replace: sandbox.stub().callsFake(() => childrenMap.clear()),
                    },
                };
            }),
            items: {
                add: sandbox.stub().callsFake((item: any) => {
                    testItemsMock.set(item.id, item);
                }),
                get: sandbox.stub().callsFake((id: string) => testItemsMock.get(id)),
                delete: sandbox.stub().callsFake((id: string) => {
                    testItemsMock.delete(id);
                }),
                replace: sandbox.stub().callsFake(() => {
                    testItemsMock.clear();
                }),
            },
            createRunProfile: sandbox.stub(),
            resolveHandler: undefined,
            refreshHandler: undefined,
        };

        // Mock VS Code API
        vscodeMock = {
            tests: {
                createTestController: sandbox.stub().returns(testControllerMock),
            },
            commands: {
                registerCommand: sandbox.stub().callsFake((command: string, handler: any) => {
                    // Store the handler for testing
                    return { dispose: sandbox.stub() };
                }),
            },
            window: {
                showErrorMessage: sandbox.stub(),
            },
            workspace: {
                workspaceFolders: [{
                    uri: { toString: () => 'file:///workspace' },
                    name: 'test-workspace',
                    index: 0,
                }],
            },
            TestRunProfileKind: {
                Run: 1,
                Debug: 2,
                Coverage: 3,
            },
            Uri: {
                parse: sandbox.stub().callsFake((uri: string) => ({
                    toString: () => uri,
                    fsPath: uri.replace('file://', ''),
                })),
            },
            Range: class {
                constructor(public start: any, public end: any) { }
            },
            Position: class {
                constructor(public line: number, public character: number) { }
            },
            TestRunRequest: class {
                constructor(public include: any[]) { }
            },
            CancellationTokenSource: class {
                token = {};
                dispose = sandbox.stub();
            },
        };

        // Mock context
        contextMock = {
            subscriptions: {
                push: sandbox.stub(),
            },
        };

        // Mock execution service
        executionServiceMock = {
            runTests: sandbox.stub().resolves(),
            debugTests: sandbox.stub().resolves(),
        };

        // Mock test service
        testServiceMock = {
            discoverTestsInWorkspace: sandbox.stub().resolves([]),
        };

        // Load GroovyTestController with mocks
        const module = (proxyquire as any).noCallThru()('../../../../src/features/testing/GroovyTestController', {
            'vscode': vscodeMock,
        });
        GroovyTestController = module.GroovyTestController;
    });

    afterEach(() => {
        sandbox.restore();
    });

    describe('runTestCommand with external files', () => {
        it('should create on-the-fly test item for external file when test is not found', async () => {
            // Arrange
            controller = new GroovyTestController(
                contextMock,
                executionServiceMock,
                testServiceMock
            );

            const externalUri = 'file:///Users/adsc/dev/refs/jenkins-spock/ExternalSpec.groovy';
            const suiteName = 'com.example.ExternalSpec';
            const testName = 'should work from external file';
            const args = {
                uri: externalUri,
                suite: suiteName,
                test: testName,
            };

            // Get the registered command handler
            const registerCommandCalls = vscodeMock.commands.registerCommand.getCalls();
            const runTestCall = registerCommandCalls.find((call: any) => call.args[0] === 'groovy.test.run');
            assert.ok(runTestCall, 'groovy.test.run command should be registered');
            const runTestHandler = runTestCall.args[1];

            // Act
            await runTestHandler(args);

            // Assert
            // Should NOT show error message since we create the item on-the-fly
            assert.ok(
                vscodeMock.window.showErrorMessage.notCalled,
                'Should not show error message when external test is run'
            );

            // Should have called runTests with the created test item
            assert.ok(
                executionServiceMock.runTests.calledOnce,
                'Should call runTests once'
            );

            const runTestsCall = executionServiceMock.runTests.firstCall;
            const request = runTestsCall.args[0];

            // Verify the request contains a test item
            assert.ok(request.include, 'Request should have include array');
            assert.strictEqual(request.include.length, 1, 'Should have one test item');

            const testItem = request.include[0];
            assert.ok(testItem, 'Test item should be created');
            assert.strictEqual(testItem.label, testName, 'Test item label should match test name');
            assert.strictEqual(testItem.uri.toString(), externalUri, 'Test item URI should match external file URI');
        });

        it('should use existing test item if found in workspace', async () => {
            // Arrange
            controller = new GroovyTestController(
                contextMock,
                executionServiceMock,
                testServiceMock
            );

            const workspaceUri = 'file:///workspace/src/test/groovy/WorkspaceSpec.groovy';
            const suiteName = 'com.example.WorkspaceSpec';
            const testName = 'should work from workspace';

            // Pre-populate the test tree with a workspace test
            testServiceMock.discoverTestsInWorkspace.resolves([
                {
                    uri: workspaceUri,
                    suite: suiteName,
                    tests: [{ test: testName, line: 10 }],
                },
            ]);

            // Manually trigger discovery
            if (testControllerMock.resolveHandler) {
                await testControllerMock.resolveHandler(undefined);
            }

            const args = {
                uri: workspaceUri,
                suite: suiteName,
                test: testName,
            };

            // Get the registered command handler
            const registerCommandCalls = vscodeMock.commands.registerCommand.getCalls();
            const runTestCall = registerCommandCalls.find((call: any) => call.args[0] === 'groovy.test.run');
            const runTestHandler = runTestCall.args[1];

            // Act
            await runTestHandler(args);

            // Assert
            assert.ok(
                vscodeMock.window.showErrorMessage.notCalled,
                'Should not show error message when workspace test is found'
            );

            assert.ok(
                executionServiceMock.runTests.calledOnce,
                'Should call runTests once'
            );
        });

        it('should handle missing suite name gracefully', async () => {
            // Arrange
            controller = new GroovyTestController(
                contextMock,
                executionServiceMock,
                testServiceMock
            );

            const externalUri = 'file:///Users/adsc/dev/refs/jenkins-spock/ExternalSpec.groovy';
            const args = {
                uri: externalUri,
                suite: '',
                test: 'should work',
            };

            // Get the registered command handler
            const registerCommandCalls = vscodeMock.commands.registerCommand.getCalls();
            const runTestCall = registerCommandCalls.find((call: any) => call.args[0] === 'groovy.test.run');
            const runTestHandler = runTestCall.args[1];

            // Act
            await runTestHandler(args);

            // Assert
            assert.ok(
                vscodeMock.window.showErrorMessage.calledOnce,
                'Should show error message for empty suite name'
            );
            assert.ok(
                executionServiceMock.runTests.notCalled,
                'Should not attempt to run tests with empty suite name'
            );
        });

        it('should preserve other test suites when updating suite with different URI', async () => {
            // Regression test for bug where ctrl.items.replace([suiteItem]) wiped all test suites
            // Arrange
            controller = new GroovyTestController(
                contextMock,
                executionServiceMock,
                testServiceMock
            );

            const workspaceUri = 'file:///workspace/TestA.groovy';
            const externalUri = 'file:///external/TestA.groovy';
            const suiteAName = 'com.example.TestA';
            const suiteBName = 'com.example.TestB';
            const workspaceTestName = 'workspace test method';
            const externalTestName = 'external test method';

            // Pre-populate test tree with two workspace suites
            // Note: external test is NOT in discovery results (it's external to workspace)
            testServiceMock.discoverTestsInWorkspace.resolves([
                {
                    uri: workspaceUri,
                    suite: suiteAName,
                    tests: [{ test: workspaceTestName, line: 10 }],
                },
                {
                    uri: 'file:///workspace/TestB.groovy',
                    suite: suiteBName,
                    tests: [{ test: 'other test', line: 20 }],
                },
            ]);

            // Trigger discovery to populate both suites
            if (testControllerMock.resolveHandler) {
                await testControllerMock.resolveHandler(undefined);
            }

            // Verify both suites exist before running external test
            const suiteABefore = testControllerMock.items.get(suiteAName);
            const suiteBBefore = testControllerMock.items.get(suiteBName);
            assert.ok(suiteABefore, 'Suite A should exist before external test run');
            assert.ok(suiteBBefore, 'Suite B should exist before external test run');
            assert.strictEqual(suiteABefore.uri.toString(), workspaceUri, 'Suite A should have workspace URI initially');

            // Run test from external file with same suite name but different test name
            // This simulates clicking "Run Test" on an external file that wasn't discovered
            const args = {
                uri: externalUri,
                suite: suiteAName,
                test: externalTestName, // Different test, so won't be found
            };

            const registerCommandCalls = vscodeMock.commands.registerCommand.getCalls();
            const runTestCall = registerCommandCalls.find((call: any) => call.args[0] === 'groovy.test.run');
            const runTestHandler = runTestCall.args[1];

            // Act
            await runTestHandler(args);

            // Assert - Suite B should still exist (regression check for the bug)
            const suiteBAfter = testControllerMock.items.get(suiteBName);
            assert.ok(
                suiteBAfter,
                'Suite B should still exist after running external test for Suite A (bug would have wiped this)'
            );

            // Suite A should have updated URI
            const suiteAAfter = testControllerMock.items.get(suiteAName);
            assert.ok(suiteAAfter, 'Suite A should still exist');
            assert.strictEqual(
                suiteAAfter.uri.toString(),
                externalUri,
                'Suite A URI should be updated to external file'
            );

            // Execution should have succeeded
            assert.ok(
                executionServiceMock.runTests.calledOnce,
                'Should have executed the external test'
            );
        });
    });
});
