import * as path from 'path';
import { ExtensionContext } from 'vscode';
import { IFileSystem, NodeFileSystem } from './FileSystem';

export interface ServerResolverConfig {
    serverPath?: string;
}

export class ServerResolver {
    private fs: IFileSystem;

    constructor(fs: IFileSystem = new NodeFileSystem()) {
        this.fs = fs;
    }

    async resolve(context: ExtensionContext, config: ServerResolverConfig): Promise<string> {
        if (config.serverPath) {
            if (this.fs.existsSync(config.serverPath)) {
                return config.serverPath;
            }
            throw new Error(`Custom server path not found: ${config.serverPath}`);
        }

        const serverDir = context.asAbsolutePath('server');
        const jarPath = path.join(serverDir, 'gls.jar');

        if (this.fs.existsSync(jarPath)) {
            return jarPath;
        }

        throw new Error(`Groovy Language Server JAR not found at ${jarPath}`);
    }
}
