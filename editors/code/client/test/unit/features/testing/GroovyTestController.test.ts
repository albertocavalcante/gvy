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
                return {
                    id,
                    label,
                    uri,
                    range: undefined,
                    children: {
                        add: sandbox.stub(),
                        get: sandbox.stub(),
                        replace: sandbox.stub(),
                    },
                };
            }),
            items: {
                add: sandbox.stub().callsFake((item: any) => {
                    testItemsMock.set(item.id, item);
                }),
                get: sandbox.stub().callsFake((id: string) => testItemsMock.get(id)),
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
            // Even with empty suite name, should attempt to create item
            // (implementation may vary on how to handle this edge case)
            assert.ok(
                executionServiceMock.runTests.calledOnce ||
                vscodeMock.window.showErrorMessage.calledOnce,
                'Should either run test or show error'
            );
        });
    });
});
