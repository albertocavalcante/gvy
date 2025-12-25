import { defineConfig } from '@vscode/test-cli';

export default defineConfig([
    {
        label: 'integrationTests',
        files: 'client/out/test/integration/**/*.test.js',
        version: 'stable',
        workspaceFolder: './',
        mocha: {
            ui: 'tdd',
            timeout: 30000
        }
    }
]);
