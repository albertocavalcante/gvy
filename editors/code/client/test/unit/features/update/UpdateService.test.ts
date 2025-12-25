import * as sinon from 'sinon';
import { assert } from 'chai';
import * as proxyquire from 'proxyquire';
import { createMementoStub, sampleRelease } from './testUtils';

describe('UpdateService', () => {
    let mockWindow: any;
    let mockWorkspace: any;
    let mockEnv: any;
    let mockUri: any;
    let checkForUpdateStub: sinon.SinonStub;
    let checkForUpdateNowStub: sinon.SinonStub;
    let getUpdateConfigurationStub: sinon.SinonStub;

    function createUpdateService(overrides: {
        checkNowResult?: any;
        showInfoResponse?: string;
    } = {}) {
        // Create fresh stubs for each test
        mockWindow = {
            showInformationMessage: sinon.stub().resolves(overrides.showInfoResponse ?? undefined),
            showWarningMessage: sinon.stub()
        };

        const updateStub = sinon.stub().resolves();
        mockWorkspace = {
            getConfiguration: sinon.stub().returns({ update: updateStub })
        };

        mockEnv = {
            openExternal: sinon.stub().resolves(true)
        };

        mockUri = {
            parse: sinon.stub().callsFake((url: string) => ({ toString: () => url }))
        };

        checkForUpdateStub = sinon.stub().resolves({
            status: 'up-to-date',
            currentVersion: '1.0.0',
            latestRelease: sampleRelease('1.0.0'),
            checkedAt: Date.now(),
            source: 'network'
        });

        checkForUpdateNowStub = sinon.stub().resolves(
            overrides.checkNowResult ?? {
                status: 'up-to-date',
                currentVersion: '1.0.0',
                latestRelease: sampleRelease('1.0.0'),
                checkedAt: Date.now(),
                source: 'network'
            }
        );

        getUpdateConfigurationStub = sinon.stub().returns({
            checkOnStartup: true,
            checkIntervalHours: 24,
            notifications: 'onlyWhenOutdated'
        });

        const UpdateServiceModule = proxyquire.noCallThru()(
            '../../../../src/features/update/UpdateService',
            {
                vscode: {
                    window: mockWindow,
                    workspace: mockWorkspace,
                    env: mockEnv,
                    Uri: mockUri,
                    Memento: {}
                },
                './index': {
                    UpdateChecker: class {
                        checkForUpdate = checkForUpdateStub;
                        checkForUpdateNow = checkForUpdateNowStub;
                    },
                    VersionCache: class { },
                    SystemClock: class { now() { return Date.now(); } }
                },
                './GitHubReleaseProvider': {
                    GitHubReleaseProvider: class {
                        fetchLatestRelease = sinon.stub().resolves(sampleRelease('2.0.0'));
                    }
                },
                '../../configuration/settings': {
                    getUpdateConfiguration: getUpdateConfigurationStub,
                    UpdateNotificationLevel: {}
                }
            }
        );

        const memento = createMementoStub();
        return new UpdateServiceModule.UpdateService('1.0.0', memento);
    }

    afterEach(() => {
        sinon.restore();
    });

    describe('checkNow', () => {
        it('calls checkForUpdateNow and shows result for up-to-date', async () => {
            const updateService = createUpdateService();

            await updateService.checkNow();

            assert.isTrue(checkForUpdateNowStub.calledOnce, 'Should call checkForUpdateNow');
            assert.isTrue(mockWindow.showInformationMessage.calledOnce, 'Should show message');
            updateService.dispose();
        });

        it('shows update available notification with actions', async () => {
            const updateService = createUpdateService({
                checkNowResult: {
                    status: 'update-available',
                    currentVersion: '1.0.0',
                    latestRelease: sampleRelease('2.0.0'),
                    checkedAt: Date.now(),
                    source: 'network'
                }
            });

            await updateService.checkNow();

            assert.isTrue(mockWindow.showInformationMessage.calledOnce);
            const [message, ...actions] = mockWindow.showInformationMessage.firstCall.args;
            assert.include(message, '2.0.0');
            assert.include(message, '1.0.0');
            assert.include(actions, 'Open Release');
            assert.include(actions, 'Download');
            assert.include(actions, "Don't Show Again");
            updateService.dispose();
        });

        it('shows warning on error status', async () => {
            const updateService = createUpdateService({
                checkNowResult: {
                    status: 'error',
                    currentVersion: '1.0.0',
                    latestRelease: null,
                    checkedAt: Date.now(),
                    source: 'network',
                    error: 'Network error'
                }
            });

            await updateService.checkNow();

            assert.isTrue(mockWindow.showWarningMessage.calledOnce);
            const [message] = mockWindow.showWarningMessage.firstCall.args;
            assert.include(message, 'Failed to check for updates');
            updateService.dispose();
        });

        it('opens release URL when Open Release action selected', async () => {
            const updateService = createUpdateService({
                checkNowResult: {
                    status: 'update-available',
                    currentVersion: '1.0.0',
                    latestRelease: sampleRelease('2.0.0'),
                    checkedAt: Date.now(),
                    source: 'network'
                },
                showInfoResponse: 'Open Release'
            });

            await updateService.checkNow();

            assert.isTrue(mockEnv.openExternal.calledOnce);
            updateService.dispose();
        });

        it('opens download URL when Download action selected', async () => {
            const updateService = createUpdateService({
                checkNowResult: {
                    status: 'update-available',
                    currentVersion: '1.0.0',
                    latestRelease: sampleRelease('2.0.0'),
                    checkedAt: Date.now(),
                    source: 'network'
                },
                showInfoResponse: 'Download'
            });

            await updateService.checkNow();

            assert.isTrue(mockEnv.openExternal.calledOnce);
            updateService.dispose();
        });

        it('disables notifications when Don\'t Show Again selected', async () => {
            const updateService = createUpdateService({
                checkNowResult: {
                    status: 'update-available',
                    currentVersion: '1.0.0',
                    latestRelease: sampleRelease('2.0.0'),
                    checkedAt: Date.now(),
                    source: 'network'
                },
                showInfoResponse: "Don't Show Again"
            });

            await updateService.checkNow();

            // After Don't Show Again, configuration should be updated
            const updateCall = mockWorkspace.getConfiguration().update;
            assert.isTrue(updateCall.calledWith('update.notifications', 'off', true));
            updateService.dispose();
        });
    });

    describe('dispose', () => {
        it('clears scheduled timeout without error', () => {
            const updateService = createUpdateService();
            // Should not throw
            updateService.dispose();
        });

        it('can be called multiple times safely', () => {
            const updateService = createUpdateService();
            updateService.dispose();
            updateService.dispose();
            // Should not throw
        });
    });
});
