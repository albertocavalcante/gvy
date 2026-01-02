# Using the Groovy Language Server

## Requirements

- Java 17 or newer
- Groovy 4.0+

## Build

```bash
./gradlew build
```

This produces a fat JAR under `build/libs/`.

## Run

### Stdio mode (default)

```bash
java -jar build/libs/groovy-lsp-<version>.jar
```

### Socket mode

```bash
java -jar build/libs/groovy-lsp-<version>.jar socket 8080
```

## Editors

- VS Code extension: `../../editors/code/README.md`
- Jupyter kernels: `../../jupyter/` (early WIP)

## Troubleshooting

For internal troubleshooting notes, see `../../kb/TROUBLESHOOTING.md`.
