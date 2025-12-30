import * as assert from 'assert';
import { determineStateFromStatus, inferStateFromMessage, GroovyStatusParams } from '../../ui/statusUtils';

describe('Status Utils', () => {
    describe('determineStateFromStatus', () => {
        it('should return error state on error health', () => {
            const params: GroovyStatusParams = {
                health: 'error',
                quiescent: true
            };
            assert.strictEqual(determineStateFromStatus(params), 'error');
        });

        it('should return error state when errorCode is present', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: true,
                errorCode: 'GRADLE_JDK_INCOMPATIBLE'
            };
            assert.strictEqual(determineStateFromStatus(params), 'error');
        });

        it('should return degraded on warning', () => {
            const params: GroovyStatusParams = {
                health: 'warning',
                quiescent: true
            };
            assert.strictEqual(determineStateFromStatus(params), 'degraded');
        });

        it('should return ready when quiescent and healthy', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: true
            };
            assert.strictEqual(determineStateFromStatus(params), 'ready');
        });

        it('should return resolving-deps when message indicates so', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: false,
                message: 'Resolving dependencies...'
            };
            assert.strictEqual(determineStateFromStatus(params), 'resolving-deps');
        });

        it('should return indexing when message indicates so', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: false,
                message: 'Indexing files...'
            };
            assert.strictEqual(determineStateFromStatus(params), 'indexing');
        });

        it('should default to starting if unknown message', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: false,
                message: 'Doing something weird...'
            };
            assert.strictEqual(determineStateFromStatus(params), 'starting');
        });

        it('should return indexing if filesTotal > 0', () => {
            const params: GroovyStatusParams = {
                health: 'ok',
                quiescent: false,
                filesTotal: 100
            };
            assert.strictEqual(determineStateFromStatus(params), 'indexing');
        });
    });

    describe('inferStateFromMessage', () => {
        it('should return undefined if filesTotal is set', () => {
            assert.strictEqual(inferStateFromMessage('error', 10), undefined);
        });

        it('should infer degraded from error message', () => {
            assert.strictEqual(inferStateFromMessage('Something failed', undefined), 'degraded');
        });

        it('should infer resolving-deps', () => {
            assert.strictEqual(inferStateFromMessage('Resolving Gradle dependencies', undefined), 'resolving-deps');
        });

        it('should infer indexing', () => {
            assert.strictEqual(inferStateFromMessage('Indexing 50 files', undefined), 'indexing');
        });

        it('should infer ready', () => {
            assert.strictEqual(inferStateFromMessage('Service Ready', undefined), 'ready');
        });

        it('should return undefined for unknown messages', () => {
            assert.strictEqual(inferStateFromMessage('Unknown state', undefined), undefined);
        });
    });
});
