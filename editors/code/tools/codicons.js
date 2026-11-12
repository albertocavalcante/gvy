const fs = require("node:fs");
const path = require("node:path");

/** Copy codicon assets and their upstream licenses into packaged resources. */
function copyCodicons(dist, target) {
  fs.mkdirSync(target, { recursive: true });
  for (const file of ["codicon.css", "codicon.ttf"]) {
    fs.copyFileSync(path.join(dist, file), path.join(target, file));
  }
  for (const file of ["LICENSE", "LICENSE-CODE"]) {
    fs.copyFileSync(path.join(dist, "..", file), path.join(target, file));
  }
  fs.writeFileSync(
    path.join(target, "NOTICE"),
    "Codicons by Microsoft Corporation and contributors\n" +
      "Source: https://github.com/microsoft/vscode-codicons\n" +
      "The icon font is licensed under CC BY 4.0 (LICENSE). " +
      "The CSS is licensed under MIT (LICENSE-CODE).\n",
  );
}

module.exports = { copyCodicons };
