
// Main script for Groovy AST Visualization
// Handles communication with VSCode and renders the AST tree

// Type definitions for VSCode API and AST
interface VsCodeApi {
    postMessage(message: unknown): void;
    getState(): unknown;
    setState(state: unknown): void;
}

declare function acquireVsCodeApi(): VsCodeApi;

interface AstNode {
    id: string;
    type: string;
    range?: {
        startLine: number;
        startColumn: number;
        endLine: number;
        endColumn: number;
    };
    children?: AstNode[];
    properties?: Record<string, string>;
}

interface AppState {
    ast: AstNode | null;
    parser: string;
}

(function () {
    const vscode = acquireVsCodeApi();

    // Application State
    // Application State
    const state: AppState = {
        ast: null,
        parser: 'core'
    };

    // DOM Elements
    const treeContainer = document.getElementById('tree-container') as HTMLElement;
    const detailsContainer = document.getElementById('details-container') as HTMLElement;
    const parserSelect = document.getElementById('parser-select') as HTMLSelectElement;
    const refreshBtn = document.getElementById('btn-refresh') as HTMLButtonElement;
    const exportBtn = document.getElementById('btn-export') as HTMLButtonElement;

    // Event Listeners
    if (parserSelect) {
        parserSelect.addEventListener('change', () => {
            state.parser = parserSelect.value;
            vscode.postMessage({ type: 'refresh', parser: state.parser });
        });
    }

    if (refreshBtn) {
        refreshBtn.addEventListener('click', () => {
            vscode.postMessage({ type: 'refresh', parser: state.parser });
        });
    }

    if (exportBtn) {
        exportBtn.addEventListener('click', () => {
            if (state.ast) {
                const json = JSON.stringify(state.ast, null, 2);
                navigator.clipboard.writeText(json).then(() => {
                    exportBtn.textContent = 'Copied!';
                    setTimeout(() => exportBtn.textContent = 'Export JSON', 2000);
                });
            }
        });
    }

    // Message Handler
    window.addEventListener('message', event => {
        const message = event.data;
        switch (message.type) {
            case 'updateAst':
                state.ast = message.ast;
                state.parser = message.parser;
                if (parserSelect) parserSelect.value = message.parser;
                renderTree();
                break;
            case 'error':
                if (treeContainer) {
                    treeContainer.innerHTML = '';
                    const errorDiv = document.createElement('div');
                    errorDiv.className = 'empty-state';
                    errorDiv.textContent = message.message as string;
                    treeContainer.appendChild(errorDiv);
                }
                if (detailsContainer) detailsContainer.textContent = 'Select a node to view details';
                break;
        }
    });

    /**
     * Renders the AST tree into the container
     */
    function renderTree() {
        if (!treeContainer) return;

        if (!state.ast) {
            treeContainer.innerHTML = '<div class="empty-state">No AST available</div>';
            return;
        }

        treeContainer.innerHTML = '';
        const root = createTreeNode(state.ast);
        treeContainer.appendChild(root);
    }

    /**
     * Creates a DOM element for a single AST node
     */
    function createTreeNode(node: AstNode): HTMLElement {
        const element = document.createElement('div');
        element.className = 'tree-node';
        element.dataset.id = node.id;

        // Node Content (Label)
        const content = document.createElement('div');
        content.className = 'node-content';

        // Toggle Arrow (if children exist)
        if (node.children && node.children.length > 0) {
            const toggle = document.createElement('span');
            toggle.className = 'node-toggle codicon codicon-chevron-right';
            toggle.onclick = (e) => {
                e.stopPropagation();
                element.classList.toggle('expanded');
                toggle.classList.toggle('codicon-chevron-down');
                toggle.classList.toggle('codicon-chevron-right');
            };
            content.appendChild(toggle);

            // Allow clicking content to expand too
            content.onclick = () => {
                element.classList.toggle('expanded');
                toggle.classList.toggle('codicon-chevron-down');
                toggle.classList.toggle('codicon-chevron-right');
                showDetails(node);
            };
        } else {
            const spacer = document.createElement('span');
            spacer.className = 'node-spacer';
            content.appendChild(spacer);
            content.onclick = () => showDetails(node);
        }

        // Icon based on node type
        const icon = document.createElement('span');
        icon.className = `node-icon codicon ${getNodeIcon(node.type)}`;
        content.appendChild(icon);

        // Type Label
        const label = document.createElement('span');
        label.className = 'node-label';
        label.textContent = node.type;
        content.appendChild(label);

        // Properties Preview (if relevant)
        if (node.properties && node.properties['name']) {
            const name = document.createElement('span');
            name.className = 'node-name';
            name.textContent = node.properties['name'];
            content.appendChild(name);
        }

        element.appendChild(content);

        // Children Container
        if (node.children && node.children.length > 0) {
            const childrenContainer = document.createElement('div');
            childrenContainer.className = 'node-children';
            node.children.forEach(child => {
                childrenContainer.appendChild(createTreeNode(child));
            });
            element.appendChild(childrenContainer);
        }

        return element;
    }

    /**
     * Shows details for the selected node
     */
    function showDetails(node: AstNode) {
        if (!detailsContainer) return;

        // Highlight selection
        document.querySelectorAll('.node-content.selected').forEach(el => el.classList.remove('selected'));
        // Escape ID for CSS selector
        const escapedId = CSS.escape(node.id);
        const selected = treeContainer.querySelector(`[data-id="${escapedId}"] > .node-content`);
        if (selected) selected.classList.add('selected');

        // Clear container
        detailsContainer.innerHTML = '';

        // Header
        const header = document.createElement('h3');
        header.textContent = node.type;
        detailsContainer.appendChild(header);

        const table = document.createElement('table');
        table.className = 'details-table';

        // Helper to add row
        const addRow = (key: string, value: string) => {
            const row = document.createElement('tr');
            const keyCell = document.createElement('td');
            keyCell.className = 'key';
            keyCell.textContent = key;
            const valueCell = document.createElement('td');
            valueCell.className = 'value';
            valueCell.textContent = value;
            row.appendChild(keyCell);
            row.appendChild(valueCell);
            table.appendChild(row);
        };

        // Add ID
        addRow('ID', node.id);

        // Add Range
        if (node.range) {
            addRow('Range', `${node.range.startLine}:${node.range.startColumn} - ${node.range.endLine}:${node.range.endColumn}`);
        }

        // Add Properties
        if (node.properties) {
            for (const [key, value] of Object.entries(node.properties)) {
                addRow(key, String(value));
            }
        }

        detailsContainer.appendChild(table);

        // Notify extension to highlight range
        if (node.range) {
            vscode.postMessage({
                type: 'highlight',
                range: node.range
            });
        }
    }

    /**
     * Returns a codicon class for the given node type
     */
    function getNodeIcon(type: string): string {
        if (type.includes('Class')) return 'codicon-symbol-class';
        if (type.includes('Method')) return 'codicon-symbol-method';
        if (type.includes('Field')) return 'codicon-symbol-field';
        if (type.includes('Property')) return 'codicon-symbol-property';
        if (type.includes('Import')) return 'codicon-symbol-interface';
        return 'codicon-symbol-misc';
    }

    // Signal ready
    vscode.postMessage({ type: 'ready' });
})();
