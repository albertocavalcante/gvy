import type { Memento } from 'vscode';
import type { ReleaseInfo } from '../../../../src/features/update/VersionChecker';
import type { ReleaseProvider } from '../../../../src/features/update/ReleaseProvider';
import type { Clock } from '../../../../src/features/update/Clock';

export function createMementoStub(initial: Record<string, unknown> = {}): Memento {
    const store = new Map<string, unknown>(Object.entries(initial));

    function get<T>(key: string): T | undefined;
    function get<T>(key: string, defaultValue: T): T;
    function get<T>(key: string, defaultValue?: T): T | undefined {
        if (store.has(key)) {
            return store.get(key) as T;
        }
        return defaultValue;
    }

    const memento: Memento = {
        keys: () => Array.from(store.keys()),
        get,
        update: async (key: string, value: any): Promise<void> => {
            if (value === undefined) {
                store.delete(key);
                return;
            }
            store.set(key, value);
        }
    };

    return memento;
}

export function sampleRelease(version: string = '1.2.3'): ReleaseInfo {
    return {
        tagName: `v${version}`,
        version,
        releaseUrl: `https://github.com/x/y/releases/tag/v${version}`,
        downloadUrl: `https://example.invalid/groovy-lsp-${version}.jar`,
        publishedAt: '2020-01-01T00:00:00Z'
    };
}

export function createReleaseProviderStub(release: ReleaseInfo | null = null): ReleaseProvider {
    return {
        fetchLatestRelease: async () => release
    };
}

export function createClockStub(timestamp: number = 1000000): Clock {
    return {
        now: () => timestamp
    };
}
