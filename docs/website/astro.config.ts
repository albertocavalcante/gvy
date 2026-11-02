import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";

export default defineConfig({
  site: "https://albertocavalcante.github.io",
  base: "/gvy",
  integrations: [
    starlight({
      title: "Groovy Devtools",
      description:
        "Developer tooling for Apache Groovy: Language Server, Diagnostics, Formatting, and more.",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/albertocavalcante/gvy",
        },
      ],
      editLink: {
        baseUrl: "https://github.com/albertocavalcante/gvy/edit/main/docs/website/",
      },
      lastUpdated: true,
      customCss: ["./src/styles/custom.css"],
      sidebar: [
        {
          label: "Overview",
          items: [{ autogenerate: { directory: "overview" } }],
        },
        {
          label: "Language Server",
          items: [{ autogenerate: { directory: "lsp" } }],
        },
        {
          label: "Architecture",
          items: [{ autogenerate: { directory: "architecture" } }],
        },
      ],
    }),
  ],
});
