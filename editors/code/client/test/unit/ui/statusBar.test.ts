import * as sinon from 'sinon';
import { assert } from 'chai';
import * as proxyquire from 'proxyquire';

describe('StatusBar', () => {
    let mockVscode: any;
    let mockLanguageClient: any;
    let statusBarModule: any;
    let statusBarItemStub: any;
    let clientStub: any;

    beforeEach(() => {
        // Create fresh mocks for each test
        statusBarItemStub = {
            text: '',
            tooltip: undefined,
            backgroundColor: undefined,
            color: undefined,
            command: undefined,
            name: '',
            show: sinon.stub(),
            hide: sinon.stub(),
            dispose: sinon.stub()
        };

        mockVscode = {
            window: {
                createStatusBarItem: sinon.stub().returns(statusBarItemStub),
                onDidChangeActiveTextEditor: sinon.stub().returns({ dispose: sinon.stub() }),
                showQuickPick: sinon.stub().resolves(undefined),
                activeTextEditor: undefined
            },
            workspace: {
                getConfiguration: sinon.stub().returns({
                    get: sinon.stub().callsFake((key: string, defaultValue: any) => defaultValue)
                }),
                onDidChangeConfiguration: sinon.stub().returns({ dispose: sinon.stub() })
            },
            languages: {
                match: sinon.stub().returns(0) // Default: no match
            },
            commands: {
                executeCommand: sinon.stub().resolves()
            },
            StatusBarAlignment: {
                Left: 1,
                Right: 2
            },
            QuickPickItemKind: {
                Separator: -1
            },
            ThemeColor: sinon.stub().callsFake((id: string) => ({ id })),
            MarkdownString: sinon.stub().callsFake(() => {
                const instance = {
                    isTrusted: false,
                    supportThemeIcons: false,
                    supportHtml: false,
                    value: '',
                    appendMarkdown: sinon.stub().callsFake((text: string) => {
                        instance.value += text;
                    })
                };
                return instance;
            })
        };

        // Mock State enum
        const State = {
            Stopped: 1,
            Starting: 2,
            Running: 3
        };

        mockLanguageClient = {
            State,
            ProgressType: sinon.stub().callsFake(function () { return {}; })
        };

        clientStub = {
            state: State.Stopped,
            onDidChangeState: sinon.stub().returns({ dispose: sinon.stub() }),
            onProgress: sinon.stub().returns({ dispose: sinon.stub() }),
            // Basic stub required by all tests; individual suites may override with more specific behavior
            onNotification: sinon.stub().returns({ dispose: sinon.stub() })
        };

        // Mock languageStatus module to avoid transitive vscode import
        const mockLanguageStatus = {
            getLanguageStatusManager: sinon.stub().returns(undefined),
            createLanguageStatusManager: sinon.stub().returns({
                updateServerStatus: sinon.stub(),
                updateJavaRuntime: sinon.stub(),
                updateBuildTool: sinon.stub(),
                dispose: sinon.stub()
            }),
            disposeLanguageStatusManager: sinon.stub()
        };

        // Load module with mocked dependencies
        statusBarModule = proxyquire.noCallThru()('../../../src/ui/statusBar', {
            'vscode': mockVscode,
            'vscode-languageclient/node': mockLanguageClient,
            'vscode-languageserver-protocol': {
                WorkDoneProgressBegin: {},
                WorkDoneProgressReport: {},
                WorkDoneProgressEnd: {}
            },
            './languageStatus': mockLanguageStatus
        });
    });

    afterEach(() => {
        sinon.restore();
    });

    describe('registerStatusBarItem', () => {
        it('should create status bar item', () => {
            statusBarModule.registerStatusBarItem();

            assert.isTrue(mockVscode.window.createStatusBarItem.calledOnce);
            // With smart visibility, hide is called when no active editor
            assert.isTrue(statusBarItemStub.hide.calledOnce);
        });

        it('should show status bar when active editor matches Groovy files', () => {
            // Simulate a Groovy file being active
            mockVscode.languages.match.returns(1); // Match found
            mockVscode.window.activeTextEditor = { document: {} };

            statusBarModule.registerStatusBarItem();

            assert.isTrue(statusBarItemStub.show.calledOnce);
        });

        it('should return disposable', () => {
            const disposable = statusBarModule.registerStatusBarItem();

            assert.isFunction(disposable.dispose);
        });

        it('should dispose status bar item when disposable is called', () => {
            const disposable = statusBarModule.registerStatusBarItem();

            disposable.dispose();

            assert.isTrue(statusBarItemStub.dispose.calledOnce);
        });

        it('should set client if provided', () => {
            statusBarModule.registerStatusBarItem(clientStub);

            assert.isTrue(clientStub.onDidChangeState.calledOnce);
            assert.isTrue(clientStub.onProgress.calledOnce);
        });
    });

    describe('setClient', () => {
        beforeEach(() => {
            statusBarModule.registerStatusBarItem();
        });

        it('should clean up previous listeners when setting new client', () => {
            const firstClient = {
                ...clientStub,
                onDidChangeState: sinon.stub().returns({ dispose: sinon.stub() }),
                onProgress: sinon.stub().returns({ dispose: sinon.stub() })
            };

            statusBarModule.setClient(firstClient);
            const firstStateDisposable = firstClient.onDidChangeState.returnValues[0];
            const firstProgressDisposable = firstClient.onProgress.returnValues[0];

            const secondClient = {
                ...clientStub,
                onDidChangeState: sinon.stub().returns({ dispose: sinon.stub() }),
                onProgress: sinon.stub().returns({ dispose: sinon.stub() })
            };

            statusBarModule.setClient(secondClient);

            assert.isTrue(firstStateDisposable.dispose.calledOnce);
            assert.isTrue(firstProgressDisposable.dispose.calledOnce);
        });

        it('should subscribe to client state changes', () => {
            statusBarModule.setClient(clientStub);

            assert.isTrue(clientStub.onDidChangeState.calledOnce);
        });

        it('should subscribe to progress notifications', () => {
            statusBarModule.setClient(clientStub);

            assert.isTrue(clientStub.onProgress.calledOnce);
        });

        it('should update status bar text to stopped when client is undefined', () => {
            statusBarModule.setClient(undefined);

            assert.include(statusBarItemStub.text, '$(stop-circle)');
        });

        it('should update status bar text when client state changes', () => {
            statusBarModule.setClient(clientStub);

            // Simulate state change to Running
            const stateChangeHandler = clientStub.onDidChangeState.firstCall.args[0];
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });
    });

    describe('progress notification handling', () => {
        let progressHandler: any;

        beforeEach(() => {
            statusBarModule.registerStatusBarItem();
            statusBarModule.setClient(clientStub);
            progressHandler = clientStub.onProgress.firstCall.args[2];
        });

        it('should update state on progress begin', () => {
            progressHandler({
                kind: 'begin',
                title: 'Resolving dependencies',
                message: 'Connecting to Gradle...'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should update message on progress report', () => {
            progressHandler({
                kind: 'begin',
                title: 'Resolving dependencies',
                message: 'Starting...'
            });

            progressHandler({
                kind: 'report',
                message: 'Found 50 dependencies'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should transition to ready on progress end', () => {
            progressHandler({
                kind: 'begin',
                title: 'Resolving dependencies'
            });

            progressHandler({
                kind: 'end',
                message: 'Complete'
            });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });

        it('should handle concurrent progress tasks', () => {
            // Start two tasks
            progressHandler({
                kind: 'begin',
                title: 'Resolving dependencies'
            });

            progressHandler({
                kind: 'begin',
                title: 'Indexing'
            });

            // End first task - should NOT transition to ready
            progressHandler({
                kind: 'end'
            });

            assert.notInclude(statusBarItemStub.text, '$(pass-filled)');

            // End second task - NOW should transition to ready
            progressHandler({
                kind: 'end'
            });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });
    });

    describe('state inference from messages', () => {
        let progressHandler: any;

        beforeEach(() => {
            statusBarModule.registerStatusBarItem();
            statusBarModule.setClient(clientStub);
            progressHandler = clientStub.onProgress.firstCall.args[2];
        });

        it('should detect error state from "failed" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Dependency resolution failed'
            });

            assert.include(statusBarItemStub.text, '$(warning)');
        });

        it('should detect error state from "error" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Error connecting to Maven'
            });

            assert.include(statusBarItemStub.text, '$(warning)');
        });

        it('should detect resolving-deps from "gradle" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Connecting to Gradle'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should detect resolving-deps from "maven" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Resolving Maven dependencies'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should detect resolving-deps from "dependencies" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Loading dependencies'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should detect indexing from "compiling" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Compiling workspace'
            });

            assert.include(statusBarItemStub.text, 'Indexing');
        });

        it('should detect indexing from "analyzing" keyword', () => {
            progressHandler({
                kind: 'begin',
                message: 'Analyzing source files'
            });

            assert.include(statusBarItemStub.text, 'Indexing');
        });

        it('should prioritize error over other keywords', () => {
            progressHandler({
                kind: 'begin',
                message: 'Gradle indexing failed'
            });

            // Should show degraded (warning), not indexing
            assert.include(statusBarItemStub.text, '$(warning)');
        });
    });

    describe('client state transitions', () => {
        let stateChangeHandler: any;

        beforeEach(() => {
            statusBarModule.registerStatusBarItem();
            statusBarModule.setClient(clientStub);
            stateChangeHandler = clientStub.onDidChangeState.firstCall.args[0];
        });

        it('should transition to ready when client is Running', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });

        it('should transition to starting when client is Starting', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Starting });

            assert.include(statusBarItemStub.text, '$(loading~spin)');
            assert.notInclude(statusBarItemStub.text, 'Deps');
            assert.notInclude(statusBarItemStub.text, 'Indexing');
        });

        it('should transition to stopped when client is Stopped', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Stopped });

            assert.include(statusBarItemStub.text, '$(stop-circle)');
            assert.isDefined(statusBarItemStub.backgroundColor);
            assert.equal(statusBarItemStub.backgroundColor.id, 'statusBarItem.errorBackground');
        });

        it('should preserve granular state when transitioning to Running', () => {
            // First set a granular state via progress
            const progressHandler = clientStub.onProgress.firstCall.args[2];
            progressHandler({
                kind: 'begin',
                message: 'Resolving dependencies'
            });

            // Then transition client to Running
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            // Should preserve "Deps" state, not transition to ready
            assert.include(statusBarItemStub.text, 'Deps');
        });
    });

    describe('background colors', () => {
        let progressHandler: any;

        beforeEach(() => {
            statusBarModule.registerStatusBarItem();
            statusBarModule.setClient(clientStub);
            progressHandler = clientStub.onProgress.firstCall.args[2];
        });

        it('should set warning background for degraded state', () => {
            progressHandler({
                kind: 'begin',
                message: 'Build failed'
            });

            assert.isDefined(statusBarItemStub.backgroundColor);
            assert.equal(statusBarItemStub.backgroundColor.id, 'statusBarItem.warningBackground');
        });

        it('should set error background for stopped state', () => {
            statusBarModule.setClient(undefined);

            assert.isDefined(statusBarItemStub.backgroundColor);
            assert.equal(statusBarItemStub.backgroundColor.id, 'statusBarItem.errorBackground');
        });

        it('should clear background for ready state', () => {
            const stateChangeHandler = clientStub.onDidChangeState.firstCall.args[0];
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            assert.isUndefined(statusBarItemStub.backgroundColor);
        });
    });

    describe('smart visibility settings', () => {
        it('should always show when setting is "always"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.show') return 'always';
                    return defaultValue;
                })
            });

            statusBarModule.registerStatusBarItem();

            assert.isTrue(statusBarItemStub.show.calledOnce);
            assert.isFalse(statusBarItemStub.hide.called);
        });

        it('should never show when setting is "never"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.show') return 'never';
                    return defaultValue;
                })
            });
            mockVscode.languages.match.returns(1); // Even with Groovy file
            mockVscode.window.activeTextEditor = { document: {} };

            statusBarModule.registerStatusBarItem();

            assert.isTrue(statusBarItemStub.hide.calledOnce);
            assert.isFalse(statusBarItemStub.show.called);
        });

        it('should only show on Groovy files when setting is "onGroovyFile"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.show') return 'onGroovyFile';
                    return defaultValue;
                })
            });
            mockVscode.languages.match.returns(0); // No match
            mockVscode.window.activeTextEditor = { document: {} };

            statusBarModule.registerStatusBarItem();

            assert.isTrue(statusBarItemStub.hide.calledOnce);
        });
    });

    describe('click action settings', () => {
        it('should set menu command when clickAction is "menu"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.clickAction') return 'menu';
                    return defaultValue;
                })
            });

            statusBarModule.registerStatusBarItem();

            assert.equal(statusBarItemStub.command, 'groovy.showStatusMenu');
        });

        it('should set logs command when clickAction is "logs"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.clickAction') return 'logs';
                    return defaultValue;
                })
            });

            statusBarModule.registerStatusBarItem();

            assert.equal(statusBarItemStub.command, 'groovy.openLogs');
        });

        it('should set restart command when clickAction is "restart"', () => {
            mockVscode.workspace.getConfiguration.returns({
                get: sinon.stub().callsFake((key: string, defaultValue: any) => {
                    if (key === 'statusBar.clickAction') return 'restart';
                    return defaultValue;
                })
            });

            statusBarModule.registerStatusBarItem();

            assert.equal(statusBarItemStub.command, 'groovy.restartServer');
        });
    });

    describe('tooltip content', () => {
        let stateChangeHandler: any;

        beforeEach(() => {
            statusBarModule.registerStatusBarItem(undefined, '0.4.8');
            statusBarModule.setClient(clientStub);
            stateChangeHandler = clientStub.onDidChangeState.firstCall.args[0];
        });

        it('should include state description in tooltip', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            const tooltip = statusBarItemStub.tooltip;
            assert.isDefined(tooltip);
            assert.isTrue(tooltip.isTrusted);
            assert.isTrue(tooltip.supportThemeIcons);
            // New tooltip format uses "Ready" not "ready"
            assert.include(tooltip.value, 'Ready');
        });

        it('should include progress message in tooltip when active', () => {
            const progressHandler = clientStub.onProgress.firstCall.args[2];
            progressHandler({
                kind: 'begin',
                message: 'Downloading dependencies...'
            });

            const tooltip = statusBarItemStub.tooltip;
            assert.include(tooltip.value, 'Downloading dependencies');
        });

        it('should include restart button in tooltip', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            const tooltip = statusBarItemStub.tooltip;
            assert.include(tooltip.value, 'groovy.restartServer');
        });

        it('should include reload button when ready', () => {
            stateChangeHandler({ newState: mockLanguageClient.State.Running });

            const tooltip = statusBarItemStub.tooltip;
            // New tooltip has Reload instead of check for updates inline
            assert.include(tooltip.value, 'groovy.gradle.refresh');
        });
    });

    describe('groovy/status notification handling', () => {
        let statusHandler: any;

        beforeEach(() => {
            // Add onNotification mock to client
            clientStub.onNotification = sinon.stub().callsFake((method: string, handler: any) => {
                if (method === 'groovy/status') {
                    statusHandler = handler;
                }
                return { dispose: sinon.stub() };
            });

            statusBarModule.registerStatusBarItem();
            statusBarModule.setClient(clientStub);
        });

        it('should register groovy/status notification handler', () => {
            assert.isTrue(clientStub.onNotification.calledWith('groovy/status', sinon.match.func));
        });

        it('should transition to ready when health is ok and quiescent is true', () => {
            statusHandler({
                health: 'ok',
                quiescent: true,
                message: 'Ready'
            });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });

        it('should transition to error when health is error', () => {
            statusHandler({
                health: 'error',
                quiescent: true,
                message: 'Build configuration error'
            });

            assert.include(statusBarItemStub.text, '$(error)');
        });

        it('should transition to degraded when health is warning', () => {
            statusHandler({
                health: 'warning',
                quiescent: true,
                message: 'Some dependencies could not be resolved'
            });

            assert.include(statusBarItemStub.text, '$(warning)');
        });

        it('should transition to indexing when not quiescent and message contains indexing', () => {
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Indexing 50/100 files',
                filesIndexed: 50,
                filesTotal: 100
            });

            // Should show file counts in status bar
            assert.include(statusBarItemStub.text, '50/100');
        });

        it('should transition to resolving-deps when not quiescent and message contains dependencies', () => {
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Resolving dependencies...'
            });

            assert.include(statusBarItemStub.text, 'Deps');
        });

        it('should show file counts during indexing', () => {
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Indexing 25/50 files',
                filesIndexed: 25,
                filesTotal: 50
            });

            assert.include(statusBarItemStub.text, '25/50');
        });

        it('should clear file counts when quiescent', () => {
            // First set file counts
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Indexing 25/50 files',
                filesIndexed: 25,
                filesTotal: 50
            });

            // Then transition to ready
            statusHandler({
                health: 'ok',
                quiescent: true,
                message: 'Ready'
            });

            // File counts should not be shown
            assert.notInclude(statusBarItemStub.text, '/');
            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });

        it('should ignore progress inference when receiving groovy/status notifications', () => {
            // First receive a groovy/status notification with file counts
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Indexing 10/100 files',
                filesIndexed: 10,
                filesTotal: 100
            });

            // Then receive a progress notification (legacy fallback)
            const progressHandler = clientStub.onProgress.firstCall.args[2];
            progressHandler({
                kind: 'begin',
                message: 'Resolving dependencies' // Would normally trigger resolving-deps
            });

            // Should still show indexing with file counts, not deps
            // (because groovy/status takes precedence)
            assert.include(statusBarItemStub.text, '10/100');
        });

        it('should update progress message from groovy/status', () => {
            statusHandler({
                health: 'ok',
                quiescent: false,
                message: 'Indexing workspace files...'
            });

            const tooltip = statusBarItemStub.tooltip;
            assert.include(tooltip.value, 'Indexing workspace files');
        });

        it('should handle missing optional fields', () => {
            // Minimal notification with only required fields
            statusHandler({
                health: 'ok',
                quiescent: true
            });

            assert.include(statusBarItemStub.text, '$(pass-filled)');
        });
    });
});
