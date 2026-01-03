"use strict";
(() => {
  // client/src/features/ast/webview/main.ts
  (function() {
    const vscode = acquireVsCodeApi();
    const state = {
      ast: null,
      parser: "core"
    };
    const treeContainer = document.getElementById("tree-container");
    const detailsContainer = document.getElementById("details-container");
    const parserSelect = document.getElementById("parser-select");
    const refreshBtn = document.getElementById("btn-refresh");
    const exportBtn = document.getElementById("btn-export");
    if (parserSelect) {
      parserSelect.addEventListener("change", () => {
        state.parser = parserSelect.value;
        vscode.postMessage({ type: "refresh", parser: state.parser });
      });
    }
    if (refreshBtn) {
      refreshBtn.addEventListener("click", () => {
        vscode.postMessage({ type: "refresh", parser: state.parser });
      });
    }
    if (exportBtn) {
      exportBtn.addEventListener("click", () => {
        if (state.ast) {
          const json = JSON.stringify(state.ast, null, 2);
          navigator.clipboard.writeText(json).then(() => {
            exportBtn.textContent = "Copied!";
            setTimeout(() => exportBtn.textContent = "Export JSON", 2e3);
          });
        }
      });
    }
    window.addEventListener("message", (event) => {
      const message = event.data;
      switch (message.type) {
        case "updateAst":
          state.ast = message.ast;
          state.parser = message.parser;
          if (parserSelect) parserSelect.value = message.parser;
          renderTree();
          break;
        case "error":
          if (treeContainer) {
            treeContainer.innerHTML = "";
            const errorDiv = document.createElement("div");
            errorDiv.className = "empty-state";
            errorDiv.textContent = message.message;
            treeContainer.appendChild(errorDiv);
          }
          if (detailsContainer) detailsContainer.textContent = "Select a node to view details";
          break;
      }
    });
    function renderTree() {
      if (!treeContainer) return;
      if (!state.ast) {
        treeContainer.innerHTML = '<div class="empty-state">No AST available</div>';
        return;
      }
      treeContainer.innerHTML = "";
      const root = createTreeNode(state.ast);
      treeContainer.appendChild(root);
    }
    function createTreeNode(node) {
      const element = document.createElement("div");
      element.className = "tree-node";
      element.dataset.id = node.id;
      const content = document.createElement("div");
      content.className = "node-content";
      if (node.children && node.children.length > 0) {
        const toggle = document.createElement("span");
        toggle.className = "node-toggle codicon codicon-chevron-right";
        toggle.onclick = (e) => {
          e.stopPropagation();
          element.classList.toggle("expanded");
          toggle.classList.toggle("codicon-chevron-down");
          toggle.classList.toggle("codicon-chevron-right");
        };
        content.appendChild(toggle);
        content.onclick = () => {
          element.classList.toggle("expanded");
          toggle.classList.toggle("codicon-chevron-down");
          toggle.classList.toggle("codicon-chevron-right");
          showDetails(node);
        };
      } else {
        const spacer = document.createElement("span");
        spacer.className = "node-spacer";
        content.appendChild(spacer);
        content.onclick = () => showDetails(node);
      }
      const icon = document.createElement("span");
      icon.className = `node-icon codicon ${getNodeIcon(node.type)}`;
      content.appendChild(icon);
      const label = document.createElement("span");
      label.className = "node-label";
      label.textContent = node.type;
      content.appendChild(label);
      if (node.properties && node.properties["name"]) {
        const name = document.createElement("span");
        name.className = "node-name";
        name.textContent = node.properties["name"];
        content.appendChild(name);
      }
      element.appendChild(content);
      if (node.children && node.children.length > 0) {
        const childrenContainer = document.createElement("div");
        childrenContainer.className = "node-children";
        node.children.forEach((child) => {
          childrenContainer.appendChild(createTreeNode(child));
        });
        element.appendChild(childrenContainer);
      }
      return element;
    }
    function showDetails(node) {
      if (!detailsContainer) return;
      document.querySelectorAll(".node-content.selected").forEach((el) => el.classList.remove("selected"));
      const escapedId = CSS.escape(node.id);
      const selected = treeContainer.querySelector(`[data-id="${escapedId}"] > .node-content`);
      if (selected) selected.classList.add("selected");
      detailsContainer.innerHTML = "";
      const header = document.createElement("h3");
      header.textContent = node.type;
      detailsContainer.appendChild(header);
      const table = document.createElement("table");
      table.className = "details-table";
      const addRow = (key, value) => {
        const row = document.createElement("tr");
        const keyCell = document.createElement("td");
        keyCell.className = "key";
        keyCell.textContent = key;
        const valueCell = document.createElement("td");
        valueCell.className = "value";
        valueCell.textContent = value;
        row.appendChild(keyCell);
        row.appendChild(valueCell);
        table.appendChild(row);
      };
      addRow("ID", node.id);
      if (node.range) {
        addRow("Range", `${node.range.startLine}:${node.range.startColumn} - ${node.range.endLine}:${node.range.endColumn}`);
      }
      if (node.properties) {
        for (const [key, value] of Object.entries(node.properties)) {
          addRow(key, String(value));
        }
      }
      detailsContainer.appendChild(table);
      if (node.range) {
        vscode.postMessage({
          type: "highlight",
          range: node.range
        });
      }
    }
    function getNodeIcon(type) {
      if (type.includes("Class")) return "codicon-symbol-class";
      if (type.includes("Method")) return "codicon-symbol-method";
      if (type.includes("Field")) return "codicon-symbol-field";
      if (type.includes("Property")) return "codicon-symbol-property";
      if (type.includes("Import")) return "codicon-symbol-interface";
      return "codicon-symbol-misc";
    }
    vscode.postMessage({ type: "ready" });
  })();
})();
//# sourceMappingURL=main.js.map
