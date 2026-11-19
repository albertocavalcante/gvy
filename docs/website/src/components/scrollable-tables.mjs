// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Extend Astro's configured processor rather than importing a second copy of it.
export function focusableTables() {
  return {
    name: "gvy-focusable-tables",
    hooks: {
      "astro:config:setup": ({ config }) => {
        const processor = config.markdown.processor;
        if (processor.name !== "satteri")
          throw new Error("Focusable tables require the Satteri processor");
        processor.options.hastPlugins.push({
          name: "focusable-tables",
          element: {
            filter: ["table"],
            visit(node, context) {
              context.setProperty(node, "tabIndex", 0);
            },
          },
        });
      },
    },
  };
}
