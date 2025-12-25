import { languages, ExtensionContext, workspace } from 'vscode';
import { getClient } from '../../server/client';
import { GroovyFormattingProvider } from './GroovyFormattingProvider';

export function registerFormatting(context: ExtensionContext) {
    const config = workspace.getConfiguration('groovy');
    if (!config.get('format.enable', true)) {
        return;
    }

    const client = getClient();
    if (!client) {
        // Client not ready yet. Ideally we should wait or listen for client start.
        // For now, we assume this is called after client start or client is singleton.
        // But wait, extension.ts calls startClient() at the end.
        // We should probably register this AFTER startClient.
        console.warn('Groovy Formatting Provider: Language Client not found.');
        return;
    }

    const provider = new GroovyFormattingProvider(client);
    const selector = [
        { language: 'groovy', scheme: 'file' },
        { language: 'jenkinsfile', scheme: 'file' }
    ];

    context.subscriptions.push(
        languages.registerDocumentFormattingEditProvider(selector, provider)
    );
}
