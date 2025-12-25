import type { Memento } from 'vscode';
import type { ReleaseInfo } from './VersionChecker';

const CACHE_KEY = 'groovy.update.cachedRelease';
const MS_PER_HOUR = 60 * 60 * 1000;
const MINIMUM_CHECK_INTERVAL_HOURS = 1;

export interface CachedRelease {
    release: ReleaseInfo;
    checkedAt: number;
    expiresAt: number;
}

export class VersionCache {
    private readonly globalState: Memento;
    private readonly checkIntervalHours: number;

    constructor(globalState: Memento, checkIntervalHours: number = 24) {
        this.globalState = globalState;
        this.checkIntervalHours = Math.max(MINIMUM_CHECK_INTERVAL_HOURS, checkIntervalHours);
    }

    getCachedRelease(): CachedRelease | null {
        const cached = this.globalState.get<CachedRelease>(CACHE_KEY);
        if (!cached || this.isExpired(cached)) {
            return null;
        }

        return cached;
    }

    async setCachedRelease(release: ReleaseInfo): Promise<void> {
        const now = Date.now();
        const expiresAt = now + this.checkIntervalHours * MS_PER_HOUR;
        const cached: CachedRelease = {
            release,
            checkedAt: now,
            expiresAt
        };
        await this.globalState.update(CACHE_KEY, cached);
    }

    async clear(): Promise<void> {
        await this.globalState.update(CACHE_KEY, undefined);
    }

    private isExpired(cached: CachedRelease): boolean {
        return Date.now() >= cached.expiresAt;
    }
}
