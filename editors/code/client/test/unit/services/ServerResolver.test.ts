import { expect } from 'chai';
import * as path from 'path';
import { ServerResolver } from '../../../src/services/ServerResolver';
import { IFileSystem } from '../../../src/services/FileSystem';

class MockFileSystem implements IFileSystem {
    private existingPaths: Set<string> = new Set();

    setExists(path: string, exists: boolean) {
        if (exists) {
            this.existingPaths.add(path);
        } else {
            this.existingPaths.delete(path);
        }
    }

    existsSync(path: string): boolean {
        return this.existingPaths.has(path);
    }
}

describe('ServerResolver', () => {
    let resolver: ServerResolver;
    let mockFs: MockFileSystem;
    let mockContext: any;

    beforeEach(() => {
        mockFs = new MockFileSystem();
        resolver = new ServerResolver(mockFs);
        mockContext = {
            asAbsolutePath: (p: string) => path.join('/mock/extension/root', p)
        };
    });

    it('should resolve to the custom path when configured and file exists', async () => {
        const customPath = '/custom/path/to/server.jar';
        mockFs.setExists(customPath, true);

        const result = await resolver.resolve(mockContext, { serverPath: customPath });

        expect(result).to.equal(customPath);
    });

    it('should throw an error when custom path is configured but does not exist', async () => {
        const customPath = '/invalid/path/server.jar';
        mockFs.setExists(customPath, false);

        try {
            await resolver.resolve(mockContext, { serverPath: customPath });
            expect.fail('Should have thrown an error');
        } catch (error: any) {
            expect(error.message).to.contain('Custom server path not found');
        }
    });

    it('should resolve to the embedded server path when no custom path is configured', async () => {
        const expectedPath = path.join('/mock/extension/root', 'server', 'gls.jar');
        mockFs.setExists(expectedPath, true);

        const result = await resolver.resolve(mockContext, { serverPath: undefined });

        expect(result).to.equal(expectedPath);
    });

    it('should throw an error when neither custom path nor embedded server exists', async () => {
        const expectedPath = path.join('/mock/extension/root', 'server', 'gls.jar');
        mockFs.setExists(expectedPath, false);

        try {
            await resolver.resolve(mockContext, { serverPath: undefined });
            expect.fail('Should have thrown an error');
        } catch (error: any) {
            expect(error.message).to.contain('Groovy Language Server JAR not found');
        }
    });
});