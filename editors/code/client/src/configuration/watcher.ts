import { workspace, Disposable, window } from 'vscode';
import {
    requiresServerRestart,
    canBeAppliedDynamically
} from './settings';
import { restartClient, getClient, buildServerSettingsMap } from '../server/client';

/**
 * Sets up configuration change watchers
 */
export function setupConfigurationWatcher(): Disposable {
    return workspace.onDidChangeConfiguration(async (event) => {
        // Check if any groovy.* configuration changed
        if (!event.affectsConfiguration('groovy')) {
            return;
        }

        // Handle settings that require a server restart
        if (requiresServerRestart(event)) {
            const answer = await window.showInformationMessage(
                'Groovy configuration changed. The language server needs to be restarted for changes to take effect.',
                'Restart Now',
                'Restart Later'
            );

            if (answer === 'Restart Now') {
                console.log('Configuration changed, restarting server...');
                await restartClient();
            }
            return;
        }

        // Handle settings that can be applied dynamically
        if (canBeAppliedDynamically(event)) {
            console.log('Configuration changed, notifying server...');
            const client = getClient();
            if (client) {
                client.sendNotification('workspace/didChangeConfiguration', {
                    settings: buildServerSettingsMap()
                });
                console.log('Sent workspace/didChangeConfiguration to server');
            }
        }
    });
}
