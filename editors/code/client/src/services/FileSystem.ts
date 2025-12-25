import * as fs from 'fs';

export interface IFileSystem {
    existsSync(path: string): boolean;
}

export class NodeFileSystem implements IFileSystem {
    existsSync(path: string): boolean {
        return fs.existsSync(path);
    }
}
