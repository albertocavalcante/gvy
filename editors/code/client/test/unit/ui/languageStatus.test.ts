import * as sinon from 'sinon';
import { assert } from 'chai';
import * as proxyquire from 'proxyquire';

describe('LanguageStatus', () => {
    let mockVscode: any;
    let languageStatusModule: any;
    let serverStatusItemStub: any;
    let javaRuntimeItemStub: any;
    let buildToolItemStub: any;
    let createLanguageStatusItemStub: sinon.SinonStub;

    beforeEach(() => {
        // Create stubs for language status items
        serverStatusItemStub = {
            name: '',
            text: '',
            detail: '',
            severity: 0,
            command: undefined,
            dispose: sinon.stub()
        };

        javaRuntimeItemStub = {
            name: '',
            text: '',
            detail: '',
            severity: 0,
            command: undefined,
            dispose: sinon.stub()
        };

        buildToolItemStub = {
            name: '',
            text: '',
            detail: '',
            severity: 0,
            command: undefined,
            dispose: sinon.stub()
        };

        // Track which item to return based on ID
        createLanguageStatusItemStub = sinon.stub().callsFake((id: string) => {
            if (id === 'groovy.server') return serverStatusItemStub;
            if (id === 'groovy.javaRuntime') return javaRuntimeItemStub;
            if (id === 'groovy.buildTool') return buildToolItemStub;
            // Fallback for any other ID
            return {
                name: '',
                text: '',
                detail: '',
                severity: 0,
                command: undefined,
                dispose: sinon.stub()
            };
        });

        mockVscode = {
            languages: {
                createLanguageStatusItem: createLanguageStatusItemStub
            },
            LanguageStatusSeverity: {
                Information: 0,
                Warning: 1,
                Error: 2
            }
        };

        // Mock statusBar module to avoid circular dependency
        const mockStatusBar = {
            ServerState: {}
        };

        // Mock finder module
        const mockFinder = {
            JavaResolution: {}
        };

        // Load module with mocked dependencies
        languageStatusModule = proxyquire.noCallThru()('../../../src/ui/languageStatus', {
            'vscode': mockVscode,
            './statusBar': mockStatusBar,
            '../java/finder': mockFinder
        });
    });

    afterEach(() => {
        sinon.restore();
        // Reset singleton
        languageStatusModule.disposeLanguageStatusManager();
    });

    describe('createLanguageStatusManager', () => {
        it('should create language status manager', () => {
            const manager = languageStatusModule.createLanguageStatusManager();

            assert.isDefined(manager);
            assert.isTrue(createLanguageStatusItemStub.calledOnce);
        });

        it('should create server status item with correct id', () => {
            languageStatusModule.createLanguageStatusManager();

            assert.isTrue(createLanguageStatusItemStub.calledWith(
                'groovy.server',
                sinon.match.array
            ));
        });

        it('should return singleton instance', () => {
            const manager1 = languageStatusModule.createLanguageStatusManager();
            const manager2 = languageStatusModule.createLanguageStatusManager();

            assert.strictEqual(manager1, manager2);
        });
    });

    describe('getLanguageStatusManager', () => {
        it('should return undefined before creation', () => {
            const manager = languageStatusModule.getLanguageStatusManager();

            assert.isUndefined(manager);
        });

        it('should return manager after creation', () => {
            languageStatusModule.createLanguageStatusManager();
            const manager = languageStatusModule.getLanguageStatusManager();

            assert.isDefined(manager);
        });
    });

    describe('LanguageStatusManager.updateServerStatus', () => {
        it('should update text for stopped state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('stopped', 'unknown');

            assert.include(serverStatusItemStub.text, 'Stopped');
            assert.equal(serverStatusItemStub.severity, mockVscode.LanguageStatusSeverity.Error);
        });

        it('should update text for starting state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('starting', 'unknown');

            assert.include(serverStatusItemStub.text, 'Starting');
            assert.equal(serverStatusItemStub.severity, mockVscode.LanguageStatusSeverity.Information);
        });

        it('should update text for resolving-deps state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('resolving-deps', 'unknown', 'Loading Gradle...');

            assert.include(serverStatusItemStub.text, 'Dependencies');
            assert.include(serverStatusItemStub.detail, 'Loading Gradle');
        });

        it('should update text for indexing state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('indexing', 'unknown', 'Analyzing files...');

            assert.include(serverStatusItemStub.text, 'Indexing');
            assert.include(serverStatusItemStub.detail, 'Analyzing files');
        });

        it('should show file counts during indexing', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('indexing', 'unknown', undefined, 25, 100);

            // Should show file counts in text
            assert.include(serverStatusItemStub.text, '25/100');
            // Should show detailed progress in detail
            assert.include(serverStatusItemStub.detail, 'Indexing 25 of 100 files');
        });

        it('should show percentage during indexing', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('indexing', 'unknown', undefined, 50, 100);

            // Should show percentage in detail
            assert.include(serverStatusItemStub.detail, '50%');
        });

        it('should handle zero filesTotal during indexing', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('indexing', 'unknown', undefined, 0, 0);

            // Should fall back to generic indexing text
            assert.include(serverStatusItemStub.text, 'Indexing');
            assert.notInclude(serverStatusItemStub.text, '/');
        });

        it('should use message when no file counts provided', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('indexing', 'unknown', 'Custom indexing message');

            assert.include(serverStatusItemStub.detail, 'Custom indexing message');
        });

        it('should update text for ready state with version', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('ready', '0.4.8');

            assert.include(serverStatusItemStub.text, 'v0.4.8');
            assert.equal(serverStatusItemStub.severity, mockVscode.LanguageStatusSeverity.Information);
        });

        it('should update text for degraded state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('degraded', 'unknown', 'Build failed');

            assert.include(serverStatusItemStub.text, 'Degraded');
            assert.equal(serverStatusItemStub.severity, mockVscode.LanguageStatusSeverity.Warning);
        });

        it('should set restart command for stopped state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('stopped', 'unknown');

            assert.equal(serverStatusItemStub.command.command, 'groovy.restartServer');
        });

        it('should set problems command for degraded state', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('degraded', 'unknown');

            assert.equal(serverStatusItemStub.command.command, 'workbench.action.problems.focus');
        });

        it('should set status menu command for other states', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateServerStatus('ready', 'unknown');

            assert.equal(serverStatusItemStub.command.command, 'groovy.showStatusMenu');
        });
    });

    describe('LanguageStatusManager.updateJavaRuntime', () => {
        it('should dispose previous item when setting undefined', () => {
            const manager = languageStatusModule.createLanguageStatusManager();

            // First set a resolution
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-21', version: 21, source: 'java_home' });
            // Then clear it
            manager.updateJavaRuntime(undefined);

            assert.isTrue(javaRuntimeItemStub.dispose.calledOnce);
        });

        it('should create item with Java version', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-21', version: 21, source: 'java_home' });

            assert.include(javaRuntimeItemStub.text, 'Java 21');
        });

        it('should show source in detail for java_home', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-21', version: 21, source: 'java_home' });

            assert.include(javaRuntimeItemStub.detail, 'JAVA_HOME');
        });

        it('should show source in detail for jdk_manager', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/home/user/.sdkman/candidates/java/21-tem', version: 21, source: 'jdk_manager' });

            assert.include(javaRuntimeItemStub.detail, 'JDK manager');
        });

        it('should show warning severity for old Java versions', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-11', version: 11, source: 'system' });

            assert.equal(javaRuntimeItemStub.severity, mockVscode.LanguageStatusSeverity.Warning);
        });

        it('should show info severity for Java 17+', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-17', version: 17, source: 'system' });

            assert.equal(javaRuntimeItemStub.severity, mockVscode.LanguageStatusSeverity.Information);
        });
    });

    describe('LanguageStatusManager.updateBuildTool', () => {
        it('should dispose previous item when setting undefined', () => {
            const manager = languageStatusModule.createLanguageStatusManager();

            // First set build tool
            manager.updateBuildTool({ type: 'gradle', buildFile: 'build.gradle' });
            // Then clear it
            manager.updateBuildTool(undefined);

            assert.isTrue(buildToolItemStub.dispose.calledOnce);
        });

        it('should show Gradle for gradle type', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateBuildTool({ type: 'gradle', buildFile: 'build.gradle' });

            assert.include(buildToolItemStub.text, 'Gradle');
            assert.equal(buildToolItemStub.detail, 'build.gradle');
        });

        it('should show Maven for maven type', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateBuildTool({ type: 'maven', buildFile: 'pom.xml' });

            assert.include(buildToolItemStub.text, 'Maven');
            assert.equal(buildToolItemStub.detail, 'pom.xml');
        });
    });

    describe('LanguageStatusManager.dispose', () => {
        it('should dispose all status items', () => {
            const manager = languageStatusModule.createLanguageStatusManager();
            manager.updateJavaRuntime({ path: '/usr/lib/jvm/java-21', version: 21, source: 'system' });
            manager.updateBuildTool({ type: 'gradle', buildFile: 'build.gradle' });

            manager.dispose();

            assert.isTrue(serverStatusItemStub.dispose.calledOnce);
            assert.isTrue(javaRuntimeItemStub.dispose.calledOnce);
            assert.isTrue(buildToolItemStub.dispose.calledOnce);
        });
    });
});

